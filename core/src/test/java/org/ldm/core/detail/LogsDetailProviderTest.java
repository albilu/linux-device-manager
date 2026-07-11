package org.ldm.core.detail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ldm.core.model.Bus;
import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.DeviceState;
import org.ldm.core.process.CommandResult;
import org.ldm.core.process.FakeCommandRunner;
import java.util.Map;
import java.util.Optional;
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

                assertTrue(text.contains("nvidia: loading module"), text);
                assertTrue(text.contains("0000:01:00.0: power state changed"), text);
                assertFalse(text.contains("eth0: link up"), text);
        }

        @Test
        void fallsBackToDmesgWhenJournalctlFails() {
                FakeCommandRunner runner = new FakeCommandRunner()
                                .stub(new CommandResult(1, "", "no journal access", false),
                                                "journalctl", "-k", "-b", "--no-pager")
                                .stubStdout("nvidia: initialized\n", "dmesg");
                LogsDetailProvider provider = new LogsDetailProvider(runner, "journalctl", "dmesg");

                assertTrue(provider.load(device()).contains("nvidia: initialized"));
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
}
