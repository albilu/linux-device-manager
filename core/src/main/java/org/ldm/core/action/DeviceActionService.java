package org.ldm.core.action;

import org.ldm.core.model.Device;
import org.ldm.core.process.CommandResult;
import org.ldm.core.process.CommandRunner;
import java.time.Duration;
import java.util.List;

/**
 * Enables/disables a device by USB authorization or driver (un)binding through a
 * privileged helper launched via {@code pkexec}. The core validates inputs syntactically; the helper
 * re-validates independently.
 */
public final class DeviceActionService {

    // Generous timeout: the pkexec call blocks on interactive authentication.
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final CommandRunner runner;
    private final String pkexec;
    private final String helper;

    public DeviceActionService(CommandRunner runner, String pkexecPath, String helperPath) {
        this.runner = runner;
        this.pkexec = pkexecPath;
        this.helper = helperPath;
    }

    /** Capabilities are bus-specific; the generic USB parent driver is not functional state. */
    public boolean canDisable(Device device) {
        if (!device.disableSupported() || device.instanceId().isEmpty()) return false;
        return switch (device.actionKind()) {
            case USB_AUTHORIZATION -> device.authorized().orElse(false);
            case DRIVER_BINDING -> device.driver().isPresent();
            case NONE -> false;
        };
    }

    /** Unsupported class entries have no action, regardless of their inherited driver. */
    public boolean canEnable(Device device) {
        if (!device.enableSupported() || device.instanceId().isEmpty()) return false;
        return switch (device.actionKind()) {
            case USB_AUTHORIZATION -> device.authorized().map(a -> !a).orElse(false);
            case DRIVER_BINDING -> device.driver().isEmpty();
            case NONE -> false;
        };
    }

    /**
     * Authorize/deauthorize USB devices, or probe/unbind other supported bus devices.
     *
     * @return a typed result the UI can present (success / auth-cancelled / failed
     *         / unsupported)
     */
    public DeviceActionResult setDeviceEnabled(Device device, boolean enabled) {
        String syspath = device.syspath();
        if (syspath == null || !syspath.startsWith("/sys/") || syspath.contains("..")) {
            return DeviceActionResult.unsupported("Refusing to act on an invalid device path.");
        }
        if (enabled ? !canEnable(device) : !canDisable(device)) {
            return DeviceActionResult.unsupported("This operation is not supported in the device's current state.");
        }
        String verb = (enabled ? "enable-" : "disable-")
                + (device.actionKind() == org.ldm.core.model.DeviceActionKind.USB_AUTHORIZATION ? "usb" : "driver");
        CommandResult r = runner.run(TIMEOUT, List.of(pkexec, helper, verb, syspath, device.instanceId()));
        if (r.timedOut()) return DeviceActionResult.failed("The device operation timed out. Refresh to check its current state.");
        return switch (r.exitCode()) {
            case 0 -> DeviceActionResult.success();
            case 126 -> DeviceActionResult.authCancelled();
            case 127 -> DeviceActionResult.failed("Not authorized to perform this action.");
            default -> DeviceActionResult.failed(describeFailure(r));
        };
    }

    private static String describeFailure(CommandResult r) {
        String err = r.stderr().strip();
        return err.isEmpty() ? ("Action failed (exit " + r.exitCode() + ").") : err;
    }
}
