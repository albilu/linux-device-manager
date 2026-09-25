package org.ldm.core.state;

import org.ldm.core.model.Bus;
import org.ldm.core.model.DeviceState;
import org.ldm.core.model.SysfsDevice;
import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceActionKind;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Derives a {@link DeviceState} from raw sysfs facts.
 *
 * USB authorization and functional bindings come from the kernel. Verified non-USB
 * unbinds are remembered for this session; unmanaged entries without a binding are UNKNOWN.
 */
public final class StateResolver {
    private final Map<String, String> disabled = new ConcurrentHashMap<>();

    public void recordAction(Device device, boolean enabled) {
        if (enabled || device.actionKind() != DeviceActionKind.DRIVER_BINDING) disabled.remove(device.syspath());
        else if (!device.instanceId().isEmpty()) disabled.put(device.syspath(), device.instanceId());
    }

    public void retainDevices(List<SysfsDevice> devices) {
        var present = devices.stream().map(SysfsDevice::syspath).collect(Collectors.toSet());
        disabled.keySet().retainAll(present);
    }

    public DeviceState resolve(SysfsDevice device) {
        String subsystem = device.attributes().getOrDefault("SUBSYSTEM", "");
        if (subsystem.equals("memory") || subsystem.equals("cpu")) {
            String online = device.attributes().getOrDefault("online", "");
            String state = device.attributes().getOrDefault("state", "");
            if (state.equals("going-offline")) return DeviceState.UNKNOWN;
            if (online.equals("0") || state.equals("offline")) return DeviceState.DISABLED;
            if (online.equals("1") || state.equals("online")) return DeviceState.ACTIVE;
        }
        if (device.bus() == Bus.USB && device.authorized().filter(a -> !a).isPresent()) {
            return DeviceState.DISABLED;
        }
        if (device.driver().isPresent()) {
            disabled.remove(device.syspath());
            return DeviceState.ACTIVE;
        }
        if (!device.instanceId().isEmpty() && device.instanceId().equals(disabled.get(device.syspath())))
            return DeviceState.DISABLED;
        disabled.remove(device.syspath());
        if (device.actionKind() == DeviceActionKind.NONE) return DeviceState.UNKNOWN;
        if (device.bus() == Bus.USB) return DeviceState.UNKNOWN; // Authorized userspace USB need not bind a kernel driver.
        return DeviceState.INACTIVE_NO_DRIVER;
    }
}
