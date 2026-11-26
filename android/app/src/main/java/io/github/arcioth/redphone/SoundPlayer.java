package io.github.arcioth.redphone;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Port of daemon.SoundPlayer (paplay loop / one-shot).
 *
 * <p>Contract:
 * <ul>
 *  <li>Sounds: assets/sounds/&lt;name&gt;.wav when present (pickup/hangup always ship; dialtone/ringtone only
 *    if the user downloaded them with yt-dlp), otherwise dialtone/ringtone are synthesized (ToneSynth).
 *    Decoded once per name and cached.</li>
 *  <li>TARGET_PHONE = the red handset earpiece (AudioRouter phone sink; fall back to default output),
 *    voice-communication usage (STREAM_VOICE_CALL volume).</li>
 *  <li>TARGET_SPEAKER = the phone's built-in loudspeaker (desktop: laptop speakers) for the RCV ringtone,
 *    ringtone usage (STREAM_RING volume); vibrates a ring pattern too when the ringer is not NORMAL.</li>
 *  <li>playLoop: stops any previous loop, then loops until stop() (the daemon restarts paplay as soon as
 *    the clip ends). playOneshot: independent of the loop; stop() does NOT cut a one-shot.</li>
 *  <li>Thread-safe; never blocks the caller (the controller lock is held while calling). All AudioTrack
 *    work happens on dedicated worker threads, each of which releases its own track.</li>
 * </ul>
 */
public final class SoundPlayer {
    public static final String RINGTONE = "ringtone";
    public static final String DIALTONE = "dialtone";
    public static final String PICKUP = "pickup";
    public static final String HANGUP = "hangup";

    public static final int TARGET_PHONE = 0;
    public static final int TARGET_SPEAKER = 1;

    /** Cap for user-supplied yt-dlp clips (a YouTube rip can run for minutes): ~43 s of 48 kHz stereo. */
    static final int MAX_CLIP_BYTES = 8 * 1024 * 1024;
    static final int LOOP_BUFFER_MS = 200;
    static final int ONESHOT_BUFFER_MS = 100;
    /** Silence written after a one-shot so the track starts even for a clip shorter than its buffer. */
    static final int ONESHOT_TAIL_MS = 100;
    /** Extra time a finished one-shot track stays alive so HAL/USB latency does not clip its end. */
    static final long ONESHOT_LATENCY_MS = 60;
    static final long POLL_MS = 8;
    /** No buffer progress for this long = the output is wedged; the loop reopens its track. */
    static final long STALL_MS = 3000;
    /** A loop track that survived this long resets the reopen budget. */
    static final long HEALTHY_MS = 5000;
    /** pause() is ramped to silence by the mixer within one period; wait for that before destroying. */
    static final long PAUSE_RAMP_MS = 40;
    /** 6 s cycle like the ringtone: two buzzes during the 2 s ring, then 4 s still. */
    static final long[] RING_VIBRATION = {0, 800, 400, 800, 4000};

    private final Context ctx;
    private final Prefs prefs;
    private final AudioManager am;

    private final Object loopLock = new Object();
    private Loop loop; // guarded by loopLock

    private final HashMap<String, WavIO.Pcm> cache = new HashMap<String, WavIO.Pcm>(); // guarded by itself
    private final HashMap<String, Object> loadLocks = new HashMap<String, Object>(); // guarded by cache
    private final ThreadPoolExecutor oneshots;

    private final Object vibLock = new Object();
    private Object vibOwner; // guarded by vibLock: the loop whose ring vibration is running

    public SoundPlayer(Context context) {
        this.ctx = context.getApplicationContext();
        this.prefs = new Prefs(ctx);
        this.am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);

        oneshots = new ThreadPoolExecutor(3, 3, 15, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(16),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "redphone-sound-oneshot");
                        t.setDaemon(true);
                        return t;
                    }
                },
                new RejectedExecutionHandler() {
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                        RpLog.log("sound: one-shot queue full, dropped");
                    }
                });
        oneshots.allowCoreThreadTimeOut(true);

        // Decode/synthesize everything once in the background so the first click has no latency.
        Thread pre = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                } catch (RuntimeException ignored) {
                }
                clip(PICKUP);
                clip(HANGUP);
                clip(DIALTONE);
                clip(RINGTONE);
            }
        }, "redphone-sound-preload");
        pre.setDaemon(true);
        pre.start();
    }

    // ------------------------------------------------------------------ public API (non-blocking)

    public void playLoop(String sound, int target) {
        if (sound == null) return;
        synchronized (loopLock) {
            if (loop != null) loop.cancel();
            loop = new Loop(sound, target);
            Thread t = new Thread(loop, "redphone-sound-loop");
            t.setDaemon(true);
            t.start();
        }
    }

    public void playOneshot(String sound, int target) {
        if (sound == null) return;
        try {
            oneshots.execute(new Oneshot(sound, target));
        } catch (RuntimeException e) {
            RpLog.log("sound: one-shot " + sound + " not started: " + e);
        }
    }

    /** Stops the loop (ringtone/dial tone) within a few ms; one-shots keep playing (daemon semantics). */
    public void stop() {
        synchronized (loopLock) {
            if (loop != null) loop.cancel();
            loop = null;
        }
    }

    // ------------------------------------------------------------------ workers

    private final class Loop implements Runnable {
        final String name;
        final int target;
        volatile boolean cancelled;

        Loop(String name, int target) {
            this.name = name;
            this.target = target;
        }

        void cancel() {
            cancelled = true;
        }

        @Override
        public void run() {
            raisePriority();
            WavIO.Pcm pcm = clip(name);
            if (pcm == null || cancelled) return;
            short[] data = pcm.samples;
            AudioFocusRequest focus = null;
            boolean vibrating = false;
            int pos = 0;
            int failures = 0;
            try {
                if (target == TARGET_SPEAKER && ringerNormal()) focus = requestRingFocus();
                while (!cancelled) {
                    AudioTrack t = createTrack(pcm, target, LOOP_BUFFER_MS);
                    if (t == null) {
                        if (++failures > 3) {
                            RpLog.log("sound: giving up on " + name + " loop");
                            return;
                        }
                        sleepQuietly(300);
                        continue;
                    }
                    long openedAt = SystemClock.elapsedRealtime();
                    try {
                        t.play();
                        if (target == TARGET_SPEAKER && !vibrating) vibrating = startRingVibration(this);
                        long lastProgress = openedAt;
                        while (!cancelled) {
                            int n = t.write(data, pos, data.length - pos, AudioTrack.WRITE_NON_BLOCKING);
                            long now = SystemClock.elapsedRealtime();
                            if (n < 0) {
                                RpLog.log("sound: " + name + " write error " + n);
                                break;
                            }
                            if (n > 0) {
                                pos += n;
                                if (pos >= data.length) pos = 0; // seamless restart, like paplay re-run
                                lastProgress = now;
                            } else if (now - lastProgress > STALL_MS) {
                                RpLog.log("sound: " + name + " output stalled, reopening");
                                break;
                            } else {
                                sleepQuietly(POLL_MS);
                            }
                        }
                    } catch (RuntimeException e) {
                        RpLog.log("sound: " + name + " loop error: " + e);
                    } finally {
                        teardown(t, cancelled);
                    }
                    if (!cancelled) {
                        // A track that played fine for a while (e.g. audioserver restarted once) earns a
                        // fresh retry budget; one that keeps dying right away is given up on.
                        if (SystemClock.elapsedRealtime() - openedAt > HEALTHY_MS) failures = 0;
                        if (++failures > 3) {
                            RpLog.log("sound: giving up on " + name + " loop");
                            return;
                        }
                        sleepQuietly(200);
                    }
                }
            } finally {
                if (vibrating) stopRingVibration(this);
                abandonFocus(focus);
            }
        }
    }

    private final class Oneshot implements Runnable {
        final String name;
        final int target;

        Oneshot(String name, int target) {
            this.name = name;
            this.target = target;
        }

        @Override
        public void run() {
            raisePriority();
            WavIO.Pcm pcm = clip(name);
            if (pcm == null) return;
            AudioTrack t = createTrack(pcm, target, ONESHOT_BUFFER_MS);
            if (t == null) return;
            try {
                int ch = pcm.channels;
                long frames = pcm.samples.length / ch;
                int tailFrames = Math.max(0, t.getBufferSizeInFrames()) + pcm.sampleRate * ONESHOT_TAIL_MS / 1000;
                long deadline = SystemClock.elapsedRealtime() + frames * 1000L / pcm.sampleRate
                        + 2L * (ONESHOT_BUFFER_MS + ONESHOT_TAIL_MS) + 2000L;
                t.play();
                if (!writeFully(t, pcm.samples, pcm.samples.length, deadline)) return;
                // A streaming track only starts once its buffer has filled: pad with silence so even a
                // 75 ms click (pickup.wav) is pushed through the mixer instead of waiting for stop().
                if (!writeFully(t, new short[tailFrames * ch], tailFrames * ch, deadline)) return;
                while ((t.getPlaybackHeadPosition() & 0xFFFFFFFFL) < frames
                        && SystemClock.elapsedRealtime() < deadline) {
                    sleepQuietly(POLL_MS);
                }
                sleepQuietly(ONESHOT_LATENCY_MS);
            } catch (RuntimeException e) {
                RpLog.log("sound: " + name + " one-shot error: " + e);
            } finally {
                teardown(t, false);
            }
        }
    }

    private static boolean writeFully(AudioTrack t, short[] data, int len, long deadline) {
        int pos = 0;
        while (pos < len) {
            int n = t.write(data, pos, len - pos, AudioTrack.WRITE_NON_BLOCKING);
            if (n < 0) {
                RpLog.log("sound: write error " + n);
                return false;
            }
            pos += n;
            if (pos < len) {
                if (SystemClock.elapsedRealtime() > deadline) {
                    RpLog.log("sound: one-shot output stalled");
                    return false;
                }
                if (n == 0) sleepQuietly(POLL_MS);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ AudioTrack helpers

    private static AudioAttributes attributes(int target) {
        if (target == TARGET_SPEAKER) {
            return new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
        }
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    /** Streaming PCM16 track for {@code pcm}, pointed at the target device; null when it cannot be opened. */
    private AudioTrack createTrack(WavIO.Pcm pcm, int target, int bufferMs) {
        int mask = pcm.channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int frameBytes = 2 * pcm.channels;
        int min = AudioTrack.getMinBufferSize(pcm.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(min > 0 ? min : 0, pcm.sampleRate * frameBytes / 1000 * bufferMs);
        bytes -= bytes % frameBytes;
        AudioTrack t;
        try {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(attributes(target))
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(pcm.sampleRate)
                            .setChannelMask(mask)
                            .build())
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (RuntimeException e) { // UnsupportedOperationException / IllegalArgumentException
            RpLog.log("sound: cannot create AudioTrack: " + e);
            return null;
        }
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            RpLog.log("sound: AudioTrack not initialized");
            t.release();
            return null;
        }
        AudioRouter.PhoneAudio a = AudioRouter.find(am, prefs.getBool(Prefs.ANY_USB, false),
                prefs.getBool(Prefs.SIM_PLUG, false));
        AudioDeviceInfo dev = target == TARGET_SPEAKER ? a.speakerSink : a.phoneSink;
        if (dev != null) {
            try {
                t.setPreferredDevice(dev);
            } catch (RuntimeException ignored) {
                // default routing
            }
        }
        return t;
    }

    /** Releases a track; {@code cut} = stop mid-sound (fade via pause, then drop what is queued). */
    private static void teardown(AudioTrack t, boolean cut) {
        try {
            if (cut && t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                // AudioFlinger ramps a pausing track to zero over one mix period (no click); flush then
                // discards the ~200 ms still queued so the ring stops at once, like killing paplay.
                t.pause();
                sleepQuietly(PAUSE_RAMP_MS);
                t.flush();
            }
            t.stop();
        } catch (RuntimeException ignored) {
        }
        t.release();
    }

    private static void raisePriority() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (RuntimeException ignored) {
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ ring extras: focus + vibration

    private boolean ringerNormal() {
        try {
            return am == null || am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Ducks music while it rings (only when the ring is audible). */
    private AudioFocusRequest requestRingFocus() {
        if (am == null) return null;
        try {
            AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes(TARGET_SPEAKER))
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int focusChange) {
                            // The bell keeps ringing until answered/missed.
                        }
                    }, AudioRouter.handler())
                    .build();
            am.requestAudioFocus(req);
            return req;
        } catch (RuntimeException e) {
            RpLog.log("sound: ring focus error: " + e);
            return null;
        }
    }

    private void abandonFocus(AudioFocusRequest req) {
        if (req == null || am == null) return;
        try {
            am.abandonAudioFocusRequest(req);
        } catch (RuntimeException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private Vibrator vibrator() {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm != null ? vm.getDefaultVibrator() : null;
        }
        return (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }

    /**
     * Vibrate/silent ringer: the ring stream is muted, so buzz instead. The system itself still drops
     * ringtone-usage vibration in SILENT (and per the user's "vibrate for calls" settings).
     */
    @SuppressWarnings("deprecation")
    private boolean startRingVibration(Object owner) {
        if (ringerNormal()) return false;
        try {
            Vibrator v = vibrator();
            if (v == null || !v.hasVibrator()) return false;
            VibrationEffect fx = VibrationEffect.createWaveform(RING_VIBRATION, 0);
            synchronized (vibLock) {
                if (Build.VERSION.SDK_INT >= 33) {
                    v.vibrate(fx, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE));
                } else {
                    v.vibrate(fx, attributes(TARGET_SPEAKER));
                }
                vibOwner = owner;
            }
            return true;
        } catch (RuntimeException e) { // SecurityException without VIBRATE, vendor quirks
            RpLog.log("sound: vibrate error: " + e);
            return false;
        }
    }

    /** Cancels the ring vibration only if {@code owner} started the one that is running. */
    private void stopRingVibration(Object owner) {
        synchronized (vibLock) {
            if (vibOwner != owner) return; // a newer ring loop took over the vibrator
            vibOwner = null;
            try {
                Vibrator v = vibrator();
                if (v != null) v.cancel();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ clip cache

    /** Decoded clip for {@code name} (cached; null = unavailable). Blocks while decoding: worker threads only. */
    WavIO.Pcm clip(String name) {
        Object nameLock;
        synchronized (cache) {
            if (cache.containsKey(name)) return cache.get(name);
            nameLock = loadLocks.get(name);
            if (nameLock == null) {
                nameLock = new Object();
                loadLocks.put(name, nameLock);
            }
        }
        synchronized (nameLock) {
            synchronized (cache) {
                if (cache.containsKey(name)) return cache.get(name);
            }
            WavIO.Pcm p = load(name);
            synchronized (cache) {
                cache.put(name, p);
            }
            return p;
        }
    }

    private WavIO.Pcm load(String name) {
        String path = "sounds/" + name + ".wav";
        WavIO.Pcm p = null;
        if (name.matches("[a-z0-9_]+")) {
            InputStream in = null;
            try {
                in = ctx.getAssets().open(path);
                p = usable(WavIO.parse(readCapped(in, MAX_CLIP_BYTES)));
                if (p == null) RpLog.log("sound: unusable wav " + path);
            } catch (FileNotFoundException e) {
                // not bundled (dialtone/ringtone are optional yt-dlp downloads)
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                RpLog.log("sound: cannot decode " + path + ": " + e);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        if (p != null) return p;
        if (DIALTONE.equals(name)) {
            RpLog.log("sound: " + path + " not bundled, using synthesized ringback");
            return ToneSynth.dialtone();
        }
        if (RINGTONE.equals(name)) {
            RpLog.log("sound: " + path + " not bundled, using synthesized bell");
            return ToneSynth.ringtone();
        }
        RpLog.log("missing wav: " + path);
        return null;
    }

    /** Reads at most {@code cap} bytes (WavIO.parse tolerates a data chunk cut short). */
    static byte[] readCapped(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16384];
        int n;
        while (bos.size() < cap && (n = in.read(buf, 0, Math.min(buf.length, cap - bos.size()))) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** Playable form of a decoded WAV: mono or stereo, whole frames, sane rate; null if unusable. */
    static WavIO.Pcm usable(WavIO.Pcm p) {
        if (p == null || p.channels <= 0 || p.sampleRate < 4000 || p.sampleRate > 192000) return null;
        short[] s = p.samples;
        int ch = p.channels;
        if (ch > 2) {
            float[] m = p.monoFloat();
            s = new short[m.length];
            for (int i = 0; i < m.length; i++) {
                s[i] = (short) Math.max(-32768, Math.min(32767, Math.round(m[i] * 32768f)));
            }
            ch = 1;
        }
        int frames = s.length / ch;
        if (frames == 0) return null;
        if (frames * ch != s.length) s = Arrays.copyOf(s, frames * ch);
        return (s == p.samples && ch == p.channels) ? p : new WavIO.Pcm(s, p.sampleRate, ch);
    }
}
