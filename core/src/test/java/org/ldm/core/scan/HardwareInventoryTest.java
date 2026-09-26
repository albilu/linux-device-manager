package org.ldm.core.scan;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ldm.core.DeviceManager;
import org.ldm.core.categorize.Categorizer;
import org.ldm.core.detail.AdvancedDetailProvider;
import org.ldm.core.model.*;
import org.ldm.core.process.FakeCommandRunner;
import org.ldm.core.state.StateResolver;
import org.ldm.core.udev.UdevEnricher;

class HardwareInventoryTest {
    @TempDir Path root;

    @Test
    void networkAdaptersAppearOnceWithTheirInterfacesAndOwnActionIdentity() throws Exception {
        Path ethernet = pci("0000:01:00.0", "020000", "igc");
        Path wifi = pci("0000:02:00.0", "028000", null);
        endpoint(ethernet, "net", "veth-physical"); // Names never decide physical/virtual status.
        endpoint(ethernet, "net", "enp1s0");
        endpoint(wifi, "net", "wlan0");
        endpoint(root.resolve("devices/virtual"), "net", "eth0");
        endpoint(root.resolve("devices/virtual"), "net", "docker0");
        endpoint(root.resolve("devices/virtual"), "net", "lo");
        List<Device> devices = devices();
        assertEquals(Set.of(ethernet.toString(), wifi.toString()), paths(devices));
        assertTrue(devices.stream().allMatch(d -> d.category() == DeviceCategory.NETWORK));
        Device nic = devices.stream().filter(d -> d.syspath().equals(ethernet.toString())).findFirst().orElseThrow();
        assertEquals("enp1s0, veth-physical", nic.properties().get("SYSFS_NET"));
        assertEquals(SysfsIdentity.read(ethernet), nic.instanceId());
        assertEquals(DeviceActionKind.DRIVER_BINDING, nic.actionKind());
        assertTrue(nic.disableSupported());
        assertTrue(new AdvancedDetailProvider(new FakeCommandRunner(), "lspci", "lsusb")
                .load(nic).contains("Interfaces: enp1s0, veth-physical"));
        assertEquals(DeviceState.INACTIVE_NO_DRIVER, devices.stream().filter(d -> d.syspath().equals(wifi.toString()))
                .findFirst().orElseThrow().state());
    }

    @Test
    void displayAudioAndCompositeCameraEndpointsBelongToTheirHardware() throws Exception {
        Path gpu = pci("0000:01:00.0", "030000", "gpu");
        endpoint(gpu, "drm", "card0");
        endpoint(gpu, "drm", "card0-HDMI-A-1");
        Path audio = pci("0000:01:00.1", "040300", "snd_hda_intel");
        busNode("hdaudio", audio.resolve("hdaudioC0D0"));
        Path card = endpoint(audio, "sound", "card0");
        endpoint(audio, "sound", "pcmC0D0p");
        endpoint(card, "input", "input0"); // HDMI jack detection is not a keyboard.
        Path camera = usb("1-1", "01", "snd-usb-audio");
        Path videoInterface = camera.resolve("1-1:1.1");
        write(videoInterface.resolve("bInterfaceClass"), "0e");
        driver(videoInterface, "uvcvideo");
        endpoint(videoInterface, "video4linux", "video0");
        endpoint(videoInterface, "video4linux", "video1");
        endpoint(camera.resolve("1-1:1.0"), "sound", "card1");
        Path hid = busNode("hid", camera.resolve("1-1:1.2/0003:1234:5678.0001"));
        driver(hid, "hid-generic");
        endpoint(hid, "input", "input1");
        List<Device> devices = devices();
        assertEquals(Set.of(gpu.toString(), audio.toString(), camera.toString()), paths(devices));
        assertEquals(Set.of(DeviceCategory.DISPLAY, DeviceCategory.MULTIMEDIA, DeviceCategory.IMAGING),
                devices.stream().map(Device::category).collect(Collectors.toSet()));
        Device webcam = devices.stream().filter(d -> d.bus() == Bus.USB).findFirst().orElseThrow();
        assertEquals(Set.of("snd-usb-audio", "uvcvideo", "hid-generic"), webcam.driverBindings().stream()
                .map(DriverBinding::name).collect(Collectors.toSet()));
        assertEquals("video0, video1", webcam.properties().get("SYSFS_VIDEO4LINUX"));
    }

    @Test
    void softwareObjectsAreExcludedAcrossBusesAndClasses() throws Exception {
        Path real = pci("0000:01:00.0", "030000", null);
        for (String kind : List.of("block", "net", "sound", "input", "video4linux", "drm")) {
            String name = switch (kind) {
                case "sound", "drm" -> "card8";
                case "input" -> "input8";
                default -> "software0";
            };
            endpoint(root.resolve("devices/virtual"), kind, name);
        }
        for (String bus : List.of("workqueue", "event_source", "clocksource", "clockevents", "memory", "machinecheck", "faux")) {
            busNode(bus, root.resolve("devices/system/" + bus + "/object0"));
        }
        Path loopbackSound = busNode("platform", root.resolve("devices/platform/snd_aloop.0"));
        driver(loopbackSound, "snd_aloop");
        endpoint(loopbackSound, "sound", "card9");
        busNode("pci", root.resolve("devices/virtual/pci/0000:02:00.0"));
        assertEquals(Set.of(real.toString()), paths(devices()));
    }

    @Test
    void firmwareAliasesAndAbsentSlotsDoNotCreateExtraHardware() throws Exception {
        Path pci = pci("0000:01:00.0", "0c8000", "controller");
        Path firmware = acpi("INT0001:00", "INT0001", "15");
        Files.createSymbolicLink(firmware.resolve("physical_node"), pci);
        Files.createSymbolicLink(pci.resolve("firmware_node"), firmware);
        Path platform = busNode("platform", pci.resolve("platform-driver.0"));
        Files.createSymbolicLink(platform.resolve("firmware_node"), firmware);
        driver(platform, "subdriver");
        Path absent = acpi("ABSENT:00", "ABSENT", "0");
        Path absentPlatform = busNode("platform", root.resolve("devices/platform/ABSENT:00"));
        Files.createSymbolicLink(absentPlatform.resolve("firmware_node"), absent);
        acpi("ACPI0007:ff", "ACPI0007", null); // Possible CPU slot with no instantiated CPU.
        acpi("PNP0C14:00", "PNP0C14", "15"); // WMI control interface.
        busNode("acpi", root.resolve("devices/LNXSYSTM:00/device:ff")); // Namespace only.
        Path disabled = acpi("UNBOUND:00", "UNBOUND", "1"); // Present, even without a driver.
        List<Device> devices = devices();
        assertEquals(Set.of(pci.toString(), disabled.toString()), paths(devices));
        Device controller = devices.stream().filter(d -> d.bus() == Bus.PCI).findFirst().orElseThrow();
        assertEquals(Set.of("controller", "subdriver"), controller.driverBindings().stream()
                .map(DriverBinding::name).collect(Collectors.toSet()));
    }

    @Test
    void firmwareOwnedButtonsKeepTheirNameAndDoNotGainAliasBindingActions() throws Exception {
        Path firmware = acpi("PNP0C0C:00", "PNP0C0C", "15");
        driver(firmware, "button");
        Path platform = busNode("platform", root.resolve("devices/platform/PNP0C0C:00"));
        Files.createSymbolicLink(platform.resolve("firmware_node"), firmware);
        Files.createSymbolicLink(firmware.resolve("physical_node"), platform);
        Path input = endpoint(firmware, "input", "input0");
        write(input.resolve("name"), "Power Button");
        Device button = devices().getFirst();
        assertEquals(1, devices().size());
        assertEquals(platform.toString(), button.syspath());
        assertEquals(DeviceCategory.INPUT, button.category());
        assertEquals("Power Button", button.displayName());
        assertFalse(button.disableSupported(), "the alias's unbind file is not the retained node's capability");
    }

    @Test
    void cpuThreadsAreGroupedOnlyWhenPackageIdentityIsKnown() throws Exception {
        Path cpu0 = cpu(0, "0", "0");
        cpu(1, "0", "1");
        Path cpu2 = cpu(2, "1", "0");
        cpu(3, "1", "0");
        Path cpu4 = cpu(4, "-1", "1");
        Path cpu5 = cpu(5, null, "1");
        List<Device> devices = devices();
        assertEquals(Set.of(cpu0.toString(), cpu2.toString(), cpu4.toString(), cpu5.toString()), paths(devices));
        Device first = devices.stream().filter(d -> d.syspath().equals(cpu0.toString())).findFirst().orElseThrow();
        assertEquals("Processor package 0", first.displayName());
        assertEquals("cpu0, cpu1", first.properties().get("LOGICAL_CPUS"));
        assertEquals(DeviceState.ACTIVE, first.state());
        assertFalse(first.enableSupported() || first.disableSupported());
        assertEquals(DeviceState.DISABLED, devices.stream().filter(d -> d.syspath().equals(cpu2.toString()))
                .findFirst().orElseThrow().state());
    }

    @Test
    void controllersAndSeparateDrivesSurviveWhileScsiEndpointsAndPartitionsDoNotDuplicateThem() throws Exception {
        Path sata = pci("0000:01:00.0", "010600", "ahci");
        Path bridge = usb("1-1", "08", "uas");
        Set<String> expected = new java.util.HashSet<>(Set.of(sata.toString(), bridge.toString()));
        for (int i = 0; i < 4; i++) {
            Path owner = i < 2 ? sata : bridge.resolve("1-1:1.0");
            Path host = busNode("scsi", owner.resolve("host" + i));
            Path target = busNode("scsi", host.resolve("target" + i + ":0:0"));
            Path lun = busNode("scsi", target.resolve(i + ":0:0:0"));
            write(lun.resolve("type"), "0");
            write(lun.resolve("model"), "Drive " + i);
            driver(lun, "sd");
            Path disk = endpoint(lun, "block", "sd" + (char) ('a' + i));
            Files.createSymbolicLink(disk.resolve("device"), lun);
            expected.add(disk.toString());
            Path partition = endpoint(disk, "block", "sd" + (char) ('a' + i) + "1");
            write(partition.resolve("partition"), "1");
        }
        endpoint(root.resolve("devices/virtual"), "block", "dm-0");
        List<Device> devices = devices();
        assertEquals(expected, paths(devices));
        assertTrue(devices.stream().allMatch(d -> d.category() == DeviceCategory.STORAGE));
        List<Device> drives = devices.stream().filter(d -> d.busInfo().matches("sd[a-d]")).toList();
        assertEquals(4, drives.size());
        assertTrue(drives.stream().allMatch(d -> d.actionKind() == DeviceActionKind.NONE));
        assertEquals(Set.of("Drive 0", "Drive 1", "Drive 2", "Drive 3"), drives.stream().map(Device::displayName).collect(Collectors.toSet()));
    }

    @Test
    void nvmeNamespacesDoNotDuplicateThePciDrive() throws Exception {
        Path nvme = pci("0000:01:00.0", "010802", "nvme");
        Path controller = nvme.resolve("nvme/nvme0");
        endpoint(controller, "block", "nvme0n1");
        endpoint(controller, "block", "nvme0n2");
        List<Device> devices = devices();
        assertEquals(Set.of(nvme.toString()), paths(devices));
        assertEquals("nvme0n1, nvme0n2", devices.getFirst().properties().get("SYSFS_BLOCK"));
    }

    @Test
    void nonPciHardwareAndIdenticalUsbPeripheralsAreRetained() throws Exception {
        Path wifi = busNode("sdio", root.resolve("devices/platform/mmc/mmc1:0001:1"));
        endpoint(wifi, "net", "wlan1");
        Path touchpad = busNode("i2c", root.resolve("devices/platform/i2c-1/1-002c"));
        write(touchpad.resolve("modalias"), "i2c:touchpad");
        Path hid = busNode("hid", touchpad.resolve("0018:1234:5678.0001"));
        endpoint(hid, "input", "input0");
        busNode("i2c", root.resolve("devices/platform/i2c-1")); // Adapter handle, not another touchpad.
        Path usb1 = usb("1-1", "03", "usbhid");
        Path usb2 = usb("1-2", "03", null);
        write(usb2.resolve("authorized"), "0");
        List<Device> devices = devices();
        assertEquals(Set.of(wifi.toString(), touchpad.toString(), usb1.toString(), usb2.toString()), paths(devices));
        assertEquals(1, devices.stream().filter(d -> d.category() == DeviceCategory.NETWORK).count());
        assertEquals(3, devices.stream().filter(d -> d.category() == DeviceCategory.INPUT).count());
        Device disabled = devices.stream().filter(d -> d.syspath().equals(usb2.toString())).findFirst().orElseThrow();
        assertEquals(DeviceState.DISABLED, disabled.state());
        assertTrue(disabled.enableSupported());
    }

    @Test
    void bluetoothAndReceiverPairedInputDevicesAreNotMistakenForSoftwareOrReceiverAliases() throws Exception {
        Path bluetooth = busNode("hid", root.resolve("devices/virtual/misc/uhid/0005:1234:5678.0001"));
        write(bluetooth.resolve("uevent"), "HID_ID=0005:00001234:00005678\nHID_UNIQ=aa:bb:cc:dd:ee:ff\nHID_NAME=Bluetooth mouse");
        endpoint(bluetooth, "input", "input0");
        Path receiver = usb("1-1", "03", "usbhid");
        Path receiverHid = busNode("hid", receiver.resolve("1-1:1.0/0003:1234:5678.0001"));
        Path pairedMouse = busNode("hid", receiverHid.resolve("0003:1234:0001.0002"));
        Path pairedKeyboard = busNode("hid", receiverHid.resolve("0003:1234:0002.0003"));
        endpoint(pairedMouse, "input", "input1");
        endpoint(pairedKeyboard, "input", "input2");
        List<Device> devices = devices();
        assertEquals(Set.of(bluetooth.toString(), receiver.toString(), pairedMouse.toString(), pairedKeyboard.toString()), paths(devices));
        assertTrue(devices.stream().allMatch(d -> d.category() == DeviceCategory.INPUT));
        assertEquals("Bluetooth mouse", devices.stream().filter(d -> d.syspath().equals(bluetooth.toString())).findFirst().orElseThrow().displayName());
    }

    @Test
    void removingAnEndpointKeepsHardwareIdentityAndRemovingHardwareClearsDanglingAliases() throws Exception {
        Path nic = pci("0000:01:00.0", "020000", "nic");
        Path endpoint = endpoint(nic, "net", "eth0");
        DeviceManager manager = manager();
        Device before = manager.refresh().getFirst().devices().getFirst();
        Files.delete(root.resolve("class/net/eth0"));
        Device after = manager.refresh().getFirst().devices().getFirst();
        assertEquals(before.id(), after.id());
        assertEquals(before.instanceId(), after.instanceId());
        assertEquals(DeviceCategory.NETWORK, after.category());
        Files.createSymbolicLink(root.resolve("class/net/eth0"), endpoint);
        Files.move(nic, root.resolve("removed"));
        assertTrue(manager.refresh().isEmpty());
    }

    private DeviceManager manager() {
        return new DeviceManager(new SysfsScanner(root), new UdevEnricher(new FakeCommandRunner(), "udevadm"),
                new Categorizer(), new StateResolver(), Map.of());
    }

    private List<Device> devices() {
        return manager().refresh().stream().flatMap(g -> g.devices().stream()).toList();
    }

    private Set<String> paths(List<Device> devices) {
        return devices.stream().map(Device::syspath).collect(Collectors.toSet());
    }

    private Path busNode(String bus, Path path) throws Exception {
        Files.createDirectories(path);
        Path subsystem = Files.createDirectories(root.resolve("bus/" + bus));
        Files.createDirectories(subsystem.resolve("devices"));
        Files.writeString(subsystem.resolve("drivers_probe"), "");
        Files.createSymbolicLink(path.resolve("subsystem"), subsystem);
        Files.createSymbolicLink(subsystem.resolve("devices").resolve(path.getFileName()), path);
        return path;
    }

    private Path pci(String name, String clazz, String driver) throws Exception {
        Path path = busNode("pci", root.resolve("devices/pci0000:00/" + name));
        write(path.resolve("class"), clazz);
        write(path.resolve("vendor"), "0x1234");
        write(path.resolve("device"), "0x5678");
        if (driver != null) driver(path, driver);
        return path;
    }

    private Path usb(String name, String ifaceClass, String driver) throws Exception {
        Path path = busNode("usb", root.resolve("devices/platform/usb1/" + name));
        write(path.resolve("idVendor"), "1234");
        write(path.resolve("idProduct"), "5678");
        write(path.resolve("bDeviceClass"), "00");
        write(path.resolve("authorized"), "1");
        Path iface = path.resolve(name + ":1.0");
        write(iface.resolve("bInterfaceClass"), ifaceClass);
        if (driver != null) driver(iface, driver);
        return path;
    }

    private Path acpi(String name, String hid, String status) throws Exception {
        Path path = busNode("acpi", root.resolve("devices/LNXSYSTM:00/" + name));
        write(path.resolve("hid"), hid);
        if (status != null) write(path.resolve("status"), status);
        return path;
    }

    private Path cpu(int index, String packageId, String online) throws Exception {
        Path path = busNode("cpu", root.resolve("devices/system/cpu/cpu" + index));
        if (packageId != null) write(path.resolve("topology/physical_package_id"), packageId);
        write(path.resolve("online"), online);
        return path;
    }

    private Path endpoint(Path owner, String kind, String name) throws Exception {
        Path path = Files.createDirectories(owner.resolve(kind).resolve(name));
        Path subsystem = Files.createDirectories(root.resolve("class/" + kind));
        Files.createSymbolicLink(path.resolve("subsystem"), subsystem);
        Files.createSymbolicLink(subsystem.resolve(name), path);
        return path;
    }

    private void driver(Path path, String name) throws Exception {
        Path driver = Files.createDirectories(root.resolve("drivers/" + name));
        write(driver.resolve("unbind"), "");
        Files.createSymbolicLink(path.resolve("driver"), driver);
    }

    private void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value + "\n");
    }
}
