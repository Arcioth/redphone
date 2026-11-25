package io.github.arcioth.redphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * bin/redphone-ctl for Android, over adb (the receiver requires android.permission.DUMP, which the
 * adb shell holds and ordinary apps cannot get):
 *
 * <pre>
 * adb shell am broadcast -n io.github.arcioth.redphone/.CtlReceiver \
 *     -a io.github.arcioth.redphone.CTL --es cmd set-delay --es arg 15
 * </pre>
 *
 * The reply (result data) is the state snapshot JSON, like {@code redphone-ctl status}.
 */
public final class CtlReceiver extends BroadcastReceiver {
    public static final String ACTION = "io.github.arcioth.redphone.CTL";

    @Override
    public void onReceive(Context context, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        String arg = intent.getStringExtra("arg");
        if (cmd == null) cmd = "status";
        try {
            JSONObject reply = run(context, cmd, arg);
            setResultCode(0);
            setResultData(reply.toString());
        } catch (IllegalArgumentException e) {
            setResultCode(2);
            setResultData("usage: cmd=plug|unplug|toggle-mode|mode-call|mode-receive|cycle-provider|"
                    + "set-provider <id>|set-delay <random|sec>|set-lang <tr|auto|en>|hide-voice|show-voice|"
                    + "toggle-hide|dial|answer|hangup|decline|test-ring|record-clone|reset-clone|open-ui|"
                    + "focus-voice|sim-plug <on|off>|status");
        } catch (ApiRouter.NotFound | JSONException e) {
            setResultCode(1);
            setResultData(String.valueOf(e));
        }
    }

    static JSONObject run(Context context, String cmd, String arg) throws ApiRouter.NotFound, JSONException {
        if (!"status".equals(cmd) && !"unplug".equals(cmd)) RedPhoneService.start(context);
        JSONObject b = new JSONObject();
        switch (cmd) {
            case "plug": return post("/api/action", b.put("action", "plug"));
            case "unplug": return post("/api/action", b.put("action", "unplug"));
            case "toggle-mode": return post("/api/mode", b.put("mode", "toggle"));
            case "mode-call": return post("/api/mode", b.put("mode", "call"));
            case "mode-receive": return post("/api/mode", b.put("mode", "receive"));
            case "cycle-provider": return post("/api/provider", b.put("provider", "cycle"));
            case "set-provider": return post("/api/provider", b.put("provider", arg == null ? "local" : arg));
            case "set-delay": return post("/api/delay", b.put("delay", arg == null ? "random" : arg));
            case "set-lang": return post("/api/lang", b.put("lang", arg == null ? "tr" : arg));
            case "hide-voice": return post("/api/hide_voice", b.put("hide", true));
            case "show-voice": return post("/api/hide_voice", b.put("hide", false));
            case "toggle-hide": return post("/api/hide_voice", b.put("hide", "toggle"));
            case "dial": return post("/api/action", b.put("action", "dial"));
            case "answer": return post("/api/action", b.put("action", "answer"));
            case "hangup": return post("/api/action", b.put("action", "hangup"));
            case "decline": return post("/api/action", b.put("action", "decline"));
            case "test-ring": return post("/api/action", b.put("action", "test_ring"));
            case "record-clone": return post("/api/action", b.put("action", "record_clone"));
            case "reset-clone": return post("/api/action", b.put("action", "reset_clone"));
            case "open-ui": return post("/api/action", b.put("action", "open_ui"));
            case "focus-voice": return post("/api/action", b.put("action", "focus_voice"));
            case "sim-plug":
                return post("/api/android/settings", b.put("sim_plug", "on".equals(arg) || "1".equals(arg) || "true".equals(arg)));
            case "status": return ApiRouter.handle("GET", "/api/state", null);
            default: throw new IllegalArgumentException(cmd);
        }
    }

    private static JSONObject post(String path, JSONObject body) throws ApiRouter.NotFound {
        return ApiRouter.handle("POST", path, body);
    }
}
