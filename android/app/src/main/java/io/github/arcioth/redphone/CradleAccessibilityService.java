package io.github.arcioth.redphone;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional global key filter: a hardware-keyboard Spacebar (and the handset button) works as the cradle
 * while Red Phone is in the background. Every key is passed through unchanged; no screen content is read.
 */
public final class CradleAccessibilityService extends AccessibilityService {

    public static boolean isEnabled(Context ctx) {
        try {
            if (Settings.Secure.getInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false;
            String list = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null) return false;
            ComponentName cn = new ComponentName(ctx, CradleAccessibilityService.class);
            String full = cn.flattenToString();
            String shortName = cn.flattenToShortString();
            for (String s : list.split(":")) {
                if (s.equalsIgnoreCase(full) || s.equalsIgnoreCase(shortName)) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (HookInput.isSpace(code) || HookInput.isHandsetButton(code)) HookInput.handle(e);
        return false; // always pass the key through
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
