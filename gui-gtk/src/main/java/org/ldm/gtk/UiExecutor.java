package org.ldm.gtk;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.gnome.glib.GLib;

public class UiExecutor {

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ldm-worker");
        thread.setDaemon(true);
        return thread;
    });

    public <T> Future<?> runAsync(Supplier<T> work, Consumer<T> onResult) {
        return worker.submit(() -> {
            T delivered;
            try {
                delivered = work.get();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                delivered = null;
            }
            T result = delivered;
            GLib.idleAddOnce(() -> onResult.accept(result));
        });
    }
}
