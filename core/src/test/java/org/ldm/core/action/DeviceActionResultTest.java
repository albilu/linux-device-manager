package org.ldm.core.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ldm.core.action.DeviceActionResult.Outcome;
import org.junit.jupiter.api.Test;

class DeviceActionResultTest {

    @Test
    void successFactory() {
        DeviceActionResult r = DeviceActionResult.success();
        assertEquals(Outcome.SUCCESS, r.outcome());
        assertTrue(r.isSuccess());
    }

    @Test
    void failedCarriesMessage() {
        DeviceActionResult r = DeviceActionResult.failed("boom");
        assertEquals(Outcome.FAILED, r.outcome());
        assertEquals("boom", r.message());
        assertFalse(r.isSuccess());
    }

    @Test
    void cancelledAndUnsupported() {
        assertEquals(Outcome.AUTH_CANCELLED, DeviceActionResult.authCancelled().outcome());
        assertEquals(Outcome.UNSUPPORTED, DeviceActionResult.unsupported("nope").outcome());
    }
}
