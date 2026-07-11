package org.ldm.core.action;

import org.ldm.core.model.Device;
import org.ldm.core.process.CommandResult;
import org.ldm.core.process.CommandRunner;
import java.time.Duration;
import java.util.List;

/**
 * Enables/disables a device by driver (un)binding, executed through a
 * privileged helper launched
 * via {@code pkexec}. The core validates inputs syntactically; the helper
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

    /** A device can be disabled if it currently has a driver bound. */
    public boolean canDisable(Device device) {
        return device.driver().isPresent();
    }

    /** A device can be (re)enabled if it currently has no driver bound. */
    public boolean canEnable(Device device) {
        return device.driver().isEmpty();
    }

    /**
     * Enable (re-probe) or disable (unbind driver) the device.
     *
     * @return a typed result the UI can present (success / auth-cancelled / failed
     *         / unsupported)
     */
    public DeviceActionResult setDeviceEnabled(Device device, boolean enabled) {
        String syspath = device.syspath();
        if (syspath == null || !syspath.startsWith("/sys/") || syspath.contains("..")) {
            return DeviceActionResult.unsupported("Refusing to act on an invalid device path.");
        }
        if (!enabled && !canDisable(device)) {
            return DeviceActionResult.unsupported("This device has no driver to disable.");
        }
        String verb = enabled ? "enable-driver" : "disable-driver";
        CommandResult r = runner.run(TIMEOUT, List.of(pkexec, helper, verb, syspath));
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
