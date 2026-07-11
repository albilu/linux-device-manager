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
                + "Bus:      " + device.bus() + " (" + device.busInfo() + ")\n"
                + "Vendor:   " + device.vendorId() + '\n'
                + "Product:  " + device.productId() + '\n'
                + "Driver:   " + device.driver().orElse("none");
    }
}
