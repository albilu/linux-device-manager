package org.ldm.core;

import org.ldm.core.action.DeviceActionService;
import org.ldm.core.categorize.Categorizer;
import org.ldm.core.detail.AdvancedDetailProvider;
import org.ldm.core.detail.DetailProvider;
import org.ldm.core.detail.DriverDetailProvider;
import org.ldm.core.detail.GeneralDetailProvider;
import org.ldm.core.detail.HardwareDetailsReader;
import org.ldm.core.detail.LogsDetailProvider;
import org.ldm.core.model.CategoryGroup;
import org.ldm.core.model.Device;
import org.ldm.core.model.DetailTab;
import org.ldm.core.process.CommandRunner;
import org.ldm.core.process.ProcessCommandRunner;
import org.ldm.core.process.ToolLocator;
import org.ldm.core.scan.SysfsScanner;
import org.ldm.core.state.StateResolver;
import org.ldm.core.udev.UdevEnricher;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Manual smoke entry point: prints the device tree, the first device's details,
 * and its actions.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        ToolLocator tools = new ToolLocator(null);
        CommandRunner runner = new ProcessCommandRunner();
        var hardware = new HardwareDetailsReader(runner, tools);
        Map<DetailTab, DetailProvider> providers = Map.of(
                DetailTab.GENERAL, new GeneralDetailProvider(hardware),
                DetailTab.ADVANCED,
                new AdvancedDetailProvider(runner, tools.locate("lspci"), tools.locate("lsusb"), hardware),
                DetailTab.DRIVER, new DriverDetailProvider(runner, tools.locate("modinfo")),
                DetailTab.LOGS,
                new LogsDetailProvider(runner, tools.locate("journalctl"), tools.locate("dmesg")));

        DeviceManager manager = new DeviceManager(
                new SysfsScanner(Path.of("/sys")),
                new UdevEnricher(runner, tools.locate("udevadm")),
                new Categorizer(),
                new StateResolver(),
                providers);
        DeviceActionService actions = new DeviceActionService(runner, tools.locate("pkexec"),
                "/usr/libexec/ldm-helper");

        List<CategoryGroup> groups = manager.refresh();
        for (CategoryGroup group : groups) {
            System.out.println(group.displayName());
            for (Device d : group.devices()) {
                System.out.printf("  - %s [%s] (%s)%n",
                        d.displayName(), d.state(), d.driver().orElse("no driver"));
            }
        }

        groups.stream().flatMap(g -> g.devices().stream()).findFirst().ifPresent(first -> {
            System.out.println();
            System.out.println("=== General: " + first.displayName() + " ===");
            System.out.println(manager.loadDetails(first, DetailTab.GENERAL));
            System.out.println();
            System.out.println("=== Driver ===");
            System.out.println(manager.loadDetails(first, DetailTab.DRIVER));
            System.out.println();
            System.out.println("=== Actions ===");
            System.out.printf("canDisable=%s  canEnable=%s%n",
                    actions.canDisable(first), actions.canEnable(first));
            System.out.println("(enable/disable require pkexec authorization; not executed here)");
        });
    }
}
