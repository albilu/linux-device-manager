package org.ldm.core.udev;

import org.ldm.core.model.SysfsDevice;
import org.ldm.core.process.CommandResult;
import org.ldm.core.process.CommandRunner;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enriches a device with udev properties (via {@code udevadm info -q property})
 * and derives a
 * human-readable display name.
 */
public final class UdevEnricher {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final CommandRunner runner;
    private final String udevadm;

    public UdevEnricher(CommandRunner runner, String udevadmPath) {
        this.runner = runner;
        this.udevadm = udevadmPath;
    }

    /**
     * @return parsed udev {@code KEY=VALUE} properties for the device, possibly
     *         empty.
     */
    public Map<String, String> properties(SysfsDevice device) {
        CommandResult r = runner.run(
                TIMEOUT, udevadm, "info", "-q", "property", "-p", device.syspath());
        Map<String, String> props = new LinkedHashMap<>();
        if (!r.success()) {
            return props;
        }
        for (String line : r.stdout().split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                props.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return props;
    }

    /**
     * Derive a display name from udev properties, falling back to
     * {@code vendor:product} ids.
     */
    public String displayName(SysfsDevice device, Map<String, String> props) {
        String model = firstNonBlank(props.get("ID_MODEL_FROM_DATABASE"), props.get("ID_MODEL"));
        String vendor = firstNonBlank(props.get("ID_VENDOR_FROM_DATABASE"), props.get("ID_VENDOR"));
        if (model != null && vendor != null) {
            return vendor + " " + model;
        }
        if (model != null) {
            return model;
        }
        return device.vendorId() + ":" + device.productId();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
