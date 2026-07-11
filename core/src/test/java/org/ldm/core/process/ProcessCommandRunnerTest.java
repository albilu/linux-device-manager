package org.ldm.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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
}
