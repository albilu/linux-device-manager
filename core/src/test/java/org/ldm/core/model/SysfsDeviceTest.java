package org.ldm.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SysfsDeviceTest {

    @Test
    void decomposesPciClassCode() {
        SysfsDevice d = new SysfsDevice(
                "/sys/devices/pci0000:00/0000:01:00.0", "0000:01:00.0", Bus.PCI,
                "10de", "2503", 0x030000, Optional.of("nvidia"), Optional.empty(), Map.of());

        assertEquals(0x03, d.pciBaseClass());
        assertEquals(0x00, d.pciSubClass());
    }

    @Test
    void exposesUsbClassInLowByte() {
        SysfsDevice d = new SysfsDevice(
                "/sys/bus/usb/devices/1-1", "1-1", Bus.USB,
                "046d", "0825", 0x0e, Optional.empty(), Optional.of(true), Map.of());

        assertEquals(0x0e, d.usbClass());
    }
}
