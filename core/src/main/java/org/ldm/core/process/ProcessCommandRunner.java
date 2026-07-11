package org.ldm.core.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Real {@link CommandRunner} backed by {@link ProcessBuilder}. */
public final class ProcessCommandRunner implements CommandRunner {

    @Override
    public CommandResult run(Duration timeout, List<String> command) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            return new CommandResult(-1, "", "failed to start: " + e.getMessage(), false);
        }
        closeQuietly(process.getOutputStream()); // we never write stdin; signal EOF
        // Drain stdout/stderr concurrently to avoid pipe-buffer deadlock.
        CompletableFuture<String> out = readAsync(process.getInputStream());
        CompletableFuture<String> err = readAsync(process.getErrorStream());
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                killTree(process);
                // A surviving descendant could hold the pipe open, so do not block on the
                // readers here; killTree lets them reach EOF and complete shortly after.
                return new CommandResult(-1, out.getNow(""), err.getNow(""), true);
            }
            return new CommandResult(process.exitValue(), out.join(), err.join(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            return new CommandResult(-1, "", "interrupted", false);
        }
    }

    /** Forcibly terminate the process and all of its descendants. */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static CompletableFuture<String> readAsync(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }
}
