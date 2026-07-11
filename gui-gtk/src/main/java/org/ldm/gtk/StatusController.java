package org.ldm.gtk;

import org.gnome.gtk.Label;
import org.gnome.gtk.Spinner;

public final class StatusController {

    private final Spinner spinner;
    private final Label label;

    public StatusController(Spinner spinner, Label label) {
        this.spinner = spinner;
        this.label = label;
    }

    public void busy(String text) {
        spinner.setSpinning(true);
        label.setLabel(text);
    }

    public void message(String text) {
        spinner.setSpinning(false);
        label.setLabel(text);
    }

    public void idle() {
        message("Ready");
    }
}
