package org.ldm.gtk;

import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceCategory;
import org.gnome.gdk.Display;
import org.gnome.gtk.IconTheme;

/** Resolves category and state icons against the active GTK theme, including symbolic fallbacks. */
final class Icons {

    private Icons() {
    }

    static String deviceIcon(Device device) {
        IconTheme theme = IconTheme.getForDisplay(Display.getDefault());
        return switch (device.state()) {
            case ACTIVE, UNKNOWN -> categoryIcon(device.category(), theme);
            case INACTIVE_NO_DRIVER -> resolve(theme, "dialog-warning", "dialog-warning-symbolic");
            case DISABLED -> resolve(theme, "action-unavailable", "action-unavailable-symbolic");
            case ERROR -> resolve(theme, "dialog-error", "dialog-error-symbolic");
        };
    }

    static String categoryIcon(DeviceCategory category) {
        return categoryIcon(category, IconTheme.getForDisplay(Display.getDefault()));
    }

    static String categoryIcon(DeviceCategory category, IconTheme theme) {
        if (category == DeviceCategory.PROCESSOR) {
            return resolve(theme, "cpu", "cpu-symbolic", "processor", "processor-symbolic", "xsi-cpu-symbolic");
        }
        if (category == DeviceCategory.BLUETOOTH) {
            return resolve(theme, "bluetooth", "bluetooth-symbolic", "xsi-bluetooth-symbolic",
                    "bluetooth-active", "bluetooth-active-symbolic");
        }
        return resolve(theme, category.iconName(), category.iconName() + "-symbolic");
    }

    private static String resolve(IconTheme theme, String... names) {
        for (String name : names) {
            if (theme.hasIcon(name)) return name;
        }
        for (String fallback : new String[] {"computer", "computer-symbolic", "application-x-executable", "application-x-executable-symbolic"}) {
            if (theme.hasIcon(fallback)) return fallback;
        }
        return "image-missing";
    }
}
