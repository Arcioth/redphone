package io.github.arcioth.redphone;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Port of daemon.py's HTTP {@code Handler}: same paths, same bodies, same snapshot replies.
 * The desktop UI (ui/index.html) talks to it through the in-process WebView bridge instead
 * of HTTP, so nothing listens on a TCP port that other apps could reach.
 */
public final class ApiRouter {

    /** Thrown for unknown routes (HTTP 404 in the daemon). */
    public static final class NotFound extends Exception {
        public NotFound(String path) {
            super(path);
        }
    }

    private ApiRouter() {
    }

    public static JSONObject handle(String method, String path, JSONObject data) throws NotFound {
        Controller c = RedPhoneApp.get().controller();
        if (data == null) data = new JSONObject();
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);

        if ("GET".equalsIgnoreCase(method)) {
            if ("/api/state".equals(path)) return c.snapshot();
            if ("/api/ext/command".equals(path)) return c.extCommand();
            throw new NotFound(path);
        }

        if ("/api/mode".equals(path)) {
            c.setMode(str(data, "mode", "toggle"));
            return c.snapshot();
        }
        if ("/api/delay".equals(path)) {
            c.setDelay(data.has("delay") ? data.opt("delay") : "random");
            return c.snapshot();
        }
        if ("/api/lang".equals(path)) {
            c.setLang(str(data, "lang", "tr"));
            return c.snapshot();
        }
        if ("/api/hide_voice".equals(path)) {
            c.setHideVoice(data.has("hide") ? data.opt("hide") : "toggle");
            return c.snapshot();
        }
        if ("/api/provider".equals(path)) {
            c.setProvider(str(data, "provider", "cycle"));
            return c.snapshot();
        }
        if ("/api/ext/heartbeat".equals(path)) {
            c.onExtHeartbeat(data.optString("provider", ""), data.optString("status", ""), data.optString("detail", ""));
            JSONObject ok = new JSONObject();
            try {
                ok.put("ok", true);
            } catch (JSONException ignored) {
            }
            return ok;
        }
        if ("/api/action".equals(path)) {
            action(c, data.optString("action", ""));
            return c.snapshot();
        }
        // ---- Android-only routes ----
        if ("/api/android/settings".equals(path)) {
            c.applyAndroidSettings(data);
            return c.snapshot();
        }
        if ("/api/android/cradle".equals(path)) {
            // On-screen SPACEBAR cradle pad: down=true while the handset/finger rests on it.
            c.onSpaceChanged(data.optBoolean("down", false));
            return c.snapshot();
        }
        if ("/api/android/handset".equals(path)) {
            c.onHandsetButton();
            return c.snapshot();
        }
        throw new NotFound(path);
    }

    static void action(Controller c, String act) {
        if ("plug".equals(act)) {
            c.onPlug();
        } else if ("unplug".equals(act)) {
            c.onUnplug();
        } else if ("dial".equals(act)) {
            c.startOutgoingCall();
        } else if ("answer".equals(act)) {
            c.answer();
        } else if ("decline".equals(act)) {
            c.decline();
        } else if ("hangup".equals(act)) {
            c.hangup();
        } else if ("test_ring".equals(act)) {
            c.triggerIncomingRing();
        } else if ("arm".equals(act)) {
            c.arm();
        } else if ("open_ui".equals(act)) {
            c.openUi();
        } else if ("focus_voice".equals(act)) {
            c.focusVoice();
        } else if ("manual_connected".equals(act)) {
            c.finishConnect("");
        } else if ("reset_clone".equals(act)) {
            c.resetClone();
        } else if ("record_clone".equals(act)) {
            c.recordCloneSample();
        }
    }

    private static String str(JSONObject o, String key, String def) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return def;
        String s = String.valueOf(v);
        return s.isEmpty() ? def : s;
    }
}
