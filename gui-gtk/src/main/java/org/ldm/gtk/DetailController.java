package org.ldm.gtk;

import org.ldm.core.DeviceManager;
import org.ldm.core.model.DetailTab;
import org.ldm.core.model.Device;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import org.gnome.gtk.Label;
import org.gnome.gtk.TextView;

/** Loads only the visible tab; cached data belongs to one inventory generation. */
public final class DetailController {
    private static final String LOADING = "Loading...";
    private static final String FAILED = "(failed to load)";
    private static final String NO_DEVICE = "Select a device to view details.";
    private final DeviceManager manager;
    private final UiExecutor ui;
    private final StatusController status;
    private final Label generalLabel;
    private final Label advancedLabel;
    private final Label driverLabel;
    private final TextView logsView;
    private final Map<Device, EnumMap<DetailTab, String>> cache = new HashMap<>();
    private Future<?> pending;
    private int generation;
    private StatusController.Operation operation;
    private Device selected;
    private DetailTab visibleTab = DetailTab.GENERAL;
    private boolean suspended;

    public DetailController(DeviceManager manager, UiExecutor ui, StatusController status,
                            Label generalLabel, Label advancedLabel, Label driverLabel,
                            TextView logsView) {
        this.manager = manager;
        this.ui = ui;
        this.status = status;
        this.generalLabel = generalLabel;
        this.advancedLabel = advancedLabel;
        this.driverLabel = driverLabel;
        this.logsView = logsView;
    }

    public void clearSelection() {
        cancelPending();
        selected = null;
        generalLabel.setLabel(NO_DEVICE);
        advancedLabel.setLabel("");
        driverLabel.setLabel("");
        logsView.getBuffer().setText("", -1);
    }

    public void invalidate() {
        clearSelection();
        cache.clear();
    }

    public void showDevice(Device device) {
        cancelPending();
        selected = device;
        generalLabel.setLabel(manager.loadDetails(device, DetailTab.GENERAL));
        var values = cache.computeIfAbsent(device, ignored -> new EnumMap<>(DetailTab.class));
        for (DetailTab tab : DetailTab.values()) {
            if (tab != DetailTab.GENERAL) update(tab, values.getOrDefault(tab, ""));
        }
        loadVisible();
    }

    public void showTab(DetailTab tab) {
        if (visibleTab == tab) return;
        cancelPending();
        visibleTab = tab;
        loadVisible();
    }

    /** Explicit device actions cancel expendable detail work before joining the shared worker. */
    public void setSuspended(boolean value) {
        suspended = value;
        if (value) cancelPending();
        else loadVisible();
    }

    private void loadVisible() {
        if (suspended || selected == null || visibleTab == DetailTab.GENERAL || pending != null) return;
        Device device = selected;
        DetailTab tab = visibleTab;
        String cached = cache.get(device).get(tab);
        if (cached != null) { update(tab, cached); return; }
        int request = generation;
        operation = status.begin(StatusController.Kind.DETAILS, "Loading details for " + device.displayName() + "...");
        update(tab, LOADING);
        pending = ui.runAsync(() -> manager.loadDetails(device, tab), text -> {
            if (request != generation) return;
            pending = null;
            if (text != null) cache.get(device).put(tab, text);
            update(tab, text == null ? FAILED : text);
            if (operation != null) { operation.close(); operation = null; }
        });
    }

    private void update(DetailTab tab, String value) {
        switch (tab) {
            case GENERAL -> generalLabel.setLabel(value);
            case ADVANCED -> advancedLabel.setLabel(value);
            case DRIVER -> driverLabel.setLabel(value);
            case LOGS -> logsView.getBuffer().setText(value, -1);
        }
    }

    private void cancelPending() {
        generation++;
        if (pending != null) { pending.cancel(true); pending = null; }
        if (operation != null) { operation.close(); operation = null; }
    }
}
