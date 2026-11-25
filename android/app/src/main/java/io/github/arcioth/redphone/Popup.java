package io.github.arcioth.redphone;

import android.content.Context;

/**
 * STUB — owned by the UI module. daemon.open_popup / close_popup (Hyprland window move+center+focus).
 *
 * Contract:
 *  - open(ctx): bring the deck (MainActivity) to the front if Android allows it from the current state
 *    (already visible, or overlay permission granted); never throws. Incoming-ring pop-ups over the
 *    lock screen are handled by Notifier's full-screen intent, not here.
 *  - close(ctx): finish the deck if it is showing (unplug teardown).
 */
public final class Popup {
    private Popup() {
    }

    public static void open(Context ctx) {
    }

    public static void close(Context ctx) {
    }
}
