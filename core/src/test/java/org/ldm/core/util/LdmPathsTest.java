package org.ldm.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LdmPathsTest {

    @Test
    void honorsXdgStateHome() {
        assertEquals(Path.of("/tmp/custom-state", "linux-device-manager"),
                LdmPaths.stateDirectory("/tmp/custom-state", "/home/alice"));
    }

    @Test
    void fallsBackToHomeLocalState() {
        assertEquals(Path.of("/home/alice", ".local", "state", "linux-device-manager"),
                LdmPaths.stateDirectory(null, "/home/alice"));
    }

    @Test
    void blankXdgStateHomeFallsBack() {
        assertEquals(Path.of("/home/alice", ".local", "state", "linux-device-manager"),
                LdmPaths.stateDirectory("  ", "/home/alice"));
    }
}
