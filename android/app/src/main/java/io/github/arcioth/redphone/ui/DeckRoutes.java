package io.github.arcioth.redphone.ui;

import java.util.Locale;

/**
 * Pure routing rules for the deck WebView's virtual origin {@code https://redphone.local/}
 * (nothing is fetched from the network: {@link DeckClient} serves everything from the APK assets).
 * No android.* imports so it can be unit-tested on the desktop JVM.
 */
public final class DeckRoutes {

    public static final String HOST = "redphone.local";
    public static final String ORIGIN = "https://" + HOST;
    public static final String START_URL = ORIGIN + "/";

    /** The desktop deck, shipped unchanged (ui/index.html). */
    public static final String INDEX_ASSET = "ui/index.html";
    /** Android additions (android.js / android.css). */
    public static final String ANDROID_ASSET_DIR = "ui-android/";
    public static final String ANDROID_PREFIX = "/android/";

    /** Injected right after the opening {@code <head>} tag, so android.js runs before index.html's script. */
    public static final String HEAD_INJECTION =
            "<link rel=\"stylesheet\" href=\"/android/android.css\"><script src=\"/android/android.js\"></script>";

    /**
     * Defence in depth for the page that holds the RedPhoneAndroid bridge: only same-origin scripts,
     * styles and requests; no frames, plugins, forms or base-URL tricks (WebSockets are not seen by
     * shouldInterceptRequest, connect-src covers them).
     */
    public static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self' 'unsafe-inline'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; "
            + "media-src 'none'; object-src 'none'; frame-src 'none'; worker-src 'none'; base-uri 'none'; "
            + "form-action 'none'";

    /** A request served from the APK assets. */
    public static final class Route {
        public final String asset;
        public final String mime;
        /** true for the deck document: apply {@link #injectHead(String)}. */
        public final boolean injectHead;

        Route(String asset, String mime, boolean injectHead) {
            this.asset = asset;
            this.mime = mime;
            this.injectHead = injectHead;
        }
    }

    private DeckRoutes() {
    }

    /** true for https://redphone.local[:443] (the only origin the deck may load or navigate to). */
    public static boolean isDeckOrigin(String scheme, String host, int port) {
        return "https".equalsIgnoreCase(scheme) && HOST.equalsIgnoreCase(host) && (port == -1 || port == 443);
    }

    /**
     * Maps a deck request to an asset, or null for 404.
     *
     * @param path decoded URL path (android.net.Uri#getPath()), may be null
     */
    public static Route resolve(String method, String scheme, String host, int port, String path) {
        if (!isDeckOrigin(scheme, host, port)) return null;
        if (method != null && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) return null;
        String p = (path == null || path.isEmpty()) ? "/" : path;
        if ("/".equals(p) || "/index.html".equals(p) || "/popup".equals(p)) {
            return new Route(INDEX_ASSET, "text/html", true);
        }
        if (p.startsWith(ANDROID_PREFIX)) {
            String name = p.substring(ANDROID_PREFIX.length());
            if (!isSafeFileName(name)) return null;
            return new Route(ANDROID_ASSET_DIR + name, mimeType(name), false);
        }
        return null;
    }

    /** One plain file name: no directories, no dot-files, no "..", ASCII only. */
    static boolean isSafeFileName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) return false;
        char first = name.charAt(0);
        if (!isAlnum(first)) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(isAlnum(c) || c == '.' || c == '_' || c == '-')) return false;
        }
        return !name.contains("..");
    }

    private static boolean isAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    public static String mimeType(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".woff")) return "font/woff";
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    /** Text types are served with encoding UTF-8; binary ones with no encoding. */
    public static boolean isText(String mime) {
        return mime != null && (mime.startsWith("text/") || mime.equals("application/javascript")
                || mime.equals("application/json") || mime.equals("image/svg+xml"));
    }

    /**
     * Inserts {@link #HEAD_INJECTION} immediately after the opening {@code <head>} tag (attributes
     * allowed; {@code <header>} is not a match). Falls back to after {@code <html ...>}, else prepends.
     */
    public static String injectHead(String html) {
        if (html == null) return HEAD_INJECTION;
        int at = endOfOpenTag(html, "head");
        if (at < 0) at = endOfOpenTag(html, "html");
        if (at < 0) return HEAD_INJECTION + html;
        return html.substring(0, at) + HEAD_INJECTION + html.substring(at);
    }

    /** Index just past the '>' of the first {@code <tag ...>} (case-insensitive), or -1. */
    static int endOfOpenTag(String html, String tag) {
        String lower = html.toLowerCase(Locale.ROOT);
        String needle = "<" + tag;
        int from = 0;
        while (true) {
            int i = lower.indexOf(needle, from);
            if (i < 0) return -1;
            int after = i + needle.length();
            if (after >= lower.length()) return -1;
            char c = lower.charAt(after);
            if (c == '>' || c == '/' || Character.isWhitespace(c)) {
                int close = lower.indexOf('>', after);
                return close < 0 ? -1 : close + 1;
            }
            from = after; // e.g. <header>: keep looking
        }
    }

    /** States in which the deck keeps the screen on (a call is ringing, being set up or live). */
    public static boolean keepsScreenOn(String state) {
        return "ringing".equals(state) || "dialing".equals(state) || "connecting".equals(state)
                || "connected".equals(state) || "auth_required".equals(state);
    }

    /** Script that hands a snapshot to android.js: {@code window.__redphonePush("<json>")}. */
    public static String pushScript(String json) {
        return "window.__redphonePush&&window.__redphonePush(" + jsString(json) + ")";
    }

    /**
     * A JavaScript string literal for any Java string (JSON-compatible escaping plus U+2028/U+2029,
     * which were line terminators in pre-ES2019 engines, and "</" for good measure).
     */
    public static String jsString(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 16);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                case '\b':
                    b.append("\\b");
                    break;
                case '\f':
                    b.append("\\f");
                    break;
                case '/':
                    b.append(i > 0 && s.charAt(i - 1) == '<' ? "\\/" : "/");
                    break;
                default:
                    if (c < 0x20 || c == ' ' || c == ' ' || (c >= 0xD800 && c <= 0xDFFF && !validSurrogate(s, i))) {
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
        return b.toString();
    }

    /** Lone surrogates are escaped so the literal stays valid UTF-16 when the WebView converts it. */
    private static boolean validSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }
}
