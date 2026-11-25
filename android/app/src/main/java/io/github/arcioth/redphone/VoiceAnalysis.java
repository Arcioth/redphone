package io.github.arcioth.redphone;

import java.util.Arrays;

/**
 * Pure-Java voice helpers (no Android imports): voice_clone.prepare_reference_16k and a pitch (F0)
 * estimator used for Evil Twin voice matching on Android (TTS pitch follows the caller's voice).
 */
public final class VoiceAnalysis {
    public static final int REF_SAMPLE_RATE = 16000;

    private VoiceAnalysis() {
    }

    /** Exact port of voice_clone.prepare_reference_16k (silence trim @20 ms / 0.012 RMS, cap, peak normalise). */
    public static float[] prepareReference16k(float[] samples, int sr, double maxSec) {
        float[] pcm = WavIO.resampleLinear(samples, sr, REF_SAMPLE_RATE);
        int hop = REF_SAMPLE_RATE / 50;
        float[] voiced = new float[pcm.length];
        int nv = 0;
        for (int i = 0; i < pcm.length; i += hop) {
            int end = Math.min(pcm.length, i + hop);
            double acc = 0;
            for (int j = i; j < end; j++) acc += pcm[j] * pcm[j];
            double rms = Math.sqrt(acc / (end - i));
            if (rms > 0.012) {
                System.arraycopy(pcm, i, voiced, nv, end - i);
                nv += end - i;
            }
        }
        if (nv >= REF_SAMPLE_RATE * 1.5) pcm = Arrays.copyOf(voiced, nv);
        int maxSamples = (int) (maxSec * REF_SAMPLE_RATE);
        if (pcm.length > maxSamples) pcm = Arrays.copyOf(pcm, maxSamples);
        float peak = 0f;
        for (float x : pcm) peak = Math.max(peak, Math.abs(x));
        if (peak > 1e-4f) {
            float gain = Math.min(4.0f, 0.85f / peak);
            for (int i = 0; i < pcm.length; i++) pcm[i] = Math.max(-1f, Math.min(1f, pcm[i] * gain));
        }
        return pcm;
    }

    public static double rms(float[] x, int from, int to) {
        if (to <= from) return 0;
        double acc = 0;
        for (int i = from; i < to; i++) acc += x[i] * x[i];
        return Math.sqrt(acc / (to - from));
    }

    /**
     * Median fundamental frequency (Hz) over voiced 40 ms frames using normalised autocorrelation
     * (search 70–400 Hz). Returns 0 when fewer than 5 voiced frames are found.
     */
    public static float medianF0(float[] x, int sr) {
        int frame = Math.max(64, (int) (sr * 0.040));
        int hop = Math.max(32, frame / 2);
        int minLag = Math.max(2, sr / 400);
        int maxLag = Math.min(frame - 1, sr / 70);
        float[] f0s = new float[Math.max(1, x.length / hop + 1)];
        int n = 0;
        for (int start = 0; start + frame <= x.length; start += hop) {
            if (rms(x, start, start + frame) < 0.02) continue;
            double e0 = 0;
            for (int i = 0; i < frame; i++) e0 += x[start + i] * x[start + i];
            int bestLag = -1;
            double best = 0;
            double[] r = new double[maxLag + 2];
            for (int lag = minLag; lag <= maxLag + 1 && lag < frame; lag++) {
                double acc = 0, e1 = 0;
                for (int i = 0; i + lag < frame; i++) {
                    acc += x[start + i] * x[start + i + lag];
                    e1 += x[start + i + lag] * x[start + i + lag];
                }
                r[lag] = acc / Math.sqrt(e0 * e1 + 1e-12);
            }
            // first strong local maximum (avoids octave errors from later multiples)
            for (int lag = minLag + 1; lag <= maxLag && lag + 1 < r.length; lag++) {
                if (r[lag] > 0.55 && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) {
                    bestLag = lag;
                    best = r[lag];
                    // prefer a clearly stronger peak at up to 1.25x the lag (rare), else take this one
                    break;
                }
            }
            if (bestLag > 0 && best > 0.55) {
                // parabolic interpolation for sub-sample lag
                double a = r[bestLag - 1], b = r[bestLag], c = r[bestLag + 1];
                double denom = a - 2 * b + c;
                double shift = Math.abs(denom) < 1e-9 ? 0 : 0.5 * (a - c) / denom;
                f0s[n++] = (float) (sr / (bestLag + Math.max(-0.5, Math.min(0.5, shift))));
            }
        }
        if (n < 5) return 0f;
        float[] v = Arrays.copyOf(f0s, n);
        Arrays.sort(v);
        return v[n / 2];
    }
}
