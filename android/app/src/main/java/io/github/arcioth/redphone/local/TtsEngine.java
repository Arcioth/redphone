package io.github.arcioth.redphone.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import io.github.arcioth.redphone.RpLog;
import io.github.arcioth.redphone.VoiceAnalysis;
import io.github.arcioth.redphone.WavIO;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Android TextToSpeech in place of Piper: every utterance is rendered with synthesizeToFile() into the
 * app cache, parsed with {@link WavIO} and played by the session on the handset sink (TTS' own playback
 * cannot be pinned to the USB handset and cannot be cut by our session).
 *
 * <p>All methods except {@link #stopNow()} are called from the session worker thread only. Utterance
 * callbacks arrive on TTS binder threads and only count down latches.
 */
final class TtsEngine {
    static final Locale TR = new Locale("tr", "TR");
    static final Locale EN = Locale.US;

    private static final int DONE = 1;
    private static final int FAILED = 2;

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile int status;
    }

    private final Context app;
    private final Liveness live;
    private final File dir;
    private final String prefix;
    private final SharedPreferences cache;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<String, Pending>();
    private final AtomicInteger seq = new AtomicInteger();
    private final CountDownLatch initLatch = new CountDownLatch(1);
    private volatile int initStatus = TextToSpeech.ERROR;

    private volatile TextToSpeech tts;
    private boolean ready;
    private boolean trOk;
    private boolean enOk;
    private String engine = "";
    private String currentLang; // "tr", "en", "" (engine default) or null (nothing selected yet)
    private float pitch = 1f;
    private float rate = 1f;

    TtsEngine(Context app, Liveness live, File dir, String prefix) {
        this.app = app;
        this.live = live;
        this.dir = dir;
        this.prefix = prefix;
        this.cache = app.getSharedPreferences("redphone_tts", Context.MODE_PRIVATE);
    }

    /** Binds the default TTS engine and waits for onInit (the listener fires on the main thread). */
    boolean init(long timeoutMs) throws InterruptedException {
        TextToSpeech t;
        try {
            t = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    initStatus = status;
                    initLatch.countDown();
                }
            });
        } catch (RuntimeException e) {
            RpLog.log("[local] TextToSpeech create failed: " + e);
            return false;
        }
        tts = t;
        if (!initLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            RpLog.log("[local] TTS init timeout");
            return false;
        }
        if (initStatus != TextToSpeech.SUCCESS || !live.alive()) return false;
        t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(String id) {
                finish(id, DONE);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onError(String id) {
                finish(id, FAILED);
            }

            @Override
            public void onError(String id, int errorCode) {
                RpLog.log("[local] TTS error " + errorCode);
                finish(id, FAILED);
            }

            @Override
            public void onStop(String id, boolean interrupted) {
                finish(id, FAILED);
            }
        });
        String eng = t.getDefaultEngine();
        engine = eng == null ? "" : eng;
        trOk = available(t, TR);
        enOk = available(t, EN);
        ready = true;
        RpLog.log("[local] TTS ready engine=" + engine + " tr=" + trOk + " en=" + enOk);
        return true;
    }

    private void finish(String id, int status) {
        if (id == null) return;
        Pending p = pending.get(id);
        if (p != null) {
            p.status = status;
            p.latch.countDown();
        }
    }

    private static boolean available(TextToSpeech t, Locale l) {
        try {
            return t.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE;
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean ready() {
        return ready;
    }

    boolean trAvailable() {
        return trOk;
    }

    boolean enAvailable() {
        return enOk;
    }

    /**
     * piper_info_for_lang: Turkish voice for "tr" when installed, otherwise English, otherwise the
     * engine default. Returns the language actually selected ("tr", "en" or "").
     */
    String select(String lang) {
        TextToSpeech t = tts;
        if (!ready || t == null) return "";
        for (int tries = 0; tries < 3; tries++) {
            String want;
            if ("tr".equals(lang) && trOk) {
                want = "tr";
            } else if (enOk) {
                want = "en";
            } else {
                want = trOk ? "tr" : "";
            }
            if (want.equals(currentLang)) return want;
            if (want.isEmpty()) {
                currentLang = want; // engine default voice
                return want;
            }
            int r;
            try {
                r = t.setLanguage("tr".equals(want) ? TR : EN);
            } catch (RuntimeException e) {
                r = TextToSpeech.LANG_NOT_SUPPORTED;
            }
            if (r >= TextToSpeech.LANG_AVAILABLE) {
                preferInstalledVoice(t, want);
                currentLang = want;
                return want;
            }
            RpLog.log("[local] TTS setLanguage(" + want + ") = " + r);
            // never retry a broken voice on every utterance; fall back like piper_info_for_lang
            if ("tr".equals(want)) {
                trOk = false;
            } else {
                enOk = false;
            }
        }
        currentLang = "";
        return "";
    }

    /** Offline-first: if the locale's default voice needs the network or is not downloaded, use a local one. */
    private static void preferInstalledVoice(TextToSpeech t, String lang) {
        try {
            Voice cur = t.getVoice();
            if (cur != null && usable(cur)) return;
            Set<Voice> voices = t.getVoices();
            if (voices == null) return;
            String country = "tr".equals(lang) ? "TR" : "US";
            Voice best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Voice v : voices) {
                Locale l = v.getLocale();
                if (l == null || !lang.equals(l.getLanguage()) || !usable(v)) continue;
                int score = (country.equals(l.getCountry()) ? 1000 : 0) + v.getQuality() - v.getLatency() / 10;
                if (score > bestScore) {
                    bestScore = score;
                    best = v;
                }
            }
            if (best != null && t.setVoice(best) == TextToSpeech.SUCCESS) {
                RpLog.log("[local] TTS voice -> " + best.getName());
            }
        } catch (RuntimeException e) {
            RpLog.log("[local] TTS voice pick failed: " + e);
        }
    }

    private static boolean usable(Voice v) {
        if (v.isNetworkConnectionRequired()) return false;
        Set<String> f = v.getFeatures();
        return f == null || !f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
    }

    /** Stable id of the selected voice (engine + voice name) for caches. */
    String voiceKey() {
        TextToSpeech t = tts;
        String name = null;
        try {
            Voice v = t == null ? null : t.getVoice();
            if (v != null) name = v.getName();
        } catch (RuntimeException ignored) {
        }
        if (name == null) name = String.valueOf(currentLang);
        return engine + "|" + name;
    }

    /**
     * Renders {@code text} with the given pitch / rate and returns the PCM (null on failure or cancel).
     * The temp WAV is always deleted.
     */
    WavIO.Pcm synthesize(String text, float pitchMul, float rateMul, long timeoutMs) throws InterruptedException {
        TextToSpeech t = tts;
        if (!ready || t == null || text == null || text.isEmpty() || !live.alive()) return null;
        int max = TextToSpeech.getMaxSpeechInputLength();
        if (max > 0 && text.length() > max) text = text.substring(0, max);
        if (pitchMul != pitch) {
            if (t.setPitch(pitchMul) == TextToSpeech.SUCCESS) pitch = pitchMul;
        }
        if (rateMul != rate) {
            if (t.setSpeechRate(rateMul) == TextToSpeech.SUCCESS) rate = rateMul;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            RpLog.log("[local] cannot create " + dir);
            return null;
        }
        String id = prefix + seq.incrementAndGet();
        File f = new File(dir, id + ".wav");
        Pending p = new Pending();
        pending.put(id, p);
        try {
            int rc;
            try {
                rc = t.synthesizeToFile(text, new Bundle(), f, id);
            } catch (RuntimeException e) {
                RpLog.log("[local] synthesizeToFile failed: " + e);
                return null;
            }
            if (rc != TextToSpeech.SUCCESS) {
                RpLog.log("[local] synthesizeToFile rc=" + rc);
                return null;
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!p.latch.await(100, TimeUnit.MILLISECONDS)) {
                if (!live.alive()) return null;
                if (System.currentTimeMillis() > deadline) {
                    RpLog.log("[local] TTS synthesis timeout");
                    stopNow();
                    return null;
                }
            }
            if (p.status != DONE || !live.alive()) return null;
            return WavIO.parse(readFile(f));
        } catch (IOException e) {
            RpLog.log("[local] TTS wav read failed: " + e);
            return null;
        } finally {
            pending.remove(id);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static byte[] readFile(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            return WavIO.readAll(in);
        } finally {
            in.close();
        }
    }

    /**
     * Median F0 of the selected voice at pitch 1.0, measured once per (engine, voice) by rendering a
     * calibration phrase, cached in SharedPreferences "redphone_tts". 0 when unknown.
     */
    float baseF0() throws InterruptedException {
        if (!ready) return 0f;
        String key = "f0|" + voiceKey();
        float cached = cache.getFloat(key, 0f);
        if (cached > 0f) return cached;
        String phrase = "tr".equals(currentLang)
                ? "Merhaba, bu kısa bir ses ayarı cümlesidir. Beni rahatça duyabiliyor musun?"
                : "Hello there, this is a short voice calibration sentence. Can you hear me clearly?";
        // Neutral pitch/rate; the next real utterance re-applies its own (synthesize() tracks both).
        WavIO.Pcm pcm = synthesize(phrase, 1f, 1f, 15000);
        if (pcm == null) return 0f;
        float f0 = VoiceAnalysis.medianF0(pcm.monoFloat(), pcm.sampleRate);
        RpLog.log(String.format(Locale.ROOT, "[local] TTS base F0 %s = %.1f Hz", key, f0));
        if (f0 > 0f) cache.edit().putFloat(key, f0).apply();
        return f0;
    }

    /** Any thread: drop queued/active synthesis (session cancel). */
    void stopNow() {
        TextToSpeech t = tts;
        if (t == null) return;
        try {
            t.stop();
        } catch (RuntimeException ignored) {
        }
        for (Pending p : pending.values()) {
            p.status = FAILED;
            p.latch.countDown();
        }
    }

    /** Worker thread, once: unbind the engine. */
    void shutdown() {
        TextToSpeech t = tts;
        tts = null;
        ready = false;
        if (t == null) return;
        try {
            t.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            t.shutdown();
        } catch (RuntimeException ignored) {
        }
    }
}
