package org.ldm.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryGroupTest {

    @Test
    void delegatesDisplayNameAndIcon() {
        CategoryGroup g = new CategoryGroup(DeviceCategory.NETWORK, List.of());

        assertEquals("Network adapters", g.displayName());
        assertEquals("network-wired", g.iconName());
    }
}
