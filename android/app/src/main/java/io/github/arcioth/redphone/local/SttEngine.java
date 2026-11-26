package io.github.arcioth.redphone.local;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;

import io.github.arcioth.redphone.RpLog;

import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * android.speech.SpeechRecognizer in place of faster-whisper. The recognizer is created, used and
 * destroyed on the main looper only (a framework requirement); its callbacks are turned into
 * {@link Event}s that the session worker consumes, so the call flow stays a sequential half-duplex loop
 * like LocalCallRunner.run(). Every listen attempt has an id; events of other attempts are stale.
 */
final class SttEngine {
    static final int READY = 1;
    static final int BEGIN = 2;
    static final int END = 3;
    static final int RESULT = 4;
    static final int ERROR = 5;

    static final class Event {
        final int type;
        final int attempt;
        final int error;
        final String text;
        final String detected;

        Event(int type, int attempt, int error, String text, String detected) {
            this.type = type;
            this.attempt = attempt;
            this.error = error;
            this.text = text;
            this.detected = detected;
        }
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<Event>();
    private final AtomicInteger attempts = new AtomicInteger();
    private volatile boolean destroyed;

    // ---- main thread only
    private SpeechRecognizer recognizer;
    private boolean recognizerOnDevice;
    private int currentAttempt;
    private String detectedLang;

    SttEngine(Context app) {
        this.app = app;
    }

    /** Worker: starts one recognition (posted to the main looper). Returns the attempt id. */
    int listen(final Intent intent, final boolean onDevice) {
        final int id = attempts.incrementAndGet();
        events.clear();
        main.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                currentAttempt = id;
                detectedLang = null;
                try {
                    if (recognizer != null && recognizerOnDevice != onDevice) destroyRecognizer();
                    if (recognizer == null) {
                        recognizer = create(onDevice);
                        recognizerOnDevice = onDevice;
                        recognizer.setRecognitionListener(new Listener(recognizer));
                    }
                    recognizer.startListening(intent);
                } catch (RuntimeException e) {
                    RpLog.log("[local] recognizer start failed: " + e);
                    destroyRecognizer();
                    events.offer(new Event(ERROR, id, SpeechRecognizer.ERROR_CLIENT, null, null));
                }
            }
        });
        return id;
    }

    private SpeechRecognizer create(boolean onDevice) {
        if (onDevice && Build.VERSION.SDK_INT >= 31) {
            try {
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(app);
            } catch (RuntimeException e) {
                RpLog.log("[local] on-device recognizer unavailable: " + e);
            }
        }
        return SpeechRecognizer.createSpeechRecognizer(app);
    }

    private void destroyRecognizer() {
        SpeechRecognizer r = recognizer;
        recognizer = null;
        if (r == null) return;
        try {
            r.cancel();
        } catch (RuntimeException ignored) {
        }
        try {
            r.destroy();
        } catch (RuntimeException ignored) {
        }
    }

    /** Worker: wait for the next event (null on timeout). */
    Event poll(long ms) throws InterruptedException {
        return events.poll(ms, TimeUnit.MILLISECONDS);
    }

    /** Worker: end the current utterance now and deliver its result (the desktop's 12 s cap). */
    void stopListening() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || recognizer == null) return;
                try {
                    recognizer.stopListening();
                } catch (RuntimeException ignored) {
                }
            }
        });
    }

    /** Worker: throw the recognizer away (busy / client / service errors); the next listen makes a new one. */
    void recreate() {
        main.post(new Runnable() {
            @Override
            public void run() {
                destroyRecognizer();
            }
        });
    }

    /** API 33+: ask the on-device recognizer to download the language model for {@code intent}. */
    void triggerModelDownload(final Intent intent) {
        if (Build.VERSION.SDK_INT < 33) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || recognizer == null || !recognizerOnDevice) return;
                try {
                    recognizer.triggerModelDownload(intent);
                    RpLog.log("[local] requested on-device speech model download");
                } catch (RuntimeException e) {
                    RpLog.log("[local] model download request failed: " + e);
                }
            }
        });
    }

    /** Any thread, idempotent: cancel and release the recognizer; no further events. */
    void destroy() {
        destroyed = true;
        events.clear();
        main.post(new Runnable() {
            @Override
            public void run() {
                destroyRecognizer();
            }
        });
    }

    private void emit(SpeechRecognizer owner, int type, int error, String text, String detected) {
        if (destroyed || owner != recognizer) return;
        events.offer(new Event(type, currentAttempt, error, text, detected));
    }

    /** Callbacks arrive on the main thread. */
    private final class Listener implements RecognitionListener {
        private final SpeechRecognizer owner;

        Listener(SpeechRecognizer owner) {
            this.owner = owner;
        }

        @Override
        public void onReadyForSpeech(Bundle params) {
            emit(owner, READY, 0, null, null);
        }

        @Override
        public void onBeginningOfSpeech() {
            emit(owner, BEGIN, 0, null, null);
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
            emit(owner, END, 0, null, null);
        }

        @Override
        public void onError(int error) {
            emit(owner, ERROR, error, null, null);
        }

        @Override
        public void onResults(Bundle results) {
            String text = null;
            String det = null;
            if (results != null) {
                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null) {
                    for (String s : list) {
                        if (s != null && !s.trim().isEmpty()) {
                            text = s;
                            break;
                        }
                    }
                }
                det = results.getString(SpeechRecognizer.DETECTED_LANGUAGE);
            }
            if (det == null) det = detectedLang;
            if (text == null) {
                emit(owner, ERROR, SpeechRecognizer.ERROR_NO_MATCH, null, null);
            } else {
                emit(owner, RESULT, 0, text, det);
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }

        /** API 34+ (EXTRA_ENABLE_LANGUAGE_DETECTION): remember the detected language for onResults. */
        @Override
        public void onLanguageDetection(Bundle results) {
            if (results == null || owner != recognizer) return;
            String lang = results.getString(SpeechRecognizer.DETECTED_LANGUAGE);
            int conf = results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL,
                    SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN);
            if (lang != null && conf != SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_NOT_CONFIDENT) {
                detectedLang = lang;
            }
        }
    }
}
