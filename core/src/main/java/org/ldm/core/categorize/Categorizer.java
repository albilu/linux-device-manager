package org.ldm.core.categorize;

import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.SysfsDevice;
import org.ldm.core.model.DriverBinding;
import org.ldm.core.model.UsbInterface;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Assigns a {@link DeviceCategory} by deriving from authoritative bus
 * taxonomies: PCI base/sub
 * class codes and USB interface/device class codes. This is the single tunable
 * place for
 * categorization rules.
 */
public final class Categorizer {

    public DeviceCategory categorize(SysfsDevice device) {
        String kind = device.attributes().getOrDefault("DEVICE_CLASS", "");
        DeviceCategory functional = switch (kind) {
            case "block" -> DeviceCategory.STORAGE;
            case "net" -> DeviceCategory.NETWORK;
            case "sound" -> DeviceCategory.MULTIMEDIA;
            case "input" -> DeviceCategory.INPUT;
            case "video4linux" -> DeviceCategory.IMAGING;
            case "drm" -> DeviceCategory.DISPLAY;
            default -> null;
        };
        if (functional != null) return functional;
        return switch (device.bus()) {
            case PCI -> fromPci(device.pciBaseClass(), device.pciSubClass());
            case USB -> device.usbInterfaces().stream().map(this::fromInterface)
                    .min(Comparator.comparingInt(this::usbPriority))
                    .orElseGet(() -> fromUsb(device.usbClass()));
            case OTHER -> fromGeneric(device);
        };
    }

    private DeviceCategory fromGeneric(SysfsDevice device) {
        String description = (device.busInfo() + " " + device.driver().orElse("") + " "
                + device.attributes().getOrDefault("modalias", "")).toLowerCase(Locale.ROOT);
        if (description.contains("snd") || description.contains("sound") || description.contains("audio"))
            return DeviceCategory.MULTIMEDIA;
        return switch (device.attributes().getOrDefault("SUBSYSTEM", "")) {
            case "cpu" -> DeviceCategory.PROCESSOR;
            case "memory" -> DeviceCategory.MEMORY;
            case "acpi", "pnp" -> DeviceCategory.SYSTEM;
            case "hid", "serio" -> DeviceCategory.INPUT;
            case "hdaudio" -> DeviceCategory.MULTIMEDIA;
            case "scsi", "nvme" -> DeviceCategory.STORAGE;
            case "i2c", "spi" -> DeviceCategory.SERIAL_BUS;
            default -> DeviceCategory.OTHER;
        };
    }

    private DeviceCategory fromInterface(UsbInterface iface) {
        String driver = iface.binding().map(DriverBinding::name).orElse("");
        if (driver.equals("btusb")) return DeviceCategory.BLUETOOTH;
        if (List.of("cdc_ether", "cdc_ncm", "cdc_mbim", "rndis_host", "r8152", "asix", "ax88179_178a",
                "lan78xx", "rtl8xxxu", "rt2800usb", "mt76x2u", "ath9k_htc").contains(driver)
                || (iface.classCode() == 0x02 && List.of(0x06, 0x0d, 0x0e).contains(iface.subClass()))
                || (iface.classCode() == 0xe0 && iface.subClass() == 1 && iface.protocol() == 3))
            return DeviceCategory.NETWORK;
        return fromUsb(iface.classCode());
    }

    private int usbPriority(DeviceCategory category) {
        // A webcam's video function and an audio device's audio function take precedence over
        // incidental microphones/buttons. Enumeration order never decides the result.
        return switch (category) {
            case NETWORK -> 0;
            case BLUETOOTH -> 1;
            case IMAGING -> 2;
            case MULTIMEDIA -> 3;
            case STORAGE -> 4;
            case PRINTER -> 5;
            case INPUT -> 6;
            case USB -> 7;
            case COMMUNICATION -> 8;
            default -> 9;
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
            default -> DeviceCategory.PCI;
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
