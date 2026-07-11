package org.ldm.core.action;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the repo's {@code helper/} directory from test code (walks up from
 * the working dir).
 */
public final class HelperFiles {

    private HelperFiles() {
    }

    public static Path helperScript() {
        return helperDir().resolve("ldm-helper");
    }

    public static Path polkitPolicy() {
        return helperDir().resolve("org.ldm.policy");
    }

    private static Path helperDir() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("helper");
            if (Files.isDirectory(candidate) && Files.exists(candidate.resolve("ldm-helper"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not locate helper/ from user.dir=" + System.getProperty("user.dir"));
    }
}
