package org.ldm.gtk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.gnome.glib.Type;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeSelection;
import org.gnome.gtk.TreeStore;
import org.gnome.gtk.TreeView;
import org.javagi.gobject.types.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class LinuxDeviceManagerAppTest {

    @Test
    void selectingAlreadySelectedPathIsNoOp() {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");

        TreeSelection selection = selectionWithRows(1);
        TreePath path = TreePath.fromString("0");

        assertTrue(LinuxDeviceManagerApp.selectPathIfNeeded(selection, path));
        assertFalse(LinuxDeviceManagerApp.selectPathIfNeeded(selection, path));
    }

    @Test
    void selectingDifferentPathUpdatesSelection() {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");

        TreeSelection selection = selectionWithRows(2);
        TreePath first = TreePath.fromString("0");
        TreePath second = TreePath.fromString("1");

        assertTrue(LinuxDeviceManagerApp.selectPathIfNeeded(selection, first));
        assertTrue(LinuxDeviceManagerApp.selectPathIfNeeded(selection, second));
        assertTrue(selection.pathIsSelected(second));
    }

    @Test
    void aboutLogoLoadsFromClasspathResource() {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");

        assertNotNull(LinuxDeviceManagerApp.loadAboutLogo());
    }

    private static TreeSelection selectionWithRows(int rows) {
        TreeStore store = new TreeStore(new Type[] {Types.STRING});
        for (int i = 0; i < rows; i++) {
            store.append(new TreeIter(), null);
        }
        TreeView view = new TreeView();
        view.setModel(store);
        return view.getSelection();
    }
}
