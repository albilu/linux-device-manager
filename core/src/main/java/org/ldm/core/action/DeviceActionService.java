package org.ldm.core.action;

import org.ldm.core.model.Device;
import org.ldm.core.process.CommandResult;
import org.ldm.core.process.CommandRunner;
import org.ldm.core.util.AppLog;
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
            AppLog.warn("Refusing device action: invalid path " + syspath);
            return DeviceActionResult.unsupported("Refusing to act on an invalid device path.");
        }
        if (enabled ? !canEnable(device) : !canDisable(device)) {
            AppLog.warn("Refusing device action: unsupported state for " + syspath);
            return DeviceActionResult.unsupported("This operation is not supported in the device's current state.");
        }
        String verb = (enabled ? "enable-" : "disable-")
                + (device.actionKind() == org.ldm.core.model.DeviceActionKind.USB_AUTHORIZATION ? "usb" : "driver");
        AppLog.info("Action " + verb + " on " + syspath + " (instance " + device.instanceId() + ")");
        CommandResult r = runner.run(TIMEOUT, List.of(pkexec, helper, verb, syspath, device.instanceId()));
        if (r.timedOut()) {
            AppLog.error("Action " + verb + " timed out on " + syspath);
            return DeviceActionResult.failed("The device operation timed out. Refresh to check its current state.");
        }
        return switch (r.exitCode()) {
            case 0 -> {
                AppLog.info("Action " + verb + " succeeded on " + syspath);
                yield DeviceActionResult.success();
            }
            case 126 -> {
                AppLog.info("Action " + verb + " cancelled (authorization) on " + syspath);
                yield DeviceActionResult.authCancelled();
            }
            case 127 -> {
                AppLog.error("Action " + verb + " not authorized on " + syspath);
                yield DeviceActionResult.failed("Not authorized to perform this action.");
            }
            default -> {
                String failure = describeFailure(r);
                AppLog.error("Action " + verb + " failed on " + syspath + ": " + failure);
                yield DeviceActionResult.failed(failure);
            }
        };
    }

    private static String describeFailure(CommandResult r) {
        String err = r.stderr().strip();
        return err.isEmpty() ? ("Action failed (exit " + r.exitCode() + ").") : err;
    }
}
