package org.ldm.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FakeCommandRunnerTest {

    @Test
    void returnsStubbedResultAndRecordsInvocation() {
        FakeCommandRunner runner = new FakeCommandRunner().stubStdout("hello\n", "echo", "hello");

        CommandResult result = runner.run(Duration.ofSeconds(1), "echo", "hello");

        assertTrue(result.success());
        assertEquals("hello\n", result.stdout());
        assertEquals(1, runner.invocations().size());
    }

    @Test
    void returnsFallbackForUnstubbedCommand() {
        FakeCommandRunner runner = new FakeCommandRunner();

        CommandResult result = runner.run(Duration.ofSeconds(1), "nope");

        assertFalse(result.success());
        assertEquals(127, result.exitCode());
    }
}
