package org.ldm.core.detail;

import org.ldm.core.model.Device;
import org.ldm.core.process.CommandResult;
import org.ldm.core.process.CommandRunner;
import java.time.Duration;

/**
 * Builds the Driver tab text using {@code modinfo} for the bound kernel module.
 */
public final class DriverDetailProvider implements DetailProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final CommandRunner runner;
    private final String modinfo;

    public DriverDetailProvider(CommandRunner runner, String modinfoPath) {
        this.runner = runner;
        this.modinfo = modinfoPath;
    }

    @Override
    public String load(Device device) {
        if (device.driver().isEmpty()) {
            return "No kernel driver is bound to this device.";
        }
        String driver = device.driver().get();
        CommandResult r = runner.run(TIMEOUT, modinfo, driver);
        if (!r.success() || r.stdout().isBlank()) {
            return "Driver: " + driver + "\n(No modinfo details available.)";
        }
        return r.stdout().strip();
    }
}
