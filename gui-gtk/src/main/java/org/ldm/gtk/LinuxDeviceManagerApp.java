package org.ldm.gtk;

import org.ldm.core.DeviceManager;
import org.ldm.core.action.DeviceActionResult;
import org.ldm.core.action.DeviceActionService;
import org.ldm.core.categorize.Categorizer;
import org.ldm.core.detail.AdvancedDetailProvider;
import org.ldm.core.detail.DetailProvider;
import org.ldm.core.detail.DriverDetailProvider;
import org.ldm.core.detail.GeneralDetailProvider;
import org.ldm.core.detail.LogsDetailProvider;
import org.ldm.core.model.Device;
import org.ldm.core.model.DetailTab;
import org.ldm.core.process.ProcessCommandRunner;
import org.ldm.core.process.ToolLocator;
import org.ldm.core.scan.SysfsScanner;
import org.ldm.core.state.StateResolver;
import org.ldm.core.udev.UdevEnricher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.gnome.gdk.Rectangle;
import org.gnome.gio.ApplicationFlags;
import org.gnome.gio.Menu;
import org.gnome.gio.SimpleAction;
import org.gnome.gtk.AlertDialog;
import org.gnome.gtk.Application;
import org.gnome.gtk.ApplicationWindow;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.PopoverMenu;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.Spinner;
import org.gnome.gtk.TextView;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreeModel;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeSelection;
import org.gnome.gtk.TreeStore;
import org.gnome.gtk.TreeView;
import org.gnome.gtk.TreeViewColumn;
import org.javagi.base.GErrorException;
import org.javagi.base.Out;

/**
 * GTK application entry point. Loads the authored {@code main.ui} via {@link GtkBuilder} (never
 * building widgets programmatically) and wires the core library onto the resolved widgets.
 */
public final class LinuxDeviceManagerApp {

    private final Application app;
    private final DeviceManager manager;
    private final DeviceActionService actionService;
    private final UiExecutor ui = new UiExecutor();

    private ApplicationWindow window;
    private StatusController status;
    private DetailController detailController;
    private DeviceTreeController treeController;
    private TreeStore store;
    private TreeView treeView;
    private SimpleAction enableAction;
    private SimpleAction disableAction;
    private Device selectedDevice;
    private boolean actionInFlight;

    public LinuxDeviceManagerApp() {
        app = new Application("com.example.DeviceManager", ApplicationFlags.DEFAULT_FLAGS);
        app.onActivate(this::activate);
        manager = buildManager();
        actionService = new DeviceActionService(
                new ProcessCommandRunner(),
                new ToolLocator(null).locate("pkexec"),
                "/usr/libexec/ldm-helper");
    }

    private void activate() {
        GtkBuilder builder = GtkBuilder.fromString(loadUi(), -1);
        window = (ApplicationWindow) builder.getObject("main_window");
        window.setApplication(app);

        store = (TreeStore) builder.getObject("device_store");
        treeView = (TreeView) builder.getObject("device_tree_view");
        treeController = new DeviceTreeController(store);

        Spinner statusSpinner = (Spinner) builder.getObject("status_spinner");
        Label statusLabel = (Label) builder.getObject("status_label");
        Label generalLabel = (Label) builder.getObject("general_info_label");
        Label advancedLabel = (Label) builder.getObject("advanced_info_label");
        Label driverLabel = (Label) builder.getObject("driver_info_label");
        TextView logsView = (TextView) builder.getObject("logs_text_view");

        status = new StatusController(statusSpinner, statusLabel);
        detailController = new DetailController(
                manager, ui, status, generalLabel, advancedLabel, driverLabel, logsView);

        TreeSelection selection = treeView.getSelection();
        selection.onChanged(() -> onSelectionChanged(selection));

        setUpActions();
        setUpContextMenu(builder);

        window.present();
        refreshDevices();
    }

    private void onSelectionChanged(TreeSelection selection) {
        TreeIter iter = new TreeIter();
        Out<TreeModel> model = new Out<>();
        if (!selection.getSelected(model, iter)) {
            selectedDevice = null;
            updateActionState(null);
            detailController.clearSelection();
            return;
        }

        Device device = treeController.deviceAt(store.getPath(iter));
        if (device != null) {
            selectedDevice = device;
            updateActionState(device);
            detailController.showDevice(device);
        } else {
            selectedDevice = null;
            updateActionState(null);
            detailController.clearSelection();
        }
    }

    private void updateActionState(Device device) {
        if (enableAction != null) {
            enableAction.setEnabled(!actionInFlight && device != null && actionService.canEnable(device));
        }
        if (disableAction != null) {
            disableAction.setEnabled(!actionInFlight && device != null && actionService.canDisable(device));
        }
    }

    private void setUpActions() {
        enableAction = new SimpleAction("enable_device", null);
        enableAction.setEnabled(false);
        enableAction.onActivate(variant -> requestSetEnabled(true));
        app.addAction(enableAction);

        disableAction = new SimpleAction("disable_device", null);
        disableAction.setEnabled(false);
        disableAction.onActivate(variant -> requestSetEnabled(false));
        app.addAction(disableAction);

        SimpleAction refreshAction = new SimpleAction("refresh_devices", null);
        refreshAction.onActivate(variant -> refreshDevices());
        app.addAction(refreshAction);

        SimpleAction exitAction = new SimpleAction("exit", null);
        exitAction.onActivate(variant -> window.close());
        app.addAction(exitAction);

        SimpleAction aboutAction = new SimpleAction("about", null);
        aboutAction.onActivate(variant -> showAbout());
        app.addAction(aboutAction);
    }

    private void setUpContextMenu(GtkBuilder builder) {
        Menu menuModel = (Menu) builder.getObject("device_context_menu");
        PopoverMenu popover = PopoverMenu.fromModel(menuModel);
        // Parent to the ScrolledWindow ancestor: parenting a PopoverMenu to a TreeView trips
        // gtk_css_node_insert_after (Gtk-CRITICAL) in GTK 4.22.
        ScrolledWindow scroll = (ScrolledWindow) builder.getObject("tree_scroll");
        popover.setParent(scroll);
        popover.setHasArrow(false);

        GestureClick rightClick = new GestureClick();
        rightClick.setButton(3);
        rightClick.onPressed((pressCount, x, y) -> {
            Out<TreePath> path = new Out<>();
            Out<TreeViewColumn> column = new Out<>();
            Out<Integer> cellX = new Out<>();
            Out<Integer> cellY = new Out<>();
            if (treeView.getPathAtPos((int) x, (int) y, path, column, cellX, cellY)) {
                treeView.getSelection().selectPath(path.get());
            }
            popover.setPointingTo(new Rectangle((int) x, (int) y, 1, 1));
            popover.popup();
        });
        treeView.addController(rightClick);
    }

    private void requestSetEnabled(boolean enabled) {
        Device device = selectedDevice;
        if (device == null) {
            return;
        }
        if (enabled) {
            performAction(device, true);
            return;
        }

        AlertDialog dialog = new AlertDialog("Disable \"" + device.displayName() + "\"?");
        dialog.setDetail(SafetyPolicy.isCritical(device)
                ? "This is a storage device. Disabling it may destabilize the system or disconnect the root filesystem."
                : "Disabling this device will unbind its driver. You can enable it again from this menu.");
        dialog.setButtons(new String[] {"Cancel", "Disable"});
        dialog.setCancelButton(0);
        dialog.setDefaultButton(0);
        dialog.setModal(true);
        dialog.choose(window, null, (source, result, data) -> {
            try {
                if (dialog.chooseFinish(result) == 1) {
                    performAction(device, false);
                }
            } catch (GErrorException ignored) {
                // Dialog cancellation is not an action failure.
            }
        });
    }

    private void performAction(Device device, boolean enabled) {
        status.busy((enabled ? "Enabling " : "Disabling ") + device.displayName() + "...");
        actionInFlight = true;
        updateActionState(null);

        ui.runAsync(() -> {
            DeviceActionResult result = actionService.setDeviceEnabled(device, enabled);
            return result;
        }, result -> {
            actionInFlight = false;
            if (result == null) {
                status.message("Action failed");
                showError(null);
                updateActionState(selectedDevice);
                return;
            }

            switch (result.outcome()) {
                case SUCCESS -> refreshDevices();
                case AUTH_CANCELLED -> {
                    status.message("Authentication cancelled.");
                    updateActionState(selectedDevice);
                }
                case UNSUPPORTED, FAILED -> {
                    String message = result.message();
                    if (message == null || message.isBlank()) {
                        message = "Action failed.";
                    }
                    status.message(message);
                    showError(message);
                    updateActionState(selectedDevice);
                }
            }
        });
    }

    private void refreshDevices() {
        selectedDevice = null;
        updateActionState(null);
        detailController.clearSelection();
        status.busy("Scanning devices...");
        ui.runAsync(manager::refresh, groups -> {
            if (groups == null) {
                status.message("Failed to load devices");
                return;
            }
            treeController.populate(groups);
            treeView.expandAll();
            status.idle();
        });
    }

    private void showError(String message) {
        AlertDialog dialog = new AlertDialog("Action failed");
        dialog.setDetail(message == null || message.isBlank() ? "Unknown error." : message);
        dialog.setButtons(new String[] {"OK"});
        dialog.setCancelButton(0);
        dialog.setDefaultButton(0);
        dialog.setModal(true);
        dialog.choose(window, null, (source, result, data) -> {
            try {
                dialog.chooseFinish(result);
            } catch (GErrorException ignored) {
                // Dismissing the error dialog needs no follow-up.
            }
        });
    }

    private void showAbout() {
        AlertDialog dialog = new AlertDialog("Linux Device Manager");
        dialog.setDetail("View and manage hardware devices on Linux.");
        dialog.setButtons(new String[] {"OK"});
        dialog.setCancelButton(0);
        dialog.setDefaultButton(0);
        dialog.setModal(true);
        dialog.choose(window, null, (source, result, data) -> {
            try {
                dialog.chooseFinish(result);
            } catch (GErrorException ignored) {
                // Dismissing the about dialog needs no follow-up.
            }
        });
    }

    private static DeviceManager buildManager() {
        ToolLocator tools = new ToolLocator(null);
        var runner = new ProcessCommandRunner();
        Map<DetailTab, DetailProvider> providers = Map.of(
                DetailTab.GENERAL, new GeneralDetailProvider(),
                DetailTab.ADVANCED, new AdvancedDetailProvider(
                        runner, tools.locate("lspci"), tools.locate("lsusb")),
                DetailTab.DRIVER, new DriverDetailProvider(runner, tools.locate("modinfo")),
                DetailTab.LOGS, new LogsDetailProvider(
                        runner, tools.locate("journalctl"), tools.locate("dmesg")));
        return new DeviceManager(
                new SysfsScanner(Path.of("/sys")),
                new UdevEnricher(runner, tools.locate("udevadm")),
                new Categorizer(),
                new StateResolver(),
                providers);
    }

    /** Read the authored UI definition from the classpath. */
    static String loadUi() {
        try (var in = LinuxDeviceManagerApp.class.getResourceAsStream("/main.ui")) {
            if (in == null) {
                throw new IllegalStateException("main.ui not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int run(String[] args) {
        return app.run(args);
    }

    public static void main(String[] args) {
        System.exit(new LinuxDeviceManagerApp().run(args));
    }
}
