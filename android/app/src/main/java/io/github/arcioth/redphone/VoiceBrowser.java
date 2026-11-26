package io.github.arcioth.redphone;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;

import io.github.arcioth.redphone.web.VoiceWindow;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

/**
 * Android replacement for the Chromium "redphone-voice" window + MV3 extension (extension/): one
 * persistent WebView on chatgpt.com / grok.com running the repo's own extension/page-hook.js and
 * extension/content.js through a chrome.runtime shim (assets/web-android/chrome-shim.js), so the
 * provider's voice mode starts by itself, reports heartbeats and shows only the crimson voice orb.
 * The WebView work lives in {@link VoiceWindow} (package io.github.arcioth.redphone.web).
 *
 * <p>Contract (all methods thread-safe, return immediately, do UI work on the main thread):
 * <ul>
 *   <li>exists(provider): a WebView for that provider exists (pre-warmed or live). Answered
 *       synchronously from the requested state, so a load() is visible before the main thread
 *       has created the view.</li>
 *   <li>load(provider, url, focus): create (if needed; another provider's page is closed first) +
 *       load url. Once the first page is up: focus ? show(true) : hide().</li>
 *   <li>show(full): full=false -> centred orb panel (desktop 340x340 window); full=true -> whole host
 *       (sign-in / VOICE TAB) with a "◀ DECK" button. A full page brings the deck forward (desktop:
 *       window focus).</li>
 *   <li>hide(): out of sight but running, mic and audio included (HIDE WHOLE BROWSER / special
 *       workspace). The page stays "visible" to Chromium, so it is never throttled.</li>
 *   <li>close(): destroy the WebView (page audio + mic stop).</li>
 *   <li>attachHost/detachHost: MainActivity lends its overlay container; the WebView survives activity
 *       recreation (application-context WebView re-parented between hosts). Without a host the
 *       requested visibility is remembered and applied on the next attachHost.</li>
 *   <li>onBackPressed(): true if it consumed Back (collapsed a full-screen page).</li>
 *   <li>isFullScreen(): the page currently fills the host (e.g. a sign-in form has the keyboard).</li>
 * </ul>
 *
 * <p>Threading: load/show/hide/close are called with the controller lock held; they only post to
 * the main thread. Controller calls made on behalf of the page (heartbeats, sign-in detection,
 * collapse decisions) run on one background thread, never on the main thread; the bridge's poll
 * runs on WebView's JavaBridge thread.
 */
public final class VoiceBrowser {

    private final Context app;
    private final Handler main;
    private final ExecutorService bg;
    private final VoiceWindow window;
    private final Object lock = new Object();
    /** Provider whose WebView exists or has been requested (guarded by lock). */
    private String liveProvider;

    public VoiceBrowser(Context ctx) {
        Context a = ctx.getApplicationContext();
        app = a != null ? a : ctx;
        main = new Handler(Looper.getMainLooper());
        bg = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "redphone-voice");
                t.setDaemon(true);
                return t;
            }
        });
        // No WebView or controller access here: this runs inside the Controller constructor.
        window = new VoiceWindow(app, main, new Callbacks());
    }

    public boolean exists(String provider) {
        synchronized (lock) {
            return provider != null && provider.equals(liveProvider);
        }
    }

    public void load(final String provider, final String url, final boolean focus) {
        if (provider == null || url == null || url.isEmpty()) return;
        synchronized (lock) {
            liveProvider = provider;
        }
        post(new Runnable() {
            @Override
            public void run() {
                window.load(provider, url, focus);
            }
        });
    }

    public void show(final boolean full) {
        post(new Runnable() {
            @Override
            public void run() {
                window.show(full);
            }
        });
    }

    public void hide() {
        post(new Runnable() {
            @Override
            public void run() {
                window.hide();
            }
        });
    }

    public void close() {
        synchronized (lock) {
            liveProvider = null;
        }
        post(new Runnable() {
            @Override
            public void run() {
                window.close();
            }
        });
    }

    /** Called by MainActivity; runs synchronously when already on the main thread. */
    public void attachHost(final FrameLayout host) {
        onMain(new Runnable() {
            @Override
            public void run() {
                window.attachHost(host);
            }
        });
    }

    /** Called by MainActivity (e.g. onDestroy); runs synchronously when already on the main thread. */
    public void detachHost(final FrameLayout host) {
        onMain(new Runnable() {
            @Override
            public void run() {
                window.detachHost(host);
            }
        });
    }

    public boolean onBackPressed() {
        if (isMainThread()) {
            try {
                return window.onBackPressed();
            } catch (RuntimeException e) {
                RpLog.log("voice browser: back failed: " + e);
                return false;
            }
        }
        if (!window.isFullScreen()) return false;
        post(new Runnable() {
            @Override
            public void run() {
                window.onBackPressed();
            }
        });
        return true;
    }

    /** True while the voice page fills the deck (sign-in / VOICE TAB). Any thread. */
    public boolean isFullScreen() {
        return window.isFullScreen();
    }

    // ------------------------------------------------------------------ plumbing

    private static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    private static Runnable guarded(final Runnable r) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    RpLog.log("voice browser: " + e);
                }
            }
        };
    }

    private void post(Runnable r) {
        main.post(guarded(r));
    }

    private void onMain(Runnable r) {
        if (isMainThread()) {
            guarded(r).run();
        } else {
            post(r);
        }
    }

    private void background(Runnable r) {
        try {
            bg.execute(guarded(r));
        } catch (RejectedExecutionException e) {
            RpLog.log("voice browser: background task rejected: " + e);
        }
    }

    /** Resolved lazily: this object is built inside RedPhoneApp.controller(). Never call on the main thread. */
    private static Controller controller() {
        RedPhoneApp a = RedPhoneApp.get();
        return a == null ? null : a.controller();
    }

    private final class Callbacks implements VoiceWindow.Callbacks {
        @Override
        public String extCommand() {
            Controller c = controller();
            return c == null ? "{}" : c.extCommand().toString();
        }

        @Override
        public void heartbeat(final String provider, final String status, final String detail) {
            background(new Runnable() {
                @Override
                public void run() {
                    Controller c = controller();
                    if (c != null) c.onExtHeartbeat(provider, status, detail);
                }
            });
        }

        @Override
        public void background(Runnable task) {
            VoiceBrowser.this.background(task);
        }

        @Override
        public void resolveCollapse(final VoiceWindow.CollapseTarget target) {
            background(new Runnable() {
                @Override
                public void run() {
                    Controller c = controller();
                    final boolean toOrb = c != null && Controller.CONNECTED.equals(c.getState()) && !c.isHideVoice();
                    post(new Runnable() {
                        @Override
                        public void run() {
                            target.apply(toOrb);
                        }
                    });
                }
            });
        }

        @Override
        public void onGone(String provider) {
            synchronized (lock) {
                if (provider != null && provider.equals(liveProvider)) liveProvider = null;
            }
        }

        @Override
        public void bringDeckToFront() {
            // Popup.open is callable from any thread (the controller calls it with its lock held).
            background(new Runnable() {
                @Override
                public void run() {
                    Popup.open(app);
                }
            });
        }
    }
}
