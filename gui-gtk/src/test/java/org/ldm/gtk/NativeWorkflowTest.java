package org.ldm.gtk;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.gnome.gdk.Rectangle;
import org.gnome.gio.ApplicationFlags;
import org.gnome.gio.SimpleAction;
import org.gnome.glib.MainContext;
import org.gnome.gtk.*;
import org.javagi.base.Out;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ldm.core.DeviceManager;
import org.ldm.core.action.DeviceActionService;
import org.ldm.core.categorize.Categorizer;
import org.ldm.core.model.*;
import org.ldm.core.process.CommandResult;
import org.ldm.core.scan.SysfsScanner;
import org.ldm.core.state.StateResolver;
import org.ldm.core.udev.UdevEnricher;

/** Real GTK widgets, X11 pointer/key events and dialogs; never invokes a privileged helper. */
class NativeWorkflowTest {
    @TempDir Path root;
    private LinuxDeviceManagerApp owner;
    private ApplicationWindow window;
    private TreeView tree;
    private TreeStore store;
    private final QueuedExecutor executor = new QueuedExecutor();
    private int actionCalls;
    private int actionExit;

    @Test
    void nativeMenusLoadingFailuresAndActionLifecycle() throws Exception {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");
        assumeTrue(System.getenv("DISPLAY") != null, "native pointer checks require X11");
        assumeTrue(new ProcessBuilder("sh", "-c", "command -v xdotool").start().waitFor() == 0,
                "install xdotool for native pointer/key regression checks");
        DeviceManager manager = new DeviceManager(new SysfsScanner(root),
                new UdevEnricher((timeout, command) -> new CommandResult(0, "", "", false), "fixture"),
                new Categorizer(), new StateResolver(), Map.of(
                        DetailTab.GENERAL, d -> "General " + d.displayName(),
                        DetailTab.ADVANCED, d -> "Advanced " + d.displayName(),
                        DetailTab.DRIVER, d -> "Driver " + d.displayName(),
                        DetailTab.LOGS, d -> "Logs " + d.displayName()));
        owner = new LinuxDeviceManagerApp(manager, new DeviceActionService((timeout, command) -> {
            actionCalls++;
            return new CommandResult(actionExit, "", "Fixture action failure", false);
        }, "never-pkexec", "never-helper"), executor);
        Application app = field(owner, "app", Application.class);
        app.setFlags(ApplicationFlags.NON_UNIQUE);
        assertTrue(app.register(null));
        try {
            app.activate();
            window = field(owner, "window", ApplicationWindow.class);
            window.setTitle("LDM native regression");
            tree = field(owner, "treeView", TreeView.class);
            store = field(owner, "store", TreeStore.class);
            pump();

            // First scan, first failure, retry and genuinely empty discovery contain no sample rows.
            assertEquals(0, store.iterNChildren(null));
            assertStatus("Scanning devices...", true);
            assertFalse(action("refreshAction").getEnabled());
            invoke("refreshDevices");
            invoke("refreshDevices");
            assertEquals(1, executor.queue.size());
            executor.failNext();
            assertStatus("Failed to load devices", false);
            assertEquals(0, store.iterNChildren(null));
            Label treeState = field(owner, "treeStateLabel", Label.class);
            assertTrue(treeState.getVisible());
            assertTrue(treeState.getLabel().contains("retry"));
            action("refreshAction").activate(null);
            executor.flush();
            assertEquals("No devices found.", treeState.getLabel());
            assertTrue(treeState.getVisible());

            populate();
            clickRow("0:0", "1");
            pump();
            executor.flush();
            assertEquals("Device 0", selected().displayName());
            Notebook notebook = notebookIn(window);
            assertNotNull(notebook);
            notebook.setCurrentPage(DetailTab.DRIVER.ordinal());
            assertEquals(1, executor.queue.size(), "opening Driver must schedule only Driver");
            executor.flush();
            assertEquals("Driver Device 0", field(field(owner, "detailController", DetailController.class),
                    "driverLabel", Label.class).getLabel());
            notebook.setCurrentPage(DetailTab.GENERAL.ordinal());
            notebook.setCurrentPage(DetailTab.DRIVER.ordinal());
            assertEquals(0, executor.queue.size(), "reopening a tab must use its cache");
            notebook.setCurrentPage(DetailTab.GENERAL.ordinal());
            int[] changes = {0};
            tree.getSelection().onChanged(() -> changes[0]++);
            clickRow("0:0", "3");
            pump();
            assertEquals(0, changes[0], "right-clicking the selected row must not select its neighbor");
            assertEquals(0, executor.queue.size(), "selected-row context menu must not reload details");
            assertNotNull(visiblePopover(window));
            hideMenu();
            clickRow("0:1", "3");
            pump();
            assertEquals("Device 1", selected().displayName());
            assertEquals(1, changes[0]);
            assertEquals(0, executor.queue.size(), "General must not load hidden tabs");
            hideMenu();
            executor.flush();

            // Coordinates must still identify the clicked row after scrolling with visible headers.
            tree.scrollToCell(TreePath.fromString("0:30"), tree.getColumn(0), true, 0.5f, 0);
            pump();
            clickRow("0:30", "3");
            pump();
            assertEquals("Device 30", selected().displayName());
            assertEquals(2, changes[0]);
            assertEquals(0, executor.queue.size());
            hideMenu();
            executor.flush();

            for (String key : List.of("shift+F10", "Menu")) {
                tree.grabFocus();
                external("xdotool", "windowfocus", windowId(), "key", key);
                pump();
                assertNotNull(visiblePopover(window), key + " must open the selected device menu");
                assertTrue(action("disableAction").getEnabled());
                assertFalse(action("enableAction").getEnabled());
                hideMenu();
            }
            tree.getSelection().selectPath(TreePath.fromString("0"));
            tree.scrollToCell(TreePath.fromString("0"), tree.getColumn(0), true, 0, 0);
            tree.grabFocus();
            pump();
            external("xdotool", "key", "Menu");
            pump();
            assertNotNull(visiblePopover(window));
            assertFalse(action("disableAction").getEnabled());
            assertFalse(action("enableAction").getEnabled());
            hideMenu();
            tree.getSelection().unselectAll();
            tree.grabFocus();
            external("xdotool", "key", "shift+F10");
            pump();
            assertNull(visiblePopover(window));

            // Real type-to-search must target names and avoid the internal GTK popup critical.
            tree.setCursor(TreePath.fromString("0:0"), tree.getColumn(0), false);
            tree.grabFocus();
            assertEquals(1, tree.getSearchColumn());
            assertNotNull(tree.getSearchEntry());
            external("xdotool", "windowfocus", windowId(), "type", "--clearmodifiers", "--delay", "10", "Device 31");
            pump();
            assertEquals("Device 31", tree.getSearchEntry().getText(), "all typed text must reach the search entry");
            assertEquals("Device 31", selected().displayName());
            external("xdotool", "key", "Escape");
            pump();
            assertTrue(tree.hasFocus());
            external("xdotool", "key", "shift+F10");
            pump();
            assertNotNull(visiblePopover(window));
            hideMenu();

            tree.grabFocus();
            external("xdotool", "key", "ctrl+f");
            external("xdotool", "type", "--clearmodifiers", "--delay", "10", "Device 12");
            pump();
            assertEquals("Device 12", selected().displayName(), "Ctrl+F must search visible device names");
            external("xdotool", "key", "Escape");
            pump();
            assertTrue(tree.hasFocus());

            // The real confirmation dialog gates dispatch; category selection cannot end an action.
            tree.getSelection().selectPath(TreePath.fromString("0:0"));
            executor.flush();
            action("disableAction").activate(null);
            pump();
            assertEquals(0, actionCalls);
            dialogButton("Cancel").emitClicked();
            pump();
            assertEquals(0, actionCalls);
            action("disableAction").activate(null);
            pump();
            dialogButton("Disable").emitClicked();
            pump();
            tree.getSelection().selectPath(TreePath.fromString("0"));
            assertStatus("Disabling Device 0...", true);
            assertFalse(action("refreshAction").getEnabled());
            invoke("refreshDevices");
            assertEquals(1, executor.queue.size());
            actionExit = 126;
            executor.flush();
            assertEquals(1, actionCalls);
            assertStatus("Authentication cancelled.", false);
            assertTrue(action("refreshAction").getEnabled());

            // A verified action remains busy through its follow-up refresh.
            tree.getSelection().selectPath(TreePath.fromString("0:0"));
            executor.flush();
            actionExit = 0;
            invokeAction(selected(), false);
            executor.finishNext();
            assertStatus("Scanning devices...", true);
            assertEquals(1, executor.queue.size());
            executor.flush();
            assertStatus("Device disabled.", false);
            assertEquals(0, store.iterNChildren(null));

            // Later refresh failure clears the old inventory and offers retry.
            populate();
            tree.getSelection().selectPath(TreePath.fromString("0:0"));
            executor.flush();
            invoke("refreshDevices");
            assertEquals(0, store.iterNChildren(null));
            executor.failNext();
            assertStatus("Failed to load devices", false);
            assertTrue(treeState.getVisible());
            assertTrue(action("refreshAction").getEnabled());

            // A helper failure shows the actual error and refreshes any partially changed state.
            populate();
            tree.getSelection().selectPath(TreePath.fromString("0:0"));
            executor.flush();
            actionExit = 6;
            invokeAction(selected(), false);
            executor.finishNext();
            pump();
            assertStatus("Scanning devices...", true);
            dialogButton("OK").emitClicked();
            pump();
            executor.flush();
            assertStatus("Fixture action failure", false);
            assertEquals(3, actionCalls);
        } finally {
            if (window != null) window.destroy();
            app.quit();
            executor.close();
            pump();
        }
    }

    private void populate() throws Exception {
        var devices = IntStream.range(0, 40).mapToObj(i -> new Device("/sys/fixture/" + i,
                "/sys/fixture/" + i, "0000:01:00.0", Bus.PCI, "Device " + i, "1234", "5678",
                DeviceCategory.NETWORK, DeviceState.ACTIVE, Optional.of("fixture"), Map.<String, String>of(),
                List.of(), Optional.empty(), DeviceActionKind.DRIVER_BINDING, "a".repeat(64), true, true)).toList();
        field(owner, "treeController", DeviceTreeController.class)
                .populate(List.of(new CategoryGroup(DeviceCategory.NETWORK, devices)));
        field(owner, "treeStateLabel", Label.class).setVisible(false);
        tree.expandAll();
        pump();
    }

    private SimpleAction action(String name) throws Exception { return field(owner, name, SimpleAction.class); }
    private Device selected() throws Exception { return field(owner, "selectedDevice", Device.class); }
    private void assertStatus(String text, boolean busy) throws Exception {
        StatusController status = field(owner, "status", StatusController.class);
        assertEquals(text, field(status, "label", Label.class).getLabel());
        assertEquals(busy, field(status, "spinner", Spinner.class).getSpinning());
    }

    private void invoke(String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(owner);
    }

    private void invokeAction(Device device, boolean enabled) throws Exception {
        Method method = owner.getClass().getDeclaredMethod("performAction", Device.class, boolean.class);
        method.setAccessible(true);
        method.invoke(owner, device, enabled);
    }

    private static <T> T field(Object object, String name, Class<T> type) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(object));
    }

    private void clickRow(String path, String button) throws Exception {
        Rectangle area = new Rectangle();
        tree.getCellArea(TreePath.fromString(path), tree.getColumn(0), area);
        Out<Integer> wx = new Out<>(), wy = new Out<>();
        tree.convertBinWindowToWidgetCoords(Math.min(150, area.readX() + 70),
                area.readY() + area.readHeight() / 2, wx, wy);
        Out<Double> x = new Out<>(), y = new Out<>();
        assertTrue(tree.translateCoordinates(window, wx.get(), wy.get(), x, y));
        external("xdotool", "mousemove", "--window", windowId(), x.get().intValue() + "",
                y.get().intValue() + "", "click", button);
    }

    private static String windowId() throws Exception {
        Process p = new ProcessBuilder("xdotool", "search", "--name", "^LDM native regression$").start();
        String id = new String(p.getInputStream().readAllBytes()).strip().lines().findFirst().orElseThrow();
        assertEquals(0, p.waitFor());
        return id;
    }

    private static void external(String... args) throws Exception {
        Process process = new ProcessBuilder(args).inheritIO().start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (process.isAlive() && System.nanoTime() < deadline) {
            while (MainContext.default_().pending()) MainContext.default_().iteration(false);
            Thread.sleep(2);
        }
        assertFalse(process.isAlive(), "input command timed out");
        assertEquals(0, process.exitValue());
    }

    private static void pump() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(180);
        do {
            while (MainContext.default_().pending()) MainContext.default_().iteration(false);
            Thread.sleep(5);
        } while (System.nanoTime() < until);
    }

    private void hideMenu() throws InterruptedException {
        Popover popover = visiblePopover(window);
        if (popover != null) popover.popdown();
        pump();
    }

    private static Popover visiblePopover(Widget widget) {
        if (widget instanceof Popover p && p.getVisible()) return p;
        for (Widget child = widget.getFirstChild(); child != null; child = child.getNextSibling()) {
            Popover result = visiblePopover(child);
            if (result != null) return result;
        }
        return null;
    }

    private static Notebook notebookIn(Widget widget) {
        if (widget instanceof Notebook notebook) return notebook;
        for (Widget child = widget.getFirstChild(); child != null; child = child.getNextSibling()) {
            Notebook result = notebookIn(child);
            if (result != null) return result;
        }
        return null;
    }

    private Button dialogButton(String label) {
        var windows = Window.getToplevels();
        for (int i = 0; i < windows.getNItems(); i++) {
            Window w = (Window) windows.getItem(i);
            if (!w.equals(window) && w.getVisible()) {
                Button button = buttonIn(w, label);
                if (button != null) return button;
            }
        }
        throw new AssertionError("Dialog button missing: " + label);
    }

    private static Button buttonIn(Widget widget, String label) {
        if (widget instanceof Button button && label.equals(button.getLabel())) return button;
        for (Widget child = widget.getFirstChild(); child != null; child = child.getNextSibling()) {
            Button button = buttonIn(child, label);
            if (button != null) return button;
        }
        return null;
    }

    private static final class QueuedExecutor extends UiExecutor {
        private record Task(Runnable finish, Runnable fail) { }
        final ArrayDeque<Task> queue = new ArrayDeque<>();
        @Override public <T> Future<?> runAsync(Supplier<T> work, Consumer<T> callback) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            queue.add(new Task(() -> {
                if (!future.isCancelled()) callback.accept(work.get());
                future.complete(null);
            }, () -> { if (!future.isCancelled()) callback.accept(null); future.complete(null); }));
            return future;
        }
        void finishNext() { queue.remove().finish().run(); }
        void failNext() { queue.remove().fail().run(); }
        void flush() { while (!queue.isEmpty()) finishNext(); }
    }
}
