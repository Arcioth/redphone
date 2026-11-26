package io.github.arcioth.redphone.ui;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

/**
 * One background thread for the deck's controller calls (keys, refreshes), so the main thread never
 * waits on the controller lock. Single-threaded on purpose: key down/up reach the controller in order.
 */
public final class Background {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "redphone-deck");
            t.setDaemon(true);
            return t;
        }
    });

    private Background() {
    }

    public static void run(final Runnable task) {
        try {
            EXEC.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        android.util.Log.w("RedPhone", "deck background task failed", e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            android.util.Log.w("RedPhone", "deck background task rejected", e);
        }
    }
}
