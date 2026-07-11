package org.ldm.core.detail;

import org.ldm.core.model.Device;
import org.ldm.core.process.CommandResult;
import org.ldm.core.process.CommandRunner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the Logs tab by filtering kernel log output for lines relevant to the
 * device.
 */
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
        if (log == null) {
            return "Kernel logs are unavailable (may require elevated privileges).";
        }
        List<String> keywords = keywords(device);
        List<String> matches = new ArrayList<>();
        for (String line : log.split("\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (!keyword.isBlank() && lower.contains(keyword)) {
                    matches.add(line);
                    break;
                }
            }
        }
        if (matches.isEmpty()) {
            return "No recent kernel log entries mention this device.";
        }
        int from = Math.max(0, matches.size() - MAX_LINES);
        return String.join("\n", matches.subList(from, matches.size()));
    }

    private String readKernelLog() {
        CommandResult j = runner.run(TIMEOUT, journalctl, "-k", "-b", "--no-pager");
        if (j.success() && !j.stdout().isBlank()) {
            return j.stdout();
        }
        CommandResult d = runner.run(TIMEOUT, dmesg);
        if (d.success() && !d.stdout().isBlank()) {
            return d.stdout();
        }
        return null;
    }

    private List<String> keywords(Device device) {
        List<String> keywords = new ArrayList<>();
        device.driver().ifPresent(driver -> keywords.add(driver.toLowerCase(Locale.ROOT)));
        if (!device.busInfo().isBlank()) {
            keywords.add(device.busInfo().toLowerCase(Locale.ROOT));
        }
        return keywords;
    }
}
