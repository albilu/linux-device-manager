package org.ldm.gtk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.ldm.core.DeviceManager;
import org.ldm.core.model.Bus;
import org.ldm.core.model.Device;
import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.DeviceState;
import org.ldm.core.model.DetailTab;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.Label;
import org.gnome.gtk.Spinner;
import org.gnome.gtk.TextIter;
import org.gnome.gtk.TextView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class DetailControllerTest {

    @Test
    void clearSelectionDoesNotFinishAnUnrelatedOperation() {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");

        Spinner spinner = new Spinner();
        Label statusLabel = new Label("Working...");
        StatusController status = new StatusController(spinner, statusLabel);
        var operation = status.begin(StatusController.Kind.SCAN, "Working...");

        Label generalLabel = new Label("old general");
        Label advancedLabel = new Label("old advanced");
        Label driverLabel = new Label("old driver");
        TextView logsView = new TextView();
        logsView.getBuffer().setText("old logs", -1);
        DetailController controller = new DetailController(
                null, new UiExecutor(), status, generalLabel, advancedLabel, driverLabel, logsView);

        controller.clearSelection();

        assertTrue(spinner.getSpinning());
        assertEquals("Working...", statusLabel.getLabel());
        operation.close();
        assertFalse(spinner.getSpinning());
        assertEquals("Select a device to view details.", generalLabel.getLabel());
        assertEquals("", advancedLabel.getLabel());
        assertEquals("", driverLabel.getLabel());
        assertEquals("", logsText(logsView));
    }

    @Test
    void generalDoesNotLoadHiddenTabs() {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");

        Fixture f = new Fixture();
        f.controller.showDevice(device("GPU"));
        f.executor.flushCallbacks();

        assertEquals("general-GPU", f.generalLabel.getLabel());
        assertEquals("", f.advancedLabel.getLabel());
        assertEquals("", f.driverLabel.getLabel());
        assertEquals("", f.logsText());
        assertEquals(0, f.executor.submissions);
        assertFalse(f.spinner.getSpinning());
        assertEquals("Ready", f.statusLabel.getLabel());
    }

    @Test
    void showsLoadingPlaceholdersBeforeResultsArrive() {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");

        Fixture f = new Fixture();
        f.controller.showDevice(device("GPU"));
        f.controller.showTab(DetailTab.DRIVER);

        assertEquals("general-GPU", f.generalLabel.getLabel());
        assertEquals("", f.advancedLabel.getLabel());
        assertEquals("Loading...", f.driverLabel.getLabel());
        assertEquals("", f.logsText());
        assertEquals(1, f.executor.submissions);
        assertTrue(f.spinner.getSpinning());
    }

    @Test
    void staleResultsFromPreviousSelectionAreIgnored() {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");

        Fixture f = new Fixture();
        f.controller.showDevice(device("GPU"));
        f.controller.showTab(DetailTab.ADVANCED);
        f.controller.showDevice(device("NIC"));
        f.executor.flushCallbacks();

        assertEquals("general-NIC", f.generalLabel.getLabel());
        assertEquals("advanced-NIC", f.advancedLabel.getLabel());
        assertEquals("", f.driverLabel.getLabel());
        assertEquals("", f.logsText());
    }

    @Test
    void cachedTabsAreReusedUntilInventoryRefresh() {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");
        Fixture f = new Fixture();
        Device device = device("GPU");
        f.controller.showDevice(device);
        f.controller.showTab(DetailTab.DRIVER);
        f.executor.flushCallbacks();
        assertEquals("driver-GPU", f.driverLabel.getLabel());
        f.controller.showTab(DetailTab.GENERAL);
        f.controller.showTab(DetailTab.DRIVER);
        f.controller.clearSelection();
        f.controller.showDevice(device);
        assertEquals(1, f.executor.submissions);
        assertEquals("driver-GPU", f.driverLabel.getLabel());
        f.controller.invalidate();
        f.controller.showDevice(device);
        assertEquals(2, f.executor.submissions);
        assertEquals("Loading...", f.driverLabel.getLabel());
        f.executor.flushCallbacks();
    }

    @Test
    void suspendedDetailsCannotDelayAnExplicitAction() throws Exception {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");
        var started = new java.util.concurrent.CountDownLatch(1);
        var interrupted = new java.util.concurrent.CountDownLatch(1);
        try (UiExecutor executor = new UiExecutor()) {
            var manager = new DeviceManager(null, null, null, null, Map.of(
                    DetailTab.GENERAL, d -> "general",
                    DetailTab.LOGS, d -> {
                        started.countDown();
                        try { Thread.sleep(30000); }
                        catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                        return "stale logs";
                    }));
            var spinner = new Spinner();
            var controller = new DetailController(manager, executor, new StatusController(spinner, new Label("")),
                    new Label(""), new Label(""), new Label(""), new TextView());
            controller.showDevice(device("GPU"));
            controller.showTab(DetailTab.LOGS);
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            controller.setSuspended(true);
            var action = new java.util.concurrent.CountDownLatch(1);
            executor.runAsync(() -> { action.countDown(); return true; }, ignored -> { });
            assertTrue(action.await(1, java.util.concurrent.TimeUnit.SECONDS), "action was stuck behind details");
            assertTrue(interrupted.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(spinner.getSpinning());
            controller.clearSelection();
        }
    }

    private Device device(String name) {
        return new Device("/sys/" + name, "/sys/" + name, name, Bus.PCI, name, "10de", "2503",
                DeviceCategory.DISPLAY, DeviceState.ACTIVE, Optional.of("drv"), Map.of());
    }

    private static String logsText(TextView logsView) {
        TextIter start = new TextIter();
        TextIter end = new TextIter();
        logsView.getBuffer().getBounds(start, end);
        return logsView.getBuffer().getText(start, end, false);
    }

    private static final class Fixture {
        final ControllableExecutor executor = new ControllableExecutor();
        final Spinner spinner = new Spinner();
        final Label statusLabel = new Label("Ready");
        final StatusController status = new StatusController(spinner, statusLabel);
        final Label generalLabel = new Label("");
        final Label advancedLabel = new Label("");
        final Label driverLabel = new Label("");
        final TextView logsView = new TextView();
        final DetailController controller;

        Fixture() {
            DeviceManager manager = new DeviceManager(null, null, null, null, Map.of(
                    DetailTab.GENERAL, d -> "general-" + d.displayName(),
                    DetailTab.ADVANCED, d -> "advanced-" + d.displayName(),
                    DetailTab.DRIVER, d -> "driver-" + d.displayName(),
                    DetailTab.LOGS, d -> "logs-" + d.displayName()));
            controller = new DetailController(manager, executor, status,
                    generalLabel, advancedLabel, driverLabel, logsView);
        }

        String logsText() {
            return DetailControllerTest.logsText(logsView);
        }
    }

    private static class ControllableExecutor extends UiExecutor {
        int submissions;
        private final List<Runnable> pendingCallbacks = new ArrayList<>();

        @Override
        public <T> Future<?> runAsync(Supplier<T> work, Consumer<T> onResult) {
            submissions++;
            T delivered = null;
            try {
                delivered = work.get();
            } catch (Throwable t) {
                t.printStackTrace();
            }
            T result = delivered;
            pendingCallbacks.add(() -> onResult.accept(result));
            return CompletableFuture.completedFuture(null);
        }

        void flushCallbacks() {
            for (Runnable callback : pendingCallbacks) {
                callback.run();
            }
            pendingCallbacks.clear();
        }
    }
}
