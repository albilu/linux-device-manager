package org.ldm.core.model;

/** The operation supported by this exact sysfs node, not by one of its ancestors. */
public enum DeviceActionKind {
    USB_AUTHORIZATION,
    DRIVER_BINDING,
    NONE
}
