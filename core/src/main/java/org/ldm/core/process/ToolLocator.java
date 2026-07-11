package org.ldm.core.process;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves a tool binary, preferring a bundled directory (embedded static
 * binaries)
 * over the system {@code PATH}.
 */
public final class ToolLocator {

    private final Path bundledDir;

    /**
     * @param bundledDir directory of bundled tool binaries; may be {@code null} if
     *                   none bundled.
     */
    public ToolLocator(Path bundledDir) {
        this.bundledDir = bundledDir;
    }

    /**
     * @return absolute path to the bundled binary if present and executable,
     *         otherwise the bare
     *         tool name (to be resolved later via {@code PATH} by
     *         {@link ProcessBuilder}).
     */
    public String locate(String tool) {
        if (bundledDir != null) {
            Path candidate = bundledDir.resolve(tool);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return tool;
    }
}
