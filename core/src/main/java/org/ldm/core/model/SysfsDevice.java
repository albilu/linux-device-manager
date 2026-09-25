package org.ldm.core.model;

import java.util.Map;
import java.util.List;
import java.util.Optional;

/**
 * Raw device data read from sysfs, before udev enrichment / categorization.
 *
 * <p>
 * For PCI devices {@code classCode} is the full 24-bit PCI class
 * ({@code base<<16 | sub<<8 | progif}). For USB devices {@code classCode} holds
 * the device class; {@code usbInterfaces} retains every interface descriptor and binding.
 *
 * @param syspath    absolute sysfs path of the device node
 * @param busInfo    bus address (e.g. PCI slot "0000:01:00.0" or USB name
 *                   "1-1")
 * @param bus        which bus the device is on
 * @param vendorId   4-hex-digit vendor id (no "0x"), e.g. "10de"
 * @param productId  4-hex-digit product/device id (no "0x"), e.g. "2503"
 * @param classCode  see class note above
 * @param driver     bound kernel driver name, or empty if none
 * @param authorized USB authorized flag; empty for non-USB devices
 * @param attributes any extra raw sysfs attributes captured
 */
public record SysfsDevice(
        String syspath,
        String busInfo,
        Bus bus,
        String vendorId,
        String productId,
        int classCode,
        Optional<String> driver,
        Optional<Boolean> authorized,
        Map<String, String> attributes,
        List<DriverBinding> driverBindings,
        List<UsbInterface> usbInterfaces,
        DeviceActionKind actionKind,
        String instanceId,
        boolean enableSupported,
        boolean disableSupported) {

    public SysfsDevice {
        attributes = Map.copyOf(attributes);
        driverBindings = List.copyOf(driverBindings);
        usbInterfaces = List.copyOf(usbInterfaces);
    }

    public SysfsDevice(String syspath, String busInfo, Bus bus, String vendorId, String productId,
                       int classCode, Optional<String> driver, Optional<Boolean> authorized,
                       Map<String, String> attributes, List<DriverBinding> bindings,
                       List<UsbInterface> interfaces, DeviceActionKind actionKind) {
        this(syspath, busInfo, bus, vendorId, productId, classCode, driver, authorized, attributes,
                bindings, interfaces, actionKind, "", false, false);
    }

    public SysfsDevice(String syspath, String busInfo, Bus bus, String vendorId, String productId,
                       int classCode, Optional<String> driver, Optional<Boolean> authorized,
                       Map<String, String> attributes) {
        this(syspath, busInfo, bus, vendorId, productId, classCode, driver, authorized, attributes,
                driver.map(name -> List.of(new DriverBinding(syspath, name, Optional.of(name))))
                        .orElse(List.of()), List.of(),
                bus == Bus.USB ? DeviceActionKind.USB_AUTHORIZATION :
                        bus == Bus.PCI ? DeviceActionKind.DRIVER_BINDING : DeviceActionKind.NONE);
    }

    public int pciBaseClass() {
        return (classCode >> 16) & 0xff;
    }

    public int pciSubClass() {
        return (classCode >> 8) & 0xff;
    }

    public int usbClass() {
        return classCode & 0xff;
    }
}
