package org.ldm.core.detail;

import org.ldm.core.model.Device;
import org.ldm.core.model.DriverBinding;
import org.ldm.core.process.CommandResult;
import org.ldm.core.process.CommandRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Describes functional bindings using the owning module recorded in sysfs. */
public final class DriverDetailProvider implements DetailProvider {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final CommandRunner runner;
    private final String modinfo;

    public DriverDetailProvider(CommandRunner runner, String modinfoPath) {
        this.runner = runner;
        this.modinfo = modinfoPath;
    }

    @Override
    public String load(Device device) {
        if (device.driverBindings().isEmpty()) return "No kernel driver is bound to this device.";
        Map<String, List<DriverBinding>> groups = device.driverBindings().stream().collect(
                Collectors.groupingBy(b -> b.name() + ":" + b.module().orElse(""), LinkedHashMap::new, Collectors.toList()));
        Map<String, CommandResult> modules = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        for (List<DriverBinding> bindings : groups.values()) {
            if (Thread.currentThread().isInterrupted()) break;
            DriverBinding binding = bindings.getFirst();
            if (!text.isEmpty()) text.append("\n\n");
            text.append("Driver: ").append(binding.name()).append('\n');
            text.append("Device/interface: ").append(bindings.stream()
                    .map(b -> Path.of(b.syspath()).getFileName().toString()).collect(Collectors.joining(", ")));
            if (binding.module().isEmpty()) {
                text.append("\nNo module link is available; this driver may be built into the kernel.");
                continue;
            }
            String module = binding.module().get();
            text.append("\nModule: ").append(module).append('\n');
            CommandResult result = modules.computeIfAbsent(module, name -> runner.run(TIMEOUT, modinfo, name));
            text.append(result.success() && !result.stdout().isBlank() ? result.stdout().strip()
                    : "(No modinfo details available.)");
        }
        return text.toString();
    }
}
