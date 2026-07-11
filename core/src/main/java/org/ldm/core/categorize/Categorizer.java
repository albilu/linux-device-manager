package org.ldm.core.categorize;

import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.SysfsDevice;

/**
 * Assigns a {@link DeviceCategory} by deriving from authoritative bus
 * taxonomies: PCI base/sub
 * class codes and USB interface/device class codes. This is the single tunable
 * place for
 * categorization rules.
 */
public final class Categorizer {

    public DeviceCategory categorize(SysfsDevice device) {
        return switch (device.bus()) {
            case PCI -> fromPci(device.pciBaseClass(), device.pciSubClass());
            case USB -> fromUsb(device.usbClass());
            case OTHER -> DeviceCategory.OTHER;
        };
    }

    private DeviceCategory fromPci(int baseClass, int subClass) {
        return switch (baseClass) {
            case 0x01 -> DeviceCategory.STORAGE;
            case 0x02 -> DeviceCategory.NETWORK;
            case 0x03 -> DeviceCategory.DISPLAY;
            case 0x04 -> DeviceCategory.MULTIMEDIA;
            case 0x05 -> DeviceCategory.MEMORY;
            case 0x06 -> DeviceCategory.BRIDGE;
            case 0x07 -> DeviceCategory.COMMUNICATION;
            case 0x00, 0x08 -> DeviceCategory.SYSTEM;
            case 0x09 -> DeviceCategory.INPUT;
            case 0x0b -> DeviceCategory.PROCESSOR;
            case 0x0c -> (subClass == 0x03) ? DeviceCategory.USB : DeviceCategory.SERIAL_BUS;
            case 0x0d -> (subClass == 0x11) ? DeviceCategory.BLUETOOTH : DeviceCategory.NETWORK;
            case 0x10 -> DeviceCategory.ENCRYPTION;
            default -> DeviceCategory.OTHER;
        };
    }

    private DeviceCategory fromUsb(int usbClass) {
        return switch (usbClass) {
            case 0x01, 0x10 -> DeviceCategory.MULTIMEDIA;
            case 0x02, 0x0a -> DeviceCategory.COMMUNICATION;
            case 0x03 -> DeviceCategory.INPUT;
            case 0x06, 0x0e -> DeviceCategory.IMAGING;
            case 0x07 -> DeviceCategory.PRINTER;
            case 0x08 -> DeviceCategory.STORAGE;
            case 0x09 -> DeviceCategory.USB;
            case 0xe0 -> DeviceCategory.BLUETOOTH;
            default -> DeviceCategory.OTHER;
        };
    }
}
