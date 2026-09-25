package org.ldm.core.detail;

import org.ldm.core.model.Device;
import org.ldm.core.process.CommandResult;
import org.ldm.core.process.CommandRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Current-boot kernel records matched to device/interface identities, never a shared driver name. */
public final class LogsDetailProvider implements DetailProvider {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_LINES = 200;
    private final CommandRunner runner;
    private final String journalctl;
    private final String dmesg;

    public LogsDetailProvider(CommandRunner runner, String journalctlPath, String dmesgPath) {
        this.runner = runner;
        this.journalctl = journalctlPath;
        this.dmesg = dmesgPath;
    }

    @Override
    public String load(Device device) {
        String log = readKernelLog();
        if (log == null) return "Kernel logs are unavailable (may require elevated privileges).";
        Set<String> identities = new LinkedHashSet<>();
        identities.add(device.busInfo());
        identities.add(device.properties().getOrDefault("INTERFACE", ""));
        String devname = device.properties().getOrDefault("DEVNAME", "");
        if (!devname.isBlank()) identities.add(Path.of(devname).getFileName().toString());
        device.driverBindings().forEach(b -> identities.add(Path.of(b.syspath()).getFileName().toString()));
        var patterns = identities.stream().filter(s -> !s.isBlank()).map(s -> Pattern.compile(
                "(?<![\\p{Alnum}_:.-])" + Pattern.quote(s) + "(?![\\p{Alnum}_.-])",
                Pattern.CASE_INSENSITIVE)).toList();
        ArrayDeque<String> matches = new ArrayDeque<>();
        log.lines().filter(line -> patterns.stream().anyMatch(p -> p.matcher(line).find())).forEach(line -> {
            if (matches.size() == MAX_LINES) matches.removeFirst();
            matches.addLast(line);
        });
        return matches.isEmpty() ? "No recent kernel log entries mention this device." : String.join("\n", matches);
    }

    private String readKernelLog() {
        CommandResult journal = runner.run(TIMEOUT, journalctl, "-k", "-b", "--no-pager");
        if (journal.success() && hasRecords(journal.stdout())) return journal.stdout();
        if (Thread.currentThread().isInterrupted()) return null;
        CommandResult fallback = runner.run(TIMEOUT, dmesg);
        if (fallback.success() && hasRecords(fallback.stdout())) return fallback.stdout();
        // An accessible but empty source is different from two inaccessible sources.
        if ((journal.success() && !restricted(journal.stderr())) || fallback.success()) return "";
        return null;
    }

    private static boolean hasRecords(String text) {
        return text.lines().map(String::strip).anyMatch(line -> !line.isEmpty()
                && !line.equals("-- No entries --") && !line.startsWith("-- Journal begins")
                && !line.startsWith("-- Logs begin"));
    }

    private static boolean restricted(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("not seeing messages") || lower.contains("permission denied")
                || lower.contains("operation not permitted") || lower.contains("no journal files were opened");
    }
}
