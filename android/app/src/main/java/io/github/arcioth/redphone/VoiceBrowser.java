package io.github.arcioth.redphone;

import android.content.Context;
import android.widget.FrameLayout;

/**
 * STUB — owned by the VOICE BROWSER module. Android replacement for the Chromium "redphone-voice"
 * window + MV3 extension (extension/): a persistent WebView on chatgpt.com / grok.com running the
 * repo's own extension/page-hook.js + extension/content.js through a chrome.runtime shim.
 *
 * Contract (all methods thread-safe, return immediately, do UI work on the main thread):
 *  - exists(provider): a WebView for that provider exists (pre-warmed or live).
 *  - load(provider, url, focus): create (if needed) + load url. When ready: focus ? show(true) : hide().
 *  - show(full): make it visible inside the host. full=false -> centred orb panel (desktop 340x340
 *    window); full=true -> whole-screen page (sign-in / VOICE TAB).
 *  - hide(): keep running but invisible (HIDE WHOLE BROWSER / special workspace).
 *  - close(): destroy the WebView (page audio + mic stop).
 *  - attachHost/detachHost: MainActivity lends its overlay container; the WebView survives activity
 *    recreation (application-context WebView re-parented between hosts).
 *  - onBackPressed(): true if it consumed Back (e.g. collapsed a full-screen page).
 */
public final class VoiceBrowser {
    public VoiceBrowser(Context ctx) {
    }

    public boolean exists(String provider) {
        return false;
    }

    public void load(String provider, String url, boolean focus) {
    }

    public void show(boolean full) {
    }

    public void hide() {
    }

    public void close() {
    }

    public void attachHost(FrameLayout host) {
    }

    public void detachHost(FrameLayout host) {
    }

    public boolean onBackPressed() {
        return false;
    }
}
