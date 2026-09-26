package org.ldm.core.detail;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    void showsReadableIdentityAndStatusWithoutBusDiagnostics() {
        String text = provider.load(device(Optional.of("nvidia")));

        assertTrue(text.contains("NVIDIA RTX 3060"), text);
        assertTrue(text.contains("Display adapters"), text);
        assertTrue(text.contains("Status: Enabled"), text);
        assertTrue(text.contains("Connection: PCI"), text);
        assertFalse(text.contains("0000:01:00.0"), text);
        assertFalse(text.contains("10de"), text);
        assertFalse(text.contains("2503"), text);
        assertFalse(text.contains("/sys/x"), text);
    }

    @Test
    void explainsAMissingDriverInPlainLanguage() {
        var device = new Device("/sys/x", "/sys/x", "0000:01:00.0", Bus.PCI, "GPU", "10de", "2503",
                DeviceCategory.DISPLAY, DeviceState.INACTIVE_NO_DRIVER, Optional.empty(), Map.of());
        String text = provider.load(device);

        assertTrue(text.contains("Status: No driver loaded"), text);
    }
}
