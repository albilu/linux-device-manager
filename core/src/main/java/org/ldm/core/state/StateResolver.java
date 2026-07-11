package org.ldm.core.state;

import org.ldm.core.model.Bus;
import org.ldm.core.model.DeviceState;
import org.ldm.core.model.SysfsDevice;

/**
 * Derives a {@link DeviceState} from raw sysfs facts.
 *
 * <p>
 * Plan-1 scope produces ACTIVE, INACTIVE_NO_DRIVER, and DISABLED. ERROR/UNKNOWN
 * detection
 * (kernel error signals) is added in a later plan.
 */
public final class StateResolver {

    public DeviceState resolve(SysfsDevice device) {
        if (device.bus() == Bus.USB && device.authorized().filter(a -> !a).isPresent()) {
            return DeviceState.DISABLED;
        }
        if (device.driver().isPresent()) {
            return DeviceState.ACTIVE;
        }
        return DeviceState.INACTIVE_NO_DRIVER;
    }
}
