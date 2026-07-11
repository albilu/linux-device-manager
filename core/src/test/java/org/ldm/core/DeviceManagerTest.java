package org.ldm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ldm.core.categorize.Categorizer;
import org.ldm.core.model.CategoryGroup;
import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.DeviceState;
import org.ldm.core.process.FakeCommandRunner;
import org.ldm.core.scan.FakeSysfs;
import org.ldm.core.scan.SysfsScanner;
import org.ldm.core.state.StateResolver;
import org.ldm.core.udev.UdevEnricher;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class DeviceManagerTest {

    @Test
    void refreshGroupsAndOrdersByCategory(@TempDir Path tmp) {
        FakeSysfs fs = new FakeSysfs(tmp);
        fs.pciDevice("0000:01:00.0", "0x030000", "0x10de", "0x2503", "nvidia"); // DISPLAY, ACTIVE
        fs.pciDevice("0000:00:1f.6", "0x020000", "0x8086", "0x15bc", null); // NETWORK, INACTIVE
        fs.usbDevice("1-1", "046d", "0825", "0e", "1", "uvcvideo"); // IMAGING, ACTIVE

        FakeCommandRunner runner = new FakeCommandRunner(); // no udev names -> hex fallback
        DeviceManager manager = new DeviceManager(
                new SysfsScanner(tmp),
                new UdevEnricher(runner, "udevadm"),
                new Categorizer(),
                new StateResolver(),
                Map.of());

        List<CategoryGroup> groups = manager.refresh();

        // Enum display order: DISPLAY(1) before IMAGING(3) before NETWORK(4).
        assertEquals(
                List.of(DeviceCategory.DISPLAY, DeviceCategory.IMAGING, DeviceCategory.NETWORK),
                groups.stream().map(CategoryGroup::category).toList());
        assertEquals(DeviceState.ACTIVE, groups.get(0).devices().get(0).state());
        assertEquals(DeviceState.INACTIVE_NO_DRIVER, groups.get(2).devices().get(0).state());
        assertTrue(groups.get(0).devices().get(0).displayName().contains("10de"));
    }

    @Test
    void refreshOmitsEmptyCategories(@TempDir Path tmp) {
        new FakeSysfs(tmp).pciDevice("0000:01:00.0", "0x030000", "0x10de", "0x2503", "nvidia");

        DeviceManager manager = new DeviceManager(
                new SysfsScanner(tmp),
                new UdevEnricher(new FakeCommandRunner(), "udevadm"),
                new Categorizer(),
                new StateResolver(),
                Map.of());

        List<CategoryGroup> groups = manager.refresh();

        assertEquals(1, groups.size());
        assertEquals(DeviceCategory.DISPLAY, groups.get(0).category());
    }
}
