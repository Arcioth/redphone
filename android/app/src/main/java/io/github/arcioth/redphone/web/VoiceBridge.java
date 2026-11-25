package io.github.arcioth.redphone.web;

import android.webkit.JavascriptInterface;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * {@code window.RedPhoneVoiceBridge}: the two extension service-worker endpoints (background.js
 * "poll" / "heartbeat") that chrome-shim.js forwards to.
 *
 * <p>addJavascriptInterface exposes this object to every frame of every page the WebView loads
 * (sign-in pages and third-party iframes included), so both methods only answer while the committed
 * top-level page is https://chatgpt.com or https://grok.com, and the provider is taken from that page,
 * never from the payload. Methods run on WebView's "JavaBridge" thread, never on the main thread; poll
 * blocks the page's JS only for the controller's (short) lock.
 *
 * <p>Public class with public methods: the bridge looks methods up reflectively.
 */
public final class VoiceBridge {
    public static final String NAME = "RedPhoneVoiceBridge";

    private static final int MAX_STATUS = 40;
    private static final int MAX_DETAIL = 200;

    /** The page state the bridge consults (written on the main thread). */
    interface Gate {
        /** Provider of the committed top-level page if it is a trusted provider page, else null. */
        String trustedProvider();
    }

    interface Sink {
        /** controller.extCommand().toString(). */
        String extCommand();

        /** Forward to controller.onExtHeartbeat off the calling thread. */
        void heartbeat(String provider, String status, String detail);
    }

    private final Gate gate;
    private final Sink sink;

    VoiceBridge(Gate gate, Sink sink) {
        this.gate = gate;
        this.sink = sink;
    }

    /** background.js {type: "poll"}: the /api/ext/command JSON, or "{}" for untrusted pages. */
    @JavascriptInterface
    public String poll() {
        if (gate.trustedProvider() == null) return "{}";
        try {
            String r = sink.extCommand();
            return r == null ? "{}" : r;
        } catch (RuntimeException e) {
            return "{}";
        }
    }

    /** background.js {type: "heartbeat", payload}: /api/ext/heartbeat. The payload's provider is ignored. */
    @JavascriptInterface
    public void heartbeat(String json) {
        String provider = gate.trustedProvider();
        if (provider == null || json == null) return;
        String status;
        String detail;
        try {
            JSONObject o = new JSONObject(json);
            status = WebPolicy.clip(o.optString("status", ""), MAX_STATUS);
            detail = WebPolicy.clip(o.optString("detail", ""), MAX_DETAIL);
        } catch (JSONException e) {
            return;
        }
        if (status.isEmpty()) return;
        try {
            sink.heartbeat(provider, status, detail);
        } catch (RuntimeException ignored) {
        }
    }
}
