package org.ldm.core.process;

import java.time.Duration;
import java.util.List;

/**
 * Abstraction over spawning external processes; the single choke point for
 * shelling out.
 */
public interface CommandRunner {

    /**
     * Run a command and capture its output.
     *
     * @param timeout maximum time to wait before the process is killed
     * @param command the command and its arguments (argv[0] first)
     * @return the captured result
     */
    CommandResult run(Duration timeout, List<String> command);

    /** Varargs convenience wrapper. */
    default CommandResult run(Duration timeout, String... command) {
        return run(timeout, List.of(command));
    }
}
