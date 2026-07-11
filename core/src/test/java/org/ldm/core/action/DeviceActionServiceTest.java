package org.ldm.core.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ldm.core.action.DeviceActionResult.Outcome;
import org.ldm.core.model.Bus;
import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.DeviceState;
import org.ldm.core.process.CommandResult;
import org.ldm.core.process.FakeCommandRunner;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeviceActionServiceTest {

    private static final String HELPER = "/usr/libexec/ldm-helper";

    private Device device(String syspath, Optional<String> driver) {
        return new Device(syspath, syspath, "0000:01:00.0", Bus.PCI, "GPU", "10de", "2503",
                DeviceCategory.DISPLAY, DeviceState.ACTIVE, driver, Map.of());
    }

    @Test
    void capabilitiesReflectDriverPresence() {
        DeviceActionService svc = new DeviceActionService(new FakeCommandRunner(), "pkexec", HELPER);

        assertTrue(svc.canDisable(device("/sys/x", Optional.of("nvidia"))));
        assertFalse(svc.canEnable(device("/sys/x", Optional.of("nvidia"))));
        assertTrue(svc.canEnable(device("/sys/x", Optional.empty())));
        assertFalse(svc.canDisable(device("/sys/x", Optional.empty())));
    }

    @Test
    void disableInvokesHelperViaPkexecAndMapsSuccess() {
        FakeCommandRunner runner = new FakeCommandRunner()
                .stub(new CommandResult(0, "disabled 0000:01:00.0", "", false),
                        "pkexec", HELPER, "disable-driver", "/sys/devices/x");
        DeviceActionService svc = new DeviceActionService(runner, "pkexec", HELPER);

        DeviceActionResult r = svc.setDeviceEnabled(device("/sys/devices/x", Optional.of("nvidia")), false);

        assertEquals(Outcome.SUCCESS, r.outcome());
        assertEquals(List.of("pkexec", HELPER, "disable-driver", "/sys/devices/x"),
                runner.invocations().get(0));
    }

    @Test
    void enableUsesEnableDriverVerb() {
        FakeCommandRunner runner = new FakeCommandRunner()
                .stub(new CommandResult(0, "enabled 0000:01:00.0", "", false),
                        "pkexec", HELPER, "enable-driver", "/sys/devices/x");
        DeviceActionService svc = new DeviceActionService(runner, "pkexec", HELPER);

        DeviceActionResult r = svc.setDeviceEnabled(device("/sys/devices/x", Optional.empty()), true);

        assertEquals(Outcome.SUCCESS, r.outcome());
    }

    @Test
    void dismissedAuthMapsToCancelled() {
        FakeCommandRunner runner = new FakeCommandRunner()
                .stub(new CommandResult(126, "", "", false),
                        "pkexec", HELPER, "disable-driver", "/sys/devices/x");
        DeviceActionService svc = new DeviceActionService(runner, "pkexec", HELPER);

        assertEquals(Outcome.AUTH_CANCELLED,
                svc.setDeviceEnabled(device("/sys/devices/x", Optional.of("nvidia")), false).outcome());
    }

    @Test
    void notAuthorizedMapsToFailed() {
        FakeCommandRunner runner = new FakeCommandRunner()
                .stub(new CommandResult(127, "", "", false),
                        "pkexec", HELPER, "disable-driver", "/sys/devices/x");
        DeviceActionService svc = new DeviceActionService(runner, "pkexec", HELPER);

        DeviceActionResult r = svc.setDeviceEnabled(device("/sys/devices/x", Optional.of("nvidia")), false);
        assertEquals(Outcome.FAILED, r.outcome());
        assertTrue(r.message().toLowerCase().contains("authorized"));
    }

    @Test
    void helperErrorSurfacesStderr() {
        FakeCommandRunner runner = new FakeCommandRunner()
                .stub(new CommandResult(3, "", "no such device: /sys/devices/x", false),
                        "pkexec", HELPER, "disable-driver", "/sys/devices/x");
        DeviceActionService svc = new DeviceActionService(runner, "pkexec", HELPER);

        DeviceActionResult r = svc.setDeviceEnabled(device("/sys/devices/x", Optional.of("nvidia")), false);
        assertEquals(Outcome.FAILED, r.outcome());
        assertTrue(r.message().contains("no such device"));
    }

    @Test
    void disableWithoutDriverIsUnsupportedAndDoesNotInvoke() {
        FakeCommandRunner runner = new FakeCommandRunner();
        DeviceActionService svc = new DeviceActionService(runner, "pkexec", HELPER);

        DeviceActionResult r = svc.setDeviceEnabled(device("/sys/devices/x", Optional.empty()), false);
        assertEquals(Outcome.UNSUPPORTED, r.outcome());
        assertTrue(runner.invocations().isEmpty());
    }

    @Test
    void invalidSyspathIsUnsupportedAndDoesNotInvoke() {
        FakeCommandRunner runner = new FakeCommandRunner();
        DeviceActionService svc = new DeviceActionService(runner, "pkexec", HELPER);

        DeviceActionResult r = svc.setDeviceEnabled(device("/etc/passwd", Optional.of("nvidia")), false);
        assertEquals(Outcome.UNSUPPORTED, r.outcome());
        assertTrue(runner.invocations().isEmpty());
    }

    @Test
    void pathTraversalIsUnsupportedAndDoesNotInvoke() {
        FakeCommandRunner runner = new FakeCommandRunner();
        DeviceActionService svc = new DeviceActionService(runner, "pkexec", HELPER);

        DeviceActionResult r = svc.setDeviceEnabled(device("/sys/../etc/passwd", Optional.of("nvidia")), false);

        assertEquals(Outcome.UNSUPPORTED, r.outcome());
        assertTrue(runner.invocations().isEmpty());
    }
}
