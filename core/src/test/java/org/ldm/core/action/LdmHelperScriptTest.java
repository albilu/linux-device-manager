package org.ldm.core.action;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class LdmHelperScriptTest {

    private record ProcResult(int code, String out, String err) {
    }

    private ProcResult run(Path sysPrefix, String verb, String syspath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "sh", HelperFiles.helperScript().toString(), verb, syspath);
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
        ProcResult r = run(root, "disable-driver", dev.toString());
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
        ProcResult r = run(root, "enable-driver", dev.toString());
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
}
