package io.github.arcioth.redphone;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Pure-Java WAV helpers (no Android imports): voice_clone.read_wav_mono_float / write_wav_pcm16 / resample_linear. */
public final class WavIO {

    /** Decoded PCM: interleaved 16-bit samples. */
    public static final class Pcm {
        public final short[] samples;
        public final int sampleRate;
        public final int channels;

        public Pcm(short[] samples, int sampleRate, int channels) {
            this.samples = samples;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }

        public double seconds() {
            return sampleRate <= 0 || channels <= 0 ? 0 : samples.length / (double) channels / sampleRate;
        }

        /** Mono float samples in [-1, 1] (channels averaged). */
        public float[] monoFloat() {
            int frames = samples.length / Math.max(1, channels);
            float[] out = new float[frames];
            for (int i = 0; i < frames; i++) {
                int acc = 0;
                for (int c = 0; c < channels; c++) acc += samples[i * channels + c];
                out[i] = acc / (channels * 32768f);
            }
            return out;
        }
    }

    private WavIO() {
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** Parses a RIFF/WAVE file with 16-bit PCM (also accepts 8-bit unsigned, 24/32-bit int and 32-bit float). */
    public static Pcm parse(byte[] data) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 12 || bb.getInt(0) != 0x46464952 || bb.getInt(8) != 0x45564157) {
            throw new IOException("not a RIFF/WAVE file");
        }
        int pos = 12;
        int fmt = -1, channels = 0, rate = 0, bits = 0;
        int dataOff = -1, dataLen = 0;
        while (pos + 8 <= data.length) {
            int id = bb.getInt(pos);
            int len = bb.getInt(pos + 4);
            int body = pos + 8;
            if (len < 0 || body + len > data.length) len = data.length - body; // tolerate streamed headers
            if (id == 0x20746d66) { // "fmt "
                fmt = bb.getShort(body) & 0xFFFF;
                channels = bb.getShort(body + 2) & 0xFFFF;
                rate = bb.getInt(body + 4);
                bits = bb.getShort(body + 14) & 0xFFFF;
                if (fmt == 0xFFFE && len >= 26) fmt = bb.getShort(body + 24) & 0xFFFF; // WAVE_FORMAT_EXTENSIBLE
            } else if (id == 0x61746164) { // "data"
                dataOff = body;
                dataLen = len;
                break;
            }
            pos = body + len + (len & 1);
        }
        if (fmt < 0 || dataOff < 0 || channels <= 0 || rate <= 0) throw new IOException("bad WAV header");
        int bytesPer = bits / 8;
        if (bytesPer <= 0) throw new IOException("bad bit depth " + bits);
        int n = dataLen / bytesPer;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            int p = dataOff + i * bytesPer;
            if (fmt == 3 && bits == 32) {
                float f = bb.getFloat(p);
                out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(f * 32767f)));
            } else if (fmt == 1 && bits == 16) {
                out[i] = bb.getShort(p);
            } else if (fmt == 1 && bits == 8) {
                out[i] = (short) (((data[p] & 0xFF) - 128) << 8);
            } else if (fmt == 1 && bits == 24) {
                int v = (data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8) | (data[p + 2] << 16);
                out[i] = (short) (v >> 8);
            } else if (fmt == 1 && bits == 32) {
                out[i] = (short) (bb.getInt(p) >> 16);
            } else {
                throw new IOException("unsupported WAV format " + fmt + "/" + bits + "bit");
            }
        }
        return new Pcm(out, rate, channels);
    }

    public static Pcm read(InputStream in) throws IOException {
        return parse(readAll(in));
    }

    /** voice_clone.write_wav_pcm16: mono 16-bit PCM. */
    public static void writeMono16(File file, float[] samples, int sampleRate) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdirs " + parent);
        File tmp = new File(file.getPath() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(header(samples.length * 2, sampleRate, 1));
            ByteBuffer bb = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (float s : samples) {
                float c = Math.max(-1f, Math.min(1f, s));
                bb.putShort((short) (c * 32767f));
            }
            out.write(bb.array());
        } finally {
            out.close();
        }
        if (!tmp.renameTo(file)) throw new IOException("rename " + tmp);
    }

    /** 44-byte canonical PCM16 header. */
    public static byte[] header(int dataBytes, int sampleRate, int channels) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0x46464952).putInt(36 + dataBytes).putInt(0x45564157);
        h.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) channels);
        h.putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        h.putInt(0x61746164).putInt(dataBytes);
        return h.array();
    }

    /** voice_clone.resample_linear. */
    public static float[] resampleLinear(float[] samples, int srcSr, int dstSr) {
        if (samples.length == 0 || srcSr == dstSr) return samples.clone();
        double ratio = srcSr / (double) dstSr;
        int outLen = Math.max(1, (int) Math.round(samples.length / ratio));
        float[] out = new float[outLen];
        int last = samples.length - 1;
        for (int i = 0; i < outLen; i++) {
            double p = i * ratio;
            int idx = (int) p;
            double frac = p - idx;
            out[i] = idx >= last ? samples[last] : (float) (samples[idx] * (1.0 - frac) + samples[idx + 1] * frac);
        }
        return out;
    }

    /** Header-only duration of a WAV file (cheap), or 0 when missing/invalid. */
    public static double durationSeconds(File f) {
        if (f == null || !f.isFile()) return 0;
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            int len = (int) Math.min(raf.length(), 4096);
            byte[] head = new byte[len];
            raf.readFully(head);
            ByteBuffer bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            int pos = 12, channels = 0, rate = 0, bits = 0;
            while (pos + 8 <= len) {
                int id = bb.getInt(pos);
                int clen = bb.getInt(pos + 4);
                if (id == 0x20746d66 && pos + 24 <= len) {
                    channels = bb.getShort(pos + 10) & 0xFFFF;
                    rate = bb.getInt(pos + 12);
                    bits = bb.getShort(pos + 22) & 0xFFFF;
                } else if (id == 0x61746164) {
                    long dataLen = Math.min(clen & 0xFFFFFFFFL, raf.length() - pos - 8);
                    if (rate <= 0 || channels <= 0 || bits <= 0) return 0;
                    return dataLen / (double) (rate * channels * (bits / 8));
                }
                if (clen < 0) return 0;
                pos += 8 + clen + (clen & 1);
            }
        } catch (IOException e) {
            return 0;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
        return 0;
    }
}
