package org.ldm.core.detail;

import org.ldm.core.model.Device;

/**
 * Builds the General tab text from already-collected device fields (no external
 * process).
 */
public final class GeneralDetailProvider implements DetailProvider {

    @Override
    public String load(Device device) {
        return "Name:     " + device.displayName() + '\n'
                + "Category: " + device.category().displayName() + '\n'
                + "State:    " + device.state() + '\n'
                + "Bus:      " + device.properties().getOrDefault("SUBSYSTEM", device.bus().toString())
                + " (" + device.busInfo() + ")\n"
                + "Vendor:   " + device.vendorId() + '\n'
                + "Product:  " + device.productId() + '\n'
                + "Driver:   " + device.driver().orElse("none") + '\n'
                + "Device path: " + device.syspath()
                + device.authorized().map(a -> "\nUSB authorization: " + (a ? "Allowed" : "Blocked")).orElse("")
                + (device.actionKind() == org.ldm.core.model.DeviceActionKind.NONE
                    ? "\nEnable/Disable is not supported for this entry." : "");
    }
}
