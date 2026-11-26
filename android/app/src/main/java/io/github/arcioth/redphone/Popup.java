package io.github.arcioth.redphone;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.ref.WeakReference;

/**
 * daemon.open_popup / close_popup (Hyprland move + center + focus of the 560x600 deck window).
 *
 * <ul>
 *   <li>{@link #open(Context)}: bring the deck (MainActivity) to the front if Android allows it from
 *       the current state (already visible, or the app holds the overlay permission; background
 *       activity starts are otherwise silently ignored). Incoming-ring pop-ups over the lock screen are
 *       Notifier's full-screen intent, not this.</li>
 *   <li>{@link #close(Context)}: finish the deck (unplug teardown).</li>
 * </ul>
 * Both are safe from any thread (the controller calls them with its lock held): they only post to the
 * main thread and return.
 */
public final class Popup {

    /** daemon: a 3 s spawn debounce; a start that Android drops is not retried sooner than this. */
    private static final long OPEN_DEBOUNCE_MS = 800;
    /** Do not yank the deck away from someone using it (e.g. they just switched SIM PLUG off). */
    private static final long CLOSE_GRACE_MS = 3000;

    private static final Object LOCK = new Object();
    private static WeakReference<MainActivity> deck = new WeakReference<MainActivity>(null);
    private static boolean resumed;
    /** Started = visible, maybe paused under a dialog (e.g. a permission prompt in our task). */
    private static boolean started;
    private static long lastStartAt;
    private static volatile Handler main;

    private Popup() {
    }

    public static void open(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        final Context c = app != null ? app : ctx;
        post(new Runnable() {
            @Override
            public void run() {
                openNow(c);
            }
        });
    }

    public static void close(Context ctx) {
        post(new Runnable() {
            @Override
            public void run() {
                closeNow();
            }
        });
    }

    /** true while the deck is in the foreground (resumed). */
    public static boolean isDeckResumed() {
        synchronized (LOCK) {
            return resumed && deck.get() != null;
        }
    }

    // ------------------------------------------------------------------ MainActivity lifecycle

    static void onCreated(MainActivity a) {
        synchronized (LOCK) {
            deck = new WeakReference<MainActivity>(a);
        }
    }

    static void onStarted(MainActivity a) {
        synchronized (LOCK) {
            deck = new WeakReference<MainActivity>(a);
            started = true;
        }
    }

    static void onStopped(MainActivity a) {
        synchronized (LOCK) {
            if (deck.get() == a) started = false;
        }
    }

    static void onResumed(MainActivity a) {
        synchronized (LOCK) {
            deck = new WeakReference<MainActivity>(a);
            resumed = true;
        }
    }

    static void onPaused(MainActivity a) {
        synchronized (LOCK) {
            if (deck.get() == a) resumed = false;
        }
    }

    static void onDestroyed(MainActivity a) {
        synchronized (LOCK) {
            if (deck.get() == a) {
                deck = new WeakReference<MainActivity>(null);
                resumed = false;
                started = false;
            }
        }
    }

    // ------------------------------------------------------------------ main thread

    private static void openNow(Context c) {
        MainActivity a;
        synchronized (LOCK) {
            a = deck.get();
            // Already in front, or visible under a dialog of our own task: relaunching the singleTask
            // deck would clear that dialog (e.g. cancel a permission prompt).
            if (a != null && (resumed || started) && !a.isFinishing()) return;
            long now = SystemClock.elapsedRealtime();
            if (lastStartAt != 0 && now - lastStartAt < OPEN_DEBOUNCE_MS) return;
            lastStartAt = now;
        }
        try {
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            c.startActivity(i);
        } catch (RuntimeException e) {
            RpLog.log("popup: cannot bring the deck to front: " + e);
        }
    }

    private static void closeNow() {
        MainActivity a;
        synchronized (LOCK) {
            a = deck.get();
        }
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        long idle = SystemClock.uptimeMillis() - a.lastUserInteraction();
        if (a.lastUserInteraction() != 0 && idle < CLOSE_GRACE_MS) {
            RpLog.log("popup: deck in use, not closing it");
            return;
        }
        a.finish();
    }

    private static void post(Runnable r) {
        Handler h = main;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            main = h;
        }
        h.post(r);
    }
}
