package org.ldm.core.process;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test double for {@link CommandRunner}; returns canned results keyed by the
 * exact command line.
 */
public final class FakeCommandRunner implements CommandRunner {

    private final Map<List<String>, CommandResult> responses = new LinkedHashMap<>();
    private final List<List<String>> invocations = new ArrayList<>();
    private final CommandResult fallback = new CommandResult(127, "", "command not stubbed", false);

    public FakeCommandRunner stub(CommandResult result, String... command) {
        responses.put(List.of(command), result);
        return this;
    }

    public FakeCommandRunner stubStdout(String stdout, String... command) {
        return stub(new CommandResult(0, stdout, "", false), command);
    }

    public List<List<String>> invocations() {
        return List.copyOf(invocations);
    }

    @Override
    public CommandResult run(Duration timeout, List<String> command) {
        invocations.add(List.copyOf(command));
        return responses.getOrDefault(command, fallback);
    }
}
