package io.github.arcioth.redphone.local;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import io.github.arcioth.redphone.AudioRouter;
import io.github.arcioth.redphone.RpLog;
import io.github.arcioth.redphone.VoiceAnalysis;
import io.github.arcioth.redphone.WavIO;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * One LOCAL AI / EVIL TWIN call: the Android port of local_call.py LocalCallRunner.
 *
 * <p>A single worker thread runs the call exactly like run(): load models, post the ready heartbeat,
 * speak the opener, then a half-duplex loop listen -> transcribe -> Ollama (streamed) -> speak. The
 * speech recognizer lives on the main looper ({@link SttEngine}); TTS is rendered to PCM and played on
 * the handset ({@link TtsEngine}, {@link PcmPlayer}).
 *
 * <p>Cancellation ({@link #cancel()}) is non-blocking: it flips the liveness flag, interrupts the worker
 * and lets a shared cleanup thread abort whatever the worker is blocked on (HTTP stream, AudioTrack,
 * AudioRecord, TTS). The worker then releases everything it owns in its finally block.
 */
public final class LocalCallSession implements Liveness, OllamaClient.Tracker {

    /** Services of the main package (the controller and the Evil Twin clone sample). */
    public interface Host {
        /** Is generation {@code gen} still the engine's current session? */
        boolean isCurrent(int gen);

        /**
         * Posts a heartbeat to the controller exactly like local_call.post_heartbeat(). Must re-check
         * {@code session.alive()} under the controller lock so nothing leaks after stop().
         */
        void heartbeat(LocalCallSession session, String persona, String status, String detail);

        String controllerState();

        /** Saved clone sample length (s), its median F0 (Hz) and file (may be null). */
        double cloneSeconds();

        float cloneF0();

        File cloneFile();

        /** Persists an auto-captured 16 kHz reference; returns the seconds saved (0 on failure). */
        double saveClone(float[] pcm16k);

        String ollamaUrl();

        /** Prefs.OLLAMA_MODEL ("" = auto-pick like pick_ollama_model). */
        String ollamaModel();
    }

    private static final int SR = VoiceAnalysis.REF_SAMPLE_RATE;
    private static final String TAG_TR = "tr-TR";
    private static final String TAG_EN = "en-US";

    private static final ExecutorService CLEANUP = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "redphone-local-cleanup");
            t.setDaemon(true);
            return t;
        }
    });

    private final Context app;
    private final Host host;
    private final int gen;
    private final String persona;
    /** The provider id exactly as the controller passed it (heartbeats are matched against it). */
    private final String hbPersona;
    private final String mode;
    private final String langMode;
    private final String sessionId;
    private final AudioRouter.PhoneAudio audio;
    private final boolean evil;
    private final File ttsDir;

    private final SttEngine stt;
    private final PcmPlayer player;
    private final MicCapture mic;
    private final Set<HttpURLConnection> conns = new HashSet<HttpURLConnection>();

    private volatile boolean cancelled;
    private volatile boolean finished;
    private volatile TtsEngine tts;
    private Thread worker;

    // ---- worker-thread state (LocalCallRunner fields)
    private String activeLang;
    private final List<JSONObject> history = new ArrayList<JSONObject>();
    private OllamaClient ollama;
    private String model = CallText.FALLBACK_MODEL;
    private boolean ollamaOk;
    /** Listen-label suffix while Ollama is down or the model is missing (null when fine). */
    private String ollamaIssue;
    private boolean sttAvailable;
    private boolean useOnDevice;
    private boolean detectionOn;
    private boolean downloadRequested;
    private boolean hasClone;
    private double cloneSecs;
    private float userF0;
    private boolean cloning;
    private final Map<String, Float> matchedPitch = new HashMap<String, Float>();
    private String lastStatus;
    private String lastDetail;

    public LocalCallSession(Context app, Host host, int gen, String persona, String mode, String lang,
                            String sessionId, AudioRouter.PhoneAudio audio) {
        this.app = app.getApplicationContext();
        this.host = host;
        this.gen = gen;
        this.persona = "eviltwin".equals(persona) ? "eviltwin" : "local";
        this.hbPersona = persona == null ? this.persona : persona;
        this.mode = "receive".equals(mode) ? "receive" : "call";
        this.langMode = ("tr".equals(lang) || "auto".equals(lang) || "en".equals(lang)) ? lang : "tr";
        this.sessionId = sessionId == null ? "" : sessionId;
        this.audio = audio != null ? audio : new AudioRouter.PhoneAudio(null, null, null);
        this.evil = "eviltwin".equals(this.persona);
        this.activeLang = "en".equals(this.langMode) ? "en" : "tr";
        this.ttsDir = new File(this.app.getCacheDir(), "redphone-tts");
        this.stt = new SttEngine(this.app);
        this.player = new PcmPlayer(this);
        this.mic = new MicCapture(this);
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runWorker();
            }
        }, "redphone-local-" + sessionId);
        worker = t;
        t.start();
    }

    @Override
    public boolean alive() {
        return !cancelled && host.isCurrent(gen);
    }

    public boolean isRunning() {
        return !cancelled && !finished;
    }

    /** Non-blocking and idempotent. May be called with the controller lock held. */
    public void cancel() {
        if (cancelled) return;
        cancelled = true;
        Thread w = worker;
        if (w != null) w.interrupt();
        stt.destroy();
        try {
            CLEANUP.execute(new Runnable() {
                @Override
                public void run() {
                    // execute(): a throwing task would reach the uncaught handler and kill the app.
                    try {
                        player.cut();
                        mic.interrupt();
                        disconnectAll();
                        TtsEngine t = tts;
                        if (t != null) t.stopNow();
                    } catch (Throwable e) {
                        RpLog.log("[local] cleanup error: " + e);
                    }
                }
            });
        } catch (RuntimeException e) {
            RpLog.log("[local] cleanup rejected: " + e);
        }
    }

    // ------------------------------------------------------------------ OllamaClient.Tracker

    @Override
    public boolean register(HttpURLConnection c) {
        synchronized (conns) {
            if (!alive()) return false;
            conns.add(c);
            return true;
        }
    }

    @Override
    public void unregister(HttpURLConnection c) {
        synchronized (conns) {
            conns.remove(c);
        }
    }

    private void disconnectAll() {
        List<HttpURLConnection> copy;
        synchronized (conns) {
            copy = new ArrayList<HttpURLConnection>(conns);
            conns.clear();
        }
        for (HttpURLConnection c : copy) {
            try {
                c.disconnect();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void log(String msg) {
        RpLog.log("[local_call] " + msg);
    }

    /** post_heartbeat(); de-duplicated so a restarting recognizer does not spam the log. */
    private void hb(String status, String detail) {
        if (!alive()) return;
        if (status.equals(lastStatus) && detail.equals(lastDetail)) return;
        lastStatus = status;
        lastDetail = detail;
        host.heartbeat(this, hbPersona, status, detail);
    }

    private boolean isTr() {
        return "tr".equals(activeLang);
    }

    private boolean hasMicPermission() {
        return app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Interruptible sleep in small steps; false once the session is no longer alive. */
    private boolean sleepAlive(long ms) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + ms;
        while (alive()) {
            long left = end - SystemClock.uptimeMillis();
            if (left <= 0) return true;
            Thread.sleep(Math.min(left, 100));
        }
        return false;
    }

    private static long backoff(int streak) {
        return Math.min(4000L, 250L << Math.min(Math.max(streak, 1) - 1, 4));
    }

    private String listenLabel() {
        return CallText.listenLabel(evil && !hasClone && cloning, activeLang)
                + (ollamaIssue == null ? "" : ollamaIssue);
    }

    // ------------------------------------------------------------------ worker

    private void runWorker() {
        try {
            run();
        } catch (InterruptedException e) {
            // stop()
        } catch (Throwable t) {
            // Never let anything escape: an uncaught exception kills the whole app process on Android.
            try {
                log("worker error: " + t);
                hb("connected", "Local AI error — hang up and call again");
            } catch (Throwable ignored) {
            }
        } finally {
            stt.destroy();
            disconnectAll();
            TtsEngine t = tts;
            tts = null;
            if (t != null) t.shutdown();
            finished = true;
            log("session " + sessionId + " ended");
        }
    }

    /** LocalCallRunner.run(). */
    private void run() throws InterruptedException {
        if (!alive()) return; // stopped before the worker got scheduled
        deleteStaleTempFiles();
        try {
            history.add(new JSONObject().put("role", "system").put("content", CallText.systemPrompt(persona, langMode)));
        } catch (JSONException ignored) {
        }

        if (!hasMicPermission()) {
            // Keep the call up and tell the user; continue as soon as the permission is granted.
            hb("connected", CallText.MIC_PERMISSION);
            while (!hasMicPermission()) {
                if (!sleepAlive(1000)) return;
            }
            log("RECORD_AUDIO granted");
        }

        if (!initModels()) return;

        List<String> notices = new ArrayList<String>();
        TtsEngine t = tts;
        if (t == null) {
            notices.add(CallText.NO_TTS);
        } else if (!"en".equals(langMode) && !t.trAvailable()) {
            notices.add(CallText.TR_TTS_MISSING);
        }
        if (!sttAvailable) notices.add(CallText.NO_STT);
        if (!ollamaOk) notices.add(CallText.ollamaUnreachable(ollama.displayUrl()));

        boolean tr = isTr();
        String ready = notices.isEmpty()
                ? CallText.readyMessage(persona, langMode, tr, hasClone ? cloneSecs : 0, model)
                : join(notices, " · ");
        hb("connected", ready);

        // The opener is rendered while the controller still plays the ringback (pickup at >= 2.2 s).
        String openerLabel = null;
        Rendered opener = null;
        if (evil) {
            if (hasClone) {
                openerLabel = CallText.evilSpeaking(tr);
                opener = render(CallText.evilOpener(tr, "receive".equals(mode)), null, true);
            } else {
                openerLabel = CallText.cloneSentencePrompt(tr);
                opener = render(CallText.evilBorrowVoice(tr), activeLang, false);
            }
        } else if ("receive".equals(mode)) {
            openerLabel = CallText.speaking(tr);
            opener = render(CallText.localReceiveOpener(tr), activeLang, false);
        }

        waitControllerConnected();
        if (!sleepAlive(notices.isEmpty() ? 350 : 2500)) return;
        if (openerLabel != null) {
            hb("connected", openerLabel);
            play(opener);
        }
        if (!alive()) return;

        if (evil && !hasClone) autoClone();
        listenLoop();
    }

    /** init_models(): recognizer, TTS (+ Evil Twin voice match) and the Ollama model, while the line dials. */
    private boolean initModels() throws InterruptedException {
        hb("connecting", CallText.LOADING_STT);
        ollama = new OllamaClient(host.ollamaUrl(), this);
        final OllamaProbe probe = new OllamaProbe(ollama, host.ollamaModel());
        Thread pt = new Thread(probe, "redphone-ollama-probe");
        pt.setDaemon(true);
        pt.start();

        sttAvailable = SpeechRecognizer.isRecognitionAvailable(app);
        useOnDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app);
        detectionOn = "auto".equals(langMode) && Build.VERSION.SDK_INT >= 34;
        log("STT available=" + sttAvailable + " onDevice=" + useOnDevice + " langDetect=" + detectionOn
                + " (lang=" + langMode + ")");

        if (evil) hb("connecting", CallText.LOADING_VOICE_MATCH);
        if (!alive()) return false;
        TtsEngine t = new TtsEngine(app, this, ttsDir, "s" + sessionId + "-" + gen + "-");
        tts = t;
        if (!t.init(10000)) {
            if (!alive()) return false;
            log("TTS unavailable");
            tts = null;
            t.shutdown();
        }
        if (!alive()) return false;

        TtsEngine tt = tts;
        if (tt != null) tt.select(activeLang); // loads the voice now rather than on the first reply
        if (evil) {
            double secs = host.cloneSeconds();
            if (secs >= 1.5) {
                hasClone = true;
                cloneSecs = secs;
                userF0 = host.cloneF0();
                if (userF0 <= 0f) userF0 = f0OfFile(host.cloneFile());
                log(String.format(Locale.ROOT, "Loaded saved voice reference: %.2fs (F0 %.0f Hz)", secs, userF0));
            }
            // Measure the TTS voice once (cached per engine + voice) while the dial tone still plays.
            if (tt != null) tt.baseF0();
        }

        probe.await(4000);
        if (!alive()) return false;
        model = probe.model != null ? probe.model : CallText.FALLBACK_MODEL;
        ollamaOk = probe.reachable;
        ollamaIssue = ollamaOk ? null : CallText.OLLAMA_OFFLINE_SUFFIX;
        log("Ollama " + (ollamaOk ? "reachable" : "UNREACHABLE") + " at " + ollama.displayUrl() + ", model " + model);
        if (ollamaOk) startWarmup();
        return true;
    }

    /** pick_ollama_model() on a helper thread (it may take up to the 2.5 s tags timeout). */
    private static final class OllamaProbe implements Runnable {
        private final OllamaClient client;
        private final String pref;
        private final CountDownLatch done = new CountDownLatch(1);
        volatile String model;
        volatile boolean reachable;

        OllamaProbe(OllamaClient client, String pref) {
            this.client = client;
            this.pref = pref == null ? "" : pref.trim();
        }

        @Override
        public void run() {
            try {
                List<String> names = client.listModels();
                reachable = true;
                String picked = CallText.pickModel(names);
                model = !pref.isEmpty() ? pref : (picked != null ? picked : CallText.FALLBACK_MODEL);
            } catch (IOException e) {
                reachable = false;
                model = !pref.isEmpty() ? pref : CallText.FALLBACK_MODEL;
            } catch (RuntimeException e) {
                reachable = false;
                model = !pref.isEmpty() ? pref : CallText.FALLBACK_MODEL;
            } finally {
                done.countDown();
            }
        }

        void await(long ms) throws InterruptedException {
            done.await(ms, TimeUnit.MILLISECONDS);
        }
    }

    /** _warm_ollama on its own thread. */
    private void startWarmup() {
        final OllamaClient c = ollama;
        final String m = model;
        Thread w = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    c.warm(m, evil);
                    if (alive()) log("Ollama warmed: " + m);
                } catch (IOException e) {
                    if (alive()) log("Ollama warm warning: " + e);
                } catch (RuntimeException e) {
                    if (alive()) log("Ollama warm warning: " + e);
                }
            }
        }, "redphone-ollama-warm");
        w.setDaemon(true);
        w.start();
    }

    /** Never talk over the ringback: the controller only switches to CONNECTED >= 2.2 s after dialing. */
    private void waitControllerConnected() throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 8000;
        while (alive() && SystemClock.uptimeMillis() < deadline) {
            if ("connected".equals(host.controllerState())) return;
            Thread.sleep(100);
        }
    }

    // ------------------------------------------------------------------ Evil Twin auto clone

    /**
     * CLONE: AUTO ON SPEAK. The desktop clones from every utterance it transcribes; on Android the
     * recognizer owns the mic, so the first utterance is captured here first (energy VAD), turned into
     * the reference and saved, then the normal recognizer loop starts.
     */
    private void autoClone() throws InterruptedException {
        cloning = true;
        float[] acc = null;
        try {
            for (int attempt = 0; attempt < 3 && alive(); attempt++) {
                hb("connected", listenLabel());
                float[] utt = mic.capture(audio.phoneSource, 25000, new MicCapture.Events() {
                    @Override
                    public void onSpeechStart() {
                        hb("connected", CallText.hearing(isTr()));
                    }
                });
                if (!alive()) return;
                if (utt == null) {
                    String err = mic.lastError();
                    log("auto clone: no sample (" + (err == null ? "no speech" : err) + ")");
                    hb("connected", err == null ? CallText.cloneTooQuiet(isTr()) : "Voice capture error: " + err);
                    sleepAlive(2000);
                    return;
                }
                float[] combined = EnergyVad.concat(acc, utt);
                float[] ref = VoiceAnalysis.prepareReference16k(combined, SR, 6.0);
                if (ref.length >= SR * 1.5) {
                    double secs = host.saveClone(ref);
                    if (secs < 1.5) {
                        secs = Math.round(ref.length * 10.0 / SR) / 10.0;
                        log("clone sample not persisted; using it for this call only");
                    }
                    float f0 = VoiceAnalysis.medianF0(ref, SR);
                    userF0 = f0 > 0f ? f0 : host.cloneF0();
                    hasClone = true;
                    cloneSecs = secs;
                    matchedPitch.clear();
                    log(String.format(Locale.ROOT, "Updated cloned voice ref -> %.2fs (F0 %.0f Hz)",
                            ref.length / (double) SR, userF0));
                    hb("connected", CallText.voiceCloned(isTr(), secs));
                    speakCloned(CallText.evilClonedLine(isTr()));
                    return;
                }
                acc = combined;
            }
        } finally {
            cloning = false;
        }
    }

    private static float f0OfFile(File f) {
        if (f == null || !f.isFile()) return 0f;
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                WavIO.Pcm pcm = WavIO.read(in);
                return VoiceAnalysis.medianF0(pcm.monoFloat(), pcm.sampleRate);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return 0f;
        } catch (RuntimeException e) {
            return 0f;
        }
    }

    // ------------------------------------------------------------------ speaking

    /** speak(): Evil Twin uses the matched voice, Local AI the plain TTS voice. */
    private void speak(String text) throws InterruptedException {
        if (evil) {
            speakCloned(text);
        } else {
            speakPlain(text, activeLang);
        }
    }

    /** speak_cloned(): the caller-pitch-matched voice, or the plain voice while no sample exists. */
    private void speakCloned(String text) throws InterruptedException {
        if (!hasClone) {
            speakPlain(text, null);
            return;
        }
        speakTts(text, null, true);
    }

    /** speak_piper(). */
    private void speakPlain(String text, String lang) throws InterruptedException {
        speakTts(text, lang, false);
    }

    private void speakTts(String raw, String lang, boolean matched) throws InterruptedException {
        play(render(raw, lang, matched));
    }

    /** An utterance rendered to PCM, ready for the handset. */
    private static final class Rendered {
        final WavIO.Pcm pcm;
        final String pitchKey; // non-null for the matched (Evil Twin) voice
        final float pitch;

        Rendered(WavIO.Pcm pcm, String pitchKey, float pitch) {
            this.pcm = pcm;
            this.pitchKey = pitchKey;
            this.pitch = pitch;
        }
    }

    /** speak_piper / speak_cloned up to the audio: language rule, voice, pitch match, synthesis. */
    private Rendered render(String raw, String lang, boolean matched) throws InterruptedException {
        String text = CallText.cleanSpokenText(raw);
        if (text.isEmpty() || !alive()) return null;
        String use = lang != null ? lang : activeLang;
        if ("auto".equals(langMode) && CallText.looksTurkish(text)) use = "tr";
        TtsEngine t = tts;
        if (t == null) {
            log("no TTS engine, cannot say: " + text);
            return null;
        }
        String voiceLang = t.select(use);
        float pitch = 1f;
        float rate = 1f;
        String pitchKey = null;
        if (matched) {
            pitchKey = voiceLang + "|" + t.voiceKey();
            pitch = matchedPitchFor(t, pitchKey);
            rate = 1.05f;
        }
        WavIO.Pcm pcm = t.synthesize(text, pitch, rate, 15000 + 60L * text.length());
        if (pcm == null || !alive()) return null;
        return new Rendered(pcm, pitchKey, pitch);
    }

    /** Plays a rendered utterance on the handset, then keeps the mic closed for the 0.22 s echo tail. */
    private void play(Rendered r) throws InterruptedException {
        if (r == null || !alive()) return;
        boolean played = player.play(r.pcm.samples, r.pcm.sampleRate, r.pcm.channels, audio.phoneSink);
        long doneAt = SystemClock.uptimeMillis();
        if (played && r.pitchKey != null) adaptPitch(r.pitchKey, r.pitch, r.pcm);
        long left = 220 - (SystemClock.uptimeMillis() - doneAt);
        if (left > 0) sleepAlive(left);
    }

    /** tts.setPitch(userF0 / ttsBaseF0) clamped to [0.5, 2.0]. */
    private float matchedPitchFor(TtsEngine t, String key) throws InterruptedException {
        Float cached = matchedPitch.get(key);
        if (cached != null) return cached;
        float p = 1f;
        float base = t.baseF0();
        if (base > 0f && userF0 > 0f) p = clampPitch(userF0 / base);
        log(String.format(Locale.ROOT, "voice match: caller %.0f Hz / TTS %.0f Hz -> pitch %.2f", userF0, base, p));
        matchedPitch.put(key, p);
        return p;
    }

    /**
     * Engines do not map the pitch multiplier linearly to F0: measure what was actually rendered and
     * nudge the multiplier for the next utterance (closed loop, damped).
     */
    private void adaptPitch(String key, float used, WavIO.Pcm pcm) {
        if (key == null || userF0 <= 0f) return;
        float[] mono = pcm.monoFloat();
        // 2.5 s is plenty for a median and keeps this inside the 0.22 s echo-tail pause.
        int max = (int) (pcm.sampleRate * 2.5);
        if (mono.length > max) mono = java.util.Arrays.copyOf(mono, max);
        float got = VoiceAnalysis.medianF0(mono, pcm.sampleRate);
        if (got <= 0f) return;
        float next = clampPitch((float) (used * Math.pow(userF0 / got, 0.7)));
        matchedPitch.put(key, next);
        log(String.format(Locale.ROOT, "voice match: rendered %.0f Hz (target %.0f) pitch %.2f -> %.2f",
                got, userF0, used, next));
    }

    private static float clampPitch(float p) {
        if (Float.isNaN(p) || Float.isInfinite(p)) return 1f;
        return Math.max(0.5f, Math.min(2.0f, p));
    }

    // ------------------------------------------------------------------ Ollama

    /** stream_ollama_and_speak(). */
    private void streamOllamaAndSpeak(String userText) throws InterruptedException {
        try {
            history.add(new JSONObject().put("role", "user").put("content", userText));
        } catch (JSONException ignored) {
        }
        if (history.size() > 17) {
            List<JSONObject> trimmed = new ArrayList<JSONObject>();
            trimmed.add(history.get(0));
            trimmed.addAll(history.subList(history.size() - 16, history.size()));
            history.clear();
            history.addAll(trimmed);
        }
        JSONObject opts = OllamaClient.chatOptions(evil);
        hb("connected", CallText.thinking(isTr()));

        final StringBuilder full = new StringBuilder();
        final StringBuilder buf = new StringBuilder();
        boolean failed = false;
        try {
            if (!ollamaOk) reprobeOllama();
            ollama.chat(model, new JSONArray(history), opts, new OllamaClient.TokenSink() {
                @Override
                public boolean onToken(String tok) throws InterruptedException {
                    if (!alive()) return false;
                    full.append(tok);
                    buf.append(tok);
                    if (!evil) {
                        int cut = CallText.sentenceCut(buf);
                        if (cut > 0) {
                            String sent = buf.substring(0, cut).trim();
                            buf.delete(0, cut);
                            hb("connected", CallText.speaking(isTr()));
                            speak(sent);
                        }
                    }
                    return alive();
                }
            });
            ollamaOk = true;
            ollamaIssue = null;
        } catch (IOException e) {
            if (!alive()) return;
            log("Ollama error: " + e);
            if (isUnreachable(e)) {
                ollamaOk = false;
                ollamaIssue = CallText.OLLAMA_OFFLINE_SUFFIX;
            } else if (isModelMissing(e)) {
                ollamaIssue = CallText.OLLAMA_MODEL_MISSING_SUFFIX;
            }
            if (full.toString().trim().isEmpty()) {
                full.setLength(0);
                full.append(CallText.fallbackLine(isTr()));
                failed = true;
            }
        }
        if (!alive()) return;

        // Desktop quirk fixed: for LOCAL AI the fallback line went to history but was never spoken.
        String tail = (evil || failed) ? full.toString().trim() : buf.toString().trim();
        if (!tail.isEmpty()) {
            hb("connected", CallText.tailSpeaking(evil && hasClone, isTr()));
            speak(tail);
        }
        String reply = full.toString().trim();
        if (!reply.isEmpty()) {
            try {
                history.add(new JSONObject().put("role", "assistant").put("content", reply));
            } catch (JSONException ignored) {
            }
            log("AI (" + activeLang + "): " + reply);
        }
    }

    /** Ollama was down: retry /api/tags this turn and re-pick the model if it was a blind fallback. */
    private void reprobeOllama() throws IOException {
        List<String> names = ollama.listModels();
        ollamaOk = true;
        String pref = host.ollamaModel();
        if (pref != null && !pref.trim().isEmpty()) {
            model = pref.trim();
        } else {
            String picked = CallText.pickModel(names);
            if (picked != null) model = picked;
        }
        log("Ollama back at " + ollama.displayUrl() + ", model " + model);
    }

    /** Ollama answers HTTP 404 "model 'x' not found, try pulling it first". */
    private static boolean isModelMissing(IOException e) {
        String m = e.getMessage();
        return m != null && m.startsWith("HTTP 404");
    }

    private static boolean isUnreachable(IOException e) {
        if (e instanceof ConnectException || e instanceof NoRouteToHostException
                || e instanceof UnknownHostException || e instanceof PortUnreachableException) {
            return true;
        }
        if (e instanceof SocketTimeoutException) {
            String m = e.getMessage();
            return m != null && m.toLowerCase(Locale.ROOT).contains("connect");
        }
        if (e instanceof InterruptedIOException) return false;
        String m = e.getMessage();
        return m != null && (m.contains("ECONNREFUSED") || m.contains("EHOSTUNREACH") || m.contains("ENETUNREACH"));
    }

    // ------------------------------------------------------------------ listening

    private Intent buildIntent() {
        String tag = isTr() ? TAG_TR : TAG_EN;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.getPackageName());
        if (detectionOn && Build.VERSION.SDK_INT >= 34) {
            ArrayList<String> langs = new ArrayList<String>();
            langs.add(TAG_TR);
            langs.add(TAG_EN);
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, langs);
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED);
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, langs);
        }
        return i;
    }

    private static final class Heard {
        final boolean ok;
        final String text;
        final String detected;
        final int error;
        final long elapsedMs;

        Heard(boolean ok, String text, String detected, int error, long elapsedMs) {
            this.ok = ok;
            this.text = text;
            this.detected = detected;
            this.error = error;
            this.elapsedMs = elapsedMs;
        }
    }

    /** One recognition attempt; posts "Dinliyor" when the mic opens and "Seni duyuyor" on speech. */
    private Heard awaitSpeech(int id) throws InterruptedException {
        long start = SystemClock.uptimeMillis();
        long ready = 0;
        long begin = 0;
        long end = 0;
        boolean stopSent = false;
        while (alive()) {
            SttEngine.Event e = stt.poll(100);
            long now = SystemClock.uptimeMillis();
            if (e != null && e.attempt == id) {
                if (e.type == SttEngine.READY) {
                    ready = now;
                    hb("connected", listenLabel());
                } else if (e.type == SttEngine.BEGIN) {
                    begin = now;
                    hb("connected", CallText.hearing(isTr()));
                } else if (e.type == SttEngine.END) {
                    end = now;
                } else if (e.type == SttEngine.RESULT) {
                    return new Heard(true, e.text, e.detected, 0, now - start);
                } else if (e.type == SttEngine.ERROR) {
                    return new Heard(false, null, null, e.error, now - start);
                }
            }
            // Watchdogs: a recognizer that never gets ready, never finalizes, or talks forever.
            if (ready == 0 && now - start > 8000) {
                log("recognizer never became ready");
                return new Heard(false, null, null, SpeechRecognizer.ERROR_CLIENT, now - start);
            }
            if (begin > 0 && end == 0 && !stopSent && now - begin > 12000) {
                stt.stopListening(); // max 12 s utterance, like the desktop
                stopSent = true;
            }
            if ((end > 0 && now - end > 15000) || now - start > 60000) {
                log("recognizer stalled");
                return new Heard(false, null, null, SpeechRecognizer.ERROR_CLIENT, now - start);
            }
        }
        return null;
    }

    /** The mic loop of run(): listen -> transcribe -> stream_ollama_and_speak, half duplex. */
    private void listenLoop() throws InterruptedException {
        int failures = 0;
        while (alive()) {
            try {
                listenTurns();
                return;
            } catch (RuntimeException e) {
                // One bad turn (engine bug, odd recognizer result) must not end the call.
                log("turn error: " + e);
                stt.recreate();
                if (!sleepAlive(backoff(++failures))) return;
            }
        }
    }

    private void listenTurns() throws InterruptedException {
        int streak = 0;
        int quick = 0;
        int hardOnDevice = 0;
        while (alive()) {
            if (!sttAvailable) {
                hb("connected", CallText.NO_STT);
                if (!sleepAlive(5000)) return;
                sttAvailable = SpeechRecognizer.isRecognitionAvailable(app);
                continue;
            }
            Intent intent = buildIntent();
            int id = stt.listen(intent, useOnDevice);
            Heard h = awaitSpeech(id);
            if (h == null || !alive()) return;

            if (h.ok) {
                streak = 0;
                quick = 0;
                hardOnDevice = 0;
                String text = h.text == null ? "" : h.text.trim();
                String det = h.detected != null ? CallText.langOfTag(h.detected)
                        : (CallText.looksEnglish(text) ? "en" : null);
                activeLang = CallText.updateActiveLang(langMode, activeLang, det, text);
                log("STT (det=" + det + ", active=" + activeLang + ", " + h.elapsedMs + "ms): '" + text + "'");
                if (text.length() < 2) continue;
                streamOllamaAndSpeak(text);
                continue;
            }

            int err = h.error;
            switch (err) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    streak = 0;
                    hardOnDevice = 0;
                    if (h.elapsedMs < 600) {
                        quick++; // a recognizer that fails instantly must not spin
                        if (!sleepAlive(Math.min(2000L, 150L * quick))) return;
                    } else {
                        quick = 0;
                    }
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    log("recognizer: insufficient permissions");
                    streak++;
                    if (!hasMicPermission()) {
                        hb("connected", CallText.MIC_PERMISSION);
                        while (!hasMicPermission()) {
                            if (!sleepAlive(1000)) return;
                        }
                    } else {
                        // Red Phone has the permission, so the recognizer app itself lacks it.
                        hb("connected", CallText.STT_MIC_PERMISSION);
                        if (!sleepAlive(Math.max(3000L, backoff(streak)))) return;
                    }
                    stt.recreate();
                    break;
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                    if (detectionOn) {
                        log("recognizer rejected language detection (" + err + "), using active language");
                        detectionOn = false;
                    } else if (useOnDevice) {
                        if (err == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE && !downloadRequested) {
                            downloadRequested = true;
                            stt.triggerModelDownload(intent);
                        }
                        log("on-device recognizer lacks " + (isTr() ? TAG_TR : TAG_EN) + " (" + err + "), using default");
                        useOnDevice = false;
                        stt.recreate();
                    } else {
                        streak++;
                        hb("connected", CallText.sttLanguageUnavailable(isTr() ? TAG_TR : TAG_EN));
                        if (!sleepAlive(Math.max(3000L, backoff(streak)))) return;
                    }
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    streak++;
                    log("recognizer error " + err);
                    if (streak >= 2) hb("connected", CallText.STT_OFFLINE);
                    if (!sleepAlive(backoff(streak))) return;
                    break;
                default: // BUSY, CLIENT, AUDIO, SERVER_DISCONNECTED, TOO_MANY_REQUESTS, watchdog
                    streak++;
                    log("recognizer error " + err + ", recreating");
                    if (streak >= 3) hb("connected", CallText.STT_FAILING);
                    stt.recreate();
                    if (useOnDevice && ++hardOnDevice >= 3) {
                        log("on-device recognizer keeps failing, using default");
                        useOnDevice = false;
                    }
                    long wait = backoff(streak);
                    if (err == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) wait = Math.max(wait, 2000L);
                    if (!sleepAlive(wait)) return;
                    break;
            }
        }
    }

    // ------------------------------------------------------------------ temp files

    private void deleteStaleTempFiles() {
        File[] files = ttsDir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 10 * 60 * 1000L;
        for (File f : files) {
            if (f.isFile() && f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }
}
