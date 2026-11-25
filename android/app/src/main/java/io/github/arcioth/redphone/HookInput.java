package io.github.arcioth.redphone;

import android.view.KeyEvent;

/**
 * Shared key router for the Spacebar cradle hook-switch and the AB13X handset button
 * (daemon.run_keyboard_loop / run_handset_loop). Fed by MainActivity (foreground),
 * CradleAccessibilityService (background, optional) and the MediaSession (handset button).
 */
public final class HookInput {
    private static long lastButtonDownTime = Long.MIN_VALUE;
    private static int lastButtonCode = -1;

    private HookInput() {
    }

    public static boolean isSpace(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_SPACE;
    }

    /** linux KEY_PLAYPAUSE / KEY_STOPCD as Android maps them, plus headset-hook variants. */
    public static boolean isHandsetButton(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Routes a key event to the controller.
     *
     * @return true if the key is a cradle/handset key (callers in the foreground should consume it
     *         so the Spacebar does not also click focused buttons or scroll the deck).
     */
    public static boolean handle(KeyEvent ev) {
        if (ev == null) return false;
        int code = ev.getKeyCode();
        if (isSpace(code)) {
            // Linux autorepeat (value 2) is ignored by the daemon; Android reports it as repeatCount > 0.
            if (ev.getRepeatCount() > 0) return true;
            Controller c = RedPhoneApp.get().controller();
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                c.onSpaceChanged(true);
            } else if (ev.getAction() == KeyEvent.ACTION_UP) {
                c.onSpaceChanged(false);
            }
            return true;
        }
        if (isHandsetButton(code)) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                synchronized (HookInput.class) {
                    // The same physical press can reach us through the accessibility filter, the
                    // focused activity and the media session; act on it exactly once.
                    if (ev.getDownTime() == lastButtonDownTime && code == lastButtonCode) return true;
                    lastButtonDownTime = ev.getDownTime();
                    lastButtonCode = code;
                }
                RedPhoneApp.get().controller().onHandsetButton();
            }
            return true;
        }
        return false;
    }
}
