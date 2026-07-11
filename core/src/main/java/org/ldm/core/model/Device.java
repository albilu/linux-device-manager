package org.ldm.core.model;

import java.util.Map;
import java.util.Optional;

/**
 * A fully enriched device as presented to the UI.
 *
 * @param id          stable identifier (the sysfs path)
 * @param syspath     sysfs path
 * @param busInfo     bus address
 * @param bus         bus type
 * @param displayName human-readable name
 * @param vendorId    vendor id (hex, no 0x)
 * @param productId   product id (hex, no 0x)
 * @param category    derived category
 * @param state       derived state
 * @param driver      bound driver, if any
 * @param properties  udev properties
 */
public record Device(
        String id,
        String syspath,
        String busInfo,
        Bus bus,
        String displayName,
        String vendorId,
        String productId,
        DeviceCategory category,
        DeviceState state,
        Optional<String> driver,
        Map<String, String> properties) {
}
