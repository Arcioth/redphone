package io.github.arcioth.redphone;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;

/**
 * STUB — owned by the SYSTEM module. Optional global key filter so a hardware keyboard Spacebar works as
 * the cradle (and the handset button works) while Red Phone is in the background. Passes every key through.
 */
public final class CradleAccessibilityService extends AccessibilityService {
    public static boolean isEnabled(Context ctx) {
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
