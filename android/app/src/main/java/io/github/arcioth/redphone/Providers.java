package io.github.arcioth.redphone;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** daemon.PROVIDERS / PROVIDER_ORDER. */
public final class Providers {
    public static final List<String> ORDER =
            Collections.unmodifiableList(Arrays.asList("local", "eviltwin", "chatgpt", "grok"));

    private Providers() {
    }

    public static boolean exists(String id) {
        return id != null && ORDER.contains(id);
    }

    public static String label(String id) {
        if ("eviltwin".equals(id)) return "EVIL TWIN";
        if ("chatgpt".equals(id)) return "CHATGPT";
        if ("grok".equals(id)) return "GROK";
        return "LOCAL AI";
    }

    public static String name(String id) {
        if ("eviltwin".equals(id)) return "Evil Twin (Your Cloned Voice)";
        if ("chatgpt".equals(id)) return "ChatGPT Voice";
        if ("grok".equals(id)) return "Grok Voice";
        return "Local AI (Ollama + Android TTS)";
    }

    public static String kind(String id) {
        return ("chatgpt".equals(id) || "grok".equals(id)) ? "browser" : "local";
    }

    public static String url(String id) {
        if ("chatgpt".equals(id)) return "https://chatgpt.com/";
        if ("grok".equals(id)) return "https://grok.com/";
        return "";
    }

    public static JSONObject info(String id) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("label", label(id));
            o.put("name", name(id));
            o.put("kind", kind(id));
            o.put("url", url(id));
        } catch (JSONException ignored) {
        }
        return o;
    }
}
