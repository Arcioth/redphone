package io.github.arcioth.redphone;

import android.content.Context;

import io.github.arcioth.redphone.local.LocalCallSession;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Port of local_call.py (LOCAL AI + EVIL TWIN personas); the implementation lives in
 * {@code io.github.arcioth.redphone.local} ({@link LocalCallSession}).
 *
 * Contract:
 *  - start(): non-blocking; replaces any running session. Pipeline: Android SpeechRecognizer (on-device
 *    preferred) -> Ollama /api/chat streaming (Prefs.ollamaUrl(), model Prefs.OLLAMA_MODEL or auto-pick
 *    like pick_ollama_model) -> Android TextToSpeech rendered to PCM and played on the handset sink.
 *    Same Turkish/English system prompts, openers, sentence streaming and TR/AUTO/EN rules as local_call.py.
 *  - Reports progress exactly like post_heartbeat(): controller.onExtHeartbeat(persona, status, detail)
 *    with status "connecting" while loading, then "connected" + live detail ("Dinliyor (TR)...").
 *  - stop(): idempotent, non-blocking; after it returns no further heartbeats/audio for that session.
 *
 * <p>Threading: start()/stop() are called by the controller with its lock held and only flip state and
 * spawn/signal threads. Each session runs on its own worker; a generation counter makes every older
 * session inert the moment a newer start() or a stop() happens. Heartbeats re-check that generation
 * while holding the controller lock, so none can slip in after stop() returned.
 */
public final class LocalCallEngine {
    private final Context app;
    private final AtomicInteger generation = new AtomicInteger();
    private final Host host = new Host();
    private LocalCallSession current; // guarded by this

    public LocalCallEngine(Context ctx) {
        // Called from the Controller constructor: do not touch RedPhoneApp.controller() here.
        this.app = ctx.getApplicationContext();
    }

    public void start(String persona, String mode, String lang, String sessionId, AudioRouter.PhoneAudio audio) {
        LocalCallSession old;
        LocalCallSession s;
        synchronized (this) {
            old = current;
            int gen = generation.incrementAndGet();
            s = new LocalCallSession(app, host, gen, persona, mode, lang, sessionId, audio);
            current = s;
        }
        if (old != null) old.cancel();
        RpLog.log("local engine start persona=" + persona + " mode=" + mode + " lang=" + lang + " session=" + sessionId);
        s.start();
    }

    public void stop() {
        LocalCallSession old;
        synchronized (this) {
            old = current;
            current = null;
            generation.incrementAndGet();
        }
        if (old != null) {
            old.cancel();
            RpLog.log("local engine stop");
        }
    }

    public boolean isRunning() {
        LocalCallSession s;
        synchronized (this) {
            s = current;
        }
        return s != null && s.isRunning();
    }

    private static Controller controller() {
        RedPhoneApp a = RedPhoneApp.get();
        return a == null ? null : a.controller();
    }

    private static CloneRecorder cloner() {
        Controller c = controller();
        return c == null ? null : c.cloner;
    }

    /** What the session needs from the main package. */
    private final class Host implements LocalCallSession.Host {
        @Override
        public boolean isCurrent(int gen) {
            return generation.get() == gen;
        }

        @Override
        public void heartbeat(LocalCallSession session, String persona, String status, String detail) {
            Controller c = controller();
            if (c == null) return;
            // The controller calls stop() with this same monitor held, so checking liveness inside it makes
            // "no heartbeat after stop()" exact. We hold no other lock here: no lock-order inversion.
            synchronized (c) {
                if (!session.alive()) return;
                c.onExtHeartbeat(persona, status, detail);
            }
        }

        @Override
        public String controllerState() {
            Controller c = controller();
            return c == null ? "" : c.getState();
        }

        @Override
        public double cloneSeconds() {
            CloneRecorder r = cloner();
            return r == null ? 0 : r.durationSeconds();
        }

        @Override
        public float cloneF0() {
            CloneRecorder r = cloner();
            return r == null ? 0f : r.f0Hz();
        }

        @Override
        public File cloneFile() {
            CloneRecorder r = cloner();
            return r == null ? null : r.refFile();
        }

        @Override
        public double saveClone(float[] pcm16k) {
            CloneRecorder r = cloner();
            if (r == null) return 0;
            try {
                return r.saveReference(pcm16k);
            } catch (RuntimeException e) {
                RpLog.log("[local] saveReference failed: " + e);
                return 0;
            }
        }

        @Override
        public String ollamaUrl() {
            return new Prefs(app).ollamaUrl();
        }

        @Override
        public String ollamaModel() {
            return new Prefs(app).getString(Prefs.OLLAMA_MODEL, "");
        }
    }
}
