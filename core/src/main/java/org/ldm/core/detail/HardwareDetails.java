package org.ldm.core.detail;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Readable specifications, with the most useful facts also included in General. */
public record HardwareDetails(List<Fact> facts, List<String> notes) {
    public HardwareDetails {
        facts = List.copyOf(facts);
        notes = List.copyOf(notes);
    }

    public record Fact(String label, String value, boolean overview) { }

    public String render(boolean overviewOnly) {
        String factsText = facts.stream().filter(f -> !overviewOnly || f.overview())
                .map(f -> f.label() + ": " + f.value()).collect(Collectors.joining("\n"));
        return (factsText + (notes.isEmpty() ? "" : "\n\n" + String.join("\n", notes))).strip();
    }

    static final class Builder {
        private final List<Fact> facts = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();

        void add(String label, String value) { add(label, value, true); }

        void add(String label, String value, boolean overview) {
            if (value != null && !value.isBlank()) facts.add(new Fact(label, value, overview));
        }

        void note(String value) { if (!notes.contains(value)) notes.add(value); }

        HardwareDetails build() { return new HardwareDetails(facts, notes); }
    }
}
