package io.github.arcioth.redphone;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.List;
import java.util.Locale;

/**
 * Port of daemon.discover_audio / _route_to_phone / _restore_audio.
 *
 * <p>The desktop moves the PipeWire default sink/source to the AB13X nodes for the duration of a
 * call. Android has no "default device" an app may change; the equivalent is a call audio session:
 * {@code MODE_IN_COMMUNICATION} plus (API 31+) {@code setCommunicationDevice}, which is what WebView
 * WebRTC, SpeechRecognizer, TextToSpeech and every voice-communication AudioTrack/AudioRecord follow.
 *
 * <p>Contract:
 * <ul>
 *  <li>discover(): finds the red phone USB sink + mic (product name contains "AB13X", or, when
 *    Prefs.ANY_USB, any TYPE_USB_HEADSET/TYPE_USB_DEVICE), and the built-in speaker. Fields may be null.
 *    With Prefs.SIM_PLUG and no USB phone, phone sink/source fall back to the built-in earpiece (or
 *    speaker on devices without one) + built-in mic.</li>
 *  <li>routeToPhone(): call audio session on. Idempotent; remembers what to restore. Returns discover().</li>
 *  <li>restore(): undo routeToPhone (clear communication device, previous audio mode). Idempotent.</li>
 *  <li>isRedPhonePresent(): a matching USB audio device is connected (SIM mode does not count).</li>
 *  <li>Never blocks for long; safe to call with the controller lock held. Its own lock is only ever
 *    taken after (inside) the controller lock or alone, never the other way round.</li>
 * </ul>
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

    /** The desktop matches PipeWire node names containing this (alsa_output.usb-Generic_AB13X_USB_Audio_...). */
    static final String PHONE_TAG = "ab13x";
    static final String SIM_EARPIECE_NAME = "Built-in earpiece (SIM)";
    static final String SIM_SPEAKER_NAME = "Built-in speaker (SIM)";
    /** daemon unmutes the phone sink; here: raise a zero voice-call volume to ~60%. */
    static final float VOICE_VOLUME_FALLBACK = 0.6f;

    private static HandlerThread sThread;
    private static Handler sHandler;

    private final Prefs prefs;
    private final AudioManager am;

    private final Object lock = new Object();
    // ---- guarded by lock
    private boolean active;
    private int savedMode = AudioManager.MODE_NORMAL;
    private boolean savedSpeakerphone;
    private boolean speakerphoneChanged;
    private int appliedDeviceId = -1;
    private AudioFocusRequest focusRequest;
    private AudioDeviceCallback deviceCallback;

    public AudioRouter(Context ctx) {
        Context app = ctx.getApplicationContext();
        this.prefs = new Prefs(app);
        this.am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
    }

    // ------------------------------------------------------------------ discovery

    public PhoneAudio discover() {
        return find(am, prefs.getBool(Prefs.ANY_USB, false), prefs.getBool(Prefs.SIM_PLUG, false));
    }

    /** Lock-free discovery, shared with SoundPlayer (which runs it on its own worker threads). */
    static PhoneAudio find(AudioManager am, boolean anyUsb, boolean sim) {
        if (am == null) return new PhoneAudio(null, null, null);
        AudioDeviceInfo[] outs = devices(am, AudioManager.GET_DEVICES_OUTPUTS);
        AudioDeviceInfo[] ins = devices(am, AudioManager.GET_DEVICES_INPUTS);
        AudioDeviceInfo sink = findUsbPhone(outs, anyUsb);
        AudioDeviceInfo source = findUsbPhone(ins, anyUsb);
        AudioDeviceInfo speaker = firstOfType(outs, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        if (sink == null && source == null && sim) {
            // No-hardware demo: the Android phone itself is the handset.
            sink = firstOfType(outs, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
            if (sink == null) sink = speaker; // tablets have no earpiece
            source = firstOfType(ins, AudioDeviceInfo.TYPE_BUILTIN_MIC);
        }
        return new PhoneAudio(sink, source, speaker);
    }

    private static AudioDeviceInfo[] devices(AudioManager am, int flags) {
        try {
            AudioDeviceInfo[] d = am.getDevices(flags);
            return d != null ? d : new AudioDeviceInfo[0];
        } catch (RuntimeException e) {
            return new AudioDeviceInfo[0];
        }
    }

    static boolean isUsbType(int type) {
        return type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE;
    }

    static boolean isAb13x(AudioDeviceInfo d) {
        CharSequence name = d.getProductName();
        return name != null && name.toString().toLowerCase(Locale.ROOT).contains(PHONE_TAG);
    }

    /** AB13X first (USB headset type preferred); with anyUsb, else any USB headset, else any USB device. */
    static AudioDeviceInfo findUsbPhone(AudioDeviceInfo[] devs, boolean anyUsb) {
        AudioDeviceInfo ab = null, headset = null, device = null;
        for (AudioDeviceInfo d : devs) {
            if (d == null) continue;
            int t = d.getType();
            if (!isUsbType(t)) continue;
            if (isAb13x(d)) {
                if (ab == null || (t == AudioDeviceInfo.TYPE_USB_HEADSET && ab.getType() != t)) ab = d;
            } else if (t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                if (headset == null) headset = d;
            } else if (device == null) {
                device = d;
            }
        }
        if (ab != null) return ab;
        if (!anyUsb) return null;
        return headset != null ? headset : device;
    }

    private static AudioDeviceInfo firstOfType(AudioDeviceInfo[] devs, int type) {
        for (AudioDeviceInfo d : devs) {
            if (d != null && d.getType() == type) return d;
        }
        return null;
    }

    static String describe(AudioDeviceInfo d) {
        if (d == null) return "default";
        CharSequence n = d.getProductName();
        return (n == null ? "" : n.toString().trim()) + " [type " + d.getType() + " id " + d.getId() + "]";
    }

    // ------------------------------------------------------------------ status (cheap, lock-free)

    public boolean isRedPhonePresent() {
        if (am == null) return false;
        return findUsbPhone(devices(am, AudioManager.GET_DEVICES_ALL), prefs.getBool(Prefs.ANY_USB, false)) != null;
    }

    /** Human readable name of the handset audio device for the UI, or null. */
    public String phoneDeviceName() {
        PhoneAudio a = discover();
        AudioDeviceInfo d = a.phoneSink != null ? a.phoneSink : a.phoneSource;
        if (d == null) return null;
        if (isUsbType(d.getType())) {
            CharSequence n = d.getProductName();
            String s = n == null ? "" : n.toString().trim();
            return s.isEmpty() ? "USB audio device" : s;
        }
        return d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ? SIM_SPEAKER_NAME : SIM_EARPIECE_NAME;
    }

    // ------------------------------------------------------------------ call audio session

    @SuppressWarnings("deprecation") // speakerphone flag: the only routing control before API 31
    public PhoneAudio routeToPhone() {
        PhoneAudio a = discover();
        if (am == null) return a;
        synchronized (lock) {
            // Each step is independent: a SecurityException / IllegalArgumentException from one vendor
            // audio call must not skip the others (worst case the call runs on default routing).
            if (!active) {
                active = true;
                savedMode = AudioManager.MODE_NORMAL;
                speakerphoneChanged = false;
                appliedDeviceId = -1;
                try {
                    savedMode = am.getMode();
                    if (Build.VERSION.SDK_INT < 31) savedSpeakerphone = am.isSpeakerphoneOn();
                } catch (RuntimeException e) {
                    RpLog.log("audio: cannot read audio mode: " + e);
                }
                requestFocus();
                registerDeviceCallback();
                RpLog.log("audio: call session on (previous mode " + savedMode + ") phone=" + describe(a.phoneSink)
                        + " mic=" + describe(a.phoneSource));
            }
            try {
                if (am.getMode() != AudioManager.MODE_IN_COMMUNICATION) am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            } catch (RuntimeException e) {
                RpLog.log("audio: setMode error: " + e);
            }
            try {
                applyRoute(a);
            } catch (RuntimeException e) {
                RpLog.log("audio: route error: " + e);
            }
            try {
                if (am.isMicrophoneMute()) am.setMicrophoneMute(false);
            } catch (RuntimeException e) {
                RpLog.log("audio: mic unmute error: " + e);
            }
            ensureVoiceVolume();
        }
        return a;
    }

    @SuppressWarnings("deprecation")
    public void restore() {
        if (am == null) return;
        synchronized (lock) {
            if (!active) return;
            active = false;
            unregisterDeviceCallback();
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    // Only cancels this app's own request; other apps' selections are untouched.
                    am.clearCommunicationDevice();
                } else if (speakerphoneChanged) {
                    am.setSpeakerphoneOn(savedSpeakerphone);
                }
            } catch (RuntimeException e) {
                RpLog.log("audio: route restore error: " + e);
            }
            speakerphoneChanged = false;
            appliedDeviceId = -1;
            try {
                // API 31+ keeps one mode request per app, so NORMAL just withdraws ours (and must be sent even
                // while telephony owns IN_CALL, or our request would come back after that call). Older
                // releases have one global mode: only undo it if it is still ours.
                if (Build.VERSION.SDK_INT >= 31 || am.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
                    am.setMode(restorableMode(savedMode));
                }
            } catch (RuntimeException e) {
                RpLog.log("audio: mode restore error: " + e);
            }
            abandonFocus();
            RpLog.log("audio: call session off");
        }
    }

    /**
     * Apps may only select MODE_NORMAL or MODE_IN_COMMUNICATION; RINGTONE / IN_CALL / CALL_SCREENING
     * belong to telephony, and restoring IN_COMMUNICATION would leave us owning a call mode.
     */
    static int restorableMode(int previous) {
        return previous == AudioManager.MODE_NORMAL ? previous : AudioManager.MODE_NORMAL;
    }

    /** lock held. Points voice-communication audio at the handset. */
    @SuppressWarnings("deprecation")
    private void applyRoute(PhoneAudio a) {
        AudioDeviceInfo sink = a.phoneSink;
        if (Build.VERSION.SDK_INT >= 31) {
            if (sink == null) return; // USB audio not enumerated yet: the device callback retries
            AudioDeviceInfo cur = am.getCommunicationDevice();
            if (cur != null && cur.getId() == sink.getId()) {
                appliedDeviceId = sink.getId();
                return;
            }
            AudioDeviceInfo target = null;
            List<AudioDeviceInfo> avail = am.getAvailableCommunicationDevices();
            if (avail != null) {
                for (AudioDeviceInfo d : avail) {
                    if (d != null && d.getId() == sink.getId()) target = d;
                }
                if (target == null) {
                    // Same physical device can be listed under a different port id; match type + name.
                    for (AudioDeviceInfo d : avail) {
                        if (d != null && d.getType() == sink.getType() && sameName(d, sink)) target = d;
                    }
                }
            }
            if (target == null) {
                if (appliedDeviceId != -2) RpLog.log("audio: " + describe(sink) + " is not a communication device");
                appliedDeviceId = -2;
                return;
            }
            boolean ok = am.setCommunicationDevice(target);
            appliedDeviceId = target.getId();
            RpLog.log("audio: communication device -> " + describe(target) + (ok ? "" : " (refused)"));
        } else {
            // Pre-12 policy already prefers a wired/USB headset over the earpiece in communication mode;
            // only the speakerphone flag needs to match the chosen handset.
            boolean wantSpeaker = sink != null && sink.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
            if (am.isSpeakerphoneOn() != wantSpeaker) {
                am.setSpeakerphoneOn(wantSpeaker);
                speakerphoneChanged = true;
            }
        }
    }

    private static boolean sameName(AudioDeviceInfo a, AudioDeviceInfo b) {
        CharSequence x = a.getProductName(), y = b.getProductName();
        return x != null && y != null && x.toString().equals(y.toString());
    }

    private void ensureVoiceVolume() {
        int stream = AudioManager.STREAM_VOICE_CALL;
        try {
            if (am.isStreamMute(stream)) am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
            if (am.getStreamVolume(stream) <= 0) {
                int max = am.getStreamMaxVolume(stream);
                int vol = Math.max(1, Math.round(max * VOICE_VOLUME_FALLBACK));
                am.setStreamVolume(stream, vol, 0);
                RpLog.log("audio: voice call volume 0 -> " + vol + "/" + max);
            }
        } catch (RuntimeException e) {
            // SecurityException under Do Not Disturb policies on some builds: not fatal.
            RpLog.log("audio: voice volume error: " + e);
        }
    }

    /** lock held. Transient focus for the call: music pauses, resumes on restore(). */
    private void requestFocus() {
        if (focusRequest != null) return;
        try {
            // A distinct listener per request keeps this focus client separate from SoundPlayer's ring
            // focus (requests without a listener share the AudioManager's id and abandon each other).
            AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int focusChange) {
                            // The call keeps going whatever else wants to play.
                        }
                    }, handler())
                    .build();
            am.requestAudioFocus(req);
            focusRequest = req;
        } catch (RuntimeException e) {
            RpLog.log("audio: focus request error: " + e);
        }
    }

    private void abandonFocus() {
        if (focusRequest == null) return;
        try {
            am.abandonAudioFocusRequest(focusRequest);
        } catch (RuntimeException ignored) {
        }
        focusRequest = null;
    }

    /** lock held. While a call session is on, follow the handset if audio devices come and go. */
    private void registerDeviceCallback() {
        if (deviceCallback != null) return;
        AudioDeviceCallback cb = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                reapply();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                reapply();
            }
        };
        try {
            am.registerAudioDeviceCallback(cb, handler());
            deviceCallback = cb;
        } catch (RuntimeException e) {
            RpLog.log("audio: device callback error: " + e);
        }
    }

    private void unregisterDeviceCallback() {
        if (deviceCallback == null) return;
        try {
            am.unregisterAudioDeviceCallback(deviceCallback);
        } catch (RuntimeException ignored) {
        }
        deviceCallback = null;
    }

    /** Runs on the router HandlerThread; takes only the router lock (never the controller lock). */
    private void reapply() {
        PhoneAudio a = discover();
        synchronized (lock) {
            if (!active) return;
            try {
                if (am.getMode() != AudioManager.MODE_IN_COMMUNICATION) return; // a real phone call owns audio now
                applyRoute(a);
            } catch (RuntimeException e) {
                RpLog.log("audio: re-route error: " + e);
            }
        }
    }

    /** Shared background looper for audio callbacks (focus, device changes); never the main thread. */
    static synchronized Handler handler() {
        if (sHandler == null) {
            sThread = new HandlerThread("redphone-audio");
            sThread.start();
            sHandler = new Handler(sThread.getLooper());
        }
        return sHandler;
    }
}
