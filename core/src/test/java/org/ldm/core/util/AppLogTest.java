package org.ldm.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppLogTest {

    @TempDir
    Path tempDir;

    @Test
    void createsParentDirectoriesAndTruncatesExistingContent() throws IOException {
        Path log = tempDir.resolve("nested/linux-device-manager.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "old session content\n");

        AppLog appLog = new AppLog(log);

        assertEquals("", Files.readString(log));
        appLog.append("INFO", "hello", null);
        List<String> lines = Files.readAllLines(log);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("[INFO] hello"), lines.get(0));
        assertTrue(lines.get(0).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3} .*"),
                lines.get(0));
    }

    @Test
    void errorWithThrowableWritesIndentedStackTrace() throws IOException {
        Path log = tempDir.resolve("linux-device-manager.log");
        AppLog appLog = new AppLog(log);

        appLog.append("ERROR", "boom", new IllegalStateException("kaput"));

        String content = Files.readString(log);
        assertTrue(content.contains("[ERROR] boom"), content);
        assertTrue(content.contains("java.lang.IllegalStateException: kaput"), content);
        assertTrue(content.lines().anyMatch(line -> line.startsWith("    \tat ")), content);
    }

    @Test
    void unwritableLogPathIsSwallowed() {
        AppLog appLog = new AppLog(Path.of("/proc/definitely/not/writable/linux-device-manager.log"));
        appLog.append("INFO", "no crash", null); // must not throw
    }

    @Test
    void facadeIsNoOpUntilInit() throws IOException {
        AppLog.resetForTesting();
        AppLog.info("nowhere"); // shared == null: must not throw, must not create anything
        Path log = tempDir.resolve("facade.log");
        assertFalse(Files.exists(log));

        AppLog.init(log);
        try {
            AppLog.info("via facade");
            assertTrue(Files.readString(log).contains("[INFO] via facade"));
        } finally {
            AppLog.resetForTesting();
        }
    }
}
