package org.ldm.core.detail;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ldm.core.model.Bus;
import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.DeviceState;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GeneralDetailProviderTest {

    private final GeneralDetailProvider provider = new GeneralDetailProvider();

    private Device device(Optional<String> driver) {
        return new Device("/sys/x", "/sys/x", "0000:01:00.0", Bus.PCI, "NVIDIA RTX 3060",
                "10de", "2503", DeviceCategory.DISPLAY, DeviceState.ACTIVE, driver, Map.of());
    }

    @Test
    void rendersDeviceFields() {
        String text = provider.load(device(Optional.of("nvidia")));

        assertTrue(text.contains("NVIDIA RTX 3060"), text);
        assertTrue(text.contains("Display adapters"), text);
        assertTrue(text.contains("ACTIVE"), text);
        assertTrue(text.contains("0000:01:00.0"), text);
        assertTrue(text.contains("10de"), text);
        assertTrue(text.contains("2503"), text);
        assertTrue(text.contains("nvidia"), text);
    }

    @Test
    void showsNoneWhenNoDriver() {
        String text = provider.load(device(Optional.empty()));

        assertTrue(text.contains("Driver:") && text.contains("none"), text);
    }
}
