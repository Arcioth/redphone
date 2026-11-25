package io.github.arcioth.redphone.web;

/**
 * Builds the two scripts the voice WebView evaluates in every chatgpt.com / grok.com document.
 * Pure Java so the composition can be tested on a desktop JVM / node.
 *
 * <p>Android WebView has no isolated worlds: everything runs in the page's main world.
 * <ul>
 *   <li>{@link #early}: web-android/android-voice.js + extension/page-hook.js (manifest: world MAIN,
 *       document_start). Each keeps its own once-per-document flag, so this may be evaluated many
 *       times per load (page started, progress, commit, finished).</li>
 *   <li>{@link #content}: web-android/chrome-shim.js, then extension/content.js (document_idle) exactly
 *       once per document ({@code window.__redphoneContentLoaded}). content.js is unchanged; it gets
 *       the shim as a lexically scoped {@code chrome}, so the page itself never sees a fake
 *       {@code window.chrome.runtime} (on the desktop the extension runtime is invisible to the page,
 *       too) and page code cannot swap the runtime out from under content.js.</li>
 * </ul>
 * Both re-check {@code location} in the page, so a navigation racing the evaluate call can never
 * run them on another origin.
 */
public final class ScriptBundle {
    private ScriptBundle() {
    }

    /** Statement that returns from the enclosing function unless the document is a provider page. */
    static String hostGuard() {
        return "var __rpLoc = window.location;\n"
                + "if (!__rpLoc || __rpLoc.protocol !== \"https:\" || (__rpLoc.hostname !== \""
                + WebPolicy.CHATGPT_HOST + "\" && __rpLoc.hostname !== \"" + WebPolicy.GROK_HOST + "\")) return;\n";
    }

    private static void appendGuarded(StringBuilder b, String src) {
        if (src == null || src.isEmpty()) return;
        // The newline before ";}" ends a trailing // comment; the ";" ends a trailing expression.
        b.append("try {\n").append(src).append("\n;} catch (__rpErr) {}\n");
    }

    /** Early (document_start) script, or null if neither source is available. */
    public static String early(String androidVoiceJs, String pageHookJs) {
        if ((androidVoiceJs == null || androidVoiceJs.isEmpty()) && (pageHookJs == null || pageHookJs.isEmpty())) {
            return null;
        }
        StringBuilder b = new StringBuilder(256 + len(androidVoiceJs) + len(pageHookJs));
        b.append("(function () {\n").append(hostGuard());
        appendGuarded(b, androidVoiceJs);
        appendGuarded(b, pageHookJs);
        b.append("})();\n");
        return b.toString();
    }

    /** document_idle script (shim + content.js), or null if a source is missing. */
    public static String content(String chromeShimJs, String contentJs) {
        if (chromeShimJs == null || chromeShimJs.isEmpty() || contentJs == null || contentJs.isEmpty()) return null;
        StringBuilder b = new StringBuilder(512 + chromeShimJs.length() + contentJs.length());
        b.append("(function () {\n").append(hostGuard());
        b.append("if (window.__redphoneContentLoaded) return;\n");
        appendGuarded(b, chromeShimJs);
        b.append("var __rpChrome = window.__redphoneChrome;\n");
        b.append("if (!__rpChrome || !__rpChrome.runtime || typeof __rpChrome.runtime.sendMessage !== \"function\") return;\n");
        b.append("try { Object.defineProperty(window, \"__redphoneContentLoaded\", { value: true }); } catch (__rpErr) { return; }\n");
        b.append("(function (chrome) {\n").append(contentJs).append("\n}).call(window, __rpChrome);\n");
        b.append("})();\n");
        return b.toString();
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }
}
