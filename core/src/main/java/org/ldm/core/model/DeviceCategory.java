package org.ldm.core.model;

/**
 * Device category vocabulary, derived from authoritative bus taxonomies (see
 * Categorizer).
 * Declaration order is the display order in the tree. Each constant carries a
 * display name and a
 * freedesktop icon name (resolved against the active theme, with a fallback, at
 * render time).
 */
public enum DeviceCategory {
    PROCESSOR("Processors", "cpu"),
    DISPLAY("Display adapters", "video-display"),
    MULTIMEDIA("Sound, video and game controllers", "audio-card"),
    IMAGING("Imaging devices", "camera-web"),
    NETWORK("Network adapters", "network-wired"),
    BLUETOOTH("Bluetooth", "bluetooth"),
    STORAGE("Storage controllers and drives", "drive-harddisk"),
    USB("USB controllers", "drive-removable-media-usb"),
    INPUT("Input devices", "input-keyboard"),
    PRINTER("Printers", "printer"),
    COMMUNICATION("Communication and modems", "modem"),
    MEMORY("Memory controllers", "media-flash"),
    BRIDGE("Bridges", "preferences-system"),
    SERIAL_BUS("Serial bus controllers", "preferences-system"),
    ENCRYPTION("Encryption controllers", "security-high"),
    SYSTEM("System devices", "computer"),
    OTHER("Other devices", "application-x-executable");

    private final String displayName;
    private final String iconName;

    DeviceCategory(String displayName, String iconName) {
        this.displayName = displayName;
        this.iconName = iconName;
    }

    public String displayName() {
        return displayName;
    }

    public String iconName() {
        return iconName;
    }
}
