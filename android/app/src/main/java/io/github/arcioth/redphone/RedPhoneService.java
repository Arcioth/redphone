package io.github.arcioth.redphone;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

/** redphone-daemon.service as an Android foreground service; strictly on-demand like the daemon. */
public final class RedPhoneService extends Service implements Controller.Listener {
    private static final int FGS_MEDIA_PLAYBACK = 2;
    private static final int FGS_MICROPHONE = 128;
    private static volatile boolean uiVisible;
    private static volatile RedPhoneService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Controller controller;
    private UsbMonitor usb;
    private HandsetMediaSession media;
    private ProximityCradle prox;
    private PowerManager.WakeLock wake;
    private String lastKey = "";
    private long lastNotify;
    private boolean ticking;

    public static void start(Context ctx) {
        try {
            ctx.startForegroundService(new Intent(ctx, RedPhoneService.class));
        } catch (RuntimeException e) { // background-start restrictions
            RpLog.log("service start refused: " + e);
        }
    }

    public static void setUiVisible(Context ctx, boolean visible) {
        uiVisible = visible;
        RedPhoneService s = instance;
        if (s != null) s.main.post(s.stopCheck);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        controller = RedPhoneApp.get().controller();
        usb = new UsbMonitor(this);
        media = new HandsetMediaSession(this);
        prox = new ProximityCradle(this);
        controller.addListener(this);
        usb.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        enterForeground(Notifier.buildStatus(this, controller.snapshot()));
        apply(controller.snapshot());
        main.postDelayed(stopCheck, 2000);
        return START_NOT_STICKY;
    }

    private void enterForeground(Notification n) {
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifier.STATUS_ID, n, mic ? (FGS_MICROPHONE | FGS_MEDIA_PLAYBACK) : FGS_MEDIA_PLAYBACK);
            } else {
                startForeground(Notifier.STATUS_ID, n);
            }
        } catch (SecurityException e) { // microphone type not allowed from here
            RpLog.log("foreground mic type refused: " + e);
            if (Build.VERSION.SDK_INT >= 29) startForeground(Notifier.STATUS_ID, n, FGS_MEDIA_PLAYBACK);
        }
    }

    /** Controller.Listener: may run on any thread with the controller lock held, so only post. */
    @Override
    public void onState(final JSONObject snap) {
        main.post(new Runnable() {
            @Override
            public void run() {
                apply(snap);
            }
        });
    }

    private void apply(JSONObject st) {
        boolean plugged = st.optBoolean("plugged");
        String state = st.optString("state");
        String key = Notifier.chipText(st) + "|" + st.optString("detail") + "|" + state + "|" + st.optString("mode")
                + "|" + st.optString("provider");
        long now = System.currentTimeMillis();
        boolean live = "connected".equals(state) || "armed".equals(state);
        if (!key.equals(lastKey) || (live && now - lastNotify >= 1000)) {
            lastKey = key;
            lastNotify = now;
            Notifier.update(this, st);
            TelTileUpdater.request(this);
        }
        if (live && !ticking) {
            ticking = true;
            main.postDelayed(tick, 1000);
        }
        media.setActive(plugged);
        JSONObject android = st.optJSONObject("android");
        boolean wantProx = plugged && android != null && android.optBoolean("prox_cradle");
        prox.setEnabled(wantProx);
        if (plugged) {
            if (wake == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "redphone:line");
                wake.setReferenceCounted(false);
            }
            wake.acquire(4 * 60 * 60 * 1000L); // re-armed on each state change; never leaks forever
        } else if (wake != null && wake.isHeld()) {
            wake.release();
        }
        main.removeCallbacks(stopCheck);
        main.postDelayed(stopCheck, 2000);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ticking = false;
            apply(controller.snapshot());
        }
    };

    private final Runnable stopCheck = new Runnable() {
        @Override
        public void run() {
            if (!controller.isPlugged() && !uiVisible) {
                RpLog.log("idle and unplugged: stopping service");
                stopSelf();
            }
        }
    };

    @Override
    public void onDestroy() {
        instance = null;
        main.removeCallbacksAndMessages(null);
        controller.removeListener(this);
        usb.stop();
        media.release();
        prox.setEnabled(false);
        if (wake != null && wake.isHeld()) wake.release();
        if (!controller.isPlugged()) controller.shutdown();
        Notifier.cancelAll(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Keeps TileService.requestListeningState in one place. */
    static final class TelTileUpdater {
        static void request(Context ctx) {
            try {
                android.service.quicksettings.TileService.requestListeningState(ctx,
                        new android.content.ComponentName(ctx, TelTileService.class));
            } catch (RuntimeException ignored) {
            }
        }
    }
}
