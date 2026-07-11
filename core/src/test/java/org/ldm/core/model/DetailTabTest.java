package org.ldm.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DetailTabTest {

    @Test
    void hasFourTabsInOrder() {
        assertEquals(4, DetailTab.values().length);
        assertEquals(DetailTab.GENERAL, DetailTab.values()[0]);
        assertEquals(DetailTab.LOGS, DetailTab.values()[3]);
    }
}
