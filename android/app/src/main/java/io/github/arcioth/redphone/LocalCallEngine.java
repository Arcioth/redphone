package io.github.arcioth.redphone;

import android.content.Context;

/**
 * STUB — owned by the LOCAL AI module. Port of local_call.py (LOCAL AI + EVIL TWIN personas).
 *
 * Contract:
 *  - start(): non-blocking; replaces any running session. Pipeline: Android SpeechRecognizer (on-device
 *    preferred) -> Ollama /api/chat streaming (Prefs.ollamaUrl(), model Prefs.OLLAMA_MODEL or auto-pick
 *    like pick_ollama_model) -> Android TextToSpeech rendered to PCM and played on the handset sink.
 *    Same Turkish/English system prompts, openers, sentence streaming and TR/AUTO/EN rules as local_call.py.
 *  - Reports progress exactly like post_heartbeat(): controller.onExtHeartbeat(persona, status, detail)
 *    with status "connecting" while loading, then "connected" + live detail ("Dinliyor (TR)...").
 *  - stop(): idempotent, non-blocking; after it returns no further heartbeats/audio for that session.
 */
public final class LocalCallEngine {
    public LocalCallEngine(Context ctx) {
    }

    public void start(String persona, String mode, String lang, String sessionId, AudioRouter.PhoneAudio audio) {
    }

    public void stop() {
    }

    public boolean isRunning() {
        return false;
    }
}
