package org.ldm.core.detail;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ldm.core.model.Bus;
import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.DeviceState;
import org.ldm.core.process.FakeCommandRunner;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdvancedDetailProviderTest {

    private Device pci() {
        return new Device("/sys/x", "/sys/x", "0000:01:00.0", Bus.PCI, "GPU", "10de", "2503",
                DeviceCategory.DISPLAY, DeviceState.ACTIVE, Optional.of("nvidia"), Map.of());
    }

    private Device usb(Map<String, String> props) {
        return new Device("/sys/u", "/sys/u", "1-1", Bus.USB, "Webcam", "046d", "0825",
                DeviceCategory.IMAGING, DeviceState.ACTIVE, Optional.of("uvcvideo"), props);
    }

    @Test
    void usesLspciForPciDevice() {
        FakeCommandRunner runner = new FakeCommandRunner()
                .stubStdout("01:00.0 VGA compatible controller [0300]: NVIDIA\n",
                        "lspci", "-vvnn", "-s", "0000:01:00.0");
        AdvancedDetailProvider provider = new AdvancedDetailProvider(runner, "lspci", "lsusb");

        assertTrue(provider.load(pci()).contains("VGA compatible controller"));
    }

    @Test
    void usesLsusbWithBusAndDevNumberForUsbDevice() {
        FakeCommandRunner runner = new FakeCommandRunner()
                .stubStdout("Bus 001 Device 002: ID 046d:0825 Logitech Webcam\n",
                        "lsusb", "-v", "-s", "1:2");
        AdvancedDetailProvider provider = new AdvancedDetailProvider(runner, "lspci", "lsusb");

        String text = provider.load(usb(Map.of("BUSNUM", "001", "DEVNUM", "002")));

        assertTrue(text.contains("Logitech Webcam"), text);
    }

    @Test
    void reportsUnavailableWhenUsbAddressUnknown() {
        AdvancedDetailProvider provider = new AdvancedDetailProvider(new FakeCommandRunner(), "lspci", "lsusb");

        assertTrue(provider.load(usb(Map.of())).contains("No advanced details"));
    }

    @Test
    void degradesWhenToolFails() {
        // unstubbed runner -> failure result for the lspci call
        AdvancedDetailProvider provider = new AdvancedDetailProvider(new FakeCommandRunner(), "lspci", "lsusb");

        assertTrue(provider.load(pci()).contains("unavailable"));
    }
}
