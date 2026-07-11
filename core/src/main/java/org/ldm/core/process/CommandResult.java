package org.ldm.core.process;

/** Result of running an external command. */
public record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {

    /** @return true if the process completed normally with exit code 0. */
    public boolean success() {
        return !timedOut && exitCode == 0;
    }
}
