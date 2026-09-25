package org.ldm.core.util;

import java.nio.file.Path;

/**
 * Central resolution of LDM's XDG locations. Kept minimal: only the state
 * directory is needed today (application logs). Honors XDG_STATE_HOME with
 * the conventional fallback so sandboxed installs keep working.
 */
public final class LdmPaths {

    private LdmPaths() {
    }

    /**
     * Resolves LDM's XDG state directory, honoring {@code XDG_STATE_HOME}
     * and defaulting to {@code ~/.local/state/linux-device-manager}.
     *
     * @return the directory in which LDM persists runtime state (logs)
     */
    public static Path stateDirectory() {
        return stateDirectory(System.getenv("XDG_STATE_HOME"), System.getProperty("user.home"));
    }

    static Path stateDirectory(String xdgStateHome, String userHome) {
        Path base = (xdgStateHome != null && !xdgStateHome.isBlank())
                ? Path.of(xdgStateHome)
                : Path.of(userHome, ".local", "state");
        return base.resolve("linux-device-manager");
    }
}
