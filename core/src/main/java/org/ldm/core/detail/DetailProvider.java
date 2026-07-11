package org.ldm.core.detail;

import org.ldm.core.model.Device;

/** Produces the text content for one device detail tab. */
@FunctionalInterface
public interface DetailProvider {

    /** @return human-readable text for the tab; never null. */
    String load(Device device);
}
