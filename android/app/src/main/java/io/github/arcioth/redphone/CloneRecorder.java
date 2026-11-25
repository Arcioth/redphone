package io.github.arcioth.redphone;

import android.content.Context;

import java.io.File;

/**
 * STUB — owned by the AUDIO module. Evil Twin voice sample (daemon.record_clone_sample +
 * voice_clone.prepare_reference_16k / write_wav_pcm16) and voice matching data.
 *
 * Contract:
 *  - record(audio, cb): async. Sleep 150 ms, capture 5 s of 16 kHz mono PCM from the handset mic
 *    (AudioRecord + setPreferredDevice(audio.phoneSource)), prepareReference16k(), and if >= 1.5 s
 *    remains save files/my_voice.wav, store median F0 in Prefs.CLONE_F0 and report
 *    "Voice cloned! (X.Xs ready for Evil Twin)"; else "Too quiet — talk closer to the handset and try
 *    Record 5s again"; on error "Voice capture error: ...". Exactly one cb.onDone() call, on any thread.
 *  - saveReference(pcm16k): used by LocalCallEngine to auto-clone from the caller's first utterance.
 *  - durationSeconds(): saved sample length rounded to 0.1 s (0 if none). Cheap (cached by mtime);
 *    called on every UI poll.
 *  - f0Hz(): saved median F0 or 0.
 */
public final class CloneRecorder {

    public interface Callback {
        void onDone(String message);
    }

    public CloneRecorder(Context ctx) {
    }

    public void record(AudioRouter.PhoneAudio audio, Callback cb) {
        cb.onDone("Voice capture error: not implemented");
    }

    /** Stores a 16 kHz mono reference (already captured). Returns seconds saved, or 0 if too short. */
    public double saveReference(float[] pcm16k) {
        return 0;
    }

    public double durationSeconds() {
        return 0;
    }

    public float f0Hz() {
        return 0f;
    }

    public File refFile() {
        return null;
    }

    public void reset() {
    }
}
