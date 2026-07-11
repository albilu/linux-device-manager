package org.ldm.gtk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ldm.core.model.Bus;
import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.DeviceState;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SafetyPolicyTest {

    @Test
    void treatsStorageDevicesAsCritical() {
        assertTrue(SafetyPolicy.isCritical(device(DeviceCategory.STORAGE)));
    }

    @Test
    void treatsDisplayDevicesAsNonCritical() {
        assertFalse(SafetyPolicy.isCritical(device(DeviceCategory.DISPLAY)));
    }

    private Device device(DeviceCategory category) {
        return new Device("/sys/devices/test", "/sys/devices/test", "0000:00:00.0", Bus.PCI,
                category.displayName(), "1234", "5678", category, DeviceState.ACTIVE,
                Optional.of("drv"), Map.of());
    }
}
