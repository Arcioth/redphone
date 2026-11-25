package io.github.arcioth.redphone.ui;

import android.webkit.WebView;

/** What the deck WebView's bridge and clients need from the activity hosting it (MainActivity). */
public interface DeckHost {

    /**
     * /api/android/open: show a permission prompt or a system settings screen. Called on a WebView
     * bridge thread; implementations post to the UI thread and return immediately.
     */
    void openSystemScreen(String target);

    /** android.js reports whether the ANDROID SETTINGS panel is open (so Back can close it). Any thread. */
    void onSettingsPanel(boolean open);

    /** The deck's renderer process died (UI thread). The WebView must be removed and destroyed. */
    void onDeckRendererGone(WebView view, boolean crashed);
}
