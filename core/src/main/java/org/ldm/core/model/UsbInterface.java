package org.ldm.core.model;

import java.util.Optional;

/** One USB function; composite devices can expose several different functions. */
public record UsbInterface(String syspath, int classCode, int subClass, int protocol,
                           Optional<DriverBinding> binding) {
}
