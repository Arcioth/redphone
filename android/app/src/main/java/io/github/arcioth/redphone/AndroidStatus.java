package io.github.arcioth.redphone;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The snapshot's "android" object (settings + permission status) that android.js renders in the
 * ANDROID SETTINGS panel. Called from Controller.snapshot() with the controller lock held, on every
 * poll (400 ms) and push: only cheap, non-blocking getters, and the binder-backed ones (permission
 * checks, notification/overlay state, audio devices) are cached for {@link #TTL_MS}.
 * MainActivity calls {@link #invalidate()} when it returns from a permission prompt or settings screen.
 */
public final class AndroidStatus {

    static final long TTL_MS = 1500;

    private static final Object LOCK = new Object();
    private static long permsAt;
    private static boolean mic;
    private static boolean notifications;
    private static boolean fullScreen;
    private static boolean overlay;
    private static boolean keyboardCradle;

    private static long audioAt;
    private static String audioKey;
    private static String audioName;

    private AndroidStatus() {
    }

    /** Forget cached permission/audio state (after a permission result or on resume). */
    public static void invalidate() {
        synchronized (LOCK) {
            permsAt = 0;
            audioAt = 0;
            audioKey = null;
        }
    }

    public static JSONObject snapshot(Context ctx, Controller c) {
        JSONObject o = new JSONObject();
        try {
            Prefs p = c.prefs();
            boolean hardware = c.isHardwarePresent();
            boolean prox = p.getBool(Prefs.PROX_CRADLE, false);
            boolean anyUsb = p.getBool(Prefs.ANY_USB, false);
            boolean sim = p.getBool(Prefs.SIM_PLUG, false);
            o.put("platform", "android");
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("ollama_url", p.ollamaUrl());
            o.put("ollama_model", p.getString(Prefs.OLLAMA_MODEL, ""));
            o.put("prox_cradle", prox);
            o.put("any_usb", anyUsb);
            o.put("sim_plug", sim);
            o.put("hardware_present", hardware);
            String audio = phoneAudio(c, hardware + "|" + anyUsb + "|" + sim + "|" + c.isPlugged());
            o.put("phone_audio", audio == null ? JSONObject.NULL : audio);
            float f0 = p.getFloat(Prefs.CLONE_F0, 0f);
            o.put("clone_f0", (Float.isNaN(f0) || Float.isInfinite(f0)) ? 0 : (double) f0);
            o.put("perms", perms(ctx));
        } catch (JSONException e) {
            RpLog.log("android status error: " + e);
        } catch (RuntimeException e) {
            // A vendor framework call misbehaving must never break the whole snapshot.
            RpLog.log("android status error: " + e);
        }
        return o;
    }

    /** Handset audio device name (AudioRouter.discover() is a binder call): cached per plug state. */
    private static String phoneAudio(Controller c, String key) {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (key.equals(audioKey) && now - audioAt < TTL_MS) return audioName;
        }
        String name;
        try {
            name = c.audio.phoneDeviceName();
        } catch (RuntimeException e) {
            name = null;
        }
        synchronized (LOCK) {
            audioKey = key;
            audioAt = now;
            audioName = name;
        }
        return name;
    }

    private static JSONObject perms(Context ctx) throws JSONException {
        long now = SystemClock.elapsedRealtime();
        boolean fresh;
        synchronized (LOCK) {
            fresh = permsAt != 0 && now - permsAt < TTL_MS;
        }
        if (!fresh) {
            boolean m = granted(ctx, Manifest.permission.RECORD_AUDIO);
            boolean n = false;
            boolean f = true; // granted at install before Android 14
            NotificationManager nm = null;
            try {
                nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            } catch (RuntimeException ignored) {
            }
            if (nm != null) {
                try {
                    // false when POST_NOTIFICATIONS is denied (API 33+) or the user blocked the app.
                    n = nm.areNotificationsEnabled();
                } catch (RuntimeException ignored) {
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        f = nm.canUseFullScreenIntent();
                    } catch (RuntimeException e) {
                        f = false;
                    }
                }
            }
            boolean ov = false;
            try {
                ov = Settings.canDrawOverlays(ctx);
            } catch (RuntimeException ignored) {
            }
            boolean kb = false;
            try {
                kb = CradleAccessibilityService.isEnabled(ctx);
            } catch (RuntimeException ignored) {
            }
            synchronized (LOCK) {
                mic = m;
                notifications = n;
                fullScreen = f;
                overlay = ov;
                keyboardCradle = kb;
                permsAt = now == 0 ? 1 : now;
            }
        }
        JSONObject perms = new JSONObject();
        synchronized (LOCK) {
            perms.put("mic", mic);
            perms.put("notifications", notifications);
            perms.put("full_screen", fullScreen);
            perms.put("overlay", overlay);
            perms.put("keyboard_cradle", keyboardCradle);
        }
        return perms;
    }

    private static boolean granted(Context ctx, String permission) {
        try {
            return ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
