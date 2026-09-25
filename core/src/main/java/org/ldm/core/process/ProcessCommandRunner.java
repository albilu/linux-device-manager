package org.ldm.core.process;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Real {@link CommandRunner} backed by {@link ProcessBuilder}. */
public final class ProcessCommandRunner implements CommandRunner {

    @Override
    public CommandResult run(Duration timeout, List<String> command) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Process process = null;
        Path stdout = null;
        Path stderr = null;
        Set<ProcessHandle> descendants = new HashSet<>();
        try {
            // Private files avoid pipe-reader threads and EOF waits on orphan descendants.
            // Unlink on every exit, including cancellation and launch failure.
            stdout = Files.createTempFile("ldm-stdout-", ".tmp");
            stderr = Files.createTempFile("ldm-stderr-", ".tmp");
            process = new ProcessBuilder(command).redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile()).start();
            process.getOutputStream().close();
            while (true) {
                rememberDescendants(process.toHandle(), descendants);
                if (!process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive)) break;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return new CommandResult(-1, "", "", true);
                long slice = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(5));
                if (process.isAlive()) process.waitFor(slice, TimeUnit.NANOSECONDS);
                else TimeUnit.NANOSECONDS.sleep(slice);
            }
            String out = read(stdout, deadline);
            String err = read(stderr, deadline);
            return new CommandResult(process.exitValue(), out, err, false);
        } catch (DeadlineExceeded e) {
            return new CommandResult(-1, "", "", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "", "interrupted", false);
        } catch (IOException e) {
            return new CommandResult(-1, "", "command failed: " + e.getMessage(), false);
        } finally {
            if (process != null) {
                rememberDescendants(process.toHandle(), descendants);
                descendants.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
                if (process.isAlive()) process.destroyForcibly();
            }
            delete(stdout);
            delete(stderr);
        }
    }

    private static void rememberDescendants(ProcessHandle parent, Set<ProcessHandle> descendants) {
        // ProcessHandle.descendants() scans every process on Linux. Doing that for each of
        // hundreds of short udev queries makes inventory refreshes much slower. Read only
        // this process tree, including children forked by secondary threads.
        var pending = new ArrayDeque<ProcessHandle>();
        var visited = new HashSet<ProcessHandle>();
        pending.add(parent);
        pending.addAll(descendants);
        while (!pending.isEmpty()) {
            ProcessHandle handle = pending.remove();
            if (!handle.isAlive() || !visited.add(handle)) continue;
            try (var tasks = Files.newDirectoryStream(Path.of("/proc", Long.toString(handle.pid()), "task"))) {
                for (Path task : tasks) {
                    for (String pid : Files.readString(task.resolve("children")).strip().split("\\s+")) {
                        if (pid.isEmpty()) continue;
                        ProcessHandle.of(Long.parseLong(pid)).ifPresent(child -> {
                            descendants.add(child);
                            pending.add(child);
                        });
                    }
                }
            } catch (IOException ignored) {
                // Exiting processes and threads commonly disappear during inspection.
            }
        }
    }

    private static String read(Path path, long deadline) throws IOException, InterruptedException, DeadlineExceeded {
        try (var in = Files.newInputStream(path); var out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (System.nanoTime() >= deadline) throw new DeadlineExceeded();
                int length = in.read(buffer);
                if (length < 0) return out.toString(StandardCharsets.UTF_8);
                out.write(buffer, 0, length);
            }
        }
    }

    private static final class DeadlineExceeded extends Exception { }

    private static void delete(Path path) {
        if (path != null) {
            try { Files.deleteIfExists(path); }
            catch (IOException ignored) { path.toFile().deleteOnExit(); }
        }
    }
}
