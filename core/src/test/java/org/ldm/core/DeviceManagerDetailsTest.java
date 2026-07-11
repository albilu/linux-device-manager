package org.ldm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ldm.core.categorize.Categorizer;
import org.ldm.core.detail.DetailProvider;
import org.ldm.core.model.Bus;
import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.DeviceState;
import org.ldm.core.model.DetailTab;
import org.ldm.core.process.FakeCommandRunner;
import org.ldm.core.scan.SysfsScanner;
import org.ldm.core.state.StateResolver;
import org.ldm.core.udev.UdevEnricher;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeviceManagerDetailsTest {

    private DeviceManager managerWith(Map<DetailTab, DetailProvider> providers) {
        return new DeviceManager(
                new SysfsScanner(Path.of("/nonexistent")),
                new UdevEnricher(new FakeCommandRunner(), "udevadm"),
                new Categorizer(),
                new StateResolver(),
                providers);
    }

    private Device device() {
        return new Device("/sys/x", "/sys/x", "0000:01:00.0", Bus.PCI, "Test GPU", "10de", "2503",
                DeviceCategory.DISPLAY, DeviceState.ACTIVE, Optional.of("nvidia"), Map.of());
    }

    @Test
    void dispatchesToRegisteredProvider() {
        DeviceManager manager = managerWith(Map.of(
                DetailTab.GENERAL, d -> "GENERAL:" + d.displayName(),
                DetailTab.DRIVER, d -> "DRIVER"));

        assertEquals("GENERAL:Test GPU", manager.loadDetails(device(), DetailTab.GENERAL));
        assertEquals("DRIVER", manager.loadDetails(device(), DetailTab.DRIVER));
    }

    @Test
    void returnsEmptyStringWhenNoProviderRegistered() {
        DeviceManager manager = managerWith(Map.of());

        assertEquals("", manager.loadDetails(device(), DetailTab.LOGS));
    }
}
