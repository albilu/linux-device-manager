package org.ldm.gtk;

import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceCategory;

final class SafetyPolicy {

    private SafetyPolicy() {
    }

    static boolean isCritical(Device device) {
        // Storage devices are critical because disabling them may destabilize the root filesystem.
        return device.category() == DeviceCategory.STORAGE;
    }
}
