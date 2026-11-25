package io.github.arcioth.redphone;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

import io.github.arcioth.redphone.ui.Background;
import io.github.arcioth.redphone.ui.DeckBridge;
import io.github.arcioth.redphone.ui.DeckChromeClient;
import io.github.arcioth.redphone.ui.DeckClient;
import io.github.arcioth.redphone.ui.DeckHost;
import io.github.arcioth.redphone.ui.DeckRoutes;
import io.github.arcioth.redphone.ui.SystemScreens;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The Red Phone deck: the desktop's 560x600 Chromium --app popup (http://127.0.0.1:47621/?view=popup)
 * as an activity. The unchanged ui/index.html runs in a WebView on the virtual origin
 * https://redphone.local/ with android.js/android.css injected (SPACEBAR · CRADLE pad, ANDROID SETTINGS,
 * phone layout); its /api/* calls go through the in-process RedPhoneAndroid bridge to ApiRouter.
 * On top sits an empty "orb host" the voice browser (ChatGPT / Grok) borrows.
 *
 * <p>While the deck is focused, a hardware keyboard Spacebar is the cradle and media keys are the
 * handset button (HookInput). Back only sends the deck to the background: calls keep running.
 */
public class MainActivity extends Activity implements DeckHost {

    private static final int BG = 0xFF0B0709;
    private static final String BRIDGE_NAME = "RedPhoneAndroid";
    /** Renderer deaths tolerated per minute before the deck gives up and shows the fallback text. */
    private static final int MAX_RENDERER_RESTARTS = 3;
    private static final long RENDERER_WINDOW_MS = 60000L;

    private Handler main;
    private Controller controller;
    private FrameLayout root;
    private FrameLayout orbHost;
    private WebView deck;
    private DeckBridge bridge;
    private DeckClient client;
    private TextView fallback;

    /** Read by the background key/refresh tasks. */
    private volatile boolean started;
    private volatile boolean destroyed;
    private boolean keepScreenOn;
    private boolean rebuildDeckOnStart;
    private int rendererRestarts;
    private long rendererWindowStart;
    private volatile boolean settingsPanelOpen;
    private volatile long lastUserInteraction;

    /** Latest snapshot JSON waiting for the UI thread (pushes are coalesced to one per frame). */
    private final AtomicReference<String> pendingPush = new AtomicReference<String>();
    private volatile String latestState = "";

    private final Runnable flushPush = new Runnable() {
        @Override
        public void run() {
            String json = pendingPush.getAndSet(null);
            applyKeepScreenOn(latestState);
            if (json != null && deck != null) {
                deck.evaluateJavascript(DeckRoutes.pushScript(json), null);
            }
        }
    };

    /** Runs on whatever thread changed the state, with the controller lock held: only queue + post. */
    private final Controller.Listener listener = new Controller.Listener() {
        @Override
        public void onState(JSONObject snapshot) {
            queuePush(snapshot);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        main = new Handler(Looper.getMainLooper());
        Popup.onCreated(this);
        configureWindow();
        controller = RedPhoneApp.get().controller();

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        orbHost = new FrameLayout(this);
        buildDeck();
        root.addView(orbHost, matchParent());
        setContentView(root);

        try {
            controller.voice.attachHost(orbHost);
        } catch (RuntimeException e) {
            RpLog.log("deck: voice host attach failed: " + e);
        }
        RedPhoneService.start(this);
        handleIntent(getIntent());
        if (savedInstanceState == null) SystemScreens.requestInitialPermissions(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        Popup.onStarted(this);
        if (deck == null && rebuildDeckOnStart) {
            rebuildDeckOnStart = false;
            buildDeck();
        }
        controller.addListener(listener);
        pushSnapshotAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Popup.onResumed(this);
        // Coming back from a permission prompt or a settings screen: re-read permissions now.
        AndroidStatus.invalidate();
        RedPhoneService.setUiVisible(this, true);
        if (deck != null) deck.onResume();
        pushSnapshotAsync();
    }

    @Override
    protected void onPause() {
        Popup.onPaused(this);
        RedPhoneService.setUiVisible(this, false);
        if (deck != null) deck.onPause(); // animations only; JS (and the voice WebView) keep running
        super.onPause();
    }

    @Override
    protected void onStop() {
        controller.removeListener(listener);
        started = false;
        Popup.onStopped(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        controller.removeListener(listener);
        main.removeCallbacks(flushPush);
        try {
            controller.voice.detachHost(orbHost);
        } catch (RuntimeException e) {
            RpLog.log("deck: voice host detach failed: " + e);
        }
        destroyDeck();
        Popup.onDestroyed(this);
        super.onDestroy();
    }

    /** The manifest declares configChanges: rotation, keyboard (cover) attach etc. just re-lay out. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        AndroidStatus.invalidate();
        // Lets the service upgrade its foreground type to microphone once RECORD_AUDIO is granted.
        RedPhoneService.start(this);
        final Controller c = controller;
        Background.run(new Runnable() {
            @Override
            public void run() {
                c.refresh();
            }
        });
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        lastUserInteraction = SystemClock.uptimeMillis();
    }

    /** uptimeMillis of the last touch / key the user gave the deck (0 = none). Used by Popup.close. */
    long lastUserInteraction() {
        return lastUserInteraction;
    }

    // ------------------------------------------------------------------ keys: THE SPACEBAR CRADLE

    /**
     * Spacebar = cradle hook-switch, media keys = handset button (USB/Bluetooth keyboards, tablet
     * keyboard covers, Chromebooks). Consumed here so Space never clicks a focused button or scrolls.
     * The controller call runs on the deck's background thread (in order), not on the main thread.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null) {
            int code = event.getKeyCode();
            if (HookInput.isSpace(code) || HookInput.isHandsetButton(code)) {
                final KeyEvent copy = new KeyEvent(event);
                final Controller c = controller;
                Background.run(new Runnable() {
                    @Override
                    public void run() {
                        boolean first = copy.getRepeatCount() == 0;
                        HookInput.handle(copy);
                        // The controller does not broadcast a bare "handset down": light the pad now.
                        if (first && started) queuePush(c.snapshot());
                    }
                });
                // Typing in the voice browser (e.g. the ChatGPT / Grok sign-in form): like the desktop's
                // non-grabbing evdev reader, the space also reaches the page. A typed space is a short
                // tap, below the 0.35 s dial / 0.45 s hang-up thresholds.
                if (HookInput.isSpace(code) && voiceTextFieldFocused()) return super.dispatchKeyEvent(event);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** true if the focused view is an editable field inside the voice browser's host (not the deck). */
    private boolean voiceTextFieldFocused() {
        View f = getCurrentFocus();
        if (f == null || f == deck || orbHost == null || !f.onCheckIsTextEditor()) return false;
        for (ViewParent p = f.getParent(); p != null; p = p.getParent()) {
            if (p == orbHost) return true;
        }
        return false;
    }

    /** Back never ends a call (the desktop popup could be closed too): it only hides the deck. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        try {
            if (controller.voice.onBackPressed()) return;
        } catch (RuntimeException e) {
            RpLog.log("deck: voice back failed: " + e);
        }
        if (settingsPanelOpen && deck != null) {
            settingsPanelOpen = false;
            deck.evaluateJavascript("window.__redphoneBack&&window.__redphoneBack()", null);
            return;
        }
        moveTaskToBack(true);
    }

    // ------------------------------------------------------------------ DeckHost

    @Override
    public void openSystemScreen(final String target) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                SystemScreens.open(MainActivity.this, target);
            }
        });
    }

    @Override
    public void onSettingsPanel(boolean open) {
        settingsPanelOpen = open;
    }

    @Override
    public void onDeckRendererGone(WebView view, boolean crashed) {
        if (view != deck) {
            discardWebView(view);
            return;
        }
        destroyDeck();
        if (isFinishing() || isDestroyed()) return;
        long now = SystemClock.elapsedRealtime();
        if (rendererWindowStart == 0 || now - rendererWindowStart > RENDERER_WINDOW_MS) {
            rendererWindowStart = now;
            rendererRestarts = 0;
        }
        if (++rendererRestarts > MAX_RENDERER_RESTARTS) {
            RpLog.log("deck: renderer keeps dying, giving up");
            showFallback("The deck's web renderer keeps crashing.\n\nThe Spacebar cradle and the handset button "
                    + "still work. Reopen Red Phone to try again.");
            return;
        }
        if (started) {
            buildDeck();
        } else {
            // Killed for memory in the background: rebuild when the deck is visible again.
            rebuildDeckOnStart = true;
        }
    }

    // ------------------------------------------------------------------ deck WebView

    private void buildDeck() {
        WebView w = createDeckWebView();
        if (w == null) {
            showFallback("Red Phone needs Android System WebView to show the deck.\n\nInstall or enable "
                    + "\"Android System WebView\", then reopen Red Phone. The Spacebar cradle and the handset "
                    + "button still work.");
            return;
        }
        if (fallback != null) {
            root.removeView(fallback);
            fallback = null;
        }
        deck = w;
        root.addView(w, 0, matchParent());
        w.loadUrl(DeckRoutes.START_URL);
    }

    private WebView createDeckWebView() {
        WebView w;
        try {
            w = new WebView(this);
        } catch (RuntimeException e) {
            // MissingWebViewPackageException (wrapped) while the WebView package is missing/updating.
            RpLog.log("deck: WebView unavailable: " + e);
            return null;
        }
        w.setBackgroundColor(BG); // before the first frame: no white flash
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        w.setFocusable(true);
        w.setFocusableInTouchMode(true);

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        disableFileUrlAccess(s);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        // The deck's px layout is tuned to fit the screen; system font scaling would push it off-screen.
        s.setTextZoom(100);
        // Nothing here comes from the network (virtual origin served from assets).
        s.setSafeBrowsingEnabled(false);

        client = new DeckClient(getApplicationContext().getAssets(), this);
        w.setWebViewClient(client);
        w.setWebChromeClient(new DeckChromeClient());
        bridge = new DeckBridge(this);
        w.addJavascriptInterface(bridge, BRIDGE_NAME); // the deck WebView only
        return w;
    }

    @SuppressWarnings("deprecation")
    private static void disableFileUrlAccess(WebSettings s) {
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
    }

    private void destroyDeck() {
        if (bridge != null) bridge.detach();
        if (client != null) client.detach();
        bridge = null;
        client = null;
        WebView w = deck;
        deck = null;
        if (w != null) discardWebView(w);
    }

    private static void discardWebView(WebView w) {
        try {
            ViewParent parent = w.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(w);
            w.stopLoading();
            w.removeJavascriptInterface(BRIDGE_NAME);
            w.destroy();
        } catch (RuntimeException e) {
            RpLog.log("deck: WebView teardown failed: " + e);
        }
    }

    private void showFallback(String message) {
        if (fallback == null) {
            fallback = new TextView(this);
            fallback.setGravity(Gravity.CENTER);
            fallback.setTextColor(0xFFF8EAEC);
            fallback.setTypeface(Typeface.MONOSPACE);
            fallback.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24,
                    getResources().getDisplayMetrics()));
            fallback.setPadding(pad, pad, pad, pad);
            fallback.setBackgroundColor(Color.TRANSPARENT);
            root.addView(fallback, 0, matchParent());
        }
        fallback.setText("RED PHONE\n\n" + message);
    }

    // ------------------------------------------------------------------ push + screen-on

    private void queuePush(JSONObject snapshot) {
        if (snapshot == null || destroyed) return;
        latestState = snapshot.optString("state", "");
        if (pendingPush.getAndSet(snapshot.toString()) == null) main.post(flushPush);
    }

    /** Initial / refreshed state for the deck and the screen-on flag, read off the main thread. */
    private void pushSnapshotAsync() {
        final Controller c = controller;
        Background.run(new Runnable() {
            @Override
            public void run() {
                queuePush(c.snapshot());
            }
        });
    }

    /** Keep the screen on while a call rings, is being set up, or is live. UI thread. */
    private void applyKeepScreenOn(String state) {
        boolean on = DeckRoutes.keepsScreenOn(state);
        if (on == keepScreenOn) return;
        keepScreenOn = on;
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ------------------------------------------------------------------ misc

    private void configureWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(BG));
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        // The manifest already says showWhenLocked/turnScreenOn (API 27+ attributes); belt and braces.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            addLegacyLockScreenFlags(w);
        }
    }

    @SuppressWarnings("deprecation")
    private static void addLegacyLockScreenFlags(Window w) {
        w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            RpLog.log("deck: USB device attached");
            RedPhoneService.start(this); // its UsbMonitor scans right away
        }
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
