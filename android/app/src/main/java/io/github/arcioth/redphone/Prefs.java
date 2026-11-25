package io.github.arcioth.redphone;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted settings: the desktop's ~/.local/state/redphone/{mode,provider,rcv_delay,lang,hide_voice} files + Android extras. */
public final class Prefs {
    public static final String MODE = "mode";
    public static final String PROVIDER = "provider";
    public static final String RCV_DELAY = "rcv_delay";
    public static final String LANG = "lang";
    public static final String HIDE_VOICE = "hide_voice";

    /** Ollama base URL. Default: Ollama running on the phone itself (e.g. Termux). */
    public static final String OLLAMA_URL = "ollama_url";
    public static final String OLLAMA_MODEL = "ollama_model";
    /** Use the proximity sensor as the cradle (rest the handset over the top of the phone). */
    public static final String PROX_CRADLE = "prox_cradle";
    /** Treat any USB headset (not only AB13X 001f:0b21) as the red phone. */
    public static final String ANY_USB = "any_usb";
    /** Pretend the red phone is plugged in (built-in mic/speaker) — for trying the app without hardware. */
    public static final String SIM_PLUG = "sim_plug";
    /** Evil Twin voice match: median F0 (Hz) of the recorded clone sample, 0 = none. */
    public static final String CLONE_F0 = "clone_f0";

    public static final String DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences("redphone", Context.MODE_PRIVATE);
    }

    public String getString(String key, String def) {
        String v = sp.getString(key, null);
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    public void putString(String key, String val) {
        sp.edit().putString(key, val).apply();
    }

    public boolean getBool(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    public void putBool(String key, boolean val) {
        sp.edit().putBoolean(key, val).apply();
    }

    public float getFloat(String key, float def) {
        return sp.getFloat(key, def);
    }

    public void putFloat(String key, float val) {
        sp.edit().putFloat(key, val).apply();
    }

    public String ollamaUrl() {
        String u = getString(OLLAMA_URL, DEFAULT_OLLAMA_URL);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
