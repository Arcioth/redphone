package io.github.arcioth.redphone.local;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java utterance segmenter: the LocalCallRunner.run() mic loop with the SileroVad energy fallback
 * (score = min(1, rms * 18)). 32 ms frames @ 16 kHz; speech starts after 3 frames >= 0.52 (with an
 * 8-frame pre-roll), continues while >= 0.38 and ends after 0.75 s of silence or 12 s of speech.
 * Utterances shorter than 0.45 s are dropped. Not thread-safe (one capture thread).
 */
public final class EnergyVad {
    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_SAMPLES = 512; // 32 ms @ 16 kHz

    public static final int NONE = 0;
    /** Speech just started ("Seni duyuyor..."). */
    public static final int SPEECH_START = 1;
    /** An utterance is complete: {@link #takeUtterance()}. */
    public static final int UTTERANCE = 2;
    /** Speech ended but was shorter than 0.45 s (the desktop silently drops it). */
    public static final int TOO_SHORT = 3;

    static final double START_PROB = 0.52;
    static final double CONTINUE_PROB = 0.38;
    static final int START_STREAK = 3;
    static final int PRE_ROLL = 8;
    static final int MAX_SPEECH_FRAMES = (int) (12.0 * SAMPLE_RATE / FRAME_SAMPLES);
    static final int END_SILENCE_FRAMES = (int) (0.75 * SAMPLE_RATE / FRAME_SAMPLES);
    static final int MIN_UTTERANCE_SAMPLES = (int) (SAMPLE_RATE * 0.45);

    private final ArrayDeque<float[]> preRoll = new ArrayDeque<float[]>();
    private List<float[]> speech = new ArrayList<float[]>();
    private boolean inSpeech;
    private int voicedStreak;
    private int silenceFrames;
    private float[] utterance;

    /** SileroVad.score() energy fallback. */
    public static double score(float[] frame) {
        double acc = 0;
        for (float x : frame) acc += x * x;
        double rms = Math.sqrt(acc / Math.max(1, frame.length)) + 1e-9;
        return Math.min(1.0, rms * 18.0);
    }

    public boolean inSpeech() {
        return inSpeech;
    }

    public void reset() {
        preRoll.clear();
        speech = new ArrayList<float[]>();
        inSpeech = false;
        voicedStreak = 0;
        silenceFrames = 0;
    }

    /** Feeds one frame (the array is retained; pass a fresh array per call). */
    public int feed(float[] frame) {
        double prob = score(frame);
        if (!inSpeech) {
            preRoll.addLast(frame);
            if (preRoll.size() > PRE_ROLL) preRoll.removeFirst();
            if (prob >= START_PROB) {
                voicedStreak++;
                if (voicedStreak >= START_STREAK) {
                    inSpeech = true;
                    speech = new ArrayList<float[]>(preRoll);
                    silenceFrames = 0;
                    return SPEECH_START;
                }
            } else {
                voicedStreak = 0;
            }
            return NONE;
        }
        speech.add(frame);
        if (prob >= CONTINUE_PROB) {
            silenceFrames = 0;
        } else {
            silenceFrames++;
        }
        if (silenceFrames >= END_SILENCE_FRAMES || speech.size() >= MAX_SPEECH_FRAMES) {
            float[] utt = concat(speech);
            reset();
            if (utt.length < MIN_UTTERANCE_SAMPLES) return TOO_SHORT;
            utterance = utt;
            return UTTERANCE;
        }
        return NONE;
    }

    public float[] takeUtterance() {
        float[] u = utterance;
        utterance = null;
        return u;
    }

    static float[] concat(List<float[]> parts) {
        int n = 0;
        for (float[] p : parts) n += p.length;
        float[] out = new float[n];
        int off = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    public static float[] concat(float[] a, float[] b) {
        if (a == null || a.length == 0) return b == null ? new float[0] : b.clone();
        if (b == null || b.length == 0) return a.clone();
        float[] out = new float[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
