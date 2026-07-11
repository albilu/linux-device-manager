package org.ldm.core.scan;

import org.ldm.core.model.Bus;
import org.ldm.core.model.SysfsDevice;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Walks a sysfs tree and produces raw {@link SysfsDevice} records for PCI and
 * USB devices.
 * The sysfs root is injectable so tests can point it at a fixture tree.
 */
public final class SysfsScanner {

    private final Path sysRoot;

    public SysfsScanner(Path sysRoot) {
        this.sysRoot = sysRoot;
    }

    public List<SysfsDevice> scan() {
        List<SysfsDevice> devices = new ArrayList<>();
        devices.addAll(scanPci());
        devices.addAll(scanUsb());
        return devices;
    }

    private List<SysfsDevice> scanPci() {
        List<SysfsDevice> result = new ArrayList<>();
        for (Path d : listDir(sysRoot.resolve("bus/pci/devices"))) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            result.add(new SysfsDevice(
                    d.toAbsolutePath().toString(),
                    d.getFileName().toString(),
                    Bus.PCI,
                    stripHex(readFile(d.resolve("vendor")).orElse("")),
                    stripHex(readFile(d.resolve("device")).orElse("")),
                    parseHex(readFile(d.resolve("class")).orElse("0x0")),
                    readDriver(d),
                    Optional.empty(),
                    Map.of()));
        }
        return result;
    }

    private List<SysfsDevice> scanUsb() {
        List<SysfsDevice> result = new ArrayList<>();
        for (Path d : listDir(sysRoot.resolve("bus/usb/devices"))) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            Optional<String> idVendor = readFile(d.resolve("idVendor"));
            if (idVendor.isEmpty()) {
                continue; // interface node or non-device entry: skip
            }
            Optional<Boolean> authorized = readFile(d.resolve("authorized"))
                    .map(s -> s.equals("1"));
            result.add(new SysfsDevice(
                    d.toAbsolutePath().toString(),
                    d.getFileName().toString(),
                    Bus.USB,
                    idVendor.get(),
                    readFile(d.resolve("idProduct")).orElse(""),
                    readUsbInterfaceClass(d),
                    readDriver(d),
                    authorized,
                    Map.of()));
        }
        return result;
    }

    private int readUsbInterfaceClass(Path deviceDir) {
        String prefix = deviceDir.getFileName().toString() + ":";
        try (Stream<Path> entries = Files.list(deviceDir.getParent())) {
            return entries
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .map(p -> readFile(p.resolve("bInterfaceClass")))
                    .flatMap(Optional::stream)
                    .map(s -> parseHex("0x" + s))
                    .findFirst()
                    .orElseGet(() -> parseHex("0x" + readFile(deviceDir.resolve("bDeviceClass")).orElse("0")));
        } catch (IOException e) {
            return 0;
        }
    }

    private Optional<String> readDriver(Path deviceDir) {
        Path link = deviceDir.resolve("driver");
        if (Files.isSymbolicLink(link)) {
            try {
                return Optional.of(Files.readSymbolicLink(link).getFileName().toString());
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private List<Path> listDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<String> readFile(Path p) {
        if (!Files.isRegularFile(p)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(p).trim());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static int parseHex(String value) {
        String v = value.trim();
        if (v.startsWith("0x") || v.startsWith("0X")) {
            v = v.substring(2);
        }
        if (v.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(v, 16);
        } catch (NumberFormatException e) {
            return 0; // malformed sysfs value: degrade this device rather than aborting the scan
        }
    }

    private static String stripHex(String value) {
        String v = value.trim();
        return (v.startsWith("0x") || v.startsWith("0X")) ? v.substring(2) : v;
    }
}
