package org.ldm.gtk;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.gnome.gtk.Label;
import org.gnome.gtk.Spinner;

/** GTK-thread operation tracking: finishing one task never hides another task's busy state. */
public final class StatusController {
    public enum Kind { DETAILS, SCAN, ACTION }
    private record Entry(Kind kind, String text) { }
    private final Spinner spinner;
    private final Label label;
    private final Map<Long, Entry> active = new LinkedHashMap<>();
    private long nextId;
    private String idleMessage = "Ready";
    private Operation legacy;

    public StatusController(Spinner spinner, Label label) {
        this.spinner = spinner;
        this.label = label;
    }

    public Operation begin(Kind kind, String text) {
        if (active.isEmpty()) idleMessage = "Ready";
        long id = ++nextId;
        active.put(id, new Entry(kind, text));
        render();
        return new Operation(id);
    }

    public final class Operation implements AutoCloseable {
        private final long id;
        private Operation(long id) { this.id = id; }
        @Override public void close() { active.remove(id); render(); }
    }

    public void busy(String text) {
        if (legacy != null) legacy.close();
        legacy = begin(Kind.DETAILS, text);
    }

    public void message(String text) {
        idleMessage = text;
        render();
    }

    public void idle() {
        if (legacy != null) { legacy.close(); legacy = null; }
        message("Ready");
    }

    private void render() {
        spinner.setSpinning(!active.isEmpty());
        label.setLabel(active.entrySet().stream()
                .max(Comparator.<Map.Entry<Long, Entry>, Kind>comparing(e -> e.getValue().kind())
                        .thenComparingLong(Map.Entry::getKey))
                .map(e -> e.getValue().text()).orElse(idleMessage));
    }
}
