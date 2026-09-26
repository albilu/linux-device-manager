package org.ldm.core.detail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Parses read-only V4L2 format enumeration, keeping frame rates tied to their mode. */
final class CameraFormats {
    private static final Pattern FORMAT = Pattern.compile("\\[\\d+]:\\s+'([^']+)'(?:\\s+\\((.*)\\))?");
    private static final Pattern SIZE = Pattern.compile("Size: Discrete (\\d+)x(\\d+)");
    private static final Pattern RANGE = Pattern.compile("Size: (Stepwise|Continuous) (.+)");
    private static final Pattern FPS = Pattern.compile("\\(([0-9.]+)(?:-([0-9.]+))? fps\\)");
    private final List<Mode> modes = new ArrayList<>();

    private static final class Mode {
        final String format;
        final String friendlyFormat;
        final String resolution;
        final long pixels;
        final Set<String> rates = new LinkedHashSet<>();
        double maxFps;

        Mode(String format, String friendlyFormat, String resolution, long pixels) {
            this.format = format;
            this.friendlyFormat = friendlyFormat;
            this.resolution = resolution;
            this.pixels = pixels;
        }
    }

    void add(String output) {
        String format = "";
        String friendlyFormat = "";
        Mode mode = null;
        for (String line : output.lines().toList()) {
            var formatMatch = FORMAT.matcher(line.strip());
            var sizeMatch = SIZE.matcher(line.strip());
            var rangeMatch = RANGE.matcher(line.strip());
            var fpsMatch = FPS.matcher(line);
            if (formatMatch.find()) {
                format = formatMatch.group(1) + (formatMatch.group(2) == null ? "" : " (" + formatMatch.group(2) + ")");
                friendlyFormat = formatMatch.group(2) == null ? formatMatch.group(1) : formatMatch.group(2).split(",")[0].strip();
                mode = null;
            } else if (!format.isEmpty() && sizeMatch.matches()) {
                long width = HardwareDetailsReader.positiveLong(sizeMatch.group(1));
                long height = HardwareDetailsReader.positiveLong(sizeMatch.group(2));
                // Ignore malformed/overflowing dimensions rather than inventing a capability.
                if (width == 0 || height == 0 || width > 100000 || height > 100000) { mode = null; continue; }
                mode = new Mode(format, friendlyFormat, width + " × " + height, width * height);
                modes.add(mode);
            } else if (!format.isEmpty() && rangeMatch.matches()) {
                mode = new Mode(format, friendlyFormat, rangeMatch.group(2).replaceAll("(\\d+)x(\\d+)", "$1 × $2")
                        + " (" + rangeMatch.group(1).toLowerCase(java.util.Locale.ROOT) + " range)", 0);
                modes.add(mode);
            } else if (mode != null && fpsMatch.find()) {
                double first = HardwareDetailsReader.positiveDouble(fpsMatch.group(1));
                double last = fpsMatch.group(2) == null ? first : HardwareDetailsReader.positiveDouble(fpsMatch.group(2));
                if (first > 0 && last > 0) {
                    mode.maxFps = Math.max(mode.maxFps, Math.max(first, last));
                    mode.rates.add(HardwareDetailsReader.number(first)
                            + (fpsMatch.group(2) == null ? "" : "–" + HardwareDetailsReader.number(last)) + " fps");
                }
            }
        }
    }

    boolean isEmpty() { return modes.isEmpty(); }

    void describe(HardwareDetails.Builder result) {
        // A range can extend beyond the discrete modes; do not advertise a smaller fixed
        // mode as the camera's maximum when ranges are also present.
        modes.stream().filter(m -> m.pixels > 0 && modes.stream().allMatch(other -> other.pixels > 0))
                .max(Comparator.<Mode>comparingLong(m -> m.pixels).thenComparingDouble(m -> m.maxFps))
                .ifPresent(m -> result.add("Maximum reported resolution", m.resolution
                        + (m.maxFps > 0 ? ", up to " + HardwareDetailsReader.number(m.maxFps) + " fps (" + m.friendlyFormat + ")" : "")));
        result.add("Supported resolutions", modes.stream().sorted(Comparator.comparingLong((Mode m) -> m.pixels).reversed())
                .map(m -> m.resolution).distinct().collect(Collectors.joining(", ")));
        result.add("Video formats", modes.stream().map(m -> m.format).distinct().collect(Collectors.joining(", ")), false);
        result.add("Camera modes", "\n" + modes.stream()
                .map(m -> "  " + m.format + " — " + m.resolution
                        + (m.rates.isEmpty() ? " (frame rate not reported)" : ": " + String.join(", ", m.rates)))
                .distinct().collect(Collectors.joining("\n")), false);
    }
}
