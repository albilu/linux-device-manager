package org.ldm.core.detail;

import org.ldm.core.model.Device;
import org.ldm.core.process.ProcessCommandRunner;
import org.ldm.core.process.ToolLocator;

/**
 * Presents the device and its key specifications in everyday language.
 * Specification queries run on the same background worker as the other tabs.
 */
public final class GeneralDetailProvider implements DetailProvider {
    private final HardwareDetailsReader hardware;

    public GeneralDetailProvider() {
        this(new HardwareDetailsReader(new ProcessCommandRunner(), new ToolLocator(null)));
    }

    public GeneralDetailProvider(HardwareDetailsReader hardware) { this.hardware = hardware; }

    @Override
    public String load(Device device) {
        String status = switch (device.state()) {
            case ACTIVE -> "Enabled";
            case INACTIVE_NO_DRIVER -> "No driver loaded";
            case DISABLED -> "Disabled";
            case ERROR -> "A problem was reported";
            case UNKNOWN -> "Detected (status not reported)";
        };
        String specifications = hardware.read(device).render(true);
        return "Name: " + device.displayName().replace('_', ' ') + '\n'
                + "Type: " + device.category().displayName() + '\n'
                + "Status: " + status + '\n'
                + (device.bus() == org.ldm.core.model.Bus.OTHER ? "" : "Connection: " + device.bus() + '\n')
                + (specifications.isEmpty() ? "" : "\n" + specifications)
                + (device.actionKind() == org.ldm.core.model.DeviceActionKind.NONE
                    ? "\n\nEnable/Disable is not supported for this device." : "");
    }
}
