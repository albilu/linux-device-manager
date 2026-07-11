package org.ldm.gtk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.gnome.gtk.Gtk;
import org.gnome.gtk.Label;
import org.gnome.gtk.Spinner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class StatusControllerTest {

    @Test
    void busyStartsSpinnerAndIdleStopsIt() {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");

        Spinner spinner = new Spinner();
        Label label = new Label("Ready");
        StatusController controller = new StatusController(spinner, label);

        controller.busy("Working...");

        assertTrue(spinner.getSpinning());
        assertEquals("Working...", label.getLabel());

        controller.idle();

        assertFalse(spinner.getSpinning());
        assertEquals("Ready", label.getLabel());
    }
}
