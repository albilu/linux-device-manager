package org.ldm.gtk;

import org.ldm.core.model.Device;

/** Maps a device's state to a freedesktop icon name for the tree. */
final class Icons {

    private Icons() {
    }

    static String deviceIcon(Device device) {
        return switch (device.state()) {
            case ACTIVE, UNKNOWN -> device.category().iconName();
            case INACTIVE_NO_DRIVER -> "dialog-warning";
            case DISABLED -> "action-unavailable";
            case ERROR -> "dialog-error";
        };
    }
}
