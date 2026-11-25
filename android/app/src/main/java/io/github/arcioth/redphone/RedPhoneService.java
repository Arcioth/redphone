package io.github.arcioth.redphone;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * STUB — owned by the SYSTEM module. redphone-daemon.service equivalent (foreground service).
 *
 * Contract:
 *  - start(ctx): idempotent; startForegroundService when allowed, swallow background-start exceptions.
 *  - Runs as foreground service (types microphone|mediaPlayback; microphone only once RECORD_AUDIO is
 *    granted) with Notifier's status notification. Owns UsbMonitor (plug/unplug ->
 *    controller.setHardwarePresent), the MediaSession for the handset button, ProximityCradle.
 *  - setUiVisible(ctx, visible): MainActivity reports visibility; when unplugged and no UI is visible the
 *    service stops itself (daemon is strictly on-demand).
 */
public final class RedPhoneService extends Service {
    public static void start(Context ctx) {
    }

    public static void setUiVisible(Context ctx, boolean visible) {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
