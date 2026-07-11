package org.ldm.core.model;

import java.util.List;

/** A category and the devices in it: one top-level group in the device tree. */
public record CategoryGroup(DeviceCategory category, List<Device> devices) {

    public String displayName() {
        return category.displayName();
    }

    public String iconName() {
        return category.iconName();
    }
}
