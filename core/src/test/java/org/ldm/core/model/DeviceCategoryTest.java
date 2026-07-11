package org.ldm.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class DeviceCategoryTest {

    @Test
    void everyCategoryHasDisplayNameAndIcon() {
        for (DeviceCategory c : DeviceCategory.values()) {
            assertFalse(c.displayName().isBlank(), c + " display name");
            assertFalse(c.iconName().isBlank(), c + " icon name");
        }
    }

    @Test
    void displayOrderStartsWithProcessorAndEndsWithOther() {
        DeviceCategory[] values = DeviceCategory.values();
        assertEquals(DeviceCategory.PROCESSOR, values[0]);
        assertEquals(DeviceCategory.OTHER, values[values.length - 1]);
    }
}
