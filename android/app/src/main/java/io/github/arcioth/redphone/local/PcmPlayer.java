package io.github.arcioth.redphone.local;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

import io.github.arcioth.redphone.RpLog;

/**
 * Plays rendered TTS PCM on the handset (the desktop's {@code paplay --device=<phone sink>}).
 *
 * <p>One AudioTrack per utterance, owned (created, written, released) by the calling worker thread.
 * Data goes in 50 ms chunks so a cancelled session stops within one chunk; {@link #cut()} may be called
 * from any other thread to silence the current utterance immediately (pause + flush).
 */
final class PcmPlayer {
    private static final int CHUNK_MS = 50;
    private static final int BUFFER_MS = 150;

    private final Liveness live;
    private volatile AudioTrack current;

    PcmPlayer(Liveness live) {
        this.live = live;
    }

    /**
     * Blocking playback of interleaved 16-bit PCM.
     *
     * @return true when the clip played to the end (false: cancelled or audio error)
     */
    boolean play(short[] samples, int sampleRate, int channels, AudioDeviceInfo sink) throws InterruptedException {
        if (samples == null || samples.length == 0 || sampleRate <= 0 || !live.alive()) return false;
        if (channels > 2) {
            samples = downmix(samples, channels);
            channels = 1;
        }
        final int ch = channels == 2 ? 2 : 1;
        final int mask = ch == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        final int frameBytes = ch * 2;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(minBuf > 0 ? minBuf : 0, sampleRate * BUFFER_MS / 1000 * frameBytes);
        // AudioTrack rejects sizes that are not a whole number of frames (e.g. 150 ms @ 22050 Hz mono).
        bufBytes = (bufBytes + frameBytes - 1) / frameBytes * frameBytes;

        AudioTrack t;
        try {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(mask)
                            .build())
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (RuntimeException e) {
            RpLog.log("[local] AudioTrack create failed: " + e);
            return false;
        }
        try {
            if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                RpLog.log("[local] AudioTrack not initialized (" + sampleRate + " Hz)");
                return false;
            }
            if (sink != null) t.setPreferredDevice(sink);
            current = t;
            if (!live.alive()) return false;
            t.play();

            final int frames = samples.length / ch;
            final int chunk = Math.max(ch, sampleRate * CHUNK_MS / 1000 * ch);
            if (!writeAll(t, samples, samples.length, chunk)) return false;

            // A streaming track only starts once its buffer is full (or on stop(), which would reset the
            // head position). Trailing silence fills the buffer, so even a clip shorter than the buffer
            // plays out; we then wait for the head to pass the real frames and drop the padding.
            int padFrames = Math.max(sampleRate * CHUNK_MS / 1000, t.getBufferSizeInFrames());
            if (!writeAll(t, new short[padFrames * ch], padFrames * ch, chunk)) return false;
            // The head counts frames taken by the mixer; let a little padding follow so the pause ramp
            // only ever touches silence.
            final int target = frames + Math.min(padFrames / 2, sampleRate * 60 / 1000);

            long deadline = SystemClock.uptimeMillis() + 1500L
                    + Math.max(0, target - t.getPlaybackHeadPosition()) * 1000L / sampleRate;
            while (live.alive()) {
                if (t.getPlaybackHeadPosition() >= target) return true;
                if (SystemClock.uptimeMillis() > deadline) {
                    RpLog.log("[local] playback drain timeout");
                    return true;
                }
                Thread.sleep(10);
            }
            return false;
        } catch (RuntimeException e) { // e.g. IllegalStateException from play() after a route change
            RpLog.log("[local] playback failed: " + e);
            return false;
        } finally {
            current = null;
            try {
                t.pause();
                t.flush();
            } catch (IllegalStateException ignored) {
            }
            t.release();
        }
    }

    private boolean writeAll(AudioTrack t, short[] data, int len, int chunk) throws InterruptedException {
        int off = 0;
        int stalls = 0;
        while (off < len) {
            if (!live.alive()) return false;
            int w = t.write(data, off, Math.min(chunk, len - off)); // blocking
            if (w < 0) {
                RpLog.log("[local] AudioTrack write error " + w);
                return false;
            }
            if (w == 0) {
                // Interrupted by pause() (cut) or a transient stall: never spin.
                if (++stalls > 200) return false;
                Thread.sleep(5);
                continue;
            }
            stalls = 0;
            off += w;
        }
        return true;
    }

    /** Any thread: silence the utterance that is playing right now (no-op when idle). */
    void cut() {
        AudioTrack t = current;
        if (t == null) return;
        try {
            t.pause();
            t.flush();
        } catch (RuntimeException ignored) {
            // released concurrently by the worker
        }
    }

    private static short[] downmix(short[] in, int channels) {
        int frames = in.length / channels;
        short[] out = new short[frames];
        for (int i = 0; i < frames; i++) {
            int acc = 0;
            for (int c = 0; c < channels; c++) acc += in[i * channels + c];
            out[i] = (short) (acc / channels);
        }
        return out;
    }
}
