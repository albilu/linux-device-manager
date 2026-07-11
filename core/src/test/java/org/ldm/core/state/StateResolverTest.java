package org.ldm.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ldm.core.model.Bus;
import org.ldm.core.model.DeviceState;
import org.ldm.core.model.SysfsDevice;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StateResolverTest {

    private final StateResolver resolver = new StateResolver();

    private SysfsDevice dev(Bus bus, Optional<String> driver, Optional<Boolean> authorized) {
        return new SysfsDevice("/sys/x", "x", bus, "0", "0", 0, driver, authorized, Map.of());
    }

    @Test
    void boundDriverIsActive() {
        assertEquals(DeviceState.ACTIVE,
                resolver.resolve(dev(Bus.PCI, Optional.of("nvidia"), Optional.empty())));
    }

    @Test
    void noDriverIsInactive() {
        assertEquals(DeviceState.INACTIVE_NO_DRIVER,
                resolver.resolve(dev(Bus.PCI, Optional.empty(), Optional.empty())));
    }

    @Test
    void deauthorizedUsbIsDisabled() {
        assertEquals(DeviceState.DISABLED,
                resolver.resolve(dev(Bus.USB, Optional.of("uvcvideo"), Optional.of(false))));
    }
}
