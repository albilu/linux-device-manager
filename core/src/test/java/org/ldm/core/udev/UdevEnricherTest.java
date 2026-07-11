package org.ldm.core.udev;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ldm.core.model.Bus;
import org.ldm.core.model.SysfsDevice;
import org.ldm.core.process.FakeCommandRunner;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UdevEnricherTest {

    private SysfsDevice gpu() {
        return new SysfsDevice("/sys/devices/pci0000:00/0000:01:00.0", "0000:01:00.0",
                Bus.PCI, "10de", "2503", 0x030000, Optional.of("nvidia"), Optional.empty(), Map.of());
    }

    @Test
    void parsesPropertiesAndBuildsName() {
        String output = """
                ID_MODEL_FROM_DATABASE=GA106 [GeForce RTX 3060]
                ID_VENDOR_FROM_DATABASE=NVIDIA Corporation
                DRIVER=nvidia
                """;
        FakeCommandRunner runner = new FakeCommandRunner().stubStdout(
                output, "udevadm", "info", "-q", "property", "-p",
                "/sys/devices/pci0000:00/0000:01:00.0");
        UdevEnricher enricher = new UdevEnricher(runner, "udevadm");

        Map<String, String> props = enricher.properties(gpu());

        assertEquals("nvidia", props.get("DRIVER"));
        assertEquals("NVIDIA Corporation GA106 [GeForce RTX 3060]",
                enricher.displayName(gpu(), props));
    }

    @Test
    void fallsBackToHexIdsWhenNoNames() {
        FakeCommandRunner runner = new FakeCommandRunner(); // unstubbed -> failure result
        UdevEnricher enricher = new UdevEnricher(runner, "udevadm");

        Map<String, String> props = enricher.properties(gpu());

        assertEquals("10de:2503", enricher.displayName(gpu(), props));
    }
}
