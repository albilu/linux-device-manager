package org.ldm.core;

import org.ldm.core.categorize.Categorizer;
import org.ldm.core.detail.DetailProvider;
import org.ldm.core.model.CategoryGroup;
import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.DetailTab;
import org.ldm.core.model.SysfsDevice;
import org.ldm.core.scan.SysfsScanner;
import org.ldm.core.state.StateResolver;
import org.ldm.core.udev.UdevEnricher;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import org.ldm.core.model.Bus;

/**
 * Core facade: enumerates, enriches, categorizes, groups, and loads per-tab
 * details for the UI.
 */
public final class DeviceManager {

    private final SysfsScanner scanner;
    private final UdevEnricher enricher;
    private final Categorizer categorizer;
    private final StateResolver stateResolver;
    private final Map<DetailTab, DetailProvider> detailProviders;
    private record Classification(String instanceId, DeviceCategory category) { }
    private final Map<String, Classification> usbCategories = new HashMap<>();

    public DeviceManager(SysfsScanner scanner, UdevEnricher enricher,
            Categorizer categorizer, StateResolver stateResolver,
            Map<DetailTab, DetailProvider> detailProviders) {
        this.scanner = scanner;
        this.enricher = enricher;
        this.categorizer = categorizer;
        this.stateResolver = stateResolver;
        this.detailProviders = Map.copyOf(detailProviders);
    }

    /**
     * @return non-empty category groups, ordered by {@link DeviceCategory}
     *         declaration order.
     */
    public List<CategoryGroup> refresh() {
        Map<DeviceCategory, List<Device>> byCategory = new EnumMap<>(DeviceCategory.class);
        List<SysfsDevice> scanned = scanner.scan();
        stateResolver.retainDevices(scanned);
        var present = scanned.stream().map(SysfsDevice::syspath).collect(java.util.stream.Collectors.toSet());
        usbCategories.keySet().retainAll(present);
        for (SysfsDevice raw : scanned) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            Map<String, String> props = new LinkedHashMap<>(raw.attributes());
            props.putAll(enricher.properties(raw));
            props.putIfAbsent("BUSNUM", raw.attributes().getOrDefault("busnum", ""));
            props.putIfAbsent("DEVNUM", raw.attributes().getOrDefault("devnum", ""));
            Device device = new Device(
                    raw.syspath(),
                    raw.syspath(),
                    raw.busInfo(),
                    raw.bus(),
                    enricher.displayName(raw, props),
                    raw.vendorId(),
                    raw.productId(),
                    category(raw),
                    stateResolver.resolve(raw),
                    raw.driver(),
                    props, raw.driverBindings(), raw.authorized(), raw.actionKind(), raw.instanceId(),
                    raw.enableSupported(), raw.disableSupported());
            byCategory.computeIfAbsent(device.category(), k -> new ArrayList<>()).add(device);
        }
        List<CategoryGroup> groups = new ArrayList<>();
        for (DeviceCategory category : DeviceCategory.values()) {
            List<Device> devices = byCategory.get(category);
            if (devices != null && !devices.isEmpty()) {
                groups.add(new CategoryGroup(category, List.copyOf(devices)));
            }
        }
        return groups;
    }

    private DeviceCategory category(SysfsDevice raw) {
        DeviceCategory category = categorizer.categorize(raw);
        if (raw.bus() != Bus.USB || raw.instanceId().isEmpty()) return category;
        Classification previous = usbCategories.get(raw.syspath());
        if (raw.authorized().filter(a -> !a).isPresent() && previous != null
                && previous.instanceId().equals(raw.instanceId())) return previous.category();
        usbCategories.put(raw.syspath(), new Classification(raw.instanceId(), category));
        return category;
    }

    /** Remember verified driver unbinds for this application session; USB has a kernel flag. */
    public void recordAction(Device device, boolean enabled) {
        stateResolver.recordAction(device, enabled);
    }

    /**
     * @return the text content for the given detail tab, or "" if no provider is
     *         registered.
     */
    public String loadDetails(Device device, DetailTab tab) {
        DetailProvider provider = detailProviders.get(tab);
        return provider == null ? "" : provider.load(device);
    }
}
