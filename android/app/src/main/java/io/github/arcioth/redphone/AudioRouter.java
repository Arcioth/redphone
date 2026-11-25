package io.github.arcioth.redphone;

import android.content.Context;
import android.media.AudioDeviceInfo;

/**
 * STUB — owned by the AUDIO module. Port of daemon.discover_audio / _route_to_phone / _restore_audio.
 *
 * Contract:
 *  - discover(): finds the red phone USB sink + mic (product name contains "AB13X", or — when
 *    Prefs.ANY_USB — any TYPE_USB_HEADSET/TYPE_USB_DEVICE), and the built-in speaker. Fields may be null.
 *    With Prefs.SIM_PLUG and no USB phone, phone sink/source fall back to the built-in earpiece/speaker + mic.
 *  - routeToPhone(): call audio session on: MODE_IN_COMMUNICATION + setCommunicationDevice(phone sink)
 *    on API 31+ (older: best effort), so WebView WebRTC, SpeechRecognizer and TTS use the handset.
 *    Idempotent; remembers what to restore. Returns discover().
 *  - restore(): undo routeToPhone (clear communication device, previous audio mode). Idempotent.
 *  - isRedPhonePresent(): true if a matching USB audio device is currently connected.
 *  - Never blocks for long; safe to call with the controller lock held.
 */
public final class AudioRouter {

    public static final class PhoneAudio {
        public final AudioDeviceInfo phoneSink;
        public final AudioDeviceInfo phoneSource;
        public final AudioDeviceInfo speakerSink;

        public PhoneAudio(AudioDeviceInfo phoneSink, AudioDeviceInfo phoneSource, AudioDeviceInfo speakerSink) {
            this.phoneSink = phoneSink;
            this.phoneSource = phoneSource;
            this.speakerSink = speakerSink;
        }
    }

    public AudioRouter(Context ctx) {
    }

    public PhoneAudio discover() {
        return new PhoneAudio(null, null, null);
    }

    public PhoneAudio routeToPhone() {
        return discover();
    }

    public void restore() {
    }

    public boolean isRedPhonePresent() {
        return false;
    }

    /** Human readable name of the handset audio device for the UI, or null. */
    public String phoneDeviceName() {
        return null;
    }
}
