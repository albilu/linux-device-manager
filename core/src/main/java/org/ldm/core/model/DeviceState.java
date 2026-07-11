package org.ldm.core.model;

/** Operational state of a device, derived by the state resolver. */
public enum DeviceState {
    ACTIVE,
    INACTIVE_NO_DRIVER,
    DISABLED,
    ERROR,
    UNKNOWN
}
