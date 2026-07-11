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
        for (SysfsDevice raw : scanner.scan()) {
            Map<String, String> props = enricher.properties(raw);
            Device device = new Device(
                    raw.syspath(),
                    raw.syspath(),
                    raw.busInfo(),
                    raw.bus(),
                    enricher.displayName(raw, props),
                    raw.vendorId(),
                    raw.productId(),
                    categorizer.categorize(raw),
                    stateResolver.resolve(raw),
                    raw.driver(),
                    props);
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

    /**
     * @return the text content for the given detail tab, or "" if no provider is
     *         registered.
     */
    public String loadDetails(Device device, DetailTab tab) {
        DetailProvider provider = detailProviders.get(tab);
        return provider == null ? "" : provider.load(device);
    }
}
