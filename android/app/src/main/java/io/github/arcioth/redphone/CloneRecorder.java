package io.github.arcioth.redphone;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Evil Twin voice sample: daemon.record_clone_sample worker + voice_clone.prepare_reference_16k /
 * write_wav_pcm16 + daemon.clone_wav_duration, plus the voice-matching F0 Android uses instead of ZONOS.
 *
 * <p>Contract:
 * <ul>
 *  <li>record(audio, cb): async. Sleep 150 ms, capture 5 s of 16 kHz mono PCM from the handset mic
 *    (AudioRecord + setPreferredDevice(audio.phoneSource)), prepareReference16k(), and if &gt;= 1.5 s
 *    remains save files/my_voice.wav, store median F0 in Prefs.CLONE_F0 and report
 *    "Voice cloned! (X.Xs ready for Evil Twin)"; else "Too quiet — talk closer to the handset and try
 *    Record 5s again"; on error "Voice capture error: ...". Exactly one cb.onDone() call, from the
 *    recorder thread (synchronously only in the last-resort case that no thread can be started).
 *    Android deviation: a capture with &lt; 1.5 s of voiced audio reports "Too quiet" instead of
 *    saving the untrimmed (silent) 5 s the way the desktop's prepare_reference_16k fallback would.</li>
 *  <li>saveReference(pcm16k): used by LocalCallEngine to auto-clone from the caller's first utterance.</li>
 *  <li>durationSeconds(): saved sample length rounded to 0.1 s (0 if none). Cheap (cached by mtime and
 *    size); called on every UI poll with the controller lock held.</li>
 *  <li>f0Hz(): saved median F0 or 0.</li>
 * </ul>
 */
public final class CloneRecorder {

    public interface Callback {
        void onDone(String message);
    }

    static final int SAMPLE_RATE = VoiceAnalysis.REF_SAMPLE_RATE; // 16 kHz, like parecord --rate=16000
    static final int RECORD_SECONDS = 5;
    static final long READ_TIMEOUT_MS = 6000;
    static final long PRE_ROLL_MS = 150;
    static final double MAX_REF_SEC = 6.0;
    static final double MIN_REF_SEC = 1.5;

    static final String MSG_TOO_QUIET = "Too quiet — talk closer to the handset and try Record 5s again";
    static final String MSG_ERROR_PREFIX = "Voice capture error: ";
    static final String MSG_NO_PERMISSION = MSG_ERROR_PREFIX + "microphone permission not granted";

    private final Context ctx;
    private final Prefs prefs;
    private final File ref;
    private final AtomicBoolean busy = new AtomicBoolean();

    /** Serialises writers of my_voice.wav + CLONE_F0 (record, saveReference, reset). */
    private final Object saveLock = new Object();
    /** Guards the duration cache only; never held across file I/O so UI polls never wait on a save. */
    private final Object cacheLock = new Object();
    private long cacheMtime = Long.MIN_VALUE;
    private long cacheLength = -1;
    private double cacheSeconds;

    public CloneRecorder(Context context) {
        this.ctx = context.getApplicationContext();
        // Own Prefs: the Controller constructs us inside its constructor, so controller() would recurse.
        this.prefs = new Prefs(ctx);
        this.ref = new File(ctx.getFilesDir(), "my_voice.wav");
    }

    // ------------------------------------------------------------------ RECORD 5s

    public void record(AudioRouter.PhoneAudio audio, final Callback cb) {
        final AudioDeviceInfo source = audio != null ? audio.phoneSource : null;
        if (!busy.compareAndSet(false, true)) {
            // The controller never overlaps recordings (recording_clone); keep the one-callback promise anyway.
            deliverAsync(cb, MSG_ERROR_PREFIX + "already recording");
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    msg = captureAndSave(source);
                } catch (Throwable e) { // incl. SecurityException if the permission is revoked mid-way, OOM
                    msg = MSG_ERROR_PREFIX + describe(e);
                } finally {
                    busy.set(false);
                }
                deliver(cb, msg);
            }
        }, "redphone-clone-record");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable e) { // cannot create a thread (OOM): still exactly one callback
            busy.set(false);
            deliver(cb, MSG_ERROR_PREFIX + describe(e));
        }
    }

    private static void deliverAsync(final Callback cb, final String msg) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                deliver(cb, msg);
            }
        }, "redphone-clone-cb");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable e) {
            deliver(cb, msg);
        }
    }

    private static void deliver(Callback cb, String msg) {
        if (cb == null) return;
        try {
            cb.onDone(msg);
        } catch (RuntimeException e) {
            RpLog.log("clone callback error: " + e);
        }
    }

    private String captureAndSave(AudioDeviceInfo source) throws IOException, InterruptedException {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return MSG_NO_PERMISSION;
        }
        // daemon: the pickup click (played by the controller) gets out of the way before the mic opens.
        Thread.sleep(PRE_ROLL_MS);
        return finishCapture(capture(source));
    }

    /** Captured s16 samples -> trimmed/normalised reference -> saved + user message (daemon worker tail). */
    String finishCapture(short[] raw) throws IOException {
        float[] floats = new float[raw.length];
        for (int i = 0; i < raw.length; i++) floats[i] = raw[i] / 32768.0f;
        // Android deviation (keeps the message's intent): prepare_reference_16k falls back to the
        // *untrimmed* capture when < 1.5 s is voiced, so the desktop would "clone" 5 s of silence. A
        // silenced mic (background / concurrent-capture policy) delivers exactly that on Android.
        if (voicedSamples(floats) < SAMPLE_RATE * MIN_REF_SEC) return MSG_TOO_QUIET;
        float[] clean = VoiceAnalysis.prepareReference16k(floats, SAMPLE_RATE, MAX_REF_SEC);
        if (clean.length >= SAMPLE_RATE * MIN_REF_SEC) {
            store(clean);
            return clonedMessage(clean.length);
        }
        return MSG_TOO_QUIET;
    }

    /** Samples in 20 ms frames above 0.012 RMS: the voiced-frame rule of prepare_reference_16k (16 kHz input). */
    static int voicedSamples(float[] pcm16k) {
        int hop = SAMPLE_RATE / 50;
        int voiced = 0;
        for (int i = 0; i < pcm16k.length; i += hop) {
            int end = Math.min(pcm16k.length, i + hop);
            if (VoiceAnalysis.rms(pcm16k, i, end) > 0.012) voiced += end - i;
        }
        return voiced;
    }

    /** "Voice cloned! (4.5s ready for Evil Twin)" — Python f"{len(clean)/16000:.1f}" rounding. */
    static String clonedMessage(int samples) {
        return "Voice cloned! (" + pyFixed1(samples / (double) SAMPLE_RATE) + "s ready for Evil Twin)";
    }

    /** parecord --raw --rate=16000 --channels=1 --format=s16le for 5 s (6 s timeout). */
    private short[] capture(AudioDeviceInfo source) throws IOException {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IOException("16 kHz mono capture is not supported on this device");
        int bytes = Math.max(min * 2, SAMPLE_RATE * 2); // >= 1 s of slack
        AudioRecord rec;
        try {
            rec = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bytes)
                    .build();
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            throw new IOException("could not open the microphone (" + describe(e) + ")");
        }
        try {
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("could not open the microphone");
            if (source != null && !rec.setPreferredDevice(source)) {
                RpLog.log("clone: preferred mic " + AudioRouter.describe(source) + " refused, using default");
            }
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IOException("the microphone is busy");
            }
            short[] out = new short[SAMPLE_RATE * RECORD_SECONDS];
            int got = 0;
            long t0 = SystemClock.elapsedRealtime();
            // Non-blocking reads + short sleeps: a wedged HAL cannot hold the thread past the timeout.
            while (got < out.length && SystemClock.elapsedRealtime() - t0 < READ_TIMEOUT_MS) {
                int n = rec.read(out, got, out.length - got, AudioRecord.READ_NON_BLOCKING);
                if (n > 0) {
                    got += n;
                } else if (n == 0) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    // daemon: parecord EOF ends the capture and what was read is used; nothing read = error.
                    if (got == 0) throw new IOException("microphone read failed (" + n + ")");
                    break;
                }
            }
            return got == out.length ? out : Arrays.copyOf(out, got);
        } finally {
            try {
                rec.stop();
            } catch (IllegalStateException ignored) {
            }
            rec.release();
        }
    }

    // ------------------------------------------------------------------ reference storage

    /** Stores a 16 kHz mono reference (already captured). Returns seconds saved, or 0 if too short. */
    public double saveReference(float[] pcm16k) {
        if (pcm16k == null || pcm16k.length < SAMPLE_RATE * MIN_REF_SEC) return 0;
        try {
            store(pcm16k);
            return pcm16k.length / (double) SAMPLE_RATE;
        } catch (IOException | RuntimeException e) {
            RpLog.log("Voice ref save error: " + e);
            return 0;
        }
    }

    private void store(float[] pcm) throws IOException {
        float f0 = VoiceAnalysis.medianF0(pcm, SAMPLE_RATE); // ~tens of ms: outside the lock
        synchronized (saveLock) {
            WavIO.writeMono16(ref, pcm, SAMPLE_RATE); // tmp file + rename: pollers never see half a file
            prefs.putFloat(Prefs.CLONE_F0, f0);
            invalidate();
        }
        RpLog.log(String.format(Locale.ROOT, "Saved voice clone reference: %.2fs, median F0 %.0f Hz",
                pcm.length / (double) SAMPLE_RATE, f0));
    }

    public double durationSeconds() {
        long len = ref.length(); // 0 when missing
        if (len <= 44) return 0;
        long mtime = ref.lastModified();
        synchronized (cacheLock) {
            if (mtime == cacheMtime && len == cacheLength) return cacheSeconds;
        }
        double secs = pyRound1(WavIO.durationSeconds(ref));
        synchronized (cacheLock) {
            cacheMtime = mtime;
            cacheLength = len;
            cacheSeconds = secs;
        }
        return secs;
    }

    public float f0Hz() {
        if (!ref.isFile()) return 0f;
        try {
            float f = prefs.getFloat(Prefs.CLONE_F0, 0f);
            return f > 0f && !Float.isNaN(f) && !Float.isInfinite(f) ? f : 0f;
        } catch (ClassCastException e) {
            return 0f;
        }
    }

    public File refFile() {
        return ref;
    }

    public void reset() {
        synchronized (saveLock) {
            //noinspection ResultOfMethodCallIgnored
            ref.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(ref.getPath() + ".tmp").delete();
            prefs.putFloat(Prefs.CLONE_F0, 0f);
            invalidate();
        }
    }

    private void invalidate() {
        synchronized (cacheLock) {
            cacheMtime = Long.MIN_VALUE;
            cacheLength = -1;
            cacheSeconds = 0;
        }
    }

    // ------------------------------------------------------------------ Python-compatible formatting

    /** Python round(x, 1): correctly rounded from the exact binary value, ties to even. */
    static double pyRound1(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return 0;
        return new BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** Python f"{x:.1f}" (same rounding rule as round()). */
    static String pyFixed1(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return "0.0";
        return new BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toPlainString();
    }

    static String describe(Throwable e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
