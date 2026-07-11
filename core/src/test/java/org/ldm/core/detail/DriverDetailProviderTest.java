package org.ldm.core.detail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ldm.core.model.Bus;
import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.DeviceState;
import org.ldm.core.process.FakeCommandRunner;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DriverDetailProviderTest {

    private Device device(Optional<String> driver) {
        return new Device("/sys/x", "/sys/x", "0000:01:00.0", Bus.PCI, "GPU", "10de", "2503",
                DeviceCategory.DISPLAY, DeviceState.ACTIVE, driver, Map.of());
    }

    @Test
    void returnsModinfoOutputWhenDriverBound() {
        FakeCommandRunner runner = new FakeCommandRunner()
                .stubStdout("filename: /lib/modules/x/nvidia.ko\nversion: 550.1\n", "modinfo", "nvidia");
        DriverDetailProvider provider = new DriverDetailProvider(runner, "modinfo");

        String text = provider.load(device(Optional.of("nvidia")));

        assertTrue(text.contains("nvidia.ko"), text);
        assertTrue(text.contains("550.1"), text);
    }

    @Test
    void reportsWhenNoDriverBound() {
        DriverDetailProvider provider = new DriverDetailProvider(new FakeCommandRunner(), "modinfo");

        assertEquals("No kernel driver is bound to this device.",
                provider.load(device(Optional.empty())));
    }

    @Test
    void degradesWhenModinfoFails() {
        // unstubbed FakeCommandRunner returns a failure result
        DriverDetailProvider provider = new DriverDetailProvider(new FakeCommandRunner(), "modinfo");

        String text = provider.load(device(Optional.of("nvidia")));

        assertTrue(text.contains("nvidia"), text);
        assertTrue(text.contains("No modinfo details available"), text);
    }
}
