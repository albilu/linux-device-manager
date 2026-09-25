package org.ldm.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ldm.core.action.DeviceActionService;
import org.ldm.core.categorize.Categorizer;
import org.ldm.core.model.*;
import org.ldm.core.process.FakeCommandRunner;
import org.ldm.core.scan.*;
import org.ldm.core.state.StateResolver;
import org.ldm.core.udev.UdevEnricher;

class DeviceCapabilityRegressionTest {
    private final DeviceActionService actions = new DeviceActionService(new FakeCommandRunner(), "unused", "unused");

    private DeviceManager manager(Path root) {
        return new DeviceManager(new SysfsScanner(root), new UdevEnricher(new FakeCommandRunner(), "udevadm"),
                new Categorizer(), new StateResolver(), Map.of());
    }
    private List<Device> devices(DeviceManager manager) {
        return manager.refresh().stream().flatMap(g -> g.devices().stream()).toList();
    }
    private Path infrastructure(Path root, String bus, String name) throws Exception {
        Path subsystem = Files.createDirectories(root.resolve("bus/" + bus));
        Files.writeString(subsystem.resolve("drivers_probe"), "");
        Path device = Files.createDirectories(subsystem.resolve("devices/" + name));
        Files.createSymbolicLink(device.resolve("subsystem"), subsystem);
        return device;
    }

    @Test
    void realShapedInfrastructureHasAccurateStateAndNoBindingActions(@TempDir Path root) throws Exception {
        Path memory = infrastructure(root, "memory", "memory0");
        Files.writeString(memory.resolve("online"), "1\n");
        Files.writeString(memory.resolve("state"), "online\n");
        Path cpu = infrastructure(root, "cpu", "cpu1");
        Files.writeString(cpu.resolve("online"), "0\n");
        infrastructure(root, "clocksource", "clocksource0");
        infrastructure(root, "workqueue", "events");
        List<Device> all = devices(manager(root));
        assertEquals(4, all.size());
        assertTrue(all.stream().noneMatch(d -> actions.canEnable(d) || actions.canDisable(d)));
        assertEquals(DeviceState.ACTIVE, all.stream().filter(d -> d.busInfo().equals("memory0")).findFirst().orElseThrow().state());
        assertEquals(DeviceState.DISABLED, all.stream().filter(d -> d.busInfo().equals("cpu1")).findFirst().orElseThrow().state());
        assertTrue(all.stream().filter(d -> d.busInfo().equals("events") || d.busInfo().equals("clocksource0"))
                .allMatch(d -> d.state() == DeviceState.UNKNOWN));
    }

    @Test
    void bindingSupportIsIndependentOfDriverPresenceAndUserWritePermissions(@TempDir Path root) throws Exception {
        Path device = new FakeSysfs(root).pciDevice("0000:01:00.0", "020000", "1234", "5678", "igc");
        Path unbind = device.resolve("driver/unbind");
        Files.delete(unbind);
        DeviceManager manager = manager(root);
        Device unsupported = devices(manager).getFirst();
        assertFalse(actions.canDisable(unsupported));
        assertFalse(actions.canEnable(unsupported));
        Files.writeString(unbind, "");
        Files.setPosixFilePermissions(unbind, java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
        assertTrue(actions.canDisable(devices(manager).getFirst()), "capability is not the GUI user's write permission");
        Files.delete(device.resolve("driver"));
        assertTrue(actions.canEnable(devices(manager).getFirst()));
    }

    @Test
    void unknownInstanceNeverOffersPrivilegedActions() {
        Device legacy = new Device("/sys/x", "/sys/x", "x", Bus.PCI, "Old selection", "1234", "5678",
                DeviceCategory.PCI, DeviceState.ACTIVE, Optional.of("igc"), Map.of());
        assertFalse(actions.canDisable(legacy));
        assertFalse(actions.canEnable(legacy));
    }

    @Test
    void usbCategorySurvivesDeauthorizationButNotReplacement(@TempDir Path root) throws Exception {
        FakeSysfs fs = new FakeSysfs(root);
        Path device = fs.usbDevice("1-1", "1234", "5678", "03", "1", "usbhid");
        DeviceManager manager = manager(root);
        Device initial = devices(manager).getFirst();
        assertEquals(DeviceCategory.INPUT, initial.category());
        removeTree(device.resolve("1-1:1.0"));
        Files.writeString(device.resolve("authorized"), "0");
        Device disabled = devices(manager).getFirst();
        assertEquals(initial.instanceId(), disabled.instanceId());
        assertEquals(DeviceCategory.INPUT, disabled.category());
        assertEquals(DeviceState.DISABLED, disabled.state());
        assertTrue(actions.canEnable(disabled));
        Files.move(device, root.resolve("removed"));
        Path replacement = fs.usbDevice("1-1", "1234", "5678", "08", "0", null);
        removeTree(replacement.resolve("1-1:1.0"));
        Device next = devices(manager).getFirst();
        assertNotEquals(initial.instanceId(), next.instanceId());
        assertEquals(DeviceCategory.OTHER, next.category(), "never apply previous hardware's category to a reused path");
    }

    @Test
    void nonUsbDisableHistoryDoesNotSurviveAReplacement(@TempDir Path root) throws Exception {
        FakeSysfs fs = new FakeSysfs(root);
        Path device = fs.pciDevice("0000:01:00.0", "020000", "1234", "5678", "igc");
        DeviceManager manager = manager(root);
        Device initial = devices(manager).getFirst();
        Files.delete(device.resolve("driver"));
        manager.recordAction(initial, false);
        assertEquals(DeviceState.DISABLED, devices(manager).getFirst().state());
        Files.move(device, root.resolve("removed"));
        fs.pciDevice("0000:01:00.0", "020000", "1234", "5678", null);
        assertEquals(DeviceState.INACTIVE_NO_DRIVER, devices(manager).getFirst().state());
    }

    private void removeTree(Path path) throws Exception {
        try (var entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
        }
    }
}
