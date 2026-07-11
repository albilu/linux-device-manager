package org.ldm.gtk;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.gnome.gtk.Gtk;
import org.gnome.gtk.GtkBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class MainUiSmokeTest {

    @Test
    void mainUiLoadsAndAllRequiredObjectsResolve() {
        // GTK needs a display; skip cleanly when none is available (headless CI without Xvfb).
        assumeTrue(Gtk.initCheck(), "no display available for GTK");

        GtkBuilder builder = GtkBuilder.fromString(LinuxDeviceManagerApp.loadUi(), -1);

        for (String id : new String[] {
                "main_window", "device_tree_view", "device_store",
                "general_info_label", "advanced_info_label", "driver_info_label",
                "logs_text_view", "status_spinner", "status_label", "device_context_menu",
                "window_menu", "main_menu_bar", "tree_scroll"}) {
            assertNotNull(builder.getObject(id), "unresolved object id: " + id);
        }
        assertNotNull(LinuxDeviceManagerApp.class.getResource("/linux-device-manager.svg"),
                "missing about dialog logo resource");
    }
}
