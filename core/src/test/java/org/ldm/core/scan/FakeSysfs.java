package org.ldm.core.scan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds fake sysfs trees under a temp directory for scanner tests. Public for
 * cross-package use.
 */
public final class FakeSysfs {

    private final Path root;

    public FakeSysfs(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /**
     * Create a PCI device dir with class/vendor/device files and an optional driver
     * symlink.
     */
    public Path pciDevice(String slot, String classCode, String vendor, String device, String driver) {
        Path dir = root.resolve("bus/pci/devices").resolve(slot);
        write(dir.resolve("class"), classCode);
        write(dir.resolve("vendor"), vendor);
        write(dir.resolve("device"), device);
        if (driver != null) {
            linkDriver(dir, driver);
        }
        return dir;
    }

    /**
     * Create a USB device dir plus one interface node carrying the given interface
     * class.
     */
    public Path usbDevice(String name, String idVendor, String idProduct, String ifaceClass,
            String authorized, String driver) {
        Path dir = root.resolve("bus/usb/devices").resolve(name);
        write(dir.resolve("idVendor"), idVendor);
        write(dir.resolve("idProduct"), idProduct);
        write(dir.resolve("bDeviceClass"), "00");
        write(dir.resolve("authorized"), authorized);
        Path iface = root.resolve("bus/usb/devices").resolve(name + ":1.0");
        write(iface.resolve("bInterfaceClass"), ifaceClass);
        if (driver != null) {
            linkDriver(dir, driver);
        }
        return dir;
    }

    private void linkDriver(Path deviceDir, String driver) {
        try {
            Files.createDirectories(deviceDir);
            Path target = root.resolve("bus/drivers").resolve(driver);
            Files.createDirectories(target);
            Files.createSymbolicLink(deviceDir.resolve("driver"), target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
