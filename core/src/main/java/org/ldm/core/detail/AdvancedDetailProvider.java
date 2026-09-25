package org.ldm.core.detail;

import org.ldm.core.model.Device;
import org.ldm.core.process.CommandResult;
import org.ldm.core.process.CommandRunner;
import java.time.Duration;

/**
 * Builds the Advanced tab text via {@code lspci}/{@code lsusb} scoped to the
 * one device.
 */
public final class AdvancedDetailProvider implements DetailProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final CommandRunner runner;
    private final String lspci;
    private final String lsusb;

    public AdvancedDetailProvider(CommandRunner runner, String lspciPath, String lsusbPath) {
        this.runner = runner;
        this.lspci = lspciPath;
        this.lsusb = lsusbPath;
    }

    @Override
    public String load(Device device) {
        if (device.bus() == org.ldm.core.model.Bus.OTHER) {
            return "Device path: " + device.syspath() + "\n" + device.properties().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        CommandResult r = runCommand(device);
        if (r == null) {
            return "No advanced details available for this device.";
        }
        if (!r.success() || r.stdout().isBlank()) {
            return "Advanced details are unavailable "
                    + "(the tool may be missing or require elevated privileges).";
        }
        return r.stdout().strip();
    }

    private CommandResult runCommand(Device device) {
        return switch (device.bus()) {
            case PCI -> runner.run(TIMEOUT, lspci, "-vvnn", "-s", device.busInfo());
            case USB -> {
                String address = usbAddress(device);
                yield address == null ? null : runner.run(TIMEOUT, lsusb, "-v", "-s", address);
            }
            case OTHER -> null;
        };
    }

    private String usbAddress(Device device) {
        String bus = trimLeadingZeros(device.properties().getOrDefault("BUSNUM", ""));
        String dev = trimLeadingZeros(device.properties().getOrDefault("DEVNUM", ""));
        if (bus.isBlank() || dev.isBlank()) {
            return null;
        }
        return bus + ":" + dev;
    }

    private static String trimLeadingZeros(String value) {
        String v = value.strip();
        int i = 0;
        while (i < v.length() - 1 && v.charAt(i) == '0') {
            i++;
        }
        return v.substring(i);
    }
}
