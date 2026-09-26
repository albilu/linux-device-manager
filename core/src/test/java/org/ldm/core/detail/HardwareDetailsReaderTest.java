package org.ldm.core.detail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ldm.core.model.Bus;
import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.DeviceState;
import org.ldm.core.process.CommandResult;
import org.ldm.core.process.FakeCommandRunner;
import org.ldm.core.process.ToolLocator;
import org.ldm.core.scan.SysfsIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class HardwareDetailsReaderTest {
    @TempDir Path root;
    private final FakeCommandRunner runner = new FakeCommandRunner();

    private HardwareDetailsReader reader() {
        return new HardwareDetailsReader(root.resolve("sys"), root.resolve("proc"), root.resolve("dev"), runner, new ToolLocator(null));
    }

    @Test
    void countsHybridCoresAndSharedCachesWithinTheSelectedSocket() throws Exception {
        for (int i = 0; i < 5; i++) {
            Path cpu = root.resolve("sys/devices/system/cpu/cpu" + i);
            write(cpu, "topology/physical_package_id", i == 4 ? "1" : "0");
            write(cpu, "topology/core_id", Integer.toString(i < 2 ? 0 : i - 1));
            write(cpu, "cpufreq/cpuinfo_max_freq", i == 3 ? "5200000" : "3200000");
            write(cpu, "cache/index0/level", "3");
            write(cpu, "cache/index0/type", "Unified");
            write(cpu, "cache/index0/size", "30720K");
            write(cpu, "cache/index0/shared_cpu_list", "0-3");
        }
        write(root.resolve("proc"), "cpuinfo", "processor : 0\nmodel name : Hybrid Example CPU\n\nprocessor : 4\nmodel name : Other Socket\n");
        var device = device(root.resolve("sys/devices/system/cpu/cpu0"), Bus.OTHER, DeviceCategory.PROCESSOR,
                Map.of("LOGICAL_CPUS", "cpu0, cpu1, cpu2, cpu3, cpu4"));
        String text = reader().read(device).render(false);
        assertTrue(text.contains("Processor: Hybrid Example CPU"), text);
        assertTrue(text.contains("Physical cores: 3"), text);
        assertTrue(text.contains("Threads (logical CPUs): 4"), text);
        assertTrue(text.contains("Maximum clock speed: 5.2 GHz"), text);
        assertTrue(text.contains("L3 cache (total): 30 MiB"), text);
        assertTrue(runner.invocations().isEmpty());
    }

    @Test
    void unknownCpuTopologyDoesNotBecomeOneCoreOrZeroGhz() throws Exception {
        Path cpu = node("system/cpu/cpu0");
        String text = reader().read(device(cpu, Bus.OTHER, DeviceCategory.PROCESSOR, Map.of())).render(true);
        assertTrue(text.contains("Not reported"), text);
        assertFalse(text.contains("GHz"), text);
        assertFalse(text.contains("Physical cores: 1"), text);
    }

    @Test
    void readsVramBytesFromTheSelectedAmdDevice() throws Exception {
        Path gpu = node("gpu");
        write(gpu, "mem_info_vram_total", "8589934592");
        write(gpu, "drm/card0/card0-DP-1/status", "connected");
        write(gpu, "drm/card0/card0-DP-1/modes", "3840x2160\n1920x1080\n");
        alias("drm", "card0", gpu.resolve("drm/card0"));
        var device = device(gpu, Bus.PCI, DeviceCategory.DISPLAY, Map.of("SYSFS_DRM", "card0"));
        String text = reader().read(device).render(false);
        assertTrue(text.contains("Dedicated video memory: 8 GiB"), text);
        assertTrue(text.contains("Connected displays: 1"), text);
        assertTrue(text.contains("DP-1 supported resolutions: 3840 × 2160, 1920 × 1080"), text);
        assertFalse(text.contains("Current resolution"));
        assertTrue(runner.invocations().isEmpty());
    }

    @Test
    void matchesNvidiaMemoryByPciAddressEvenWithMultipleGpus() throws Exception {
        Path gpu = node("gpu");
        runner.stubStdout("00000000:05:00.0, 4096\n00000000:01:00.0, 12227\n", gpuCommand());
        var device = nvidia(gpu);
        String text = reader().read(device).render(true);
        assertTrue(text.contains("Dedicated video memory: 11.94 GiB"), text);
        assertFalse(text.contains("Dedicated video memory: 4 GiB"), text);
        assertEquals(1, runner.invocations().size());
        assertEquals("--id=0000:01:00.0", runner.invocations().getFirst().getLast());
    }

    @Test
    void refusesMemoryFromAnotherGpuAndHandlesUnavailableTools() throws Exception {
        var device = nvidia(node("gpu"));
        for (String output : new String[]{"00000000:05:00.0, 4096", "00000000:01:00.0, [N/A]"}) {
            runner.stubStdout(output, gpuCommand());
            assertTrue(reader().read(device).render(true).contains("Not reported by the driver"));
        }
        runner.stub(new CommandResult(-1, "", "timed out", true), gpuCommand());
        assertTrue(reader().read(device).render(true).contains("Not reported by the driver"));
    }

    @Test
    void keepsCameraFrameRatesWithTheirFormatAndResolution() throws Exception {
        Path camera = node("camera");
        video(camera, "video4");
        video(camera, "video5");
        runner.stubStdout("""
                ioctl: VIDIOC_ENUM_FMT
                  Type: Video Capture
                  [0]: 'MJPG' (Motion-JPEG, compressed)
                    Size: Discrete 640x480
                      Interval: Discrete 0.017s (60.000 fps)
                    Size: Discrete 1920x1080
                      Interval: Discrete 0.033s (30.000 fps)
                  [1]: 'YUYV' (YUYV 4:2:2)
                    Size: Discrete 1920x1080
                      Interval: Discrete 0.333s (3.000 fps)
                """, cameraCommand("video4"));
        // The second endpoint is metadata-only; it must not hide the working capture node.
        runner.stubStdout("Type: Metadata Capture", cameraCommand("video5"));
        var report = reader().read(device(camera, Bus.USB, DeviceCategory.IMAGING, Map.of("SYSFS_VIDEO4LINUX", "video4, video5")));
        assertTrue(report.render(true).contains("Maximum reported resolution: 1920 × 1080, up to 30 fps (Motion-JPEG)"), report.toString());
        assertFalse(report.render(true).contains("60 fps"));
        assertTrue(report.render(false).contains("YUYV (YUYV 4:2:2) — 1920 × 1080: 3 fps"));
        assertFalse(report.render(true).contains("Not reported"));
    }

    @Test
    void preservesStepwiseCameraModesAsRanges() {
        var formats = new CameraFormats();
        formats.add("""
                [0]: 'YUYV' (YUYV 4:2:2)
                  Size: Stepwise 320x240 - 1920x1080 with step 16/8
                    Interval: Stepwise 0.033s - 0.200s with step 0.001s (5.000-30.000 fps)
                [1]: 'MJPG' (Motion-JPEG)
                  Size: Discrete 640x480
                """);
        var result = new HardwareDetails.Builder();
        formats.describe(result);
        String text = result.build().render(false);
        assertTrue(text.contains("320 × 240 - 1920 × 1080 with step 16/8 (stepwise range)"), text);
        assertTrue(text.contains("5–30 fps"), text);
        assertFalse(text.contains("Maximum reported resolution"));
    }

    @Test
    void missingCameraToolExplainsWhyResolutionsAreUnavailable() throws Exception {
        Path camera = node("camera");
        video(camera, "video0");
        String text = reader().read(device(camera, Bus.USB, DeviceCategory.IMAGING,
                Map.of("SYSFS_VIDEO4LINUX", "video0"))).render(true);
        assertTrue(text.contains("requires camera access and v4l2-ctl from v4l-utils"), text);
        assertFalse(text.contains("Maximum reported resolution"), text);
    }

    @Test
    void neverQueriesAReusedCameraNameBelongingToAnotherDevice() throws Exception {
        Path selected = node("disconnected-camera");
        Path replacement = node("other-camera");
        video(replacement, "video0");
        reader().read(device(selected, Bus.USB, DeviceCategory.IMAGING, Map.of("SYSFS_VIDEO4LINUX", "video0, ../../video1")));
        assertTrue(runner.invocations().isEmpty());
    }

    @Test
    void rejectsAReplacedUsbInstanceBeforeLoadingSpecifications() throws Exception {
        Path camera = node("camera");
        write(camera, "idVendor", "abcd");
        write(camera, "devnum", "4");
        video(camera, "video0");
        var initial = device(camera, Bus.USB, DeviceCategory.IMAGING, Map.of("SYSFS_VIDEO4LINUX", "video0"));
        var captured = new Device(initial.id(), initial.syspath(), initial.busInfo(), initial.bus(), initial.displayName(),
                initial.vendorId(), initial.productId(), initial.category(), initial.state(), initial.driver(), initial.properties(),
                initial.driverBindings(), initial.authorized(), initial.actionKind(), SysfsIdentity.read(camera), false, false);
        write(camera, "devnum", "5");
        assertTrue(reader().read(captured).render(true).contains("Refresh the device list"));
        assertTrue(runner.invocations().isEmpty());
    }

    @Test
    void capacityUses512ByteSectorsOnA4KnDrive() throws Exception {
        Path disk = node("disk/block/sda");
        alias("block", "sda", disk);
        write(disk, "size", "1953125000");
        write(disk, "queue/logical_block_size", "4096");
        write(disk, "queue/rotational", "0");
        String text = reader().read(device(disk, Bus.OTHER, DeviceCategory.STORAGE, Map.of("SYSFS_BLOCK", "sda"))).render(false);
        assertTrue(text.contains("Capacity: 1 TB (931.32 GiB)"), text);
        assertTrue(text.contains("Drive type: Solid-state storage"), text);
        assertTrue(text.contains("Logical sector size: 4096 bytes"), text);
    }

    @Test
    void reportsEachNvmeNamespaceSeparatelyInsteadOfInventingControllerCapacity() throws Exception {
        Path controller = node("nvme-controller");
        for (String name : new String[]{"nvme0n1", "nvme0n2"}) {
            Path disk = controller.resolve("nvme/nvme0/" + name);
            write(disk, "size", "2097152");
            alias("block", name, disk);
        }
        String text = reader().read(device(controller, Bus.PCI, DeviceCategory.STORAGE,
                Map.of("SYSFS_BLOCK", "nvme0n1, nvme0n2"))).render(true);
        assertTrue(text.contains("nvme0n1 Capacity: 1.07 GB (1 GiB)"), text);
        assertTrue(text.contains("nvme0n2 Capacity: 1.07 GB (1 GiB)"), text);
        assertFalse(text.contains("2 GiB"), text);
    }

    @Test
    void networkSummaryDistinguishesNegotiatedSpeedFromDisconnectedOrUnknown() throws Exception {
        Path nic = node("nic");
        Path net = nic.resolve("net/eth0");
        write(net, "type", "1");
        write(net, "carrier", "1");
        write(net, "speed", "2500");
        alias("net", "eth0", net);
        var device = device(nic, Bus.PCI, DeviceCategory.NETWORK, Map.of("SYSFS_NET", "eth0"));
        String text = reader().read(device).render(true);
        assertTrue(text.contains("Network type: Ethernet"), text);
        assertTrue(text.contains("Current link speed: 2.5 Gb/s"), text);
        write(net, "carrier", "0");
        assertFalse(reader().read(device).render(true).contains("Current link speed"));
        write(net, "carrier", "1");
        write(net, "speed", "-1");
        assertFalse(reader().read(device).render(true).contains("Current link speed"));
    }

    @Test
    void describesUsbAudioPlaybackAndRecordingCapabilities() throws Exception {
        Path audio = node("audio");
        Path card = audio.resolve("sound/card3");
        Files.createDirectories(card.resolve("pcmC3D0p"));
        Files.createDirectories(card.resolve("pcmC3D0c"));
        alias("sound", "card3", card);
        write(root.resolve("proc/asound/card3"), "stream0", """
                Playback:
                  Channels: 2
                  Rates: 44100, 48000
                  Bits: 24
                Capture:
                  Channels: 1
                  Rates: 48000
                  Bits: 24
                """);
        write(audio, "speed", "480");
        String text = reader().read(device(audio, Bus.USB, DeviceCategory.MULTIMEDIA, Map.of("SYSFS_SOUND", "card3"))).render(false);
        assertTrue(text.contains("Audio functions: Recording, Playback"), text);
        assertTrue(text.contains("Playback channels: Stereo (2)"), text);
        assertTrue(text.contains("Recording channels: Mono (1)"), text);
        assertTrue(text.contains("Playback sample rates: 44.1 kHz, 48 kHz"), text);
        assertTrue(text.contains("Recording sample rates: 48 kHz"), text);
        assertFalse(text.contains("Recording sample rates: 44.1 kHz"), text);
        assertTrue(text.contains("USB connection speed: 480 Mb/s"), text);
    }

    @Test
    void interruptedQueriesStopWithoutStartingNativeTools() throws Exception {
        Device gpu = nvidia(node("gpu"));
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> reader().read(gpu));
            assertTrue(runner.invocations().isEmpty());
        } finally { Thread.interrupted(); }
    }

    @Test
    void queriesAllVideoEndpointsWithinOneDeadline() throws Exception {
        Path camera = node("camera");
        for (int i = 0; i < 5; i++) {
            video(camera, "video" + i);
            runner.stubStdout("[0]: 'MJPG' (Motion-JPEG)\nSize: Discrete " + (i == 4 ? "3840x2160" : "640x480"),
                    cameraCommand("video" + i));
        }
        String text = reader().read(device(camera, Bus.USB, DeviceCategory.IMAGING,
                Map.of("SYSFS_VIDEO4LINUX", "video0, video1, video2, video3, video4"))).render(true);
        assertTrue(text.contains("Maximum reported resolution: 3840 × 2160"), text);
        assertEquals(5, runner.invocations().size());
    }

    private Path node(String name) throws Exception { return Files.createDirectories(root.resolve("sys/devices").resolve(name)); }

    private void write(Path node, String attribute, String value) throws Exception {
        Path file = node.resolve(attribute);
        Files.createDirectories(file.getParent());
        Files.writeString(file, value + "\n");
    }

    private void alias(String kind, String name, Path target) throws Exception {
        Path link = root.resolve("sys/class").resolve(kind).resolve(name);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, target);
    }

    private void video(Path owner, String name) throws Exception {
        Path endpoint = Files.createDirectories(owner.resolve("video4linux/" + name));
        alias("video4linux", name, endpoint);
    }

    private Device device(Path node, Bus bus, DeviceCategory category, Map<String, String> props) {
        return new Device(node.toString(), node.toString(), "0000:01:00.0", bus, "Example hardware", "1234", "abcd",
                category, DeviceState.ACTIVE, Optional.empty(), props);
    }

    private Device nvidia(Path node) {
        return new Device(node.toString(), node.toString(), "0000:01:00.0", Bus.PCI, "NVIDIA GPU", "10de", "2503",
                DeviceCategory.DISPLAY, DeviceState.ACTIVE, Optional.of("nvidia"), Map.of());
    }

    private String[] gpuCommand() {
        return new String[]{"env", "LC_ALL=C", "nvidia-smi", "--query-gpu=pci.bus_id,memory.total",
                "--format=csv,noheader,nounits", "--id=0000:01:00.0"};
    }

    private String[] cameraCommand(String name) {
        return new String[]{"env", "LC_ALL=C", "v4l2-ctl", "--device=" + root.resolve("dev").resolve(name), "--list-formats-ext"};
    }
}
