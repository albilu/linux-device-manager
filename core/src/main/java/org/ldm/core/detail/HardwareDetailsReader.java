package org.ldm.core.detail;

import org.ldm.core.model.Bus;
import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceCategory;
import org.ldm.core.process.CommandRunner;
import org.ldm.core.process.ToolLocator;
import org.ldm.core.scan.SysfsIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Read-only, device-scoped specifications. Call on a worker, never the GTK thread. */
public final class HardwareDetailsReader {
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(3);
    private static final Pattern PCI_ADDRESS = Pattern.compile("(?i)([0-9a-f]{4,8}):([0-9a-f]{2}):([0-9a-f]{2})\\.([0-7])");
    private final Path sysRoot;
    private final Path procRoot;
    private final Path devRoot;
    private final CommandRunner runner;
    private final ToolLocator tools;

    public HardwareDetailsReader(CommandRunner runner, ToolLocator tools) {
        this(Path.of("/sys"), Path.of("/proc"), Path.of("/dev"), runner, tools);
    }

    public HardwareDetailsReader(Path sysRoot, Path procRoot, Path devRoot, CommandRunner runner, ToolLocator tools) {
        this.sysRoot = sysRoot.toAbsolutePath().normalize();
        this.procRoot = procRoot;
        this.devRoot = devRoot;
        this.runner = runner;
        this.tools = tools;
    }

    public HardwareDetails read(Device device) {
        checkCancelled();
        var result = new HardwareDetails.Builder();
        Path node = realPath(Path.of(device.syspath()));
        if (node == null || !node.startsWith(sysRoot) || !sameInstance(device, node)) {
            result.note("Hardware specifications are unavailable. Refresh the device list if the device was disconnected.");
            return result.build();
        }
        String maker = device.properties().getOrDefault("ID_VENDOR_FROM_DATABASE", read(node.resolve("manufacturer")));
        result.add("Manufacturer", maker);
        String model = read(node.resolve("product"));
        if (model.isEmpty()) model = read(node.resolve("model"));
        result.add("Model", model);
        if (device.category() == DeviceCategory.PROCESSOR) cpu(device, node, result);
        if (device.category() == DeviceCategory.DISPLAY) gpu(device, node, result);
        List<Path> cameras = endpoints(device, node, "video4linux");
        if (device.category() == DeviceCategory.IMAGING || !cameras.isEmpty()) camera(cameras, result);
        storage(device, node, result);
        network(device, node, result);
        audio(device, node, result);
        if (device.bus() == Bus.USB) usb(node, result);
        checkCancelled();
        if (!sameInstance(device, node)) {
            var changed = new HardwareDetails.Builder();
            changed.note("The device changed while its specifications were loading. Refresh the device list.");
            return changed.build();
        }
        return result.build();
    }

    private void cpu(Device device, Path node, HardwareDetails.Builder result) {
        String packageId = read(node.resolve("topology/physical_package_id"));
        List<Path> cpus = new ArrayList<>();
        for (String name : device.properties().getOrDefault("LOGICAL_CPUS", node.getFileName().toString()).split(",\\s*")) {
            if (!name.matches("cpu[0-9]+")) continue;
            Path cpu = node.resolveSibling(name);
            if (Files.isDirectory(cpu) && !packageId.isEmpty()
                    && packageId.equals(read(cpu.resolve("topology/physical_package_id")))) cpus.add(cpu);
        }
        String model = cpuModel(node.getFileName().toString().replaceFirst("^cpu", ""));
        result.add("Processor", model.isBlank() ? "Model not reported" : model);
        if (cpus.isEmpty()) {
            result.add("Cores and threads", "Not reported by the system");
            return;
        }
        Set<String> cores = new LinkedHashSet<>();
        boolean completeCoreIds = true;
        long maxKhz = 0;
        Set<Long> baseKhz = new TreeSet<>();
        Map<String, Long> caches = new LinkedHashMap<>();
        Set<String> sharedCaches = new LinkedHashSet<>();
        for (Path cpu : cpus) {
            checkCancelled();
            String core = read(cpu.resolve("topology/core_id"));
            if (core.matches("[0-9]+")) cores.add(read(cpu.resolve("topology/die_id")) + ":" + core);
            else completeCoreIds = false;
            maxKhz = Math.max(maxKhz, positiveLong(read(cpu.resolve("cpufreq/cpuinfo_max_freq"))));
            long base = positiveLong(read(cpu.resolve("cpufreq/base_frequency")));
            if (base > 0) baseKhz.add(base);
            for (Path cache : children(cpu.resolve("cache"), "index[0-9]+")) {
                String level = read(cache.resolve("level"));
                String type = read(cache.resolve("type"));
                String shared = read(cache.resolve("shared_cpu_list"));
                long size = cacheBytes(read(cache.resolve("size")));
                if (level.isEmpty() || shared.isEmpty() || size == 0) continue;
                String label = "L" + level + (type.equals("Unified") ? "" : " " + type.toLowerCase(Locale.ROOT));
                if (sharedCaches.add(label + ":" + shared)) caches.merge(label, size, Long::sum);
            }
        }
        if (completeCoreIds) result.add("Physical cores", Integer.toString(cores.size()));
        else result.add("Physical cores", "Not reported by the system");
        result.add("Threads (logical CPUs)", Integer.toString(cpus.size()));
        if (maxKhz > 0) result.add("Maximum clock speed", number(maxKhz / 1_000_000.0) + " GHz");
        if (!baseKhz.isEmpty()) result.add("Base clock speeds", baseKhz.stream()
                .map(khz -> number(khz / 1_000_000.0) + " GHz").collect(Collectors.joining(", ")), false);
        caches.forEach((label, bytes) -> result.add(label + " cache (total)", binaryBytes(bytes), false));
    }

    private String cpuModel(String processor) {
        for (String section : read(procRoot.resolve("cpuinfo")).split("\\n\\s*\\n")) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (String line : section.lines().toList()) {
                int colon = line.indexOf(':');
                if (colon > 0) fields.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
            }
            if (processor.equals(fields.get("processor"))) {
                for (String key : List.of("model name", "cpu model", "Processor")) {
                    String value = fields.get(key);
                    if (value != null && !value.isBlank()) return value;
                }
            }
        }
        return "";
    }

    private void gpu(Device device, Path node, HardwareDetails.Builder result) {
        long bytes = positiveLong(read(node.resolve("mem_info_vram_total")));
        String pci = normalizePciAddress(device.busInfo());
        if (bytes == 0 && !pci.isEmpty() && (device.driver().orElse("").equals("nvidia")
                || device.vendorId().equalsIgnoreCase("10de"))) {
            var output = runner.run(TOOL_TIMEOUT, "env", "LC_ALL=C", tools.locate("nvidia-smi"),
                    "--query-gpu=pci.bus_id,memory.total", "--format=csv,noheader,nounits", "--id=" + device.busInfo());
            if (output.success()) {
                for (String line : output.stdout().lines().toList()) {
                    String[] fields = line.split(",");
                    if (fields.length == 2 && pci.equals(normalizePciAddress(fields[0].strip()))) {
                        long mib = positiveLong(fields[1].strip());
                        if (mib <= Long.MAX_VALUE / (1024 * 1024)) bytes = mib * 1024 * 1024;
                    }
                }
            }
        }
        result.add("Dedicated video memory", bytes > 0 ? binaryBytes(bytes) : "Not reported by the driver");
        List<Path> cards = endpoints(device, node, "drm").stream()
                .filter(p -> p.getFileName().toString().matches("card[0-9]+")).toList();
        int connected = 0;
        int known = 0;
        for (Path card : cards) {
            for (Path connector : children(card, "card[0-9]+-.+")) {
                String status = read(connector.resolve("status"));
                if (!status.equals("connected") && !status.equals("disconnected")) continue;
                known++;
                if (status.equals("connected")) {
                    connected++;
                    String port = connector.getFileName().toString().replaceFirst("^card[0-9]+-", "");
                    result.add("Display on " + port, "Connected", false);
                    String modes = read(connector.resolve("modes")).lines().filter(s -> s.matches("[0-9]+x[0-9]+"))
                            .map(s -> s.replace("x", " × ")).distinct().collect(Collectors.joining(", "));
                    result.add(port + " supported resolutions", modes, false);
                }
            }
        }
        if (known > 0) result.add("Connected displays", Integer.toString(connected));
    }

    private void camera(List<Path> endpoints, HardwareDetails.Builder result) {
        CameraFormats formats = new CameraFormats();
        long deadline = System.nanoTime() + TOOL_TIMEOUT.toNanos();
        // Composite cameras can expose a second metadata-only node. Read capture formats only;
        // never start streaming, change a format, or request elevated privileges.
        for (Path endpoint : endpoints) {
            checkCancelled();
            String name = endpoint.getFileName().toString();
            if (!name.matches("video[0-9]+")) continue;
            Path alias = sysRoot.resolve("class/video4linux").resolve(name);
            if (!endpoint.equals(realPath(alias))) continue;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                result.note("Camera mode queries timed out; the listed capabilities may be incomplete.");
                break;
            }
            var output = runner.run(Duration.ofNanos(remaining), "env", "LC_ALL=C", tools.locate("v4l2-ctl"),
                    "--device=" + devRoot.resolve(name), "--list-formats-ext");
            if (output.timedOut()) result.note("Camera mode queries timed out; the listed capabilities may be incomplete.");
            if (output.success() && endpoint.equals(realPath(alias))) formats.add(output.stdout());
        }
        if (formats.isEmpty()) result.add("Camera resolutions", "Not reported (requires camera access and v4l2-ctl from v4l-utils)");
        else formats.describe(result);
    }

    private void storage(Device device, Path node, HardwareDetails.Builder result) {
        List<Path> disks = endpoints(device, node, "block");
        for (Path disk : disks) {
            if (Files.exists(disk.resolve("partition"))) continue;
            String prefix = disks.size() > 1 ? disk.getFileName() + " " : "";
            // The kernel's size attribute always uses 512-byte sectors, including 4Kn drives.
            long sectors = positiveLong(read(disk.resolve("size")));
            if (sectors > 0 && sectors <= Long.MAX_VALUE / 512) result.add(prefix + "Capacity", capacity(sectors * 512));
            String model = read(disk.resolve("device/model"));
            result.add(prefix + "Drive model", model);
            String rotational = read(disk.resolve("queue/rotational"));
            if (rotational.equals("0")) result.add(prefix + "Drive type", "Solid-state storage");
            if (rotational.equals("1")) result.add(prefix + "Drive type", "Rotating disk");
            String removable = read(disk.resolve("removable"));
            if (!removable.isEmpty()) result.add(prefix + "Removable media", removable.equals("1") ? "Yes" : "No", false);
            String readOnly = read(disk.resolve("ro"));
            if (readOnly.equals("1")) result.add(prefix + "Access", "Read-only");
            long blockSize = positiveLong(read(disk.resolve("queue/logical_block_size")));
            if (blockSize > 0) result.add(prefix + "Logical sector size", blockSize + " bytes", false);
        }
    }

    private void network(Device device, Path node, HardwareDetails.Builder result) {
        List<Path> interfaces = endpoints(device, node, "net");
        for (Path net : interfaces) {
            String prefix = interfaces.size() > 1 ? net.getFileName() + " " : "";
            boolean wireless = Files.isDirectory(net.resolve("wireless")) || Files.exists(net.resolve("phy80211"));
            String type = read(net.resolve("type"));
            result.add(prefix + "Network type", wireless ? "Wi-Fi" : type.equals("1") ? "Ethernet" : "Network interface");
            String carrier = read(net.resolve("carrier"));
            String state = read(net.resolve("operstate"));
            result.add(prefix + "Link", carrier.equals("1") ? "Connected" : carrier.equals("0") ? "Disconnected"
                    : state.equals("up") ? "Connected" : state.equals("down") ? "Down" : "Not reported");
            double speed = positiveDouble(read(net.resolve("speed")));
            if (carrier.equals("1") && speed > 0) result.add(prefix + "Current link speed", speedMbps(speed));
            result.add(prefix + "MAC address", read(net.resolve("address")), false);
            result.add(prefix + "Interface", net.getFileName().toString(), false);
            long mtu = positiveLong(read(net.resolve("mtu")));
            if (mtu > 0) result.add(prefix + "Maximum packet size (MTU)", mtu + " bytes", false);
        }
    }

    private void audio(Device device, Path node, HardwareDetails.Builder result) {
        for (Path card : endpoints(device, node, "sound")) {
            String name = card.getFileName().toString();
            if (!name.matches("card[0-9]+")) continue;
            Path alias = sysRoot.resolve("class/sound").resolve(name);
            List<Path> streams = children(procRoot.resolve("asound").resolve(name), "stream[0-9]+");
            Map<String, Set<Long>> channels = new LinkedHashMap<>();
            Map<String, Set<Long>> rates = new LinkedHashMap<>();
            Map<String, Set<Long>> bits = new LinkedHashMap<>();
            for (Path stream : streams) {
                String direction = "";
                for (String line : read(stream).lines().map(String::strip).toList()) {
                    if (line.equals("Playback:")) direction = "Playback";
                    else if (line.equals("Capture:")) direction = "Recording";
                    else if (!direction.isEmpty() && line.startsWith("Channels:")) {
                        long count = positiveLong(line.substring("Channels:".length()).strip());
                        if (count > 0) channels.computeIfAbsent(direction, k -> new TreeSet<>()).add(count);
                    } else if (!direction.isEmpty() && line.startsWith("Rates:")) {
                        for (String value : line.substring("Rates:".length()).split(",")) {
                            long rate = positiveLong(value.strip());
                            if (rate > 0) rates.computeIfAbsent(direction, k -> new TreeSet<>()).add(rate);
                        }
                    } else if (!direction.isEmpty() && line.startsWith("Bits:")) {
                        long depth = positiveLong(line.substring("Bits:".length()).strip());
                        if (depth > 0) bits.computeIfAbsent(direction, k -> new TreeSet<>()).add(depth);
                    }
                }
            }
            if (!card.equals(realPath(alias))) continue;
            Set<String> functions = new LinkedHashSet<>();
            for (Path pcm : children(card, "pcmC[0-9]+D[0-9]+[pc]")) {
                functions.add(pcm.getFileName().toString().endsWith("p") ? "Playback" : "Recording");
            }
            result.add("Audio functions", String.join(", ", functions));
            channels.forEach((label, values) -> result.add(label + " channels", values.stream().map(c ->
                    c == 1 ? "Mono (1)" : c == 2 ? "Stereo (2)" : c + " channels").collect(Collectors.joining(", "))));
            rates.forEach((label, values) -> result.add(label + " sample rates", values.stream().map(r -> number(r / 1000.0) + " kHz")
                    .collect(Collectors.joining(", "))));
            bits.forEach((label, values) -> result.add(label + " bit depths", values.stream().map(b -> b + "-bit")
                    .collect(Collectors.joining(", ")), false));
        }
    }

    private void usb(Path node, HardwareDetails.Builder result) {
        String version = read(node.resolve("version"));
        if (!version.isEmpty()) result.add("USB version", version, false);
        double speed = positiveDouble(read(node.resolve("speed")));
        if (speed > 0) result.add("USB connection speed", speedMbps(speed));
        result.add("Maximum requested USB current", read(node.resolve("bMaxPower")).replace("mA", " mA"), false);
    }

    /** Never attach a reused class name (video0, card0, eth0, …) to another device. */
    private List<Path> endpoints(Device device, Path owner, String kind) {
        Set<Path> found = new LinkedHashSet<>();
        for (String name : device.properties().getOrDefault("SYSFS_" + kind.toUpperCase(Locale.ROOT), "").split(",\\s*")) {
            if (!name.matches("[A-Za-z0-9_.:-]+") || name.equals(".") || name.equals("..")) continue;
            Path path = realPath(sysRoot.resolve("class").resolve(kind).resolve(name));
            if (path == null || !path.startsWith(sysRoot)) continue;
            Path parentDevice = realPath(path.resolve("device"));
            if (path.startsWith(owner) || (parentDevice != null && parentDevice.startsWith(owner))) found.add(path);
        }
        return List.copyOf(found);
    }

    private boolean sameInstance(Device device, Path node) {
        return Files.isDirectory(node) && (device.instanceId().isEmpty() || device.instanceId().equals(SysfsIdentity.read(node)));
    }

    private static Path realPath(Path path) {
        try { return path.toRealPath(); }
        catch (IOException | SecurityException e) { return null; }
    }

    private static String read(Path path) {
        checkCancelled();
        try { return Files.readString(path).strip(); }
        catch (IOException | SecurityException e) { return ""; }
    }

    private static List<Path> children(Path path, String pattern) {
        checkCancelled();
        try (var entries = Files.list(path)) {
            return entries.filter(p -> p.getFileName().toString().matches(pattern)).sorted().toList();
        } catch (IOException | SecurityException e) { return List.of(); }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Hardware detail loading cancelled");
    }

    static long positiveLong(String value) {
        try { return Math.max(0, Long.parseLong(value)); }
        catch (NumberFormatException e) { return 0; }
    }

    static double positiveDouble(String value) {
        try { double number = Double.parseDouble(value); return Double.isFinite(number) && number > 0 ? number : 0; }
        catch (NumberFormatException | NullPointerException e) { return 0; }
    }

    static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceFirst("\\.?0+$", "");
    }

    private static String speedMbps(double value) {
        return value >= 1000 ? number(value / 1000) + " Gb/s" : number(value) + " Mb/s";
    }

    private static String binaryBytes(long bytes) {
        String[] units = {"bytes", "KiB", "MiB", "GiB", "TiB", "PiB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return number(value) + " " + units[unit];
    }

    private static String capacity(long bytes) {
        String[] units = {"bytes", "kB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1000 && unit < units.length - 1) { value /= 1000; unit++; }
        return number(value) + " " + units[unit] + " (" + binaryBytes(bytes) + ")";
    }

    private static long cacheBytes(String value) {
        var match = Pattern.compile("([0-9]+)([KMG]?)").matcher(value);
        if (!match.matches()) return 0;
        long amount = positiveLong(match.group(1));
        long multiplier = switch (match.group(2)) { case "K" -> 1024; case "M" -> 1024 * 1024; case "G" -> 1024 * 1024 * 1024; default -> 1; };
        return amount <= Long.MAX_VALUE / multiplier ? amount * multiplier : 0;
    }

    private static String normalizePciAddress(String value) {
        var match = PCI_ADDRESS.matcher(value);
        if (!match.matches()) return "";
        return String.format(Locale.ROOT, "%04x:%s:%s.%s", Long.parseLong(match.group(1), 16),
                match.group(2).toLowerCase(Locale.ROOT), match.group(3).toLowerCase(Locale.ROOT), match.group(4));
    }
}
