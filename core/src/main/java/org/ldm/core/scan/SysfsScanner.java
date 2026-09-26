package org.ldm.core.scan;

import org.ldm.core.model.Bus;
import org.ldm.core.model.DeviceActionKind;
import org.ldm.core.model.DriverBinding;
import org.ldm.core.model.SysfsDevice;
import org.ldm.core.model.UsbInterface;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Reads sysfs nodes, then resolves them into a hardware inventory. */
public final class SysfsScanner {
    // These buses support device driver binding. Infrastructure such as RAM, CPUs, clocks,
    // workqueues and performance counters has different state/management interfaces.
    private static final List<String> DRIVER_BUSES = List.of("pci", "platform", "acpi", "pnp",
            "hid", "serio", "i2c", "spi", "scsi", "hdaudio", "auxiliary", "pci_express",
            "wmi", "mei", "sdio", "virtio", "amba", "xen", "mdio", "thunderbolt", "mmc");
    private final Path sysRoot;

    public SysfsScanner(Path sysRoot) {
        this.sysRoot = sysRoot.toAbsolutePath().normalize();
    }

    public List<SysfsDevice> scan() {
        Map<String, SysfsDevice> devices = new LinkedHashMap<>();
        // Canonical paths remove symlink aliases. HardwareInventory also resolves the different
        // kernel nodes that describe the same hardware (for example a PCI NIC and its netdev).
        for (Path bus : listDir(sysRoot.resolve("bus"))) {
            if (Thread.currentThread().isInterrupted()) break;
            String subsystem = bus.getFileName().toString();
            for (Path entry : listDir(bus.resolve("devices"))) {
                if (Thread.currentThread().isInterrupted()) break;
                if (subsystem.equals("usb") && !Files.exists(entry.resolve("idVendor"))) continue;
                add(devices, entry, subsystem, "");
            }
        }
        for (String kind : List.of("block", "net", "sound", "input", "video4linux", "drm")) {
            for (Path entry : listDir(sysRoot.resolve("class").resolve(kind))) {
                if (Thread.currentThread().isInterrupted()) break;
                // Partitions and ALSA/DRM endpoint files aren't separate hardware devices.
                String name = entry.getFileName().toString();
                if (kind.equals("block") && Files.exists(entry.resolve("partition"))) continue;
                if ((kind.equals("sound") || kind.equals("drm")) && !name.matches("card[0-9]+")) continue;
                if (kind.equals("input") && !name.matches("input[0-9]+")) continue;
                add(devices, entry, kind, kind);
            }
        }
        return new HardwareInventory(sysRoot).consolidate(devices);
    }

    private void add(Map<String, SysfsDevice> devices, Path entry, String subsystem, String kind) {
        if (!Files.isDirectory(entry)) return;
        Path path;
        try {
            path = entry.toRealPath();
        } catch (IOException e) {
            return; // A device may disappear between directory enumeration and inspection.
        }
        if (!path.startsWith(sysRoot)) return;
        String instanceId = SysfsIdentity.read(path);
        SysfsDevice raw = readDevice(path, subsystem, kind, instanceId);
        if (!instanceId.equals(SysfsIdentity.read(path))) return;
        if (!devices.containsKey(raw.syspath()) || !kind.isEmpty()) {
            // Class identity supplies a more useful function than a generic platform bus entry.
            devices.put(raw.syspath(), raw);
        }
    }

    private SysfsDevice readDevice(Path path, String subsystem, String kind, String instanceId) {
        String actualSubsystem = readLinkName(path.resolve("subsystem")).orElse(subsystem);
        Bus bus = switch (actualSubsystem) {
            case "pci" -> Bus.PCI;
            case "usb" -> Bus.USB;
            default -> Bus.OTHER;
        };
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("SUBSYSTEM", actualSubsystem);
        if (!kind.isEmpty()) attributes.put("DEVICE_CLASS", kind);
        for (String file : List.of("modalias", "model", "name", "product", "manufacturer",
                "serial", "busnum", "devnum", "operstate", "size", "type", "online", "state", "hid", "status")) {
            readFile(path.resolve(file)).filter(s -> !s.isBlank()).ifPresent(s -> attributes.put(file, s));
        }
        if (kind.equals("block")) {
            readFile(path.resolve("device/model")).ifPresent(s -> attributes.put("model", s));
        }
        readFile(path.resolve("uevent")).ifPresent(text -> text.lines().forEach(line -> {
            int equals = line.indexOf('=');
            if (equals > 0) attributes.put(line.substring(0, equals), line.substring(equals + 1));
        }));

        List<UsbInterface> interfaces = new ArrayList<>();
        List<DriverBinding> bindings = new ArrayList<>();
        Optional<Boolean> authorized = Optional.empty();
        int classCode = parseHex(readFile(path.resolve("class")).orElse("0"));
        if (bus == Bus.USB && Files.exists(path.resolve("idVendor"))) {
            classCode = parseHex(readFile(path.resolve("bDeviceClass")).orElse("0"));
            authorized = readFile(path.resolve("authorized"))
                    .filter(s -> s.equals("0") || s.equals("1")).map(s -> s.equals("1"));
            for (Path child : listDir(path)) {
                if (!Files.exists(child.resolve("bInterfaceClass"))) continue;
                Optional<DriverBinding> binding = readBinding(child);
                interfaces.add(new UsbInterface(child.toString(),
                        parseHex(readFile(child.resolve("bInterfaceClass")).orElse("0")),
                        parseHex(readFile(child.resolve("bInterfaceSubClass")).orElse("0")),
                        parseHex(readFile(child.resolve("bInterfaceProtocol")).orElse("0")), binding));
                binding.ifPresent(bindings::add);
            }
            // Never mistake the generic parent "usb" binding for a functional interface driver.
        } else {
            readBinding(path).ifPresent(bindings::add);
            if (bindings.isEmpty() && !kind.isEmpty()) {
                // Class devices often inherit a controller/interface driver, but that does not
                // make it safe to expose the ancestor's unbind operation on the class entry.
                Path owner = path.resolve("device");
                if (Files.isDirectory(owner)) readBinding(owner).ifPresent(bindings::add);
                for (Path ancestor = path.getParent(); bindings.isEmpty() && ancestor != null
                        && ancestor.startsWith(sysRoot); ancestor = ancestor.getParent()) {
                    readBinding(ancestor).ifPresent(bindings::add);
                }
            }
        }
        Optional<String> driver = bindings.isEmpty() ? Optional.empty() : Optional.of(
                String.join(", ", bindings.stream().map(DriverBinding::name).distinct().toList()));
        DeviceActionKind actionKind = DeviceActionKind.NONE;
        boolean enableSupported = false;
        boolean disableSupported = false;
        if (bus == Bus.USB && authorized.isPresent()) {
            actionKind = DeviceActionKind.USB_AUTHORIZATION;
            enableSupported = disableSupported = true;
        } else if (kind.isEmpty() && DRIVER_BUSES.contains(actualSubsystem)) {
            enableSupported = Files.isRegularFile(path.resolve("subsystem/drivers_probe"));
            disableSupported = Files.isRegularFile(path.resolve("driver/unbind"));
            if (enableSupported || disableSupported) actionKind = DeviceActionKind.DRIVER_BINDING;
        }
        return new SysfsDevice(path.toString(), path.getFileName().toString(), bus,
                stripHex(readFile(path.resolve(bus == Bus.USB ? "idVendor" : "vendor")).orElse("")),
                stripHex(readFile(path.resolve(bus == Bus.USB ? "idProduct" : "device")).orElse("")),
                classCode, driver, authorized, attributes, bindings, interfaces, actionKind,
                instanceId, enableSupported && !instanceId.isEmpty(), disableSupported && !instanceId.isEmpty());
    }

    private Optional<DriverBinding> readBinding(Path path) {
        try {
            Path owner = path.toRealPath();
            return readLinkName(owner.resolve("driver")).map(driver -> new DriverBinding(owner.toString(), driver,
                    readLinkName(owner.resolve("driver/module"))));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<String> readLinkName(Path path) {
        if (!Files.isSymbolicLink(path)) return Optional.empty();
        try {
            return Optional.of(path.toRealPath().getFileName().toString());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private List<Path> listDir(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.sorted().toList();
        } catch (java.nio.file.NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<String> readFile(Path path) {
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            return Optional.of(Files.readString(path).trim());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static int parseHex(String text) {
        try { return Integer.parseInt(stripHex(text), 16); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String stripHex(String value) {
        String v = value.trim();
        return v.startsWith("0x") || v.startsWith("0X") ? v.substring(2) : v;
    }
}
