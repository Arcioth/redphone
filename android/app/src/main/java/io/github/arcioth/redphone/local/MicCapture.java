package io.github.arcioth.redphone.local;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

import io.github.arcioth.redphone.RpLog;

/**
 * Captures one caller utterance from the handset mic (the desktop's parecord + VAD loop), used by Evil
 * Twin to auto-clone the caller's voice before the speech recognizer takes over the microphone.
 *
 * <p>16 kHz mono PCM16 in 32 ms frames through {@link EnergyVad}. The AudioRecord is created, read and
 * released by the calling worker thread; {@link #interrupt()} (any thread) stops it so a blocked read
 * returns at once.
 */
final class MicCapture {

    interface Events {
        void onSpeechStart();
    }

    private final Liveness live;
    private volatile AudioRecord current;
    private String lastError;

    MicCapture(Liveness live) {
        this.live = live;
    }

    /** Why the last {@link #capture} returned null without timing out (null: timeout / cancelled). */
    String lastError() {
        return lastError;
    }

    /**
     * Blocks until one utterance (>= 0.45 s) was captured.
     *
     * @param noSpeechTimeoutMs give up after this long without any detected speech
     * @return 16 kHz mono samples in [-1, 1], or null (timeout, cancelled or audio error)
     */
    float[] capture(AudioDeviceInfo source, long noSpeechTimeoutMs, Events events) throws InterruptedException {
        lastError = null;
        AudioRecord rec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION);
        if (rec == null) rec = open(MediaRecorder.AudioSource.MIC);
        if (rec == null) {
            lastError = "microphone unavailable";
            return null;
        }
        try {
            if (source != null) rec.setPreferredDevice(source);
            current = rec;
            if (!live.alive()) return null;
            try {
                rec.startRecording();
            } catch (IllegalStateException e) {
                lastError = "start failed";
                return null;
            }
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                lastError = "microphone busy";
                return null;
            }
            EnergyVad vad = new EnergyVad();
            short[] buf = new short[EnergyVad.FRAME_SAMPLES];
            long lastActivity = SystemClock.uptimeMillis();
            while (live.alive()) {
                int got = 0;
                while (got < buf.length) {
                    int n = rec.read(buf, got, buf.length - got);
                    if (n <= 0) {
                        if (live.alive()) lastError = "read error " + n;
                        return null;
                    }
                    got += n;
                }
                if (Thread.interrupted()) throw new InterruptedException();
                float[] frame = new float[buf.length];
                for (int i = 0; i < buf.length; i++) frame[i] = buf[i] / 32768f;
                int ev = vad.feed(frame);
                if (ev == EnergyVad.SPEECH_START) {
                    lastActivity = SystemClock.uptimeMillis();
                    if (events != null) events.onSpeechStart();
                } else if (ev == EnergyVad.UTTERANCE) {
                    return vad.takeUtterance();
                } else if (ev == EnergyVad.TOO_SHORT) {
                    lastActivity = SystemClock.uptimeMillis();
                }
                if (!vad.inSpeech() && SystemClock.uptimeMillis() - lastActivity > noSpeechTimeoutMs) return null;
            }
            return null;
        } finally {
            current = null;
            try {
                rec.stop();
            } catch (IllegalStateException ignored) {
            }
            rec.release();
        }
    }

    private static AudioRecord open(int audioSource) {
        int sr = EnergyVad.SAMPLE_RATE;
        int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(min > 0 ? min * 2 : 0, sr * 2 / 2); // >= 0.5 s
        AudioRecord rec;
        try {
            rec = new AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bytes)
                    .build();
        } catch (RuntimeException e) { // UnsupportedOperationException, SecurityException (no permission)
            RpLog.log("[local] AudioRecord(" + audioSource + ") failed: " + e);
            return null;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            return null;
        }
        return rec;
    }

    /** Any thread: stop the running capture (a blocked read returns immediately). */
    void interrupt() {
        AudioRecord r = current;
        if (r == null) return;
        try {
            r.stop();
        } catch (RuntimeException ignored) {
            // released concurrently by the worker
        }
    }
}
