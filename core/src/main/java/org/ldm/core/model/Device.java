package org.ldm.core.model;

import java.util.Map;
import java.util.List;
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
        Map<String, String> properties,
        List<DriverBinding> driverBindings,
        Optional<Boolean> authorized,
        DeviceActionKind actionKind,
        String instanceId,
        boolean enableSupported,
        boolean disableSupported) {

    public Device {
        properties = Map.copyOf(properties);
        driverBindings = List.copyOf(driverBindings);
    }

    /** Older callers without a captured instance/capabilities can provide read-only details. */
    public Device(String id, String syspath, String busInfo, Bus bus, String displayName,
                  String vendorId, String productId, DeviceCategory category, DeviceState state,
                  Optional<String> driver, Map<String, String> properties, List<DriverBinding> bindings,
                  Optional<Boolean> authorized, DeviceActionKind actionKind) {
        this(id, syspath, busInfo, bus, displayName, vendorId, productId, category, state, driver,
                properties, bindings, authorized, actionKind, "", false, false);
    }

    /** Convenience constructor for callers supplying an already resolved module name. */
    public Device(String id, String syspath, String busInfo, Bus bus, String displayName,
                  String vendorId, String productId, DeviceCategory category, DeviceState state,
                  Optional<String> driver, Map<String, String> properties) {
        this(id, syspath, busInfo, bus, displayName, vendorId, productId, category, state, driver,
                properties, driver.map(name -> List.of(new DriverBinding(syspath, name, Optional.of(name))))
                        .orElse(List.of()),
                bus == Bus.USB ? Optional.of(state != DeviceState.DISABLED) : Optional.empty(),
                bus == Bus.USB ? DeviceActionKind.USB_AUTHORIZATION :
                        bus == Bus.PCI ? DeviceActionKind.DRIVER_BINDING : DeviceActionKind.NONE);
    }
}
