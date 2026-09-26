package org.ldm.core.scan;

import org.ldm.core.model.Bus;
import org.ldm.core.model.DriverBinding;
import org.ldm.core.model.SysfsDevice;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Selects hardware from the kernel's device model. A sysfs node is not necessarily a
 * piece of hardware: it can be a software interface, a firmware alias, or a service
 * belonging to another device. Ownership follows canonical parents and firmware links,
 * never a display name or vendor/product ID (two identical peripherals must survive).
 */
final class HardwareInventory {
    // Service buses (workqueue, event_source, clockevents, pci_express, mei, wmi, etc.)
    // and memory hotplug ranges are not independent hardware. Keep bus-attached devices,
    // including the hardware presented to guests, even when no driver is bound.
    private static final Set<String> HARDWARE_BUSES = Set.of("pci", "usb", "platform", "acpi", "pnp",
            "cpu", "hid", "serio", "i2c", "spi", "scsi", "hdaudio", "sdio", "virtio", "amba",
            "xen", "mdio", "thunderbolt", "mmc", "ccw", "ccwgroup", "bcma", "ssb", "vio",
            "vmbus", "firewire", "pcmcia", "mhi", "spmi", "rmi", "serdev");
    // These legacy PC devices predate firmware nodes and do not export resource files.
    private static final Set<String> LEGACY_PLATFORM = Set.of("i8042", "pcspkr", "rtc_cmos", "serial8250");
    // Namespace containers, control interfaces and processor/display aliases. CPU and PCI
    // enumeration supplies the instantiated hardware, rather than firmware's possible slots.
    private static final Set<String> ACPI_INFRASTRUCTURE = Set.of("LNXSYSTM", "LNXSYBUS", "LNXPOWER", "LNXTHERM",
            "LNXCPU", "LNXVIDEO", "ACPI0007", "ACPI000C", "ACPI0004", "PRP00001", "PNP0A05", "PNP0A06",
            "PNP0C0F", "PNP0C14");
    private final Path sysRoot;

    HardwareInventory(Path sysRoot) {
        this.sysRoot = sysRoot;
    }

    List<SysfsDevice> consolidate(Map<String, SysfsDevice> scanned) {
        Map<String, SysfsDevice> hardware = new LinkedHashMap<>();
        scanned.values().stream().filter(d -> kind(d).isEmpty()).filter(this::isHardware)
                .forEach(d -> hardware.put(d.syspath(), d));
        // A functional endpoint needs a hardware owner. This also rejects platform software
        // devices such as snd-aloop, whose paths need not live below devices/virtual.
        scanned.values().stream().filter(d -> !kind(d).isEmpty())
                .filter(d -> (!isVirtual(d) || hasBluetoothParent(d, hardware))
                        && ancestor(d, hardware, p -> true).isPresent())
                .forEach(d -> hardware.put(d.syspath(), d));

        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, String> packages = new LinkedHashMap<>();
        for (SysfsDevice device : hardware.values()) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            Path path = Path.of(device.syspath());
            String subsystem = subsystem(device);
            if (subsystem.equals("cpu")) {
                read(path.resolve("topology/physical_package_id")).filter(s -> s.matches("[0-9]+"))
                        .ifPresent(id -> {
                            String first = packages.putIfAbsent(id, device.syspath());
                            if (first != null) aliases.put(device.syspath(), first);
                        });
            } else if (subsystem.equals("acpi")) {
                physicalNodes(path).stream().filter(hardware::containsKey).findFirst()
                        .ifPresent(owner -> aliases.put(device.syspath(), owner));
            } else if (subsystem.equals("platform")) {
                // A PCI function and its platform subdrivers can share one firmware node.
                canonical(path.resolve("firmware_node")).ifPresent(firmware ->
                        physicalNodes(firmware).stream().filter(hardware::containsKey)
                                .filter(owner -> !owner.equals(device.syspath()) && path.startsWith(Path.of(owner)))
                                .findFirst().ifPresent(owner -> aliases.put(device.syspath(), owner)));
            } else if (subsystem.equals("hid")) {
                // A HID nested under another HID is a separate paired wireless peripheral.
                // Direct USB/I2C HID functions, however, belong to their bus device.
                ancestor(device, hardware, p -> true).filter(parent -> !subsystem(parent).equals("hid"))
                        .filter(parent -> Set.of("usb", "i2c", "spi", "serio").contains(subsystem(parent)))
                        .ifPresent(owner -> aliases.put(device.syspath(), owner.syspath()));
            } else if (subsystem.equals("hdaudio") || subsystem.equals("virtio")) {
                ancestor(device, hardware, p -> true)
                        .ifPresent(owner -> aliases.put(device.syspath(), owner.syspath()));
            }

            if (!kind(device).isEmpty() && !kind(device).equals("block")) {
                ancestor(device, hardware, p -> true)
                        .ifPresent(owner -> aliases.put(device.syspath(), owner.syspath()));
            }
        }
        // The SCSI LUN and its block node describe the same drive. Keep the block identity;
        // do not collapse multiple drives into their shared SATA/USB/storage controller.
        for (SysfsDevice device : hardware.values()) {
            if (!kind(device).equals("block")) continue;
            ancestor(device, hardware, p -> subsystem(p).equals("scsi"))
                    .ifPresent(owner -> aliases.putIfAbsent(owner.syspath(), device.syspath()));
            // NVMe namespaces are logical subdivisions of the PCI NVMe device.
            ancestor(device, hardware, p -> p.bus() == Bus.PCI && p.pciBaseClass() == 1 && p.pciSubClass() == 8)
                    .ifPresent(owner -> aliases.put(device.syspath(), owner.syspath()));
        }

        Map<String, List<SysfsDevice>> groups = new LinkedHashMap<>();
        for (SysfsDevice device : hardware.values()) {
            String owner = representative(device.syspath(), aliases);
            groups.computeIfAbsent(owner, k -> new ArrayList<>()).add(device);
        }
        return groups.entrySet().stream().map(entry -> merge(hardware.get(entry.getKey()), entry.getValue())).toList();
    }

    private boolean isHardware(SysfsDevice device) {
        String subsystem = subsystem(device);
        Path path = Path.of(device.syspath());
        if (!HARDWARE_BUSES.contains(subsystem) || (isVirtual(device) && !isBluetoothHid(device))) return false;
        return switch (subsystem) {
            case "acpi" -> {
                String hid = device.attributes().getOrDefault("hid", "");
                yield !hid.isBlank() && !ACPI_INFRASTRUCTURE.contains(hid) && present(device.attributes().get("status"));
            }
            case "platform" -> isPlatformHardware(device);
            case "i2c" -> !device.attributes().getOrDefault("modalias", "").isBlank(); // clients, not adapter handles
            case "scsi" -> device.attributes().containsKey("type"); // LUNs, not host/target containers
            default -> true;
        };
    }

    private boolean isPlatformHardware(SysfsDevice device) {
        Path path = Path.of(device.syspath());
        Optional<Path> firmware = canonical(path.resolve("firmware_node"));
        if (firmware.isPresent()) {
            return !read(firmware.get().resolve("hid")).filter(ACPI_INFRASTRUCTURE::contains).isPresent()
                    && present(read(firmware.get().resolve("status")).orElse(null));
        }
        return canonical(path.resolve("of_node")).isPresent()
                || read(path.resolve("resource")).filter(s -> !s.isBlank()).isPresent()
                || hasBusAncestor(path) || LEGACY_PLATFORM.contains(device.busInfo());
    }

    private boolean hasBusAncestor(Path path) {
        for (Path parent = path.getParent(); parent != null && parent.startsWith(sysRoot); parent = parent.getParent()) {
            if (canonical(parent.resolve("subsystem")).map(p -> Set.of("pci", "pnp", "usb", "i2c", "spi", "amba")
                    .contains(p.getFileName().toString())).orElse(false)) return true;
        }
        return false;
    }

    private boolean present(String status) {
        if (status == null) return true; // ACPI's default when _STA is absent.
        try {
            return (Integer.decode(status) & 1) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isVirtual(SysfsDevice device) {
        return Path.of(device.syspath()).startsWith(sysRoot.resolve("devices/virtual"));
    }

    private boolean isBluetoothHid(SysfsDevice device) {
        // BlueZ creates real Bluetooth HID peripherals through uhid, below devices/virtual.
        return subsystem(device).equals("hid")
                && device.attributes().getOrDefault("HID_ID", "").startsWith("0005:")
                && !device.attributes().getOrDefault("HID_UNIQ", "").isBlank();
    }

    private boolean hasBluetoothParent(SysfsDevice device, Map<String, SysfsDevice> hardware) {
        return ancestor(device, hardware, this::isBluetoothHid).isPresent();
    }

    private Optional<SysfsDevice> ancestor(SysfsDevice device, Map<String, SysfsDevice> hardware,
                                           Predicate<SysfsDevice> matches) {
        Path path = Path.of(device.syspath());
        for (Path parent = path.getParent(); parent != null && parent.startsWith(sysRoot); parent = parent.getParent()) {
            SysfsDevice candidate = hardware.get(parent.toString());
            if (candidate != null && matches.test(candidate)) return Optional.of(candidate);
        }
        // Compatibility with old sysfs layouts, where a class directory was not under devices.
        if (!path.startsWith(sysRoot.resolve("devices"))) {
            return canonical(path.resolve("device")).map(p -> hardware.get(p.toString())).filter(matches);
        }
        return Optional.empty();
    }

    private List<String> physicalNodes(Path path) {
        try (var children = Files.list(path)) {
            return children.filter(p -> p.getFileName().toString().matches("physical_node[0-9]*"))
                    .sorted().map(this::canonical).flatMap(Optional::stream).map(Path::toString).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private String representative(String path, Map<String, String> aliases) {
        Set<String> visited = new LinkedHashSet<>();
        while (aliases.containsKey(path) && visited.add(path)) path = aliases.get(path);
        return path;
    }

    private SysfsDevice merge(SysfsDevice owner, List<SysfsDevice> members) {
        Map<String, String> attributes = new LinkedHashMap<>(owner.attributes());
        Set<String> classes = new LinkedHashSet<>();
        Map<String, Set<String>> endpoints = new LinkedHashMap<>();
        Set<DriverBinding> bindings = new LinkedHashSet<>(owner.driverBindings());
        for (SysfsDevice member : members) {
            bindings.addAll(member.driverBindings());
            String kind = kind(member);
            if (kind.isEmpty()) continue;
            classes.add(kind);
            endpoints.computeIfAbsent(kind, k -> new LinkedHashSet<>()).add(member.busInfo());
            // A platform button or paired HID can get its useful label from the input node.
            if (owner.bus() == Bus.OTHER && !subsystem(owner).equals("cpu")) {
                for (String key : List.of("name", "model", "product")) {
                    String value = member.attributes().get(key);
                    if (value != null && !value.isBlank()) attributes.putIfAbsent(key, value);
                }
            }
        }
        if (!classes.isEmpty()) attributes.put("FUNCTIONAL_CLASSES", String.join(",", classes));
        endpoints.forEach((kind, names) -> attributes.put("SYSFS_" + kind.toUpperCase(java.util.Locale.ROOT), String.join(", ", names)));
        if (subsystem(owner).equals("cpu")) {
            read(Path.of(owner.syspath()).resolve("topology/physical_package_id")).filter(s -> s.matches("[0-9]+"))
                    .ifPresent(id -> {
                        attributes.put("PHYSICAL_PACKAGE_ID", id);
                        attributes.put("name", "Processor package " + id);
                        attributes.put("LOGICAL_CPUS", members.stream().filter(d -> subsystem(d).equals("cpu"))
                                .map(SysfsDevice::busInfo).collect(Collectors.joining(", ")));
                        boolean online = members.stream().filter(d -> subsystem(d).equals("cpu"))
                                .anyMatch(d -> !d.attributes().getOrDefault("online", "1").equals("0"));
                        attributes.put("online", online ? "1" : "0");
                    });
        }
        Optional<String> driver = bindings.isEmpty() ? owner.driver() : Optional.of(bindings.stream()
                .map(DriverBinding::name).distinct().collect(Collectors.joining(", ")));
        // Only the retained node's own capability/instance may authorize an action. Neither
        // a child driver nor a firmware alias grants permission to unbind another device.
        return new SysfsDevice(owner.syspath(), owner.busInfo(), owner.bus(), owner.vendorId(), owner.productId(),
                owner.classCode(), driver, owner.authorized(), attributes, List.copyOf(bindings), owner.usbInterfaces(),
                owner.actionKind(), owner.instanceId(), owner.enableSupported(), owner.disableSupported());
    }

    private Optional<Path> canonical(Path path) {
        try {
            Path real = path.toRealPath();
            return real.startsWith(sysRoot) ? Optional.of(real) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<String> read(Path path) {
        try {
            return Files.isRegularFile(path) ? Optional.of(Files.readString(path).strip()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String subsystem(SysfsDevice device) {
        return device.attributes().getOrDefault("SUBSYSTEM", "");
    }

    private static String kind(SysfsDevice device) {
        return device.attributes().getOrDefault("DEVICE_CLASS", "");
    }
}
