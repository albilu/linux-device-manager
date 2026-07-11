package org.ldm.gtk;

import org.ldm.core.DeviceManager;
import org.ldm.core.model.DetailTab;
import org.ldm.core.model.Device;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.gnome.gtk.Label;
import org.gnome.gtk.TextView;

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
    private final List<Future<?>> pendingFutures = new ArrayList<>();
    private volatile int generation;

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
        generation++;
        cancelPending();
        generalLabel.setLabel(NO_DEVICE);
        advancedLabel.setLabel("");
        driverLabel.setLabel("");
        logsView.getBuffer().setText("", -1);
        status.idle();
    }

    public void showDevice(Device device) {
        int callbackGeneration = ++generation;
        cancelPending();
        AtomicInteger pending = new AtomicInteger(3);

        status.busy("Loading details for " + device.displayName() + "...");
        generalLabel.setLabel(manager.loadDetails(device, DetailTab.GENERAL));
        advancedLabel.setLabel(LOADING);
        driverLabel.setLabel(LOADING);
        logsView.getBuffer().setText(LOADING, -1);

        load(callbackGeneration, pending, device, DetailTab.ADVANCED,
                value -> advancedLabel.setLabel(value));
        load(callbackGeneration, pending, device, DetailTab.DRIVER,
                value -> driverLabel.setLabel(value));
        load(callbackGeneration, pending, device, DetailTab.LOGS,
                value -> logsView.getBuffer().setText(value, -1));
    }

    private void load(int callbackGeneration, AtomicInteger pending, Device device,
                      DetailTab tab, Consumer<String> update) {
        pendingFutures.add(ui.runAsync(() -> {
            if (callbackGeneration != generation) {
                return null;
            }
            return manager.loadDetails(device, tab);
        }, text -> complete(callbackGeneration, pending, update, text)));
    }

    private void complete(int callbackGeneration, AtomicInteger pending,
                          Consumer<String> update, String text) {
        int remaining = pending.decrementAndGet();
        if (callbackGeneration != generation) {
            return;
        }
        update.accept(text == null ? FAILED : text);
        if (remaining == 0) {
            status.idle();
        }
    }

    private void cancelPending() {
        for (Future<?> future : pendingFutures) {
            future.cancel(true);
        }
        pendingFutures.clear();
    }
}
