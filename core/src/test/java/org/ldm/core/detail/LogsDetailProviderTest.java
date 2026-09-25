package org.ldm.core.detail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ldm.core.model.Bus;
import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.DeviceState;
import org.ldm.core.process.CommandResult;
import org.ldm.core.process.FakeCommandRunner;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class LogsDetailProviderTest {

        private Device device() {
                return new Device("/sys/x", "/sys/x", "0000:01:00.0", Bus.PCI, "GPU", "10de", "2503",
                                DeviceCategory.DISPLAY, DeviceState.ACTIVE, Optional.of("nvidia"), Map.of());
        }

        @Test
        void filtersJournalctlLinesForTheDevice() {
                String journal = "kernel: usb 1-1: new device\n"
                                + "kernel: nvidia: loading module\n"
                                + "kernel: 0000:01:00.0: power state changed\n"
                                + "kernel: eth0: link up\n";
                FakeCommandRunner runner = new FakeCommandRunner()
                                .stubStdout(journal, "journalctl", "-k", "-b", "--no-pager");
                LogsDetailProvider provider = new LogsDetailProvider(runner, "journalctl", "dmesg");

                String text = provider.load(device());

                assertFalse(text.contains("nvidia: loading module"), text);
                assertTrue(text.contains("0000:01:00.0: power state changed"), text);
                assertFalse(text.contains("eth0: link up"), text);
        }

        @Test
        void fallsBackToDmesgWhenJournalctlFails() {
                FakeCommandRunner runner = new FakeCommandRunner()
                                .stub(new CommandResult(1, "", "no journal access", false),
                                                "journalctl", "-k", "-b", "--no-pager")
                                .stubStdout("nvidia 0000:01:00.0: initialized\n", "dmesg");
                LogsDetailProvider provider = new LogsDetailProvider(runner, "journalctl", "dmesg");

                assertTrue(provider.load(device()).contains("0000:01:00.0: initialized"));
        }

        @Test
        void reportsUnavailableWhenBothFail() {
                // both journalctl and dmesg unstubbed -> failure results
                LogsDetailProvider provider = new LogsDetailProvider(new FakeCommandRunner(), "journalctl", "dmesg");

                assertTrue(provider.load(device()).contains("unavailable"));
        }

        @Test
        void reportsNoMatchesWhenNothingRelevant() {
                FakeCommandRunner runner = new FakeCommandRunner()
                                .stubStdout("kernel: eth0: link up\nkernel: sda: write cache enabled\n",
                                                "journalctl", "-k", "-b", "--no-pager");
                LogsDetailProvider provider = new LogsDetailProvider(runner, "journalctl", "dmesg");

                assertTrue(provider.load(device()).contains("No recent kernel log entries"));
        }

        @Test
        void usbAddressBoundariesExcludeOtherDevicesAndSharedDriversBeforeTruncation() {
                Device usb = new Device("/sys/1-1", "/sys/1-1", "1-1", Bus.USB, "USB", "1", "2",
                                DeviceCategory.USB, DeviceState.ACTIVE, Optional.of("usbhid"), Map.of());
                String unrelated = IntStream.range(0, 250).mapToObj(i -> "usb 1-10: usbhid other " + i)
                                .collect(Collectors.joining("\n"));
                String log = "usb 1-1: selected device\nhid 1-1:1.0: selected interface\n"
                                + "usb 1-1.2: child device\nusb 11-1: other bus\n" + unrelated;
                var provider = new LogsDetailProvider(new FakeCommandRunner()
                                .stubStdout(log, "journalctl", "-k", "-b", "--no-pager"), "journalctl", "dmesg");
                assertEquals("usb 1-1: selected device\nhid 1-1:1.0: selected interface", provider.load(usb));
        }

        @Test
        void retainsTheLastTwoHundredMatchingEntries() {
                String log = IntStream.range(0, 230).mapToObj(i -> "kernel 0000:01:00.0: event " + i)
                                .collect(Collectors.joining("\n"));
                var provider = new LogsDetailProvider(new FakeCommandRunner()
                                .stubStdout(log, "journalctl", "-k", "-b", "--no-pager"), "journalctl", "dmesg");
                String result = provider.load(device());
                assertEquals(200, result.lines().count());
                assertTrue(result.startsWith("kernel 0000:01:00.0: event 30\n"));
                assertTrue(result.endsWith("event 229"));
        }

        @Test
        void emptyJournalSentinelAndAccessHintAllowUsefulDmesgFallback() {
                var runner = new FakeCommandRunner().stub(new CommandResult(0, "-- No entries --\n",
                                "Hint: You are currently not seeing messages from other users and the system.", false),
                                "journalctl", "-k", "-b", "--no-pager")
                                .stubStdout("kernel 0000:01:00.0: fallback entry", "dmesg");
                assertTrue(new LogsDetailProvider(runner, "journalctl", "dmesg").load(device())
                                .contains("fallback entry"));
        }

        @Test
        void inaccessibleEmptyJournalAndDmesgAreUnavailableRatherThanNoMatches() {
                var runner = new FakeCommandRunner().stub(new CommandResult(0, "-- No entries --\n",
                                "Hint: You are currently not seeing messages from other users and the system.", false),
                                "journalctl", "-k", "-b", "--no-pager")
                                .stub(new CommandResult(1, "", "Operation not permitted", false), "dmesg");
                assertTrue(new LogsDetailProvider(runner, "journalctl", "dmesg").load(device()).contains("unavailable"));
        }

        @Test
        void genuinelyEmptySourcesReportNoMatches() {
                var runner = new FakeCommandRunner().stubStdout("-- No entries --\n", "journalctl", "-k", "-b", "--no-pager")
                                .stubStdout("", "dmesg");
                assertTrue(new LogsDetailProvider(runner, "journalctl", "dmesg").load(device())
                                .contains("No recent kernel log entries"));
        }
}
