package io.github.arcioth.redphone;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Port of {@code RedPhoneController} from daemon.py: the Red Phone state machine.
 *
 * <p>States: unplugged, idle, armed, dialing, ringing, connecting, connected, auth_required.
 * Every public method is {@code synchronized} (the daemon's RLock); timers run on a private
 * HandlerThread (the daemon's threading.Timer). Collaborators must never block or call back
 * into the controller synchronously from inside a listener.
 */
public final class Controller {

    public interface Listener {
        /** Called (possibly on any thread, with the controller lock held) after every state change. Must not block. */
        void onState(JSONObject snapshot);
    }

    public static final String UNPLUGGED = "unplugged";
    public static final String IDLE = "idle";
    public static final String ARMED = "armed";
    public static final String DIALING = "dialing";
    public static final String RINGING = "ringing";
    public static final String CONNECTING = "connecting";
    public static final String CONNECTED = "connected";
    public static final String AUTH_REQUIRED = "auth_required";

    /** daemon: lift after the Spacebar was held >= 0.35 s dials (CALL mode). */
    static final double LIFT_DIAL_MIN_HELD = 0.35;
    /** daemon: handset resting on the Spacebar >= 0.45 s hangs up. */
    static final double CRADLE_HANGUP_HELD = 0.45;
    static final long CRADLE_TICK_MS = 100;

    private final Context ctx;
    private final Prefs prefs;
    private final Handler handler;
    private final Random random = new Random();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    final SoundPlayer sound;
    final AudioRouter audio;
    final LocalCallEngine local;
    final VoiceBrowser voice;
    final CloneRecorder cloner;

    private String mode;
    private String provider;
    private Object rcvDelay; // "random" or Integer seconds
    private String lang;
    private boolean hideVoice;

    private boolean hardwarePresent;
    private boolean plugged;
    private String state;
    private String statusDetail;
    private String sessionId = "";
    private boolean wantActive;
    private boolean recordingClone;

    private double dialStartedAt;
    private double connectedAt;
    private double ringStartedAt;
    private double armedUntil;
    private boolean armedIsFixed;

    private Runnable rcvTimer;
    private Runnable missedTimer;
    private Runnable pickupTimer;
    private Runnable cradleTick;
    private boolean cradleAnnounced;

    private boolean onHook;
    private double spaceDownSince;

    Controller(Context context) {
        this.ctx = context.getApplicationContext();
        this.prefs = new Prefs(ctx);
        HandlerThread ht = new HandlerThread("redphone-controller");
        ht.start();
        this.handler = new Handler(ht.getLooper());

        this.mode = prefs.getString(Prefs.MODE, "call");
        if (!"call".equals(mode) && !"receive".equals(mode)) mode = "call";

        this.provider = prefs.getString(Prefs.PROVIDER, "local");
        if (!Providers.exists(provider)) {
            provider = "local";
            prefs.putString(Prefs.PROVIDER, provider);
        }
        this.rcvDelay = parseDelay(prefs.getString(Prefs.RCV_DELAY, "random"));
        this.lang = prefs.getString(Prefs.LANG, "tr");
        if (!"tr".equals(lang) && !"auto".equals(lang) && !"en".equals(lang)) lang = "tr";
        this.hideVoice = "1".equals(prefs.getString(Prefs.HIDE_VOICE, "0"));

        this.plugged = false;
        this.state = UNPLUGGED;
        this.statusDetail = "Unplugged";

        this.sound = new SoundPlayer(ctx);
        this.audio = new AudioRouter(ctx);
        this.local = new LocalCallEngine(ctx);
        this.voice = new VoiceBrowser(ctx);
        this.cloner = new CloneRecorder(ctx);

        RpLog.log("init mode=" + mode + " provider=" + provider + " delay=" + rcvDelay + " lang=" + lang);
    }

    // ------------------------------------------------------------------ helpers

    static double now() {
        return SystemClock.elapsedRealtime() / 1000.0;
    }

    static Object parseDelay(Object val) {
        String s = val == null ? "random" : String.valueOf(val).trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty() || "random".equals(s) || "rand".equals(s) || "auto".equals(s) || "null".equals(s)) {
            return "random";
        }
        try {
            double d = Double.parseDouble(s);
            if (Double.isNaN(d) || Double.isInfinite(d)) return "random";
            long secs = Math.round(d);
            return (int) Math.max(1, Math.min(3600, secs));
        } catch (NumberFormatException e) {
            return "random";
        }
    }

    private static String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private boolean isBrowser() {
        return "browser".equals(Providers.kind(provider));
    }

    public Prefs prefs() {
        return prefs;
    }

    public Context context() {
        return ctx;
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public synchronized String getState() {
        return state;
    }

    public synchronized String getMode() {
        return mode;
    }

    public synchronized String getProvider() {
        return provider;
    }

    public synchronized String getLang() {
        return lang;
    }

    public synchronized String getSessionId() {
        return sessionId;
    }

    public synchronized boolean isWantActive() {
        return wantActive;
    }

    public synchronized boolean isPlugged() {
        return plugged;
    }

    public synchronized boolean isOnHook() {
        return onHook;
    }

    public synchronized boolean isHideVoice() {
        return hideVoice;
    }

    public synchronized boolean isHardwarePresent() {
        return hardwarePresent;
    }

    // ------------------------------------------------------------------ snapshot

    public synchronized JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            double t = now();
            int callSecs = (CONNECTED.equals(state) && connectedAt > 0) ? (int) (t - connectedAt) : 0;
            Object armedRemaining = JSONObject.NULL;
            if (ARMED.equals(state) && armedIsFixed && armedUntil > t) {
                armedRemaining = (int) Math.max(0, Math.round(armedUntil - t));
            }
            double cloneSecs = cloner.durationSeconds();
            o.put("plugged", plugged);
            o.put("mode", mode);
            o.put("provider", provider);
            o.put("provider_info", Providers.info(provider));
            JSONArray provs = new JSONArray();
            for (String id : Providers.ORDER) provs.put(Providers.info(id));
            o.put("providers", provs);
            o.put("state", state);
            o.put("detail", statusDetail);
            o.put("session_id", sessionId);
            o.put("want_active", wantActive);
            o.put("on_hook", onHook);
            o.put("call_seconds", callSecs);
            o.put("armed_pending", ARMED.equals(state));
            o.put("rcv_delay", rcvDelay);
            o.put("lang", lang);
            o.put("hide_voice", hideVoice);
            o.put("armed_remaining", armedRemaining);
            o.put("clone_ready", cloneSecs >= 1.5);
            o.put("clone_seconds", cloneSecs);
            o.put("recording_clone", recordingClone);
            o.put("android", AndroidStatus.snapshot(ctx, this));
        } catch (JSONException e) {
            RpLog.log("snapshot error: " + e);
        }
        return o;
    }

    /** /api/ext/command (what the ChatGPT/Grok bridge polls). */
    public synchronized JSONObject extCommand() {
        JSONObject o = new JSONObject();
        try {
            o.put("want_active", wantActive && isBrowser());
            o.put("mode", mode);
            o.put("provider", provider);
            o.put("session_id", sessionId);
            o.put("state", state);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private void broadcast() {
        JSONObject snap = snapshot();
        for (Listener l : listeners) {
            try {
                l.onState(snap);
            } catch (RuntimeException e) {
                RpLog.log("listener error: " + e);
            }
        }
    }

    /** Re-publish the current snapshot (e.g. after a permission or settings change outside the controller). */
    public synchronized void refresh() {
        broadcast();
    }

    // ------------------------------------------------------------------ timers

    private Runnable schedule(final Runnable body, double delaySec) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                body.run();
            }
        };
        handler.postDelayed(r, Math.max(0L, Math.round(delaySec * 1000.0)));
        return r;
    }

    private void cancelTimers() {
        if (rcvTimer != null) handler.removeCallbacks(rcvTimer);
        if (missedTimer != null) handler.removeCallbacks(missedTimer);
        if (pickupTimer != null) handler.removeCallbacks(pickupTimer);
        rcvTimer = null;
        missedTimer = null;
        pickupTimer = null;
    }

    // ------------------------------------------------------------------ audio / windows

    private AudioRouter.PhoneAudio routeToPhone() {
        return audio.routeToPhone();
    }

    private void restoreAudio() {
        audio.restore();
    }

    private void openPopup() {
        Popup.open(ctx);
    }

    private void closePopup() {
        Popup.close(ctx);
    }

    private void hideVoiceBrowser() {
        voice.hide();
    }

    private void showVoiceBrowser(boolean full) {
        voice.show(full);
    }

    private void launchLocalWorker(AudioRouter.PhoneAudio a) {
        stopLocalWorker();
        local.start(provider, mode, lang, sessionId, a);
        RpLog.log("launched local worker persona=" + provider);
    }

    private void stopLocalWorker() {
        local.stop();
    }

    /** daemon.launch_voice_browser(focus). */
    private void launchVoiceBrowser(boolean focus) {
        if (!isBrowser()) return;
        if (voice.exists(provider)) {
            if (focus) {
                showVoiceBrowser(true);
            } else {
                openPopup();
            }
            return;
        }
        voice.load(provider, Providers.url(provider), focus);
        RpLog.log("launched voice browser for " + provider + ": " + Providers.url(provider));
    }

    private void closeVoiceBrowser() {
        voice.close();
    }

    private void startBackend(AudioRouter.PhoneAudio a) {
        if ("local".equals(Providers.kind(provider))) {
            closeVoiceBrowser();
            launchLocalWorker(a);
        } else {
            stopLocalWorker();
            launchVoiceBrowser(false);
        }
    }

    // ------------------------------------------------------------------ plug / unplug

    /** Hardware presence from UsbMonitor (AB13X VID/PID, or any USB headset when enabled). */
    public synchronized void setHardwarePresent(boolean present) {
        hardwarePresent = present;
        updatePlug();
    }

    private void updatePlug() {
        boolean effective = hardwarePresent || prefs.getBool(Prefs.SIM_PLUG, false);
        if (effective && !plugged) {
            onPlug();
        } else if (!effective && plugged) {
            onUnplug();
        }
    }

    public synchronized void onPlug() {
        RpLog.log("EVENT: USB plug");
        plugged = true;
        cancelTimers();
        sound.stop();
        // Always pop up the single Red Phone deck on plug-in (CALL or RCV).
        openPopup();
        // Pre-warm browser voice tab in the background so calls connect instantly.
        if (isBrowser()) launchVoiceBrowser(false);

        if ("receive".equals(mode)) {
            armRandomCall();
        } else {
            if (onHook) {
                state = IDLE;
                statusDetail = "On cradle (Spacebar) — lift handset to dial";
                broadcast();
            } else {
                startOutgoingCall();
            }
        }
    }

    public synchronized void onUnplug() {
        RpLog.log("EVENT: USB unplug");
        plugged = false;
        wantActive = false;
        cancelTimers();
        sound.stop();
        stopLocalWorker();
        state = UNPLUGGED;
        statusDetail = "Unplugged";
        sessionId = "";
        connectedAt = 0;
        broadcast();
        schedule(new Runnable() {
            @Override
            public void run() {
                teardownAfterUnplug();
            }
        }, 0.4);
    }

    private void teardownAfterUnplug() {
        synchronized (this) {
            if (plugged) return; // re-plugged meanwhile
        }
        closeVoiceBrowser();
        closePopup();
        restoreAudio();
    }

    // ------------------------------------------------------------------ RCV

    private void armRandomCall() {
        cancelTimers();
        sound.stop();
        wantActive = false;
        double delay;
        if ("random".equals(rcvDelay)) {
            delay = 6.0 + random.nextDouble() * 50.0;
            armedIsFixed = false;
            statusDetail = "Incoming call armed (random within 60s)";
        } else {
            delay = ((Integer) rcvDelay).doubleValue();
            armedIsFixed = true;
            statusDetail = "Incoming call scheduled in " + ((int) delay) + "s";
        }
        armedUntil = now() + delay;
        state = ARMED;
        RpLog.log(String.format(java.util.Locale.ROOT, "RECEIVE armed (%s): ringing in %.1fs", rcvDelay, delay));
        rcvTimer = schedule(new Runnable() {
            @Override
            public void run() {
                triggerIncomingRing();
            }
        }, delay);
        broadcast();
    }

    public synchronized void triggerIncomingRing() {
        if (!plugged) return;
        if (CONNECTED.equals(state) || DIALING.equals(state) || CONNECTING.equals(state)) return;
        cancelTimers();
        state = RINGING;
        ringStartedAt = now();
        armedIsFixed = false;
        statusDetail = "Incoming call · " + Providers.name(provider) + " — lift handset!";
        RpLog.log("RINGING on speaker");
        sound.playLoop(SoundPlayer.RINGTONE, SoundPlayer.TARGET_SPEAKER);
        openPopup();
        missedTimer = schedule(new Runnable() {
            @Override
            public void run() {
                onMissedCall();
            }
        }, 35.0);
        broadcast();
    }

    private synchronized void onMissedCall() {
        if (!RINGING.equals(state)) return;
        RpLog.log("Missed incoming call");
        sound.stop();
        state = IDLE;
        statusDetail = "Missed call — click Ring Now or set timer";
        broadcast();
    }

    // ------------------------------------------------------------------ calls

    public synchronized void startOutgoingCall() {
        if (!plugged) return;
        cancelTimers();
        AudioRouter.PhoneAudio a = routeToPhone();
        sessionId = newSessionId();
        wantActive = true;
        dialStartedAt = now();
        connectedAt = 0;
        state = DIALING;
        statusDetail = "Dialing " + Providers.name(provider) + "...";
        RpLog.log("DIALING session=" + sessionId + " provider=" + provider);
        sound.playLoop(SoundPlayer.DIALTONE, SoundPlayer.TARGET_PHONE);
        openPopup();
        startBackend(a);
        broadcast();
    }

    public synchronized void answer() {
        if (!plugged || !RINGING.equals(state)) return;
        cancelTimers();
        sound.stop();
        AudioRouter.PhoneAudio a = routeToPhone();
        sound.playOneshot(SoundPlayer.PICKUP, SoundPlayer.TARGET_PHONE);
        sessionId = newSessionId();
        wantActive = true;
        dialStartedAt = 0;
        connectedAt = 0;
        state = CONNECTING;
        statusDetail = "Answered — connecting to " + Providers.name(provider) + "...";
        RpLog.log("ANSWERED session=" + sessionId + " provider=" + provider);
        openPopup();
        startBackend(a);
        broadcast();
    }

    public synchronized void decline() {
        if (!RINGING.equals(state)) return;
        cancelTimers();
        sound.stop();
        wantActive = false;
        state = IDLE;
        statusDetail = "Call declined";
        broadcast();
    }

    public synchronized void hangup() {
        if (UNPLUGGED.equals(state) || IDLE.equals(state)) return;
        RpLog.log("HANGUP from state=" + state);
        final String endedSid = sessionId;
        cancelTimers();
        sound.stop();
        stopLocalWorker();
        if (plugged && (DIALING.equals(state) || CONNECTING.equals(state) || CONNECTED.equals(state)
                || AUTH_REQUIRED.equals(state))) {
            sound.playOneshot(SoundPlayer.HANGUP, SoundPlayer.TARGET_PHONE);
        }
        wantActive = false;
        connectedAt = 0;
        state = plugged ? IDLE : UNPLUGGED;
        statusDetail = plugged ? "Call ended" : "Unplugged";
        broadcast();
        schedule(new Runnable() {
            @Override
            public void run() {
                stopVoiceAfterHangup(endedSid);
            }
        }, 0.55);
    }

    private synchronized void stopVoiceAfterHangup(String endedSid) {
        if (wantActive || !sessionId.equals(endedSid)) return;
        // Keep browser voice tab warm & hidden in the background while plugged.
        if (plugged && isBrowser()) {
            hideVoiceBrowser();
        } else {
            closeVoiceBrowser();
        }
        restoreAudio();
    }

    // ------------------------------------------------------------------ settings

    public synchronized void setMode(String newMode) {
        if ("toggle".equals(newMode)) newMode = "call".equals(mode) ? "receive" : "call";
        if ((!"call".equals(newMode) && !"receive".equals(newMode)) || newMode.equals(mode)) return;
        mode = newMode;
        prefs.putString(Prefs.MODE, mode);
        RpLog.log("MODE switched -> " + mode);
        if (plugged && (IDLE.equals(state) || ARMED.equals(state))) {
            if ("receive".equals(mode)) {
                armRandomCall();
            } else {
                cancelTimers();
                state = IDLE;
                statusDetail = "CALL mode — lift handset or press Dial";
                broadcast();
            }
        } else {
            broadcast();
        }
    }

    public synchronized void setDelay(Object raw) {
        Object parsed = parseDelay(raw);
        rcvDelay = parsed;
        prefs.putString(Prefs.RCV_DELAY, String.valueOf(parsed));
        RpLog.log("RCV_DELAY set -> " + parsed);
        if (plugged && "receive".equals(mode) && (IDLE.equals(state) || ARMED.equals(state))) {
            armRandomCall();
        } else {
            broadcast();
        }
    }

    public synchronized void setLang(String raw) {
        String l = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"tr".equals(l) && !"auto".equals(l) && !"en".equals(l)) return;
        if (l.equals(lang)) return;
        lang = l;
        prefs.putString(Prefs.LANG, lang);
        RpLog.log("LANG set -> " + lang);
        broadcast();
    }

    public synchronized void setProvider(String prov) {
        if ("cycle".equals(prov)) {
            int idx = Providers.ORDER.indexOf(provider);
            prov = Providers.ORDER.get((idx + 1) % Providers.ORDER.size());
        }
        if (!Providers.exists(prov)) return;
        boolean changed = !prov.equals(provider);
        provider = prov;
        prefs.putString(Prefs.PROVIDER, provider);
        if (changed) {
            RpLog.log("PROVIDER switched -> " + provider);
            stopLocalWorker();
            closeVoiceBrowser();
            if (wantActive && (DIALING.equals(state) || CONNECTING.equals(state) || AUTH_REQUIRED.equals(state))) {
                sessionId = newSessionId();
                AudioRouter.PhoneAudio a = routeToPhone();
                statusDetail = "Connecting to " + Providers.name(provider) + "...";
                startBackend(a);
            } else if (plugged && isBrowser()) {
                launchVoiceBrowser(false);
            }
        }
        broadcast();
    }

    public synchronized void setHideVoice(Object hide) {
        boolean newVal;
        String s = String.valueOf(hide).toLowerCase(java.util.Locale.ROOT);
        if ("toggle".equals(s)) {
            newVal = !hideVoice;
        } else {
            newVal = "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s);
        }
        hideVoice = newVal;
        prefs.putString(Prefs.HIDE_VOICE, hideVoice ? "1" : "0");
        RpLog.log("HIDE_VOICE set -> " + hideVoice);
        if (hideVoice) {
            hideVoiceBrowser();
        } else if (CONNECTED.equals(state) && isBrowser()) {
            showVoiceBrowser(false);
        }
        broadcast();
    }

    /** Android-only settings (Ollama endpoint, cradle sources, plug sources). */
    public synchronized void applyAndroidSettings(JSONObject body) {
        if (body == null) return;
        if (body.has("ollama_url")) prefs.putString(Prefs.OLLAMA_URL, body.optString("ollama_url", "").trim());
        if (body.has("ollama_model")) prefs.putString(Prefs.OLLAMA_MODEL, body.optString("ollama_model", "").trim());
        if (body.has("prox_cradle")) prefs.putBool(Prefs.PROX_CRADLE, body.optBoolean("prox_cradle", false));
        if (body.has("any_usb")) prefs.putBool(Prefs.ANY_USB, body.optBoolean("any_usb", false));
        if (body.has("sim_plug")) {
            prefs.putBool(Prefs.SIM_PLUG, body.optBoolean("sim_plug", false));
            RpLog.log("SIM_PLUG set -> " + prefs.getBool(Prefs.SIM_PLUG, false));
            updatePlug();
        }
        broadcast();
    }

    // ------------------------------------------------------------------ Evil Twin clone

    public synchronized void resetClone() {
        cloner.reset();
        RpLog.log("Cleared saved voice clone sample");
        if (IDLE.equals(state)) statusDetail = "Voice clone cleared — speak or click Record 5s";
        broadcast();
    }

    public synchronized void recordCloneSample() {
        if (!plugged || recordingClone) return;
        if (DIALING.equals(state) || CONNECTING.equals(state) || CONNECTED.equals(state)) return;
        recordingClone = true;
        statusDetail = "Talk into the handset for 5 seconds to clone your voice...";
        broadcast();
        final AudioRouter.PhoneAudio a = routeToPhone();
        sound.playOneshot(SoundPlayer.PICKUP, SoundPlayer.TARGET_PHONE);
        cloner.record(a, new CloneRecorder.Callback() {
            @Override
            public void onDone(String message) {
                onCloneRecorded(message);
            }
        });
    }

    private synchronized void onCloneRecorded(String msg) {
        RpLog.log(msg);
        sound.playOneshot(SoundPlayer.HANGUP, SoundPlayer.TARGET_PHONE);
        recordingClone = false;
        if (IDLE.equals(state) || ARMED.equals(state)) statusDetail = msg;
        if (!wantActive) {
            // Android: don't leave the system in communication mode after a sample.
            schedule(new Runnable() {
                @Override
                public void run() {
                    synchronized (Controller.this) {
                        if (!wantActive && !recordingClone) restoreAudio();
                    }
                }
            }, 0.8);
        }
        broadcast();
    }

    // ------------------------------------------------------------------ backend heartbeats

    /** daemon.on_ext_heartbeat: from the local AI engine and from the ChatGPT/Grok bridge. */
    public synchronized void onExtHeartbeat(String prov, String status, final String detail) {
        if (prov == null || !prov.equals(provider) || !wantActive) return;
        status = status == null ? "" : status;
        String det = detail == null ? "" : detail;
        RpLog.log("EXT[" + prov + "]: " + status + " (" + det + ")");
        if ("auth_required".equals(status)) {
            if (!AUTH_REQUIRED.equals(state)) {
                sound.stop();
                state = AUTH_REQUIRED;
                statusDetail = "Sign in once to " + Providers.name(provider);
                launchVoiceBrowser(true);
                broadcast();
            }
        } else if ("connected".equals(status)) {
            if (DIALING.equals(state) || CONNECTING.equals(state) || AUTH_REQUIRED.equals(state)) {
                double elapsed = dialStartedAt > 0 ? now() - dialStartedAt : 99.0;
                double wait = DIALING.equals(state) ? Math.max(0.0, 2.2 - elapsed) : 0.0;
                if (wait > 0.05 && pickupTimer == null) {
                    final String d = det;
                    pickupTimer = schedule(new Runnable() {
                        @Override
                        public void run() {
                            finishConnect(d);
                        }
                    }, wait);
                } else if (wait <= 0.05) {
                    finishConnect(det);
                }
            } else if (CONNECTED.equals(state) && !det.isEmpty() && !det.equals(statusDetail)) {
                statusDetail = det;
                broadcast();
            }
        } else if (("loading".equals(status) || "connecting".equals(status))
                && (CONNECTING.equals(state) || AUTH_REQUIRED.equals(state) || DIALING.equals(state))) {
            if (!det.isEmpty()) {
                statusDetail = det;
                broadcast();
            }
        }
    }

    public synchronized void finishConnect(String detail) {
        pickupTimer = null;
        if (!wantActive || !plugged) return;
        boolean wasDialing = DIALING.equals(state);
        sound.stop();
        routeToPhone();
        if (isBrowser()) {
            if (hideVoice) {
                hideVoiceBrowser();
            } else {
                showVoiceBrowser(false);
            }
        }
        if (wasDialing) sound.playOneshot(SoundPlayer.PICKUP, SoundPlayer.TARGET_PHONE);
        state = CONNECTED;
        connectedAt = now();
        statusDetail = (detail != null && !detail.isEmpty()) ? detail : "Connected · " + Providers.name(provider);
        RpLog.log("CONNECTED session=" + sessionId + " provider=" + provider);
        broadcast();
    }

    // ------------------------------------------------------------------ Spacebar cradle + handset button

    /**
     * daemon._on_space_changed. {@code down == true} means the handset rests on the cradle
     * (Spacebar held / on-screen SPACEBAR pressed / proximity sensor covered).
     */
    public synchronized void onSpaceChanged(boolean down) {
        boolean prev = onHook;
        onHook = down;
        double t = now();
        if (down && !prev) {
            spaceDownSince = t;
            startCradleTick();
        } else if (!down && prev) {
            stopCradleTick();
            double heldFor = t - spaceDownSince;
            spaceDownSince = 0;
            if (plugged) {
                if (RINGING.equals(state)) {
                    RpLog.log(String.format(java.util.Locale.ROOT, "CRADLE LIFTED (held %.2fs) -> ANSWER", heldFor));
                    answer();
                    return;
                }
                if (IDLE.equals(state) && "call".equals(mode) && heldFor >= LIFT_DIAL_MIN_HELD) {
                    RpLog.log(String.format(java.util.Locale.ROOT, "CRADLE LIFTED (held %.2fs) -> DIAL", heldFor));
                    startOutgoingCall();
                    return;
                }
            }
            if (heldFor >= LIFT_DIAL_MIN_HELD) broadcast();
        }
    }

    /**
     * Android-only: forget the cradle state without acting on it (no answer/dial on "lift"). Used when a
     * cradle source goes away, e.g. the proximity cradle is switched off while it reported "on hook".
     */
    public synchronized void resetHook() {
        if (!onHook) return;
        stopCradleTick();
        onHook = false;
        spaceDownSince = 0;
        cradleAnnounced = false;
        broadcast();
    }

    private void startCradleTick() {
        stopCradleTick();
        cradleTick = new Runnable() {
            @Override
            public void run() {
                checkSpaceCradleHangup();
                synchronized (Controller.this) {
                    if (cradleTick == this && onHook) handler.postDelayed(this, CRADLE_TICK_MS);
                }
            }
        };
        handler.postDelayed(cradleTick, CRADLE_TICK_MS);
    }

    private void stopCradleTick() {
        if (cradleTick != null) handler.removeCallbacks(cradleTick);
        cradleTick = null;
    }

    /** daemon._check_space_cradle_hangup (polled every ~100 ms while the handset rests on the cradle). */
    synchronized void checkSpaceCradleHangup() {
        if (!(plugged && onHook && spaceDownSince > 0)) return;
        double held = now() - spaceDownSince;
        if (held >= CRADLE_HANGUP_HELD) {
            if (DIALING.equals(state) || CONNECTING.equals(state) || CONNECTED.equals(state)
                    || AUTH_REQUIRED.equals(state)) {
                RpLog.log("CRADLE DOWN >= 0.45s -> HANGUP");
                hangup();
            } else if (!cradleAnnounced) {
                cradleAnnounced = true;
                broadcast();
            }
        } else {
            cradleAnnounced = false;
        }
    }

    /** daemon._on_handset_button (AB13X KEY_PLAYPAUSE / KEY_STOPCD). */
    public synchronized void onHandsetButton() {
        RpLog.log("HANDSET BUTTON pressed in state=" + state);
        if (!plugged) return;
        if (RINGING.equals(state)) {
            answer();
        } else if (DIALING.equals(state) || CONNECTING.equals(state) || CONNECTED.equals(state)
                || AUTH_REQUIRED.equals(state)) {
            hangup();
        } else if (IDLE.equals(state)) {
            if ("call".equals(mode)) {
                startOutgoingCall();
            } else {
                triggerIncomingRing();
            }
        } else if (ARMED.equals(state)) {
            startOutgoingCall();
        }
    }

    // ------------------------------------------------------------------ misc actions

    public synchronized void arm() {
        if (plugged) armRandomCall();
    }

    public void focusVoice() {
        synchronized (this) {
            launchVoiceBrowser(true);
        }
    }

    public void openUi() {
        openPopup();
    }

    /** Called by the service when it is torn down (daemon stop_handler). */
    public synchronized void shutdown() {
        cancelTimers();
        stopCradleTick();
        sound.stop();
        stopLocalWorker();
    }
}
