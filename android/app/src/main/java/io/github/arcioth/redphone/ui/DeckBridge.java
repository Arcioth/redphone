package io.github.arcioth.redphone.ui;

import android.webkit.JavascriptInterface;

import io.github.arcioth.redphone.ApiRouter;
import io.github.arcioth.redphone.RedPhoneApp;
import io.github.arcioth.redphone.RpLog;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Locale;

/**
 * {@code window.RedPhoneAndroid} in the deck WebView only: the in-process replacement for the
 * daemon's HTTP API (android.js routes every fetch("/api/...") here). Methods run on the WebView's
 * JavaBridge thread, never on the main thread, and never throw into JavaScript.
 */
public final class DeckBridge {

    static final String NOT_FOUND = "{\"error\":\"not_found\"}";
    static final String INTERNAL = "{\"error\":\"internal\"}";
    static final String OPEN_PATH = "/api/android/open";

    private volatile DeckHost host;

    public DeckBridge(DeckHost host) {
        this.host = host;
    }

    /** Called from MainActivity.onDestroy: later calls still answer but no longer reach the activity. */
    public void detach() {
        host = null;
    }

    /**
     * daemon.py Handler: {@code method} GET/POST, {@code path} "/api/...", {@code bodyJson} the request
     * body (empty = {}). Returns the JSON reply (a snapshot for most routes), {"error":"not_found"} for
     * unknown routes.
     */
    @JavascriptInterface
    public String request(String method, String path, String bodyJson) {
        try {
            String m = method == null || method.isEmpty() ? "GET" : method.toUpperCase(Locale.ROOT);
            String p = path == null ? "" : path;
            JSONObject body = parseBody(bodyJson);
            String bare = p.indexOf('?') >= 0 ? p.substring(0, p.indexOf('?')) : p;
            if (OPEN_PATH.equals(bare) && !"GET".equals(m)) {
                DeckHost h = host;
                String target = body.optString("target", "");
                if (h != null) h.openSystemScreen(target);
                return RedPhoneApp.get().controller().snapshot().toString();
            }
            JSONObject reply = ApiRouter.handle(m, p, body);
            return reply == null ? "{}" : reply.toString();
        } catch (ApiRouter.NotFound e) {
            return NOT_FOUND;
        } catch (Throwable t) {
            // Never let an exception cross into the renderer (it would surface as a JS error).
            RpLog.log("deck bridge " + method + " " + path + " failed: " + t);
            return INTERNAL;
        }
    }

    /** android.js: the ANDROID SETTINGS panel opened / closed (Back closes it first). */
    @JavascriptInterface
    public void settingsPanel(boolean open) {
        DeckHost h = host;
        if (h != null) h.onSettingsPanel(open);
    }

    /** daemon._read_json: empty or invalid bodies (and non-objects) are {}. */
    static JSONObject parseBody(String bodyJson) {
        if (bodyJson == null) return new JSONObject();
        String s = bodyJson.trim();
        if (s.isEmpty()) return new JSONObject();
        try {
            Object v = new JSONTokener(s).nextValue();
            return v instanceof JSONObject ? (JSONObject) v : new JSONObject();
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
