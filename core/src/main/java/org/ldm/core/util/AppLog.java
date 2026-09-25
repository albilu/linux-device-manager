package org.ldm.core.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal dependency-free application log, following the
 * open-download-manager pattern: one ISO-timestamped line per entry written
 * to {@code linux-device-manager.log} in the XDG state directory, truncated
 * at each session start.
 *
 * <p>Logging is strictly best-effort: any failure is swallowed so logging
 * can never break the application. Until {@link #init(Path)} is called
 * (production entry point only), logging is a no-op, keeping tests
 * hermetic.</p>
 */
public final class AppLog {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private static volatile AppLog shared;

    private final Path logFile;

    AppLog(Path logFile) {
        this.logFile = logFile;
        truncate();
    }

    /** Binds the shared logger to {@code logFile}, truncating the previous session's content. */
    public static synchronized void init(Path logFile) {
        shared = new AppLog(logFile);
    }

    /** Visible for testing: restores the no-op state. */
    static synchronized void resetForTesting() {
        shared = null;
    }

    public static void info(String message) {
        write("INFO", message, null);
    }

    public static void warn(String message) {
        write("WARN", message, null);
    }

    public static void error(String message) {
        write("ERROR", message, null);
    }

    public static void error(String message, Throwable throwable) {
        write("ERROR", message, throwable);
    }

    private static void write(String level, String message, Throwable throwable) {
        AppLog instance = shared;
        if (instance != null) {
            instance.append(level, message, throwable);
        }
    }

    synchronized void append(String level, String message, Throwable throwable) {
        try {
            Files.createDirectories(logFile.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(TIMESTAMP.format(LocalDateTime.now()));
                writer.write(" [" + level + "] ");
                writer.write(message);
                writer.newLine();
                if (throwable != null) {
                    StringWriter trace = new StringWriter();
                    throwable.printStackTrace(new PrintWriter(trace));
                    for (String line : trace.toString().split("\n")) {
                        writer.write("    " + line);
                        writer.newLine();
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Best-effort logging: never break the app because a log write failed.
        }
    }

    private void truncate() {
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
            // Best-effort.
        }
    }
}
