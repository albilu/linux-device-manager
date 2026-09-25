package org.ldm.core.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ldm.core.model.Bus;
import org.ldm.core.model.SysfsDevice;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX) // relies on POSIX symlinks for the driver link
class SysfsScannerTest {

    @Test
    void scansPciDeviceWithDriver(@TempDir Path tmp) {
        new FakeSysfs(tmp).pciDevice("0000:01:00.0", "0x030000", "0x10de", "0x2503", "nvidia");

        List<SysfsDevice> devices = new SysfsScanner(tmp).scan();

        assertEquals(1, devices.size());
        SysfsDevice d = devices.get(0);
        assertEquals(Bus.PCI, d.bus());
        assertEquals("10de", d.vendorId());
        assertEquals("2503", d.productId());
        assertEquals(0x03, d.pciBaseClass());
        assertEquals(Optional.of("nvidia"), d.driver());
    }

    @Test
    void scansUsbDeviceAndReadsInterfaceClass(@TempDir Path tmp) {
        new FakeSysfs(tmp).usbDevice("1-1", "046d", "0825", "0e", "1", "uvcvideo");

        List<SysfsDevice> devices = new SysfsScanner(tmp).scan();

        assertEquals(1, devices.size());
        SysfsDevice d = devices.get(0);
        assertEquals(Bus.USB, d.bus());
        assertEquals(0x0e, d.usbInterfaces().getFirst().classCode());
        assertEquals(Optional.of(true), d.authorized());
        assertEquals(Optional.of("uvcvideo"), d.driver());
    }

    @Test
    void pciDeviceWithoutDriverHasEmptyDriver(@TempDir Path tmp) {
        new FakeSysfs(tmp).pciDevice("0000:02:00.0", "0x028000", "0x8086", "0x2723", null);

        List<SysfsDevice> devices = new SysfsScanner(tmp).scan();

        assertTrue(devices.get(0).driver().isEmpty());
    }

    @Test
    void malformedClassValueDegradesToZeroInsteadOfAbortingScan(@TempDir Path tmp) {
        // A garbage class value must not throw and lose the whole scan.
        new FakeSysfs(tmp).pciDevice("0000:03:00.0", "garbage", "0x10de", "0x2503", null);

        List<SysfsDevice> devices = new SysfsScanner(tmp).scan();

        assertEquals(1, devices.size());
        assertEquals(0, devices.get(0).classCode());
    }
}
