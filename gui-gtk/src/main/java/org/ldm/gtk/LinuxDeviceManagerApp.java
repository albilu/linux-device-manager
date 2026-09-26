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
import org.ldm.core.util.AppLog;
import org.ldm.core.util.LdmPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import org.gnome.gdk.Gdk;
import org.gnome.gdk.ModifierType;
import org.gnome.gdk.Rectangle;
import org.gnome.gdk.Texture;
import org.gnome.gio.ApplicationFlags;
import org.gnome.gio.Menu;
import org.gnome.gio.SimpleAction;
import org.gnome.gtk.AboutDialog;
import org.gnome.gtk.AlertDialog;
import org.gnome.gtk.Application;
import org.gnome.gtk.ApplicationWindow;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.EventControllerKey;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.Notebook;
import org.gnome.gtk.PopoverMenu;
import org.gnome.gtk.PropagationPhase;
import org.gnome.gtk.Widget;
import org.gnome.gtk.Spinner;
import org.gnome.gtk.SearchEntry;
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

    private static final String APP_VERSION = "0.2.1";

    private final Application app;
    private final DeviceManager manager;
    private final DeviceActionService actionService;
    private final UiExecutor ui;

    private ApplicationWindow window;
    private StatusController status;
    private DetailController detailController;
    private DeviceTreeController treeController;
    private TreeStore store;
    private TreeView treeView;
    private SimpleAction enableAction;
    private SimpleAction disableAction;
    private SimpleAction refreshAction;
    private Label treeStateLabel;
    private AboutDialog aboutDialog;
    private Device selectedDevice;
    private boolean actionInFlight;
    private boolean refreshInFlight;
    private boolean closed;

    public LinuxDeviceManagerApp() {
        this(buildManager(), new DeviceActionService(new ProcessCommandRunner(),
                new ToolLocator(null).locate("pkexec"), "/usr/libexec/ldm-helper"), new UiExecutor());
        AppLog.init(LdmPaths.stateDirectory().resolve("linux-device-manager.log"));
        AppLog.info("Linux Device Manager " + APP_VERSION + " starting");
    }

    LinuxDeviceManagerApp(DeviceManager manager, DeviceActionService actionService, UiExecutor ui) {
        app = new Application("com.example.DeviceManager", ApplicationFlags.DEFAULT_FLAGS);
        app.onActivate(this::activate);
        this.manager = manager;
        this.actionService = actionService;
        this.ui = ui;
        app.onShutdown(() -> {
            AppLog.info("Shutting down");
            closed = true;
            if (detailController != null) detailController.clearSelection();
            ui.close();
        });
    }

    private void activate() {
        if (window != null) { window.present(); return; }
        GtkBuilder builder = GtkBuilder.fromString(loadUi(), -1);
        window = (ApplicationWindow) builder.getObject("main_window");
        window.setApplication(app);

        store = (TreeStore) builder.getObject("device_store");
        treeView = (TreeView) builder.getObject("device_tree_view");
        treeController = new DeviceTreeController(store);
        treeStateLabel = (Label) builder.getObject("tree_state_label");

        Spinner statusSpinner = (Spinner) builder.getObject("status_spinner");
        Label statusLabel = (Label) builder.getObject("status_label");
        Label generalLabel = (Label) builder.getObject("general_info_label");
        Label advancedLabel = (Label) builder.getObject("advanced_info_label");
        Label driverLabel = (Label) builder.getObject("driver_info_label");
        TextView logsView = (TextView) builder.getObject("logs_text_view");

        status = new StatusController(statusSpinner, statusLabel);
        detailController = new DetailController(
                manager, ui, status, generalLabel, advancedLabel, driverLabel, logsView);
        Notebook notebook = (Notebook) builder.getObject("details_notebook");
        detailController.showTab(DetailTab.values()[Math.max(0, notebook.getCurrentPage())]);
        notebook.onSwitchPage((page, index) -> detailController.showTab(DetailTab.values()[index]));

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
            enableAction.setEnabled(!actionInFlight && !refreshInFlight && device != null && actionService.canEnable(device));
        }
        if (disableAction != null) {
            disableAction.setEnabled(!actionInFlight && !refreshInFlight && device != null && actionService.canDisable(device));
        }
        if (refreshAction != null) refreshAction.setEnabled(!actionInFlight && !refreshInFlight);
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

        refreshAction = new SimpleAction("refresh_devices", null);
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
        SearchEntry search = (SearchEntry) builder.getObject("device_search_entry");
        // Supplying an authored entry bypasses TreeView's broken internal search popup.
        treeView.setSearchEntry(search);
        treeView.setSearchColumn(1);
        search.setSearchDelay(0);
        search.onStopSearch(() -> treeView.grabFocus());
        search.onActivate(() -> treeView.grabFocus());
        treeView.onStartInteractiveSearch(() -> {
            search.grabFocus();
            return true;
        });
        Menu menuModel = (Menu) builder.getObject("device_context_menu");
        PopoverMenu popover = PopoverMenu.fromModel(menuModel);
        // Keep the popup outside the TreeView/ScrolledWindow internal child hierarchies.
        // Their row/scrollbar CSS reordering can otherwise encounter the popup's CSS node.
        Widget parent = (Widget) builder.getObject("tree_overlay");
        popover.setParent(parent);
        popover.setHasArrow(false);

        GestureClick rightClick = new GestureClick();
        rightClick.setButton(3);
        rightClick.onPressed((pressCount, x, y) -> {
            Out<Integer> binX = new Out<>();
            Out<Integer> binY = new Out<>();
            treeView.convertWidgetToBinWindowCoords((int) x, (int) y, binX, binY);
            Out<TreePath> path = new Out<>();
            Out<TreeViewColumn> column = new Out<>();
            Out<Integer> cellX = new Out<>();
            Out<Integer> cellY = new Out<>();
            if (treeView.getPathAtPos(binX.get(), binY.get(), path, column, cellX, cellY)) {
                selectPathIfNeeded(treeView.getSelection(), path.get());
            } else {
                treeView.getSelection().unselectAll();
            }
            showContextMenu(popover, parent, x, y);
        });
        treeView.addController(rightClick);

        EventControllerKey keys = new EventControllerKey();
        // Capture on the ancestor before TreeView forwards keys to its interactive search popup.
        keys.setPropagationPhase(PropagationPhase.CAPTURE);
        keys.onKeyPressed((key, code, modifiers) -> {
            if (!treeView.hasFocus()) return false;
            boolean control = modifiers.contains(ModifierType.CONTROL_MASK);
            boolean shortcut = control || modifiers.contains(ModifierType.ALT_MASK)
                    || modifiers.contains(ModifierType.SUPER_MASK) || modifiers.contains(ModifierType.META_MASK);
            int character = Gdk.keyvalToUnicode(key);
            if ((control && (key == Gdk.KEY_f || key == Gdk.KEY_F))
                    || (!shortcut && character > 32 && !Character.isISOControl(character))) {
                search.setText("");
                search.grabFocus();
                if (!control) keys.forward((Widget) search.getDelegate());
                return true;
            }
            if (key != Gdk.KEY_Menu && !(key == Gdk.KEY_F10 && modifiers.contains(ModifierType.SHIFT_MASK)))
                return false;
            TreeIter iter = new TreeIter();
            Out<TreeModel> model = new Out<>();
            if (!treeView.getSelection().getSelected(model, iter)) return true;
            Rectangle cell = new Rectangle();
            treeView.getCellArea(store.getPath(iter), treeView.getColumn(0), cell);
            Out<Integer> x = new Out<>();
            Out<Integer> y = new Out<>();
            treeView.convertBinWindowToWidgetCoords(cell.readX(), cell.readY() + cell.readHeight(), x, y);
            showContextMenu(popover, parent, x.get(), y.get());
            return true;
        });
        parent.addController(keys);
    }

    private void showContextMenu(PopoverMenu popover, Widget parent, double x, double y) {
        Out<Double> parentX = new Out<>();
        Out<Double> parentY = new Out<>();
        if (treeView.translateCoordinates(parent, x, y, parentX, parentY)) {
            popover.setPointingTo(new Rectangle(parentX.get().intValue(), parentY.get().intValue(), 1, 1));
            popover.popup();
        }
    }

    static boolean selectPathIfNeeded(TreeSelection selection, TreePath path) {
        if (selection.pathIsSelected(path)) {
            return false;
        }
        selection.selectPath(path);
        return true;
    }

    private void requestSetEnabled(boolean enabled) {
        Device device = selectedDevice;
        if (device == null || actionInFlight || refreshInFlight
                || (enabled ? !actionService.canEnable(device) : !actionService.canDisable(device))) {
            return;
        }
        if (enabled) {
            performAction(device, true);
            return;
        }

        AlertDialog dialog = new AlertDialog("Disable \"" + device.displayName() + "\"?");
        dialog.setDetail(SafetyPolicy.isCritical(device)
                ? "This is a storage device. Disabling it may destabilize the system or disconnect the root filesystem."
                : device.actionKind() == org.ldm.core.model.DeviceActionKind.USB_AUTHORIZATION
                    ? "This will block the USB device and disconnect all of its functions."
                    : "This will disconnect the device from its driver until you enable it again.");
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
        if (actionInFlight || refreshInFlight || closed) return;
        detailController.setSuspended(true);
        StatusController.Operation operation = status.begin(StatusController.Kind.ACTION,
                (enabled ? "Enabling " : "Disabling ") + device.displayName() + "...");
        actionInFlight = true;
        updateActionState(null);

        ui.runAsync(() -> {
            DeviceActionResult result = actionService.setDeviceEnabled(device, enabled);
            return result;
        }, result -> {
            actionInFlight = false;
            if (result == null) {
                refreshDevices();
                status.message("Action failed");
                showError(null);
                updateActionState(selectedDevice);
                detailController.setSuspended(false);
                operation.close();
                return;
            }

            switch (result.outcome()) {
                case SUCCESS -> {
                    manager.recordAction(device, enabled);
                    refreshDevices();
                    status.message("Device " + (enabled ? "enabled." : "disabled."));
                }
                case AUTH_CANCELLED -> {
                    status.message("Authentication cancelled.");
                    updateActionState(selectedDevice);
                }
                case UNSUPPORTED, FAILED -> {
                    // A failed postcondition can follow a partial state change. Reload it.
                    refreshDevices();
                    String message = result.message();
                    if (message == null || message.isBlank()) {
                        message = "Action failed.";
                    }
                    status.message(message);
                    showError(message);
                    updateActionState(selectedDevice);
                }
            }
            detailController.setSuspended(false);
            operation.close();
        });
    }

    private void refreshDevices() {
        if (refreshInFlight || actionInFlight || closed) return;
        refreshInFlight = true;
        StatusController.Operation operation = status.begin(StatusController.Kind.SCAN, "Scanning devices...");
        selectedDevice = null;
        updateActionState(null);
        detailController.invalidate();
        treeController.populate(List.of());
        treeStateLabel.setLabel("Scanning devices...");
        treeStateLabel.setVisible(true);
        ui.runAsync(manager::refresh, groups -> {
            refreshInFlight = false;
            if (groups == null) {
                treeStateLabel.setLabel("Could not load devices. Use File → Refresh Devices to retry.");
                status.message("Failed to load devices");
                updateActionState(null);
                operation.close();
                return;
            }
            treeController.populate(groups);
            treeView.expandAll();
            treeStateLabel.setLabel("No devices found.");
            treeStateLabel.setVisible(groups.isEmpty());
            updateActionState(selectedDevice);
            operation.close();
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
        if (aboutDialog != null) {
            aboutDialog.present();
            return;
        }

        AboutDialog dialog = new AboutDialog();
        aboutDialog = dialog;
        dialog.setTransientFor(window);
        dialog.setModal(true);
        dialog.setProgramName("Linux Device Manager");
        dialog.setVersion(APP_VERSION);
        dialog.setComments("View and manage hardware devices on Linux.");
        dialog.setAuthors(new String[] {"Linux Device Manager contributors"});
        Texture logo = loadAboutLogo();
        if (logo != null) {
            dialog.setLogo(logo);
        } else {
            dialog.setLogoIconName("linux-device-manager");
        }
        dialog.onCloseRequest(() -> {
            aboutDialog = null;
            return false;
        });
        dialog.present();
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

    static Texture loadAboutLogo() {
        try (var in = LinuxDeviceManagerApp.class.getResourceAsStream("/linux-device-manager.svg")) {
            if (in == null) {
                return null;
            }
            return Texture.fromBytes(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GErrorException e) {
            return null;
        }
    }

    public int run(String[] args) {
        return app.run(args);
    }

    public static void main(String[] args) {
        System.exit(new LinuxDeviceManagerApp().run(args));
    }
}
