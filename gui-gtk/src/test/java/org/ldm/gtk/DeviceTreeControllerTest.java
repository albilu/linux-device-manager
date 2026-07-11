package org.ldm.gtk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.ldm.core.model.Bus;
import org.ldm.core.model.CategoryGroup;
import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.DeviceState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.gnome.gobject.Value;
import org.gnome.glib.Type;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeStore;
import org.javagi.gobject.types.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class DeviceTreeControllerTest {

    private Device device(String name, DeviceCategory category, DeviceState state) {
        return new Device("/sys/" + name, "/sys/" + name, name, Bus.PCI, name, "10de", "2503",
                category, state, Optional.of("drv"), Map.of());
    }

    @Test
    void populatesRowsAndMapsPathsToDevices() {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");

        TreeStore store = new TreeStore(new Type[] {Types.STRING, Types.STRING});
        DeviceTreeController controller = new DeviceTreeController(store);

        Device gpu = device("GPU", DeviceCategory.DISPLAY, DeviceState.ACTIVE);
        Device nic = device("NIC", DeviceCategory.NETWORK, DeviceState.INACTIVE_NO_DRIVER);
        controller.populate(List.of(
                new CategoryGroup(DeviceCategory.DISPLAY, List.of(gpu)),
                new CategoryGroup(DeviceCategory.NETWORK, List.of(nic))));

        assertEquals(gpu, controller.deviceAt(TreePath.fromString("0:0")));
        assertEquals(nic, controller.deviceAt(TreePath.fromString("1:0")));
        assertNull(controller.deviceAt(TreePath.fromString("0")));
    }

    @Test
    void releasesTransientValuesAfterSettingStoreCells() {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");

        CapturingTreeStore store = new CapturingTreeStore();
        DeviceTreeController controller = new DeviceTreeController(store);
        Device gpu = device("GPU", DeviceCategory.DISPLAY, DeviceState.ACTIVE);

        controller.populate(List.of(new CategoryGroup(DeviceCategory.DISPLAY, List.of(gpu))));

        assertEquals(4, store.values.size());
        for (Value value : store.values) {
            assertEquals(0, value.readGType().getValue());
        }
    }

    private static final class CapturingTreeStore extends TreeStore {
        private final List<Value> values = new ArrayList<>();

        private CapturingTreeStore() {
            super(new Type[] {Types.STRING, Types.STRING});
        }

        @Override
        public void setValue(TreeIter iter, int column, Value value) {
            super.setValue(iter, column, value);
            values.add(value);
        }
    }
}
