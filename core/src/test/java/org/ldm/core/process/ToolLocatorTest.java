package org.ldm.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class ToolLocatorTest {

    @Test
    void prefersBundledExecutable(@TempDir Path dir) throws IOException {
        Path lspci = dir.resolve("lspci");
        Files.writeString(lspci, "#!/bin/sh\n");
        Files.setPosixFilePermissions(lspci, PosixFilePermissions.fromString("rwxr-xr-x"));

        ToolLocator locator = new ToolLocator(dir);

        assertEquals(lspci.toAbsolutePath().toString(), locator.locate("lspci"));
    }

    @Test
    void fallsBackToBareNameWhenNotBundled(@TempDir Path dir) {
        ToolLocator locator = new ToolLocator(dir);

        assertEquals("lsusb", locator.locate("lsusb"));
    }

    @Test
    void fallsBackWhenBundledDirNull() {
        ToolLocator locator = new ToolLocator(null);

        assertEquals("lspci", locator.locate("lspci"));
    }
}
