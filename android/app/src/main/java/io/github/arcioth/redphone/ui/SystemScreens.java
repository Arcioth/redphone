package io.github.arcioth.redphone.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import io.github.arcioth.redphone.RpLog;

import java.util.ArrayList;
import java.util.List;

/**
 * /api/android/open targets of the ANDROID SETTINGS panel: runtime permission prompts and the system
 * settings screens for permissions that have no prompt. UI thread only.
 */
public final class SystemScreens {

    public static final int REQ_INITIAL = 4701;
    public static final int REQ_MIC = 4702;
    public static final int REQ_NOTIFICATIONS = 4703;

    /** API 33 Manifest.permission.POST_NOTIFICATIONS (string literal keeps the class loadable on 26). */
    static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    private static final String UI_PREFS = "redphone_ui";
    private static final String KEY_INITIAL_ASKED = "initial_perms_asked";
    private static final String KEY_ASKED_PREFIX = "asked:";

    private SystemScreens() {
    }

    /** Opens what {@code target} names: mic, notifications, fsi, overlay, accessibility, battery, app. */
    public static void open(Activity a, String target) {
        if (a == null || a.isFinishing()) return;
        String t = target == null ? "" : target;
        RpLog.log("deck: open " + t);
        if ("mic".equals(t)) {
            requestOrSettings(a, Manifest.permission.RECORD_AUDIO, REQ_MIC);
        } else if ("notifications".equals(t)) {
            if (Build.VERSION.SDK_INT >= 33 && !granted(a, POST_NOTIFICATIONS)) {
                requestOrSettings(a, POST_NOTIFICATIONS, REQ_NOTIFICATIONS);
            } else {
                notificationSettings(a);
            }
        } else if ("fsi".equals(t)) {
            if (Build.VERSION.SDK_INT >= 34) {
                start(a, new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(a)));
            } else {
                // Before Android 14 USE_FULL_SCREEN_INTENT is granted at install; the channel settings matter.
                notificationSettings(a);
            }
        } else if ("overlay".equals(t)) {
            start(a, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(a)));
        } else if ("accessibility".equals(t)) {
            start(a, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else if ("battery".equals(t)) {
            // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS needs a permission the app does not declare.
            start(a, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } else if ("app".equals(t)) {
            appDetails(a);
        } else {
            RpLog.log("deck: unknown open target " + t);
        }
    }

    /**
     * First launch only: ask for RECORD_AUDIO and (API 33+) POST_NOTIFICATIONS together. Later the
     * MIC / NOTIF chips of the settings panel ask again or open the app's settings page.
     */
    public static void requestInitialPermissions(Activity a) {
        SharedPreferences sp = prefs(a);
        if (sp.getBoolean(KEY_INITIAL_ASKED, false)) return;
        List<String> missing = new ArrayList<String>();
        if (!granted(a, Manifest.permission.RECORD_AUDIO)) missing.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && !granted(a, POST_NOTIFICATIONS)) missing.add(POST_NOTIFICATIONS);
        SharedPreferences.Editor ed = sp.edit().putBoolean(KEY_INITIAL_ASKED, true);
        for (String p : missing) ed.putBoolean(KEY_ASKED_PREFIX + p, true);
        ed.apply();
        if (missing.isEmpty()) return;
        try {
            a.requestPermissions(missing.toArray(new String[0]), REQ_INITIAL);
        } catch (RuntimeException e) {
            RpLog.log("deck: permission request failed: " + e);
        }
    }

    public static boolean granted(Context c, String permission) {
        try {
            return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Shows the runtime prompt if Android still would (never asked, or the user can be asked again);
     * after "don't ask again" the prompt is silently refused, so open the app's settings page instead.
     */
    private static void requestOrSettings(Activity a, String permission, int code) {
        if (granted(a, permission)) {
            appDetails(a);
            return;
        }
        SharedPreferences sp = prefs(a);
        boolean asked = sp.getBoolean(KEY_ASKED_PREFIX + permission, false);
        if (!asked || a.shouldShowRequestPermissionRationale(permission)) {
            sp.edit().putBoolean(KEY_ASKED_PREFIX + permission, true).apply();
            try {
                a.requestPermissions(new String[] {permission}, code);
                return;
            } catch (RuntimeException e) {
                RpLog.log("deck: permission request failed: " + e);
            }
        }
        appDetails(a);
    }

    private static void notificationSettings(Activity a) {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        i.putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
        start(a, i);
    }

    private static void appDetails(Activity a) {
        if (!tryStart(a, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(a)))) {
            tryStart(a, new Intent(Settings.ACTION_SETTINGS));
        }
    }

    /** Starts a settings screen; vendors that lack it get the app's details page, then Settings. */
    private static void start(Activity a, Intent i) {
        if (!tryStart(a, i)) appDetails(a);
    }

    private static boolean tryStart(Activity a, Intent i) {
        try {
            a.startActivity(i);
            return true;
        } catch (RuntimeException e) { // ActivityNotFoundException, SecurityException
            RpLog.log("deck: cannot open " + i.getAction() + ": " + e);
            return false;
        }
    }

    private static Uri packageUri(Context c) {
        return Uri.fromParts("package", c.getPackageName(), null);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
    }
}
