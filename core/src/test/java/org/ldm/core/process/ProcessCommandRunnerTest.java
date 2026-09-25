package org.ldm.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class ProcessCommandRunnerTest {

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    @Test
    void capturesStdoutAndExitZero() {
        CommandResult r = runner.run(Duration.ofSeconds(5), "sh", "-c", "printf hello");
        assertTrue(r.success());
        assertEquals("hello", r.stdout());
    }

    @Test
    void capturesNonZeroExitAndStderr() {
        CommandResult r = runner.run(Duration.ofSeconds(5), "sh", "-c", "printf oops 1>&2; exit 3");
        assertFalse(r.success());
        assertEquals(3, r.exitCode());
        assertEquals("oops", r.stderr());
    }

    @Test
    void killsProcessOnTimeoutAndReturnsPromptly() {
        long startNanos = System.nanoTime();
        CommandResult r = runner.run(Duration.ofMillis(200), "sh", "-c", "sleep 5");
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertTrue(r.timedOut());
        assertFalse(r.success());
        assertTrue(elapsedMillis < 2_000,
                "run() must return promptly on timeout; took " + elapsedMillis + "ms");
    }

    @Test
    void descendantHoldingEitherOutputCannotEscapeDeadline() throws Exception {
        Set<Path> initialFiles = captureFiles();
        for (String redirect : new String[] {"", "1>&2"}) {
            long start = System.nanoTime();
            var result = runner.run(Duration.ofMillis(200), "sh", "-c", "sleep 10 " + redirect + " & sleep 0.1; exit 0");
            assertTrue(result.timedOut());
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        }
        Set<Path> remaining = captureFiles();
        remaining.removeAll(initialFiles);
        assertTrue(remaining.isEmpty(), "capture files leaked: " + remaining);
    }

    @Test
    void cancellationAfterParentExitReturnsPromptlyAndPreservesInterrupt() throws Exception {
        AtomicReference<CommandResult> result = new AtomicReference<>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread thread = new Thread(() -> {
            result.set(runner.run(Duration.ofSeconds(30), "sh", "-c", "sleep 10 & sleep 0.1; exit 0"));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        Thread.sleep(300);
        thread.interrupt();
        thread.join(1500);
        assertFalse(thread.isAlive());
        assertTrue(interrupted.get());
        assertFalse(result.get().success());
        assertEquals("interrupted", result.get().stderr());
    }

    @Test
    void collectsBothStreamsBeyondPipeBufferSize() {
        var result = runner.run(Duration.ofSeconds(5), "sh", "-c",
                "i=0; while [ $i -lt 20000 ]; do printf abcdefgh; printf ijklmnop >&2; i=$((i+1)); done");
        assertTrue(result.success(), result.stderr());
        assertEquals(160000, result.stdout().length());
        assertEquals(160000, result.stderr().length());
    }

    private Set<Path> captureFiles() throws Exception {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p -> p.getFileName().toString().matches("ldm-(stdout|stderr)-.*\\.tmp"))
                    .collect(Collectors.toSet());
        }
    }
}
