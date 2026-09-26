package org.ldm.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ldm.core.action.*;
import org.ldm.core.categorize.Categorizer;
import org.ldm.core.detail.*;
import org.ldm.core.model.*;
import org.ldm.core.process.*;
import org.ldm.core.scan.*;
import org.ldm.core.state.StateResolver;
import org.ldm.core.udev.UdevEnricher;

class FeatureRegressionTest {
    private DeviceManager manager(Path root, StateResolver state) {
        return new DeviceManager(new SysfsScanner(root), new UdevEnricher(new FakeCommandRunner(), "udevadm"),
                new Categorizer(), state, Map.of());
    }
    private Device device(Optional<String> driver, List<DriverBinding> bindings) {
        return new Device("/sys/audit", "/sys/audit", "0000:01:00.0", Bus.PCI, "Audit", "1234", "5678",
                DeviceCategory.PCI, DeviceState.ACTIVE, driver, Map.of(), bindings, Optional.empty(), DeviceActionKind.DRIVER_BINDING);
    }
    private void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent()); Files.writeString(path, value);
    }

    @Test
    void disabledUsbOffersAuthorizationDespiteGenericParentDriver(@TempDir Path root) {
        new FakeSysfs(root).usbDevice("1-1", "1234", "5678", "03", "0", null);
        Device d = manager(root, new StateResolver()).refresh().getFirst().devices().getFirst();
        DeviceActionService service = new DeviceActionService(new FakeCommandRunner(), "pkexec", "helper");
        assertEquals(DeviceState.DISABLED, d.state());
        assertTrue(service.canEnable(d));
        assertFalse(service.canDisable(d));
        assertTrue(d.driver().isEmpty());
    }

    @Test
    void usbActionsUseAuthorizationVerbsAndRespectUnknownCapabilities() {
        Device d = new Device("/sys/u", "/sys/u", "1-1", Bus.USB, "USB", "1234", "5678",
                DeviceCategory.INPUT, DeviceState.DISABLED, Optional.of("usb"), Map.of(), List.of(), Optional.of(false),
                DeviceActionKind.USB_AUTHORIZATION, "a".repeat(64), true, true);
        FakeCommandRunner runner = new FakeCommandRunner().stubStdout("verified", "pkexec", "helper", "enable-usb", "/sys/u", d.instanceId());
        DeviceActionService actions = new DeviceActionService(runner, "pkexec", "helper");
        assertTrue(actions.setDeviceEnabled(d, true).isSuccess());
        assertEquals("enable-usb", runner.invocations().getFirst().get(2));
        Device unsupported = new Device("/sys/class/net/x", "/sys/class/net/x", "x", Bus.OTHER,
                "Interface", "", "", DeviceCategory.NETWORK, DeviceState.UNKNOWN, Optional.empty(), Map.of());
        assertFalse(actions.canEnable(unsupported));
        assertFalse(actions.canDisable(unsupported));
        assertEquals(DeviceActionResult.Outcome.UNSUPPORTED, actions.setDeviceEnabled(unsupported, true).outcome());
    }

    @Test
    void usbScannerUsesInterfaceModulesNotGenericParent(@TempDir Path root) throws Exception {
        FakeSysfs fs = new FakeSysfs(root);
        Path parent = fs.usbDevice("1-1", "1234", "5678", "01", "1", "snd-usb-audio");
        Path hid = parent.resolve("1-1:1.1");
        write(hid.resolve("bInterfaceClass"), "03");
        fs.linkDriver(hid, "usbhid", "usbhid");
        SysfsDevice raw = new SysfsScanner(root).scan().getFirst();
        assertEquals(2, raw.usbInterfaces().size());
        assertEquals(List.of("snd-usb-audio", "usbhid"), raw.driverBindings().stream().map(DriverBinding::name).toList());
        assertEquals(Optional.of("snd_usb_audio"), raw.driverBindings().getFirst().module());
        assertEquals(DeviceCategory.MULTIMEDIA, new Categorizer().categorize(raw));
        Files.delete(parent.resolve("1-1:1.0/driver")); Files.delete(hid.resolve("driver"));
        assertEquals(DeviceState.UNKNOWN, new StateResolver().resolve(new SysfsScanner(root).scan().getFirst()));
    }

    @Test
    void compositeClassificationIsStableAndVideoPrecedesAudio(@TempDir Path root) throws Exception {
        FakeSysfs fs = new FakeSysfs(root);
        Path first = fs.usbDevice("1-1", "1234", "5678", "03", "1", "usbhid");
        write(first.resolve("1-1:1.7/bInterfaceClass"), "01");
        Path second = fs.usbDevice("1-2", "1234", "5678", "01", "1", "snd-usb-audio");
        write(second.resolve("1-2:1.7/bInterfaceClass"), "03");
        Path webcam = fs.usbDevice("1-3", "1234", "5678", "01", "1", "snd-usb-audio");
        write(webcam.resolve("1-3:1.7/bInterfaceClass"), "0e");
        assertEquals(List.of(DeviceCategory.MULTIMEDIA, DeviceCategory.MULTIMEDIA, DeviceCategory.IMAGING),
                new SysfsScanner(root).scan().stream().map(new Categorizer()::categorize).toList());
    }

    @Test
    void usbCdcAndVendorSpecificEthernetAreNetworkDevices(@TempDir Path root) throws Exception {
        FakeSysfs fs = new FakeSysfs(root);
        Path cdc = fs.usbDevice("1-1", "1234", "5678", "02", "1", "cdc_ether");
        write(cdc.resolve("1-1:1.0/bInterfaceSubClass"), "06");
        fs.usbDevice("1-2", "1234", "5678", "ff", "1", "r8152");
        Path ncm = fs.usbDevice("1-3", "1234", "5678", "02", "1", null);
        write(ncm.resolve("1-3:1.0/bInterfaceSubClass"), "0d");
        assertTrue(new SysfsScanner(root).scan().stream().allMatch(d -> new Categorizer().categorize(d) == DeviceCategory.NETWORK));
    }

    @Test
    void genericBusClassNodesAndAliasesHaveUsefulUniqueIdentity(@TempDir Path root) throws Exception {
        FakeSysfs fs = new FakeSysfs(root);
        Path controller = fs.pciDevice("0000:01:00.0", "0x010000", "0x1234", "0x5678", "ahci");
        Path disk = controller.resolve("block/sda");
        write(disk.resolve("device/model"), "Fixture disk");
        Files.createDirectories(root.resolve("class/block"));
        Files.createSymbolicLink(root.resolve("class/block/sda"), disk);
        Files.createSymbolicLink(root.resolve("class/block/alias"), disk);
        write(root.resolve("class/block/sda1/partition"), "1");
        Path nic = fs.pciDevice("0000:02:00.0", "0x020000", "0x1234", "0x5678", "igc");
        Path net = nic.resolve("net/eth0");
        write(net.resolve("operstate"), "up");
        Files.createDirectories(root.resolve("class/net"));
        Files.createSymbolicLink(root.resolve("class/net/eth0"), net);
        Path audio = root.resolve("bus/platform/devices/audit-audio");
        write(audio.resolve("modalias"), "platform:audit-audio");
        Path firmware = Files.createDirectories(root.resolve("firmware/devicetree/base/audio"));
        Files.createSymbolicLink(audio.resolve("of_node"), firmware);
        List<Device> all = manager(root, new StateResolver()).refresh().stream().flatMap(g -> g.devices().stream()).toList();
        assertEquals(4, all.size());
        assertEquals(4, all.stream().map(Device::id).distinct().count());
        Device drive = all.stream().filter(d -> d.busInfo().equals("sda")).findFirst().orElseThrow();
        assertEquals(DeviceCategory.STORAGE, drive.category());
        assertEquals("Fixture disk", drive.displayName());
        assertEquals(DeviceActionKind.NONE, drive.actionKind());
        assertEquals(Optional.of("ahci"), drive.driver());
        assertTrue(all.stream().anyMatch(d -> d.category() == DeviceCategory.MULTIMEDIA));
        assertTrue(all.stream().anyMatch(d -> d.category() == DeviceCategory.NETWORK));
    }

    @Test
    void classInterfaceUsesItsHardwareOwnersCanonicalIdentityAndLogs(@TempDir Path root) throws Exception {
        FakeSysfs fs = new FakeSysfs(root);
        Path controller = fs.pciDevice("0000:01:00.0", "0x020000", "0x1234", "0x5678", "igc");
        Path net = root.resolve("class/net/eth0");
        Files.createDirectories(net);
        Files.createSymbolicLink(net.resolve("device"), controller);
        List<Device> devices = manager(root, new StateResolver()).refresh().stream().flatMap(g -> g.devices().stream()).toList();
        assertEquals(1, devices.size());
        Device d = devices.getFirst();
        assertEquals("eth0", d.properties().get("SYSFS_NET"));
        assertEquals(controller.toRealPath().toString(), d.driverBindings().getFirst().syspath());
        assertEquals(DeviceActionKind.DRIVER_BINDING, d.actionKind());
        var runner = new FakeCommandRunner().stubStdout("kernel: unrelated device event\n"
                + "igc 0000:01:00.0: link changed", "journalctl", "-k", "-b", "--no-pager");
        String logs = new LogsDetailProvider(runner, "journalctl", "dmesg").load(d);
        assertFalse(logs.contains("unrelated"));
        assertTrue(logs.contains("link changed"));
    }

    @Test
    void nonPciBusCategoriesDoNotMisreportUnmanagedNodesAsMissingDrivers(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("bus/cpu/devices/cpu0"));
        Files.createDirectories(root.resolve("bus/memory/devices/memory0"));
        write(root.resolve("bus/acpi/devices/ACPI0000:00/hid"), "ACPI0000");
        List<Device> all = manager(root, new StateResolver()).refresh().stream().flatMap(g -> g.devices().stream()).toList();
        assertEquals(Set.of(DeviceCategory.PROCESSOR, DeviceCategory.SYSTEM),
                all.stream().map(Device::category).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, all.size(), "a memory hotplug range is not a memory module");
        assertTrue(all.stream().allMatch(d -> d.actionKind() == DeviceActionKind.NONE && d.state() == DeviceState.UNKNOWN));
    }

    @Test
    void moduleLookupUsesOwningModuleAndGroupsSharedInterfaces() {
        FakeCommandRunner runner = new FakeCommandRunner().stubStdout("name: i2c_i801", "modinfo", "i2c_i801");
        Device d = device(Optional.of("i801_smbus"), List.of(
                new DriverBinding("/sys/audit", "i801_smbus", Optional.of("i2c_i801")),
                new DriverBinding("/sys/audit2", "i801_smbus", Optional.of("i2c_i801"))));
        String text = new DriverDetailProvider(runner, "modinfo").load(d);
        assertTrue(text.contains("name: i2c_i801"));
        assertTrue(text.contains("audit, audit2"));
        assertEquals(1, runner.invocations().size());
    }

    @Test
    void builtInDriversDoNotInventModuleNames() {
        FakeCommandRunner runner = new FakeCommandRunner();
        String text = new DriverDetailProvider(runner, "modinfo").load(device(Optional.of("builtin"),
                List.of(new DriverBinding("/sys/audit", "builtin", Optional.empty()))));
        assertTrue(text.contains("built into the kernel"));
        assertTrue(runner.invocations().isEmpty());
    }

    @Test
    void verifiedUnbindStateSurvivesRefreshButNotRebindingOrRemoval(@TempDir Path root) throws Exception {
        FakeSysfs fs = new FakeSysfs(root);
        Path path = fs.pciDevice("0000:01:00.0", "0x020000", "0x1234", "0x5678", "igc");
        StateResolver state = new StateResolver(); DeviceManager manager = manager(root, state);
        Device initial = manager.refresh().getFirst().devices().getFirst();
        Files.delete(path.resolve("driver")); manager.recordAction(initial, false);
        assertEquals(DeviceState.DISABLED, manager.refresh().getFirst().devices().getFirst().state());
        fs.linkDriver(path, "igc", "igc");
        assertEquals(DeviceState.ACTIVE, manager.refresh().getFirst().devices().getFirst().state());
        Files.delete(path.resolve("driver"));
        assertEquals(DeviceState.INACTIVE_NO_DRIVER, manager.refresh().getFirst().devices().getFirst().state());
        manager.recordAction(initial, false); state.retainDevices(List.of());
        assertEquals(DeviceState.INACTIVE_NO_DRIVER, manager.refresh().getFirst().devices().getFirst().state());
    }

    @Test
    void usbDisableHistoryCannotOverrideCurrentKernelAuthorization(@TempDir Path root) throws Exception {
        Path path = new FakeSysfs(root).usbDevice("1-1", "1234", "5678", "03", "0", null);
        StateResolver state = new StateResolver();
        DeviceManager manager = manager(root, state);
        Device disabled = manager.refresh().getFirst().devices().getFirst();
        manager.recordAction(disabled, false);
        Files.writeString(path.resolve("authorized"), "1");
        assertEquals(DeviceState.UNKNOWN, manager.refresh().getFirst().devices().getFirst().state());
    }
}
