package io.github.arcioth.redphone;

/**
 * Pure-Java tone synthesis (no Android imports) for the two loops the desktop plays from optional
 * yt-dlp clips (README: assets/dialtone.wav, assets/ringtone.wav). Used by {@link SoundPlayer} when the
 * APK was built without them. Both tones are one loop cycle long and start and end in silence, so
 * {@code SoundPlayer.playLoop} can repeat them back to back without clicks.
 */
public final class ToneSynth {
    public static final int SAMPLE_RATE = 24000;

    /** -3 dBFS. */
    static final double RING_PEAK = 0.7079;
    /**
     * -6 dBFS for the steady two-tone ringback (crest factor ~3 dB, so its RMS sits near -9 dBFS,
     * already louder than the bell); keeps it in line with the -6 dBFS pickup/hangup clicks.
     */
    static final double DIAL_PEAK = 0.5012;

    static final double CYCLE_SEC = 6.0;
    static final double ON_SEC = 2.0;
    static final double FADE_SEC = 0.010;

    /** Hammer swings at the 20 Hz ringing-current rate, striking gong A then gong B on every swing. */
    static final double HAMMER_HZ = 20.0;

    // Bell modes {frequency Hz, relative amplitude, 1/e decay seconds}: inharmonic dome partials
    // between ~0.7 and ~2.6 kHz. The strongest mode of each gong is a slightly split doublet (as in
    // real bells, whose near-degenerate modes beat slowly), which gives the ring its shimmer.
    // The upper partials die within a swing or two, so every hammer blow is heard as a bright attack:
    // that 20 Hz flutter is the "trill" of an old desk phone.
    private static final double[][] GONG_A = {
            {735.0, 0.30, 0.40},
            {1179.2, 0.55, 0.30},
            {1180.8, 0.55, 0.30},
            {1712.0, 0.50, 0.10},
            {2347.0, 0.42, 0.045},
            {2604.0, 0.26, 0.030},
    };
    private static final double[][] GONG_B = {
            {872.0, 0.26, 0.38},
            {1394.3, 0.52, 0.28},
            {1395.7, 0.52, 0.28},
            {2031.0, 0.46, 0.09},
            {2566.0, 0.36, 0.040},
    };
    /** Fraction of a gong's vibration left after the hammer touches it (contact damping). */
    static final double HAMMER_DAMP = 0.6;

    private ToneSynth() {
    }

    /**
     * What the earpiece plays while the AI "line" connects: North American ringback (440 Hz + 480 Hz),
     * 2 s on / 4 s off, one 6 s cycle, 10 ms raised-cosine fades. Mono PCM16 at {@link #SAMPLE_RATE}.
     */
    public static WavIO.Pcm dialtone() {
        int sr = SAMPLE_RATE;
        int total = (int) Math.round(CYCLE_SEC * sr);
        int on = (int) Math.round(ON_SEC * sr);
        int fade = (int) Math.round(FADE_SEC * sr);
        double amp = DIAL_PEAK / 2.0; // two equal sines: worst-case peak = 2 * amp
        double w1 = 2 * Math.PI * 440.0 / sr;
        double w2 = 2 * Math.PI * 480.0 / sr;
        double[] x = new double[total];
        for (int i = 0; i < on; i++) {
            x[i] = amp * (Math.sin(w1 * i) + Math.sin(w2 * i)) * edgeFade(i, on, fade);
        }
        return toPcm(x, sr, 1.0); // already scaled; the true peak sits just under DIAL_PEAK
    }

    /**
     * Classic electromechanical telephone bell for the loudspeaker: 2 s of trill (a hammer at
     * {@link #HAMMER_HZ} alternately striking two gongs, each strike ringing a few exponentially
     * decaying inharmonic partials plus a faint contact click), then the gongs ring out into 4 s of
     * silence. One 6 s cycle, mono PCM16 at {@link #SAMPLE_RATE}, peak -3 dBFS.
     */
    public static WavIO.Pcm ringtone() {
        int sr = SAMPLE_RATE;
        int total = (int) Math.round(CYCLE_SEC * sr);
        double[] x = new double[total];
        // Fixed seed: the tone is identical on every run/device.
        Lcg rnd = new Lcg(0x5eed2a1L);

        double swing = 1.0 / HAMMER_HZ;
        int strikesPerGong = (int) Math.round(ON_SEC * HAMMER_HZ); // 40 swings in 2 s
        int nStrikes = strikesPerGong * 2;
        int[] strikeAt = new int[nStrikes];
        double[] strikeGain = new double[nStrikes];
        boolean[] strikeIsA = new boolean[nStrikes];
        for (int k = 0; k < nStrikes; k++) {
            int swingIdx = k / 2;
            boolean a = (k % 2) == 0;
            // Gong B is hit half a swing after gong A; a little timing jitter keeps the trill organic.
            double t = swingIdx * swing + (a ? 0.0 : swing * 0.5) + 0.002 + rnd.uniform(-0.0006, 0.0006);
            strikeAt[k] = (int) Math.round(t * sr);
            strikeIsA[k] = a;
            // The ringing current builds up over the first few swings; strikes vary a little in force.
            double ramp = 1.0 - 0.45 * Math.exp(-swingIdx / 1.6);
            strikeGain[k] = ramp * (1.0 + rnd.uniform(-0.12, 0.12)) * (a ? 1.0 : 0.92);
        }

        addGong(x, sr, GONG_A, strikeAt, strikeGain, strikeIsA, true);
        addGong(x, sr, GONG_B, strikeAt, strikeGain, strikeIsA, false);
        addHammerClicks(x, sr, strikeAt, strikeGain, rnd);

        // The partials have died away by ~4.5 s (< -80 dB); still force an exact silent loop seam.
        int fadeStart = (int) Math.round(5.4 * sr);
        int fadeEnd = (int) Math.round(5.9 * sr);
        for (int i = fadeStart; i < total; i++) {
            double g = i >= fadeEnd ? 0.0 : 0.5 * (1 + Math.cos(Math.PI * (i - fadeStart) / (double) (fadeEnd - fadeStart)));
            x[i] *= g;
        }
        double peak = 0;
        for (double v : x) peak = Math.max(peak, Math.abs(v));
        return toPcm(x, sr, peak > 1e-9 ? RING_PEAK / peak : 1.0);
    }

    /** Modal synthesis: each partial is a damped resonator kicked by that gong's strikes. */
    private static void addGong(double[] x, int sr, double[][] modes, int[] strikeAt, double[] strikeGain,
                                boolean[] strikeIsA, boolean gongA) {
        for (double[] m : modes) {
            double w = 2 * Math.PI * m[0] / sr;
            double r = Math.exp(-1.0 / (m[2] * sr));
            double c = r * Math.cos(w);
            double s = r * Math.sin(w);
            double re = 0, im = 0;
            int k = 0;
            for (int i = 0; i < x.length; i++) {
                double nre = re * c - im * s;
                double nim = re * s + im * c;
                re = nre;
                im = nim;
                while (k < strikeAt.length && strikeAt[k] <= i) {
                    // A strike adds a real kick to the phasor: the partial starts in sine phase (from
                    // zero) and superposes with whatever the gong was still ringing.
                    if (strikeIsA[k] == gongA) {
                        re = re * HAMMER_DAMP + m[1] * strikeGain[k];
                        im *= HAMMER_DAMP;
                    }
                    k++;
                }
                x[i] += im;
            }
        }
    }

    /** A very short, high-passed noise burst at each hammer contact (the metallic "tick"). */
    private static void addHammerClicks(double[] x, int sr, int[] strikeAt, double[] strikeGain, Lcg rnd) {
        int len = (int) (0.006 * sr);
        double tau = 0.0012 * sr;
        for (int k = 0; k < strikeAt.length; k++) {
            double prev = 0;
            for (int j = 0; j < len; j++) {
                int i = strikeAt[k] + j;
                if (i >= x.length) break;
                double n = rnd.uniform(-1, 1);
                double hp = n - prev; // first difference: removes the low end, keeps it a "tick"
                prev = n;
                x[i] += 0.08 * strikeGain[k] * hp * Math.exp(-j / tau);
            }
        }
    }

    /** Raised-cosine fade over {@code fade} samples at both ends of a burst of {@code len} samples. */
    static double edgeFade(int i, int len, int fade) {
        if (fade <= 0) return 1.0;
        if (i < fade) return 0.5 * (1 - Math.cos(Math.PI * i / fade));
        int fromEnd = len - 1 - i;
        if (fromEnd < fade) return 0.5 * (1 - Math.cos(Math.PI * Math.max(0, fromEnd) / fade));
        return 1.0;
    }

    private static WavIO.Pcm toPcm(double[] x, int sr, double gain) {
        short[] out = new short[x.length];
        for (int i = 0; i < x.length; i++) {
            long v = Math.round(x[i] * gain * 32767.0);
            out[i] = (short) Math.max(-32768, Math.min(32767, v));
        }
        return new WavIO.Pcm(out, sr, 1);
    }

    /** Tiny deterministic PRNG (java.util.Random would do too; this keeps the stream explicit). */
    private static final class Lcg {
        private long state;

        Lcg(long seed) {
            state = seed;
        }

        double uniform(double lo, double hi) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            double u = (state >>> 11) / (double) (1L << 53);
            return lo + (hi - lo) * u;
        }
    }
}
