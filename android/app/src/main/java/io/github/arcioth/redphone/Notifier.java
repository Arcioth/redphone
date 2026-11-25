package io.github.arcioth.redphone;

import android.content.Context;

/**
 * STUB — owned by the SYSTEM module. Waybar "TEL:" chip as notifications.
 *  - createChannels(ctx): called from RedPhoneApp.onCreate.
 *  - Status notification (ongoing, used by the foreground service) mirroring bin/redphone-waybar text.
 *  - Incoming-ring notification (category CALL, full-screen intent -> MainActivity) while state == ringing.
 */
public final class Notifier {
    private Notifier() {
    }

    public static void createChannels(Context ctx) {
    }
}
