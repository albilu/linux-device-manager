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

    @Test
    void operationsKeepTheirPriorityAndOnlyFinishTheirOwnWork() {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");
        Spinner spinner = new Spinner();
        Label label = new Label("");
        StatusController status = new StatusController(spinner, label);
        var details = status.begin(StatusController.Kind.DETAILS, "Details");
        var action = status.begin(StatusController.Kind.ACTION, "Authorizing");
        var scan = status.begin(StatusController.Kind.SCAN, "Scanning");
        assertEquals("Authorizing", label.getLabel());
        details.close();
        details.close(); // cancellation and a late callback must be harmless
        status.message("Device enabled.");
        assertTrue(spinner.getSpinning());
        assertEquals("Authorizing", label.getLabel());
        action.close();
        assertEquals("Scanning", label.getLabel());
        assertTrue(spinner.getSpinning());
        scan.close();
        assertFalse(spinner.getSpinning());
        assertEquals("Device enabled.", label.getLabel());
    }
}
