package io.github.arcioth.redphone.web;

import java.util.Locale;

/**
 * URL, host and user-agent rules of the voice browser. Pure Java (no android.*) so it can be unit
 * tested on a desktop JVM; the Android code feeds it the main-frame URLs WebView reports, which
 * Chromium has already canonicalized (lower-case scheme and host, no backslashes).
 */
public final class WebPolicy {
    public static final String CHATGPT = "chatgpt";
    public static final String GROK = "grok";
    public static final String CHATGPT_HOST = "chatgpt.com";
    public static final String GROK_HOST = "grok.com";

    /** extension/background.js AUTH_HOSTS, same order, same providers. */
    private static final String[][] AUTH_HOSTS = {
        {"auth.openai.com", CHATGPT},
        {"accounts.x.ai", GROK},
        {"x.com/i/flow/login", GROK},
    };

    /** extension/background.js notifyAuthIfNeeded() detail text. */
    public static final String AUTH_DETAIL = "Sign in once in the voice window";

    /** Hosts that third-party sign-in ("Continue with Google / Apple / Microsoft / X") goes through. */
    private static final String[] LOGIN_HOSTS = {
        "auth.openai.com", "auth0.openai.com", "accounts.x.ai", "x.com", "twitter.com", "api.x.com",
        "accounts.google.com", "appleid.apple.com", "idmsa.apple.com", "login.live.com",
        "account.live.com", "login.microsoftonline.com", "challenges.cloudflare.com",
    };

    private static final long RETRY_BASE_MS = 3000;
    private static final long RETRY_MAX_MS = 60000;

    private WebPolicy() {
    }

    /** Lower-case URL scheme, or null when {@code url} has none. */
    public static String scheme(String url) {
        if (url == null) return null;
        int colon = url.indexOf(':');
        if (colon <= 0) return null;
        for (int i = 0; i < colon; i++) {
            char c = url.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'));
            if (!ok) return null;
        }
        return url.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    /** Index just past "scheme://", or -1 for URLs without an authority. */
    private static int authorityStart(String url) {
        String s = scheme(url);
        if (s == null) return -1;
        int i = s.length() + 1;
        return url.startsWith("//", i) ? i + 2 : -1;
    }

    private static int authorityEnd(String url, int start) {
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == '\\') return i;
        }
        return url.length();
    }

    /** Lower-case host of an authority-based URL (user info and port stripped), or null. */
    public static String host(String url) {
        int a = authorityStart(url);
        if (a < 0) return null;
        String auth = url.substring(a, authorityEnd(url, a));
        int at = auth.lastIndexOf('@');
        if (at >= 0) auth = auth.substring(at + 1);
        String h;
        if (auth.startsWith("[")) {
            int rb = auth.indexOf(']');
            h = rb > 0 ? auth.substring(0, rb + 1) : auth;
        } else {
            int colon = auth.indexOf(':');
            h = colon >= 0 ? auth.substring(0, colon) : auth;
        }
        h = h.toLowerCase(Locale.ROOT);
        return h.isEmpty() ? null : h;
    }

    /** Path of an authority-based URL ("/" when empty), without query or fragment; null without authority. */
    public static String path(String url) {
        int a = authorityStart(url);
        if (a < 0) return null;
        int p = authorityEnd(url, a);
        int end = url.length();
        for (int i = p; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String path = url.substring(p, end);
        return path.isEmpty() ? "/" : path;
    }

    private static boolean isWebScheme(String scheme) {
        return "https".equals(scheme) || "http".equals(scheme);
    }

    /** http(s) URL with a host. */
    public static boolean isWebUrl(String url) {
        return isWebScheme(scheme(url)) && host(url) != null;
    }

    /** Provider whose voice page lives on exactly this host (chatgpt.com / grok.com), else null. */
    public static String providerForHost(String host) {
        if (CHATGPT_HOST.equals(host)) return CHATGPT;
        if (GROK_HOST.equals(host)) return GROK;
        return null;
    }

    /**
     * manifest.json content_scripts "matches" (https://chatgpt.com/* and https://grok.com/*): the
     * provider of a page that gets page-hook.js / content.js and the RedPhoneVoiceBridge, else null.
     */
    public static String providerForUrl(String url) {
        return "https".equals(scheme(url)) ? providerForHost(host(url)) : null;
    }

    public static boolean isInjectable(String url) {
        return providerForUrl(url) != null;
    }

    /** chatgpt.com / grok.com or one of their subdomains (microphone grants). */
    public static boolean isProviderDomain(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals(CHATGPT_HOST) || h.endsWith("." + CHATGPT_HOST)
                || h.equals(GROK_HOST) || h.endsWith("." + GROK_HOST);
    }

    /**
     * background.js notifyAuthIfNeeded(): the provider whose sign-in page {@code url} is, else null.
     * The extension matches {@code url.includes(...)}; this matches host + path only, so a sign-in
     * host that merely appears in a query string or fragment does not trigger a sign-in prompt.
     */
    public static String authProviderForUrl(String url) {
        if (!isWebScheme(scheme(url))) return null;
        String h = host(url);
        if (h == null) return null;
        String hp = h + path(url);
        for (String[] a : AUTH_HOSTS) {
            if (hp.contains(a[0])) return a[1];
        }
        return null;
    }

    /** A page of some sign-in flow (provider or third-party identity provider) the user may be in. */
    public static boolean isLoginFlow(String url) {
        if (authProviderForUrl(url) != null) return true;
        String h = host(url);
        if (h == null) return false;
        for (String lh : LOGIN_HOSTS) {
            if (h.equals(lh)) return true;
        }
        if (h.startsWith("login.") || h.startsWith("accounts.") || h.startsWith("account.")
                || h.startsWith("auth.") || h.startsWith("sso.") || h.startsWith("signin.")) {
            return true;
        }
        String p = path(url).toLowerCase(Locale.ROOT);
        return p.contains("/login") || p.contains("/log-in") || p.contains("/signin") || p.contains("/sign-in")
                || p.contains("/oauth") || p.contains("/authorize") || p.contains("/sso") || p.contains("/saml");
    }

    /**
     * Navigation policy: http(s) loads in place (sign-in flows hop across openai / google / apple /
     * microsoft / x.com), plus the about:blank / about:srcdoc / blob: documents frames are made of.
     * Everything else (intent:, market:, tel:, mailto:, file:, content:, data:, javascript:, ...) is
     * blocked.
     */
    public static boolean navigationAllowed(String url) {
        String s = scheme(url);
        if (isWebScheme(s)) return host(url) != null;
        if ("about".equals(s)) {
            String u = url.toLowerCase(Locale.ROOT);
            return u.equals("about:blank") || u.equals("about:srcdoc");
        }
        return "blob".equals(s);
    }

    /**
     * The WebView user agent made to look like Chrome for Android: drops the "; wv" token and the
     * "Version/4.0 " token that identify an embedded WebView.
     */
    public static String chromeLikeUserAgent(String ua) {
        if (ua == null || ua.isEmpty()) return ua;
        String out = ua.replace("; wv)", ")").replace("; wv;", ";");
        out = out.replaceAll("Version/\\d+(?:\\.\\d+)*\\s+", "");
        return out.replaceAll("\\s{2,}", " ").trim();
    }

    /** Back-off before reloading a voice page that failed to load: 3, 6, 12, 24, 48, 60, 60 ... s. */
    public static long retryDelayMs(int attempt) {
        int a = Math.max(0, Math.min(attempt, 10));
        return Math.min(RETRY_MAX_MS, RETRY_BASE_MS << a);
    }

    /** Trimmed and clipped to {@code max} chars; never null. */
    public static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, Math.max(0, max));
    }

    /** URL for logs: scheme://host/path, without the query or fragment (OAuth callbacks carry codes there). */
    public static String redact(String url) {
        if (url == null) return "null";
        String h = host(url);
        if (h != null) return scheme(url) + "://" + h + path(url);
        String s = scheme(url);
        return s == null ? "?" : s + ":…";
    }
}
