package org.ldm.core.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.ldm.core.scan.SysfsIdentity;

@EnabledOnOs(OS.LINUX)
class LdmHelperScriptTest {

    private record ProcResult(int code, String out, String err) {
    }

    private ProcResult run(Path sysPrefix, String verb, String syspath) throws Exception {
        return run(sysPrefix, verb, syspath, SysfsIdentity.read(Path.of(syspath)));
    }

    private ProcResult run(Path sysPrefix, String verb, String syspath, String instance) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "sh", HelperFiles.helperScript().toString(), verb, syspath, instance);
        pb.environment().put("LDM_SYS_PREFIX", sysPrefix.toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        return new ProcResult(code, out, err);
    }

    /**
     * Build a fake device dir with subsystem->bus and (optionally) driver->drv
     * symlinks.
     */
    private Path fakeDevice(Path root, boolean withDriver) throws IOException {
        Path bus = root.resolve("bus/pci");
        Files.createDirectories(bus);
        Files.writeString(bus.resolve("drivers_probe"), "");
        Path drv = root.resolve("bus/pci/drivers/nvidia");
        Files.createDirectories(drv);
        Files.writeString(drv.resolve("unbind"), "");
        Path dev = root.resolve("devices/0000:01:00.0");
        Files.createDirectories(dev);
        Files.createSymbolicLink(dev.resolve("subsystem"), bus);
        if (withDriver) {
            Files.createSymbolicLink(dev.resolve("driver"), drv);
        }
        return dev;
    }

    @Test
    void rejectsUnknownVerb(@TempDir Path root) throws Exception {
        Path dev = fakeDevice(root, true);
        assertEquals(2, run(root, "frobnicate", dev.toString()).code());
    }

    @Test
    void rejectsPathOutsidePrefix(@TempDir Path root) throws Exception {
        fakeDevice(root, true);
        assertEquals(3, run(root, "disable-driver", "/etc/passwd").code());
    }

    @Test
    void rejectsNonexistentDevice(@TempDir Path root) throws Exception {
        assertEquals(3, run(root, "disable-driver", root.resolve("devices/ghost").toString()).code());
    }

    @Test
    void disableWritesDevidToUnbind(@TempDir Path root) throws Exception {
        Path dev = fakeDevice(root, true);
        var transition = transition(root.resolve("bus/pci/drivers/nvidia/unbind"), "0000:01:00.0",
                () -> Files.delete(dev.resolve("driver")));
        ProcResult r = run(root, "disable-driver", dev.toString());
        transition.get(3, TimeUnit.SECONDS);
        assertEquals(0, r.code(), r.err());
        assertEquals("0000:01:00.0",
                Files.readString(root.resolve("bus/pci/drivers/nvidia/unbind")));
    }

    @Test
    void disableWithoutDriverFails(@TempDir Path root) throws Exception {
        Path dev = fakeDevice(root, false);
        assertEquals(4, run(root, "disable-driver", dev.toString()).code());
    }

    @Test
    void enableWritesDevidToDriversProbe(@TempDir Path root) throws Exception {
        Path dev = fakeDevice(root, false);
        var transition = transition(root.resolve("bus/pci/drivers_probe"), "0000:01:00.0",
                () -> Files.createSymbolicLink(dev.resolve("driver"), root.resolve("bus/pci/drivers/nvidia")));
        ProcResult r = run(root, "enable-driver", dev.toString());
        transition.get(3, TimeUnit.SECONDS);
        assertEquals(0, r.code(), r.err());
        assertEquals("0000:01:00.0", Files.readString(root.resolve("bus/pci/drivers_probe")));
    }

    @Test
    void rejectsPathTraversalUnderPrefix(@TempDir Path root) throws Exception {
        fakeDevice(root, true);
        // Under the prefix but contains "..": must be rejected by the traversal guard
        // (exit 3).
        String traversal = root.resolve("devices/../devices/0000:01:00.0").toString();
        assertEquals(3, run(root, "disable-driver", traversal).code());
    }

    @FunctionalInterface private interface Change { void apply() throws Exception; }

    private CompletableFuture<Void> transition(Path trigger, String value, Change change) {
        return CompletableFuture.runAsync(() -> {
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (System.nanoTime() < deadline) {
                    if (Files.exists(trigger) && Files.readString(trigger).equals(value)) {
                        change.apply();
                        return;
                    }
                    Thread.sleep(10);
                }
                throw new AssertionError("helper never wrote " + trigger);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Path usbDevice(Path root, boolean authorized) throws Exception {
        Path bus = root.resolve("bus/usb");
        Path driver = bus.resolve("drivers/usbhid");
        Files.createDirectories(driver);
        Path dev = root.resolve("devices/1-1");
        Files.createDirectories(dev.resolve("1-1:1.0"));
        Files.createSymbolicLink(dev.resolve("subsystem"), bus);
        Files.writeString(dev.resolve("idVendor"), "1234");
        Files.writeString(dev.resolve("authorized"), authorized ? "1" : "0");
        Files.writeString(dev.resolve("1-1:1.0/bInterfaceClass"), "03");
        Files.createDirectories(bus.resolve("drivers/usb"));
        Files.createSymbolicLink(dev.resolve("driver"), bus.resolve("drivers/usb"));
        if (authorized) Files.createSymbolicLink(dev.resolve("1-1:1.0/driver"), driver);
        return dev;
    }

    @Test
    void usbAuthorizationRoundTripVerifiesRequestedState(@TempDir Path root) throws Exception {
        Path dev = usbDevice(root, false);
        var binding = transition(dev.resolve("authorized"), "1", () -> Files.createSymbolicLink(
                dev.resolve("1-1:1.0/driver"), root.resolve("bus/usb/drivers/usbhid")));
        ProcResult enabled = run(root, "enable-usb", dev.toString());
        binding.get(3, TimeUnit.SECONDS);
        assertEquals(0, enabled.code(), enabled.err());
        assertEquals("1", Files.readString(dev.resolve("authorized")));
        var unbinding = transition(dev.resolve("authorized"), "0", () -> Files.delete(dev.resolve("1-1:1.0/driver")));
        ProcResult disabled = run(root, "disable-usb", dev.toString());
        unbinding.get(3, TimeUnit.SECONDS);
        assertEquals(0, disabled.code(), disabled.err());
        assertEquals("0", Files.readString(dev.resolve("authorized")));
        assertTrue(Files.exists(dev.resolve("driver")), "generic USB parent remains bound");
    }

    @Test
    void authorizationWithoutKernelDriverSucceedsForUserspaceDevices(@TempDir Path root) throws Exception {
        Path dev = usbDevice(root, false);
        ProcResult result = run(root, "enable-usb", dev.toString());
        assertEquals(0, result.code(), result.err());
        assertEquals("1", Files.readString(dev.resolve("authorized")));
    }

    @Test
    void rejectsAReplacementWithTheSameUsbIdsAndSerial(@TempDir Path root) throws Exception {
        Path dev = usbDevice(root, true);
        Files.writeString(dev.resolve("serial"), "same model and serial");
        String instance = SysfsIdentity.read(dev);
        Files.move(dev, dev.resolveSibling("removed"));
        Path replacement = usbDevice(root, true);
        Files.writeString(replacement.resolve("serial"), "same model and serial");
        ProcResult result = run(root, "disable-usb", dev.toString(), instance);
        assertEquals(7, result.code(), result.err());
        assertEquals("1", Files.readString(replacement.resolve("authorized")));
    }

    @Test
    void rejectsChangedUsbAddressEvenIfDirectoryWasReused(@TempDir Path root) throws Exception {
        Path dev = usbDevice(root, true);
        Files.writeString(dev.resolve("devnum"), "4\n");
        String instance = SysfsIdentity.read(dev);
        Files.writeString(dev.resolve("devnum"), "5\n");
        assertEquals(7, run(root, "disable-usb", dev.toString(), instance).code());
        assertEquals("1", Files.readString(dev.resolve("authorized")));
    }

    @Test
    void replacementAfterFinalIdentityReadCannotRedirectUsbWrite(@TempDir Path root) throws Exception {
        Path dev = usbDevice(root, false);
        Files.writeString(dev.resolve("authorized"), "1");
        Path bin = Files.createDirectory(root.resolve("bin"));
        Path hash = bin.resolve("sha256sum");
        // Pause the actual helper after its second identity read, just before the write.
        Files.writeString(hash, """
                #!/bin/sh
                /usr/bin/sha256sum "$@" || exit 1
                count=0
                if [ -f "$LDM_RACE_ROOT/count" ]; then count=$(cat "$LDM_RACE_ROOT/count"); fi
                count=$((count + 1))
                printf '%s' "$count" > "$LDM_RACE_ROOT/count"
                if [ "$count" = 2 ]; then
                    : > "$LDM_RACE_ROOT/ready"
                    while [ ! -f "$LDM_RACE_ROOT/proceed" ]; do sleep 0.01; done
                fi
                """);
        assertTrue(hash.toFile().setExecutable(true));
        ProcessBuilder pb = new ProcessBuilder("sh", HelperFiles.helperScript().toString(),
                "disable-usb", dev.toString(), SysfsIdentity.read(dev));
        pb.environment().put("LDM_SYS_PREFIX", root.toString());
        pb.environment().put("LDM_RACE_ROOT", root.toString());
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        Process process = pb.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(root.resolve("ready")) && process.isAlive() && System.nanoTime() < deadline)
                Thread.sleep(5);
            assertTrue(Files.exists(root.resolve("ready")), "helper never reached final identity check");
            Files.move(dev, dev.resolveSibling("removed"));
            Path replacement = usbDevice(root, true);
            Files.writeString(root.resolve("proceed"), "");
            assertTrue(process.waitFor(3, TimeUnit.SECONDS));
            assertEquals(7, process.exitValue());
            assertEquals("1", Files.readString(replacement.resolve("authorized")),
                    "a pathname replacement must never receive the old instance's write");
        } finally {
            Files.writeString(root.resolve("proceed"), "");
            process.destroyForcibly();
        }
    }

    @Test
    void refusesLegacyCallsWithoutAnInstance(@TempDir Path root) throws Exception {
        Path dev = usbDevice(root, true);
        Process p = new ProcessBuilder("sh", HelperFiles.helperScript().toString(), "disable-usb", dev.toString()).start();
        assertEquals(2, p.waitFor());
        assertEquals("1", Files.readString(dev.resolve("authorized")));
    }

    @Test
    void rejectsDriversWithoutAnUnbindAttribute(@TempDir Path root) throws Exception {
        Path dev = fakeDevice(root, true);
        Path unbind = root.resolve("bus/pci/drivers/nvidia/unbind");
        Files.delete(unbind);
        assertEquals(4, run(root, "disable-driver", dev.toString()).code());
        assertTrue(Files.notExists(unbind));
        assertTrue(Files.exists(dev.resolve("driver")));
    }

    @Test
    void acceptedProbeWithoutMatchingDriverFails(@TempDir Path root) throws Exception {
        Path dev = fakeDevice(root, false);
        assertEquals(6, run(root, "enable-driver", dev.toString()).code());
    }

    @Test
    void acceptedUnbindThatLeavesDriverBoundFails(@TempDir Path root) throws Exception {
        Path dev = fakeDevice(root, true);
        assertEquals(6, run(root, "disable-driver", dev.toString()).code());
    }

    @Test
    void hotUnplugDuringActionIsNotSuccess(@TempDir Path root) throws Exception {
        Path dev = fakeDevice(root, false);
        var disappearance = transition(root.resolve("bus/pci/drivers_probe"), "0000:01:00.0", () -> {
            Files.delete(dev.resolve("subsystem"));
            Files.delete(dev);
        });
        assertEquals(6, run(root, "enable-driver", dev.toString()).code());
        disappearance.get(3, TimeUnit.SECONDS);
    }

    @Test
    void usbCannotBeUnboundThroughGenericDriverVerb(@TempDir Path root) throws Exception {
        Path dev = usbDevice(root, true);
        assertEquals(3, run(root, "disable-driver", dev.toString()).code());
    }

    @Test
    void rejectsSymlinkEscapingSysfs(@TempDir Path root) throws Exception {
        Path prefix = Files.createDirectories(root.resolve("sys"));
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path alias = Files.createSymbolicLink(prefix.resolve("escape"), outside);
        assertEquals(3, run(prefix, "enable-driver", alias.toString()).code());
    }
}
