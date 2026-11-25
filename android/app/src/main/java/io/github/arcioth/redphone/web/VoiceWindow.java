package io.github.arcioth.redphone.web;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.MutableContextWrapper;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import io.github.arcioth.redphone.Providers;
import io.github.arcioth.redphone.RpLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The voice WebView itself: the Android replacement for the Chromium "redphone-voice" app window
 * (daemon.launch_voice_browser) plus the MV3 extension runtime. MAIN THREAD ONLY, except
 * {@link #isFullScreen()}; {@code io.github.arcioth.redphone.VoiceBrowser} does the thread hopping.
 *
 * <p>Placement. The WebView is created with a {@link MutableContextWrapper} over the application
 * context, so it survives the deck Activity being recreated. It sits inside the host's
 * {@link VoiceFrame} only while it is shown (ORB / FULL) in a host whose window is visible. Otherwise
 * it is "parked": detached from every window and laid out at the orb size. Chromium treats a WebView
 * that is detached and not paused as visible ({@code BrowserViewRenderer::IsClientVisible}), but one
 * that is attached to a stopped window or set INVISIBLE as hidden, and then throttles its timers and
 * fires visibilitychange. Parking is therefore this app's version of the desktop's
 * --disable-renderer-backgrounding / --disable-backgrounding-occluded-windows flags: a hidden voice
 * tab (HIDE WHOLE BROWSER, deck in the background, screen off) keeps running at full speed, with
 * audio. {@link WebView#onPause()} and {@link WebView#pauseTimers()} are never called.
 */
public final class VoiceWindow {

    public interface Callbacks {
        /** JavaBridge thread: controller.extCommand().toString(). */
        String extCommand();

        /** Any thread, must not block: controller.onExtHeartbeat on a background thread. */
        void heartbeat(String provider, String status, String detail);

        /** Run {@code task} on a background thread. */
        void background(Runnable task);

        /** Main thread: decide where a collapsed full page goes; calls {@code target} on the main thread. */
        void resolveCollapse(CollapseTarget target);

        /** Main thread: the page for {@code provider} is gone for good (no WebView / renderer crash loop). */
        void onGone(String provider);

        /** Main thread, must not block: bring the deck forward so a full page can be seen (desktop focus). */
        void bringDeckToFront();
    }

    public interface CollapseTarget {
        void apply(boolean toOrb);
    }

    public static final int HIDDEN = 0;
    public static final int ORB = 1;
    public static final int FULL = 2;

    /** --bg-deep of the deck; also the WebView background so nothing flashes white. */
    static final int BG = 0xFF0B0709;

    private static final String TAG = "voice browser: ";
    private static final long FIRST_SHOW_FALLBACK_MS = 4000;
    private static final long AUTH_DEBOUNCE_MS = 1500;
    private static final int MAX_RECREATES = 3;
    private static final long RECREATE_WINDOW_MS = 120000;
    private static final String MIC_DETAIL = "Allow microphone access for Red Phone";
    private static final String NO_WEBVIEW_DETAIL = "Android System WebView is unavailable";

    /** One WebView instance's page state; fields read by the JavaBridge thread are volatile. */
    static final class Session implements VoiceBridge.Gate {
        final String provider;
        final String homeUrl;
        volatile boolean alive = true;
        /** Provider of the committed top-level https://chatgpt.com or https://grok.com page, else null. */
        volatile String trusted;
        VoiceBridge bridge;
        boolean firstShowPending;
        int firstShowMode;
        boolean loadFailed;
        int retries;
        Runnable retryTask;
        String lastAuthUrl;
        long lastAuthAt;

        Session(String provider, String homeUrl) {
            this.provider = provider;
            this.homeUrl = homeUrl;
        }

        @Override
        public String trustedProvider() {
            return alive ? trusted : null;
        }
    }

    private final Context app;
    private final Handler main;
    private final Callbacks cb;
    private final long[] recreatedAt = new long[MAX_RECREATES];
    private int recreateCursor;

    private boolean scriptsLoaded;
    private String earlyJs;
    private String contentJs;
    private CookieManager cookies;

    private WebView web;
    private MutableContextWrapper wrapper;
    private Session session;
    private int mode = HIDDEN;
    private volatile int modeMirror = HIDDEN;
    private int modeSeq;

    private FrameLayout host;
    private VoiceFrame root;
    private AlertDialog dialog;
    private int lastOrbInnerPx = -1;

    private final Runnable layoutTask = new Runnable() {
        @Override
        public void run() {
            layout();
        }
    };

    private final VoiceBridge.Sink bridgeSink = new VoiceBridge.Sink() {
        @Override
        public String extCommand() {
            return cb.extCommand();
        }

        @Override
        public void heartbeat(String provider, String status, String detail) {
            cb.heartbeat(provider, status, detail);
        }
    };

    private final VoiceFrame.Listener frameListener = new VoiceFrame.Listener() {
        @Override
        public void onWindowVisibility(VoiceFrame frame, boolean visible) {
            if (frame != root) return;
            if (visible) {
                main.removeCallbacks(layoutTask);
                main.post(layoutTask);
            } else {
                // Synchronously, before the WebView itself hears that its window went away.
                rememberOrbSize();
                placeWeb(false);
            }
        }

        @Override
        public void onDetached(final VoiceFrame frame) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (frame == root && !frame.isAttachedToWindow()) {
                        RpLog.log(TAG + "host window went away without detachHost");
                        detachHostInternal();
                    }
                }
            });
        }

        @Override
        public void onDeckPressed(VoiceFrame frame) {
            if (frame == root) collapse();
        }
    };

    public VoiceWindow(Context appContext, Handler mainHandler, Callbacks callbacks) {
        this.app = appContext;
        this.main = mainHandler;
        this.cb = callbacks;
    }

    // ------------------------------------------------------------------ contract (main thread)

    /** True while the page fills the host (sign-in / VOICE TAB). Any thread. */
    public boolean isFullScreen() {
        return modeMirror == FULL;
    }

    /** daemon.launch_voice_browser spawn: create if needed (closing another provider's page) and load. */
    public void load(String provider, String url, boolean focus) {
        if (provider == null || url == null) return;
        if (web != null && !provider.equals(session.provider)) {
            RpLog.log(TAG + "closing " + session.provider + " for " + provider);
            teardown(false);
        }
        if (web == null && !create(provider, url)) {
            setMode(HIDDEN);
            cb.onGone(provider);
            cb.heartbeat(provider, "loading", NO_WEBVIEW_DETAIL);
            return;
        }
        final Session s = session;
        setMode(HIDDEN);
        // daemon arrange_later(): focus ? show : hide, once the page is there.
        s.firstShowPending = true;
        s.firstShowMode = focus ? FULL : HIDDEN;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (session == s && s.firstShowPending) firstShow(s);
            }
        }, FIRST_SHOW_FALLBACK_MS);
        loadInto(s, url);
    }

    /** daemon._show_voice_browser: full=false -> orb panel, full=true -> whole host. */
    public void show(boolean full) {
        if (web == null) {
            RpLog.log(TAG + "nothing to show");
            return;
        }
        session.firstShowPending = false;
        setMode(full ? FULL : ORB);
    }

    /** daemon._hide_voice_browser: out of sight, still running (page, mic and audio). */
    public void hide() {
        if (session != null) session.firstShowPending = false;
        setMode(HIDDEN);
        flushCookies();
        returnHomeIfStray();
    }

    /** daemon.close_voice_browser. */
    public void close() {
        if (web != null) {
            RpLog.log(TAG + "closed " + session.provider);
            teardown(false);
        }
        setMode(HIDDEN);
        flushCookies();
    }

    public void attachHost(FrameLayout h) {
        if (h == null) return;
        if (h == host && root != null && root.getParent() == h) {
            layout();
            return;
        }
        if (host != null) detachHostInternal();
        host = h;
        // Dialogs, <select> popups and permission UI need the Activity.
        if (wrapper != null) wrapper.setBaseContext(h.getContext());
        VoiceFrame f = new VoiceFrame(h.getContext(), frameListener);
        root = f;
        h.addView(f, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        layout();
    }

    public void detachHost(FrameLayout h) {
        if (h == null || h != host) return;
        detachHostInternal();
    }

    /** Back while the page is full screen collapses it, like the DECK button. */
    public boolean onBackPressed() {
        if (mode != FULL || web == null) return false;
        collapse();
        return true;
    }

    // ------------------------------------------------------------------ visibility / placement

    private void setMode(int m) {
        int prev = mode;
        if (prev == FULL && m != FULL) hideImeFor(web);
        mode = m;
        modeMirror = m;
        modeSeq++;
        layout();
        if (m == FULL && web != null && !hostVisible()) cb.bringDeckToFront();
    }

    private void firstShow(Session s) {
        s.firstShowPending = false;
        setMode(s.firstShowMode);
    }

    /** ◀ DECK / Back: to the orb panel if the call is live (and not HIDE WHOLE BROWSER), else hidden. */
    private void collapse() {
        if (mode != FULL || web == null) return;
        final int seq = modeSeq;
        cb.resolveCollapse(new CollapseTarget() {
            @Override
            public void apply(boolean toOrb) {
                if (seq != modeSeq || mode != FULL || web == null) return;
                if (toOrb) {
                    show(false);
                } else {
                    hide();
                }
            }
        });
    }

    private boolean hostVisible() {
        return root != null && root.isAttachedToWindow() && root.getWindowVisibility() == View.VISIBLE;
    }

    private void layout() {
        boolean inPanel = web != null && mode != HIDDEN && hostVisible();
        if (root != null) {
            rememberOrbSize();
            root.setMode(inPanel ? mode : HIDDEN);
            if (inPanel && host != null && host.indexOfChild(root) != host.getChildCount() - 1) root.bringToFront();
        }
        placeWeb(inPanel);
        if (inPanel) {
            web.onResume();
            if (mode == FULL && !web.hasFocus()) web.requestFocus();
        }
    }

    private void placeWeb(boolean inPanel) {
        WebView v = web;
        if (v == null) return;
        ViewParent parent = v.getParent();
        if (inPanel && root != null) {
            FrameLayout target = root.panel();
            if (parent == target) return;
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(v);
            target.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(v);
        // Hidden pages get the orb viewport (desktop: 340x340 window). A page parked only because the
        // deck went to the background keeps its size so a sign-in form is not re-laid out.
        if (mode == HIDDEN || v.getWidth() <= 0 || v.getHeight() <= 0) sizeDetached(v);
    }

    private void sizeDetached(WebView v) {
        int s = orbInnerPx();
        if (v.getWidth() == s && v.getHeight() == s) return;
        // A detached view is never laid out by a parent; give the page a real viewport ourselves.
        v.measure(View.MeasureSpec.makeMeasureSpec(s, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(s, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, s, s);
    }

    /** Orb viewport for the current host (the VoiceFrame itself keeps stale bounds while GONE). */
    private void rememberOrbSize() {
        FrameLayout h = host;
        if (h == null) return;
        int w = h.getWidth() - h.getPaddingLeft() - h.getPaddingRight();
        int ht = h.getHeight() - h.getPaddingTop() - h.getPaddingBottom();
        if (w <= 0 || ht <= 0) return;
        lastOrbInnerPx = VoiceFrame.orbInnerPx(app.getResources().getDisplayMetrics().density, w, ht);
    }

    private int orbInnerPx() {
        rememberOrbSize();
        if (lastOrbInnerPx > 0) return lastOrbInnerPx;
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        return VoiceFrame.orbInnerPx(dm.density, dm.widthPixels, dm.heightPixels);
    }

    private void detachHostInternal() {
        dismissDialog();
        rememberOrbSize();
        VoiceFrame f = root;
        root = null;
        host = null;
        main.removeCallbacks(layoutTask);
        placeWeb(false);
        if (f != null) {
            f.setListener(null);
            ViewParent p = f.getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(f);
        }
        if (wrapper != null) wrapper.setBaseContext(app);
    }

    private void hideImeFor(WebView v) {
        if (v == null) return;
        IBinder token = v.getWindowToken();
        if (token == null) return;
        InputMethodManager imm = (InputMethodManager) app.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(token, 0);
    }

    // ------------------------------------------------------------------ WebView lifecycle

    private boolean create(String provider, String homeUrl) {
        loadScripts();
        MutableContextWrapper w = new MutableContextWrapper(app);
        if (host != null) w.setBaseContext(host.getContext());
        WebView v;
        try {
            v = new WebView(w);
        } catch (Throwable t) {
            // No / disabled / updating WebView provider (MissingWebViewPackageException etc.).
            RpLog.log(TAG + "cannot create a WebView: " + t);
            return false;
        }
        Session s = new Session(provider, homeUrl);
        try {
            configure(v, s);
        } catch (RuntimeException e) {
            RpLog.log(TAG + "WebView setup failed: " + e);
            try {
                v.destroy();
            } catch (RuntimeException ignored) {
            }
            return false;
        }
        web = v;
        wrapper = w;
        session = s;
        RpLog.log(TAG + "WebView ready for " + provider);
        return true;
    }

    private void configure(WebView v, Session s) {
        v.setBackgroundColor(BG);
        v.setOverScrollMode(View.OVER_SCROLL_NEVER);
        v.setVerticalScrollBarEnabled(false);
        v.setHorizontalScrollBarEnabled(false);
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setContentDescription(Providers.name(s.provider));

        WebSettings ws = v.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        // --autoplay-policy=no-user-gesture-required (also lets WebAudio start without a gesture).
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // window.open / target=_blank (OAuth "popups") load in place instead of a new window.
        ws.setSupportMultipleWindows(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setGeolocationEnabled(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUserAgentString(WebPolicy.chromeLikeUserAgent(ws.getUserAgentString()));

        // Keep the renderer as important as the app (foreground service) even while parked.
        v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(v, true);
        cookies = cm;

        s.bridge = new VoiceBridge(s, bridgeSink);
        v.addJavascriptInterface(s.bridge, VoiceBridge.NAME);
        v.setWebViewClient(new Client(s));
        v.setWebChromeClient(new Chrome(s));
    }

    private void loadInto(Session s, String url) {
        if (session != s || web == null) return;
        cancelRetry(s);
        web.loadUrl(url);
    }

    private void teardown(boolean rendererGone) {
        WebView v = web;
        Session s = session;
        MutableContextWrapper w = wrapper;
        web = null;
        session = null;
        wrapper = null;
        if (s != null) {
            s.alive = false;
            s.trusted = null;
            cancelRetry(s);
        }
        dismissDialog();
        if (v != null) {
            hideImeFor(v);
            ViewParent p = v.getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(v);
            if (!rendererGone) {
                // Unload first so the page's microphone / WebRTC stop right away.
                try {
                    v.stopLoading();
                    v.loadUrl("about:blank");
                } catch (RuntimeException ignored) {
                }
                try {
                    v.removeJavascriptInterface(VoiceBridge.NAME);
                } catch (RuntimeException ignored) {
                }
            }
            // After a renderer death destroy() is the only call allowed on the instance.
            try {
                v.destroy();
            } catch (RuntimeException e) {
                RpLog.log(TAG + "destroy failed: " + e);
            }
        }
        if (w != null) w.setBaseContext(app);
    }

    private boolean onRendererGone(WebView view, RenderProcessGoneDetail detail) {
        if (view != web || session == null) {
            ViewParent p = view.getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(view);
            try {
                view.destroy();
            } catch (RuntimeException ignored) {
            }
            return true;
        }
        Session s = session;
        boolean crashed = detail != null && detail.didCrash();
        RpLog.log(TAG + "renderer " + (crashed ? "crashed" : "was killed to free memory") + " (" + s.provider + ")");
        int keep = mode;
        teardown(true);
        if (allowRecreate() && create(s.provider, s.homeUrl)) {
            // Same place on screen; content.js on the fresh page re-joins a live call by itself.
            mode = keep;
            modeMirror = keep;
            modeSeq++;
            layout();
            loadInto(session, s.homeUrl);
        } else {
            RpLog.log(TAG + "giving up on " + s.provider + " after repeated renderer failures");
            setMode(HIDDEN);
            cb.onGone(s.provider);
        }
        // true = the app handled it; returning false would kill the whole app process.
        return true;
    }

    private boolean allowRecreate() {
        long now = SystemClock.elapsedRealtime();
        long oldest = recreatedAt[recreateCursor];
        if (oldest != 0 && now - oldest < RECREATE_WINDOW_MS) return false;
        recreatedAt[recreateCursor] = now;
        recreateCursor = (recreateCursor + 1) % MAX_RECREATES;
        return true;
    }

    private void flushCookies() {
        final CookieManager cm = cookies;
        if (cm == null) return;
        // flush() blocks on disk I/O: never on the main thread.
        cb.background(new Runnable() {
            @Override
            public void run() {
                try {
                    cm.flush();
                } catch (RuntimeException ignored) {
                }
            }
        });
    }

    /**
     * When the page is hidden while it sits on some unrelated site (a help / policy link followed in
     * the full view), go back to the provider so the next call finds its voice controls. Sign-in
     * flows are left alone.
     */
    private void returnHomeIfStray() {
        if (web == null || session == null) return;
        String url = web.getUrl();
        if (!WebPolicy.isWebUrl(url) || WebPolicy.isInjectable(url) || WebPolicy.isLoginFlow(url)) return;
        RpLog.log(TAG + "left on " + WebPolicy.redact(url) + ", back to " + session.homeUrl);
        loadInto(session, session.homeUrl);
    }

    // ------------------------------------------------------------------ scripts

    private void loadScripts() {
        if (scriptsLoaded) return;
        scriptsLoaded = true;
        earlyJs = ScriptBundle.early(readAsset("web-android/android-voice.js"), readAsset("web/page-hook.js"));
        contentJs = ScriptBundle.content(readAsset("web-android/chrome-shim.js"), readAsset("web/content.js"));
        if (contentJs == null) RpLog.log(TAG + "bridge scripts missing: calls cannot start the voice mode");
    }

    private String readAsset(String path) {
        InputStream in = null;
        try {
            in = app.getAssets().open(path);
            ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            RpLog.log(TAG + "missing asset " + path + ": " + e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void evaluate(WebView v, String js) {
        try {
            v.evaluateJavascript(js, null);
        } catch (RuntimeException e) {
            RpLog.log(TAG + "evaluateJavascript failed: " + e);
        }
    }

    /** page-hook.js (+ android-voice.js): as early as WebView allows; each guards itself. */
    private void injectEarly(WebView v, String url) {
        if (earlyJs != null && WebPolicy.isInjectable(url)) evaluate(v, earlyJs);
    }

    /** chrome-shim.js + content.js once per document (document_idle). */
    private void injectContent(WebView v, String url) {
        if (contentJs != null && WebPolicy.isInjectable(url)) evaluate(v, contentJs);
    }

    // ------------------------------------------------------------------ background.js port

    /** chrome.tabs.onUpdated -> notifyAuthIfNeeded(url). */
    private void noteTopUrl(Session s, String url) {
        String prov = WebPolicy.authProviderForUrl(url);
        if (prov == null) return;
        long now = SystemClock.elapsedRealtime();
        // The same URL arrives via shouldOverrideUrlLoading, onPageStarted and doUpdateVisitedHistory.
        if (url.equals(s.lastAuthUrl) && now - s.lastAuthAt < AUTH_DEBOUNCE_MS) return;
        s.lastAuthUrl = url;
        s.lastAuthAt = now;
        cb.heartbeat(prov, "auth_required", WebPolicy.AUTH_DETAIL);
    }

    private void scheduleRetry(final Session s, final String url, String desc) {
        cancelRetry(s);
        long delay = WebPolicy.retryDelayMs(s.retries++);
        cb.heartbeat(s.provider, "loading", "Voice page offline (" + WebPolicy.clip(desc, 60)
                + ") — retrying in " + (delay / 1000) + "s");
        s.retryTask = new Runnable() {
            @Override
            public void run() {
                s.retryTask = null;
                if (session == s && web != null) web.loadUrl(url);
            }
        };
        main.postDelayed(s.retryTask, delay);
    }

    private void cancelRetry(Session s) {
        if (s.retryTask != null) {
            main.removeCallbacks(s.retryTask);
            s.retryTask = null;
        }
    }

    private static boolean retryable(int code) {
        switch (code) {
            case WebViewClient.ERROR_UNSUPPORTED_SCHEME:
            case WebViewClient.ERROR_BAD_URL:
            case WebViewClient.ERROR_FILE:
            case WebViewClient.ERROR_FILE_NOT_FOUND:
            case WebViewClient.ERROR_UNSAFE_RESOURCE:
                return false;
            default:
                return true;
        }
    }

    // ------------------------------------------------------------------ microphone (--use-fake-ui-for-media-stream)

    private boolean hasMicPermission() {
        return app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void handlePermission(Session s, PermissionRequest req) {
        if (req == null) return;
        Uri origin = req.getOrigin();
        String scheme = origin == null ? null : origin.getScheme();
        String h = origin == null ? null : origin.getHost();
        String[] res = req.getResources();
        boolean audioOnly = res != null && res.length > 0;
        if (res != null) {
            for (String r : res) {
                if (!PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audioOnly = false;
            }
        }
        boolean trustedOrigin = s == session && s.trustedProvider() != null
                && "https".equalsIgnoreCase(scheme) && WebPolicy.isProviderDomain(h);
        if (audioOnly && trustedOrigin) {
            if (hasMicPermission()) {
                req.grant(new String[] {PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                RpLog.log(TAG + "microphone granted to " + h);
                return;
            }
            RpLog.log(TAG + "microphone for " + h + " denied: Red Phone lacks RECORD_AUDIO");
            cb.heartbeat(s.provider, "loading", MIC_DETAIL);
        } else {
            RpLog.log(TAG + "denied " + Arrays.toString(res) + " to " + h);
        }
        req.deny();
    }

    // ------------------------------------------------------------------ JS dialogs

    private Activity hostActivity() {
        Context c = host == null ? null : host.getContext();
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** alert()/confirm() while the user looks at the full page; otherwise answered "cancel". */
    private boolean jsDialog(WebView view, String message, final JsResult result, boolean confirm) {
        Activity a = hostActivity();
        if (view != web || mode != FULL || a == null || a.isFinishing() || !hostVisible()) {
            result.cancel();
            return true;
        }
        dismissDialog();
        final boolean[] done = {false};
        AlertDialog.Builder b = new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage(WebPolicy.clip(message, 600))
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (!done[0]) {
                            done[0] = true;
                            result.confirm();
                        }
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface d) {
                        if (!done[0]) {
                            done[0] = true;
                            result.cancel();
                        }
                        if (dialog == d) dialog = null;
                    }
                });
        if (confirm) b.setNegativeButton(android.R.string.cancel, null);
        try {
            dialog = b.show();
        } catch (RuntimeException e) {
            if (!done[0]) {
                done[0] = true;
                result.cancel();
            }
        }
        return true;
    }

    private void dismissDialog() {
        AlertDialog d = dialog;
        dialog = null;
        if (d != null) {
            try {
                d.dismiss();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ clients

    private final class Client extends WebViewClient {
        private final Session s;

        Client(Session s) {
            this.s = s;
        }

        private boolean current(WebView view) {
            return view == web && s == session && s.alive;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req == null ? null : req.getUrl();
            String url = u == null ? null : u.toString();
            if (!WebPolicy.navigationAllowed(url)) {
                RpLog.log(TAG + "blocked navigation to " + WebPolicy.redact(url));
                return true;
            }
            if (req.isForMainFrame() && current(view)) noteTopUrl(s, url);
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (!current(view)) return;
            s.trusted = WebPolicy.providerForUrl(url);
            s.loadFailed = false;
            noteTopUrl(s, url);
            injectEarly(view, url);
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            if (!current(view)) return;
            s.trusted = WebPolicy.providerForUrl(url);
            injectEarly(view, url);
            if (s.firstShowPending) firstShow(s);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            if (!current(view)) return;
            s.trusted = WebPolicy.providerForUrl(url);
            noteTopUrl(s, url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!current(view)) return;
            injectEarly(view, url);
            injectContent(view, url);
            if (!s.loadFailed) s.retries = 0;
            if (s.firstShowPending) firstShow(s);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (req == null || !req.isForMainFrame() || !current(view)) return;
            s.loadFailed = true;
            int code = err == null ? ERROR_UNKNOWN : err.getErrorCode();
            CharSequence d = err == null ? null : err.getDescription();
            String desc = d == null ? "error " + code : d.toString();
            String url = String.valueOf(req.getUrl());
            RpLog.log(TAG + "load failed (" + code + " " + desc + "): " + WebPolicy.redact(url));
            if (retryable(code) && WebPolicy.providerForUrl(url) != null) scheduleRetry(s, url, desc);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            return onRendererGone(view, detail);
        }
    }

    private final class Chrome extends WebChromeClient {
        private final Session s;

        Chrome(Session s) {
            this.s = s;
        }

        @Override
        public void onProgressChanged(WebView view, int progress) {
            // Extra early-injection chances between page start and commit (WebView has no document_start).
            if (progress < 100 && view == web && s == session && s.alive) injectEarly(view, view.getUrl());
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            handlePermission(s, request);
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            return jsDialog(view, message, result, false);
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            return jsDialog(view, message, result, true);
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                JsPromptResult result) {
            result.cancel();
            return true;
        }

        @Override
        public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
            // Never let a "leave site?" prompt block close / provider switches / reloads.
            result.confirm();
            return true;
        }
    }
}
