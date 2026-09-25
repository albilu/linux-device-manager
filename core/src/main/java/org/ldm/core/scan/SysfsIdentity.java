package org.ldm.core.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Identifies a connected sysfs instance, independently of its current driver/authorization. */
public final class SysfsIdentity {
    private SysfsIdentity() { }

    /** Same byte protocol as ldm-helper: node identity, then one line per immutable attribute. */
    public static String read(Path path) {
        try {
            var node = Files.readAttributes(path, "unix:dev,ino");
            StringBuilder data = new StringBuilder().append(node.get("dev")).append(':')
                    .append(node.get("ino")).append('\n');
            boolean usb = Files.isRegularFile(path.resolve("idVendor"));
            for (String name : List.of("idVendor", "idProduct", "busnum", "devnum", "serial")) {
                Path file = path.resolve(name);
                // Shell command substitution removes trailing newlines, but preserves other bytes.
                String value = usb && Files.isRegularFile(file) ? Files.readString(file).replaceFirst("\\n+$", "") : "";
                data.append(value).append('\n');
            }
            data.append(".\n"); // Sentinel preserves empty trailing fields in shell command substitution.
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(data.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | UnsupportedOperationException e) {
            return ""; // Never advertise actions without a verifiable instance.
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
