package org.ldm.gtk;

import org.ldm.core.model.CategoryGroup;
import org.ldm.core.model.Device;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.gnome.gobject.Value;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeStore;
import org.javagi.gobject.types.Types;

/**
 * Fills the authored {@code device_store} (a 2-column {@link TreeStore}: icon-name, label) from the
 * core's category tree, and remembers which {@link Device} each row represents (keyed by tree path),
 * since the store itself only carries icon + label.
 */
public final class DeviceTreeController {

    private final TreeStore store;
    private final Map<String, Device> devicesByPath = new HashMap<>();

    public DeviceTreeController(TreeStore store) {
        this.store = store;
    }

    /** Clear and repopulate the store; only non-empty categories are present in {@code groups}. */
    public void populate(List<CategoryGroup> groups) {
        devicesByPath.clear();
        store.clear();
        for (CategoryGroup group : groups) {
            TreeIter parent = new TreeIter();
            store.append(parent, null);
            setString(parent, 0, group.iconName());
            setString(parent, 1, group.displayName());
            for (Device device : group.devices()) {
                TreeIter child = new TreeIter();
                store.append(child, parent);
                setString(child, 0, Icons.deviceIcon(device));
                setString(child, 1, device.displayName());
                devicesByPath.put(store.getPath(child).toString(), device);
            }
        }
    }

    /** @return the device at the given tree path, or {@code null} for a category (parent) row. */
    public Device deviceAt(TreePath path) {
        return path == null ? null : devicesByPath.get(path.toString());
    }

    private void setString(TreeIter iter, int column, String text) {
        Value value = new Value();
        value.init(Types.STRING);
        try {
            value.setString(text);
            store.setValue(iter, column, value);
        } finally {
            value.unset();
        }
    }
}
