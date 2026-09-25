package org.ldm.core.model;

import java.util.Optional;

/** A functional driver binding and its owning kernel module, if modular. */
public record DriverBinding(String syspath, String name, Optional<String> module) {
}
