#!/usr/bin/env python3
"""Fully local voice-call worker for Red Phone.
Supports:
  - local:    faster-whisper (CPU) + Ollama + Piper TTS (Turkish & English)
  - eviltwin: faster-whisper (CPU) + Ollama Evil Twin + ZONOS3-Mini voice cloning
"""
from __future__ import annotations

import argparse
import json
import os
import re
import signal
import subprocess
import sys
import threading
import time
import urllib.request

import numpy as np
import onnxruntime as ort
from faster_whisper import WhisperModel

ROOT = os.path.dirname(os.path.abspath(__file__))
MODELS_DIR = os.path.join(ROOT, "models")
SILERO_VAD_PATH = os.path.join(MODELS_DIR, "silero_vad.onnx")

HOME = os.path.expanduser("~")
DATA_DIR = os.path.join(HOME, ".local", "share", "redphone")
CLONE_REF_WAV = os.path.join(DATA_DIR, "my_voice.wav")

PIPER_TR_MODEL = os.path.join(MODELS_DIR, "piper", "tr_TR-fahrettin-medium.onnx")
PIPER_TR_JSON = PIPER_TR_MODEL + ".json"
PIPER_EN_MODEL = os.path.join(HOME, ".config", "grok-voice", "voice.onnx")
PIPER_EN_JSON = PIPER_EN_MODEL + ".json"

API_BASE = "http://127.0.0.1:47621"
OLLAMA_URL = "http://127.0.0.1:11434"
SAMPLE_RATE = 16000
FRAME_SAMPLES = 512  # 32ms @ 16kHz


def build_system_prompt(persona: str, lang_mode: str) -> str:
    if lang_mode == "tr":
        lang_rule = "HER ZAMAN doğal, samimi, akıcı Türkçe konuş (İngilizce kelime karıştırma)."
    elif lang_mode == "en":
        lang_rule = "ALWAYS reply in natural, fluent English."
    else:
        lang_rule = (
            "Arayan hangi dilde konuşuyorsa (Türkçe veya İngilizce) KESİNLİKLE aynı dilde "
            "doğal ve akıcı cevap ver. Emin değilsen Türkçe konuş."
        )

    if persona == "eviltwin":
        return (
            "Sen arayan kişinin paralel evrenden kırmızı telefonla arayan KÖTÜ İKİZİSİN "
            "ve onun klonlanmış kendi sesiyle konuşuyorsun. "
            "Kurnaz, hazırcevap, hafif tehditkâr ama eğlenceli ve samimi ol; "
            "asil olanın SEN olduğunu, onun sadece sıkıcı yedek kopya olduğunu iddia et ve "
            "kısa laf sokmalarla takıl (asla gerçekten kırıcı veya zararlı olma, eğlenceli kötü ikiz rolü yap). "
            "KRİTİK KURALLAR: Bu canlı bir telefon görüşmesi. Sadece 1 ila 3 kısa, doğal konuşma cümlesi kur. "
            "Asla markdown, yıldız işareti, madde işareti, emoji veya sahne açıklaması yazma. "
            + lang_rule
        )

    return (
        "Masaüstündeki retro kırmızı telefondan canlı konuşan samimi, zeki ve doğal bir sesli asistansın. "
        "KRİTİK KURALLAR: Sadece 1 ila 3 kısa, doğal konuşma cümlesi kur. "
        "Asla markdown, yıldız işareti, madde işareti, emoji veya sahne açıklaması yazma. "
        + lang_rule
    )


def log(msg: str) -> None:
    sys.stderr.write(f"[local_call {time.strftime('%H:%M:%S')}] {msg}\n")
    sys.stderr.flush()


def post_heartbeat(provider: str, session_id: str, status: str, detail: str) -> None:
    payload = json.dumps({
        "provider": provider,
        "session_id": session_id,
        "status": status,
        "detail": detail,
    }).encode("utf-8")
    req = urllib.request.Request(
        f"{API_BASE}/api/ext/heartbeat",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=1.0):
            pass
    except Exception:
        pass


def pick_ollama_model() -> str:
    env_m = os.environ.get("REDPHONE_OLLAMA_MODEL")
    if env_m:
        return env_m
    try:
        with urllib.request.urlopen(f"{OLLAMA_URL}/api/tags", timeout=2.0) as r:
            tags = json.loads(r.read().decode("utf-8")).get("models", [])
            names = [m.get("name", "") for m in tags if m.get("name")]
            for pref in ("qwen", "llama", "mistral", "gemma"):
                for n in names:
                    if pref in n.lower():
                        return n
            if names:
                return names[0]
    except Exception:
        pass
    return "thirdeyeai/Qwen2.5-Coder-7B-Instruct-Uncensored:Q8_0"


def clean_spoken_text(text: str) -> str:
    text = re.sub(r"\*[^*]+\*", " ", text)
    text = re.sub(r"[`#_~>\[\](){}]", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def looks_turkish(text: str) -> bool:
    if any(ch in text for ch in "çğıöşüÇĞİÖŞÜ"):
        return True
    low = f" {text.lower()} "
    for w in (" alo ", " naber ", " merhaba ", " selam ", " evet ", " hayır ", " değil ", " benim ", " senin ", " ne var ", " kanka "):
        if w in low:
            return True
    return False


def piper_info_for_lang(lang: str) -> tuple[str, int]:
    if lang == "tr" and os.path.isfile(PIPER_TR_MODEL):
        model, jpath = PIPER_TR_MODEL, PIPER_TR_JSON
    elif os.path.isfile(PIPER_EN_MODEL):
        model, jpath = PIPER_EN_MODEL, PIPER_EN_JSON
    else:
        model, jpath = PIPER_TR_MODEL, PIPER_TR_JSON
    sr = 22050 if "tr_TR" in model else 16000
    try:
        meta = json.load(open(jpath, "r", encoding="utf-8"))
        sr = int(meta.get("audio", {}).get("sample_rate", sr))
    except Exception:
        pass
    return model, sr


class SileroVad:
    """CPU Silero VAD v5/v6 wrapper using copied silero_vad.onnx."""

    def __init__(self, path: str) -> None:
        self.ready = False
        self.sess = None
        self.state = None
        self.context = np.zeros((1, 64), dtype=np.float32)
        if not os.path.isfile(path):
            return
        try:
            opts = ort.SessionOptions()
            opts.intra_op_num_threads = 1
            opts.inter_op_num_threads = 1
            self.sess = ort.InferenceSession(path, sess_options=opts, providers=["CPUExecutionProvider"])
            self.inputs = [i.name for i in self.sess.get_inputs()]
            self.reset()
            self.ready = True
            log(f"Silero VAD loaded ({self.inputs})")
        except Exception as e:
            log(f"Silero VAD fallback to energy: {e}")

    def reset(self) -> None:
        self.state = np.zeros((2, 1, 128), dtype=np.float32)
        self.context = np.zeros((1, 64), dtype=np.float32)

    def score(self, frame_f32: np.ndarray) -> float:
        if not self.ready or self.sess is None:
            rms = float(np.sqrt(np.mean(frame_f32 * frame_f32)) + 1e-9)
            return min(1.0, rms * 18.0)
        try:
            x = frame_f32.reshape(1, -1).astype(np.float32)
            if "state" in self.inputs:
                x_cat = np.concatenate([self.context, x], axis=1)
                self.context = x[:, -64:].copy()
                feed = {
                    "input": x_cat,
                    "state": self.state,
                    "sr": np.array(SAMPLE_RATE, dtype=np.int64),
                }
                out, self.state = self.sess.run(None, feed)
                return float(out.ravel()[0])
            rms = float(np.sqrt(np.mean(frame_f32 * frame_f32)) + 1e-9)
            return min(1.0, rms * 18.0)
        except Exception:
            rms = float(np.sqrt(np.mean(frame_f32 * frame_f32)) + 1e-9)
            return min(1.0, rms * 18.0)


class LocalCallRunner:
    def __init__(
        self,
        persona: str,
        mode: str,
        lang_mode: str,
        sink: str | None,
        source: str | None,
        session_id: str,
    ) -> None:
        self.persona = persona
        self.mode = mode
        self.lang_mode = lang_mode if lang_mode in ("tr", "auto", "en") else "tr"
        self.active_lang = "en" if self.lang_mode == "en" else "tr"
        self.sink = sink
        self.source = source
        self.session_id = session_id
        self.stop_ev = threading.Event()
        self.speaking_ev = threading.Event()
        self.play_proc: subprocess.Popen | None = None
        self.rec_proc: subprocess.Popen | None = None
        self.play_lock = threading.Lock()

        self.model_name = pick_ollama_model()
        self.history: list[dict[str, str]] = [
            {"role": "system", "content": build_system_prompt(persona, self.lang_mode)}
        ]
        self.whisper: WhisperModel | None = None
        self.vad = SileroVad(SILERO_VAD_PATH)
        self.cloner = None
        self.ref_16k: list[float] = []
        self.clone_seed = 42

    def stop(self) -> None:
        self.stop_ev.set()
        with self.play_lock:
            if self.play_proc and self.play_proc.poll() is None:
                try:
                    self.play_proc.terminate()
                except Exception:
                    pass
        if self.rec_proc and self.rec_proc.poll() is None:
            try:
                self.rec_proc.terminate()
            except Exception:
                pass

    def _load_saved_voice_ref(self) -> None:
        if not os.path.isfile(CLONE_REF_WAV):
            return
        try:
            from voice_clone import prepare_reference_16k, read_wav_mono_float

            samples, sr = read_wav_mono_float(CLONE_REF_WAV)
            ref = prepare_reference_16k(samples, sr, max_sec=6.0)
            if len(ref) >= SAMPLE_RATE * 1.5:
                self.ref_16k = ref
                log(f"Loaded saved voice reference: {len(ref)/SAMPLE_RATE:.2f}s")
        except Exception as e:
            log(f"Failed loading saved voice ref: {e}")

    def _update_voice_ref_from_utterance(self, pcm_f32: np.ndarray) -> bool:
        if self.persona != "eviltwin":
            return False
        if len(self.ref_16k) >= SAMPLE_RATE * 4.5 and os.path.isfile(CLONE_REF_WAV):
            return False
        try:
            from voice_clone import prepare_reference_16k, write_wav_pcm16

            combined = list(self.ref_16k) + pcm_f32.tolist()
            ref = prepare_reference_16k(combined, SAMPLE_RATE, max_sec=6.0)
            if len(ref) >= SAMPLE_RATE * 1.5:
                self.ref_16k = ref
                write_wav_pcm16(CLONE_REF_WAV, ref, SAMPLE_RATE)
                log(f"Updated cloned voice ref -> {len(ref)/SAMPLE_RATE:.2f}s")
                return True
        except Exception as e:
            log(f"Voice ref capture error: {e}")
        return False

    def init_models(self) -> None:
        post_heartbeat(self.persona, self.session_id, "connecting", "Loading local Whisper STT...")
        log(f"Loading faster-whisper base (CPU int8, lang={self.lang_mode})...")
        try:
            self.whisper = WhisperModel(
                "Systran/faster-whisper-base",
                device="cpu",
                compute_type="int8",
                cpu_threads=6,
                local_files_only=True,
            )
        except Exception:
            self.whisper = WhisperModel(
                "base",
                device="cpu",
                compute_type="int8",
                cpu_threads=6,
            )
        log("Whisper ready.")

        if self.persona == "eviltwin":
            post_heartbeat(
                self.persona, self.session_id, "connecting", "Loading ZONOS3-Mini voice cloner..."
            )
            from voice_clone import ZonosVoiceCloner

            self.cloner = ZonosVoiceCloner(prefer_cuda=True, cpu_threads=8)
            log(f"ZONOS3-Mini ready on {self.cloner.backend}")
            self._load_saved_voice_ref()

        threading.Thread(target=self._warm_ollama, daemon=True).start()

    def _warm_ollama(self) -> None:
        opts = {"num_gpu": 22} if self.persona == "eviltwin" else {}
        payload = json.dumps({
            "model": self.model_name,
            "messages": [{"role": "user", "content": "selam"}],
            "stream": False,
            "keep_alive": "10m",
            "options": {**opts, "num_predict": 1},
        }).encode("utf-8")
        req = urllib.request.Request(
            f"{OLLAMA_URL}/api/chat",
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=20.0):
                log(f"Ollama warmed: {self.model_name}")
        except Exception as e:
            log(f"Ollama warm warning: {e}")

    def speak_piper(self, text: str, lang: str | None = None) -> None:
        text = clean_spoken_text(text)
        if not text or self.stop_ev.is_set():
            return
        use_lang = lang or self.active_lang
        if self.lang_mode == "auto" and looks_turkish(text):
            use_lang = "tr"
        model_path, sr = piper_info_for_lang(use_lang)
        if not os.path.isfile(model_path):
            log(f"Missing piper model {model_path}")
            return
        piper_cmd = ["piper", "--model", model_path, "--output-raw"]
        play_cmd = [
            "paplay",
            "--raw",
            f"--rate={sr}",
            "--channels=1",
            "--format=s16le",
        ]
        if self.sink:
            play_cmd.append(f"--device={self.sink}")

        self.speaking_ev.set()
        try:
            p_tts = subprocess.Popen(
                piper_cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
            )
            p_play = subprocess.Popen(
                play_cmd,
                stdin=p_tts.stdout,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            with self.play_lock:
                self.play_proc = p_play
            assert p_tts.stdin is not None
            assert p_tts.stdout is not None
            p_tts.stdout.close()
            p_tts.stdin.write((text + "\n").encode("utf-8"))
            p_tts.stdin.close()
            while p_play.poll() is None and not self.stop_ev.is_set():
                time.sleep(0.03)
            if self.stop_ev.is_set():
                p_play.terminate()
                p_tts.terminate()
            p_tts.wait(timeout=2.0)
        except Exception as e:
            log(f"piper speak error: {e}")
        finally:
            with self.play_lock:
                self.play_proc = None
            time.sleep(0.22)
            self.speaking_ev.clear()

    def speak_cloned(self, text: str) -> None:
        text = clean_spoken_text(text)
        if not text or self.stop_ev.is_set():
            return
        if not self.cloner or len(self.ref_16k) < SAMPLE_RATE:
            self.speak_piper(text)
            return

        self.clone_seed = (self.clone_seed + 1) % 9999
        try:
            pcm_bytes, sr, gen_ms = self.cloner.synthesize(
                text, self.ref_16k, seed=self.clone_seed
            )
            dur = (len(pcm_bytes) / 4.0) / sr
            log(f"ZONOS3 cloned {dur:.2f}s in {gen_ms:.0f}ms")
        except Exception as e:
            log(f"ZONOS3 synth error, falling back to piper: {e}")
            self.speak_piper(text)
            return

        if self.stop_ev.is_set():
            return

        play_cmd = [
            "paplay",
            "--raw",
            f"--rate={sr}",
            "--channels=1",
            "--format=float32le",
        ]
        if self.sink:
            play_cmd.append(f"--device={self.sink}")

        self.speaking_ev.set()
        try:
            p = subprocess.Popen(
                play_cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            with self.play_lock:
                self.play_proc = p
            assert p.stdin is not None
            chunk_sz = sr * 4 // 10
            for off in range(0, len(pcm_bytes), chunk_sz):
                if self.stop_ev.is_set():
                    break
                p.stdin.write(pcm_bytes[off : off + chunk_sz])
            p.stdin.close()
            while p.poll() is None and not self.stop_ev.is_set():
                time.sleep(0.03)
            if self.stop_ev.is_set() and p.poll() is None:
                p.terminate()
        except Exception as e:
            log(f"cloned play error: {e}")
        finally:
            with self.play_lock:
                self.play_proc = None
            time.sleep(0.22)
            self.speaking_ev.clear()

    def speak(self, text: str) -> None:
        if self.persona == "eviltwin":
            self.speak_cloned(text)
        else:
            self.speak_piper(text, self.active_lang)

    def stream_ollama_and_speak(self, user_text: str) -> None:
        self.history.append({"role": "user", "content": user_text})
        if len(self.history) > 17:
            self.history = [self.history[0]] + self.history[-16:]

        opts: dict[str, object] = {
            "num_predict": 115,
            "temperature": 0.88 if self.persona == "eviltwin" else 0.72,
        }
        if self.persona == "eviltwin":
            opts["num_gpu"] = 22

        payload = json.dumps({
            "model": self.model_name,
            "messages": self.history,
            "stream": True,
            "keep_alive": "10m",
            "options": opts,
        }).encode("utf-8")

        req = urllib.request.Request(
            f"{OLLAMA_URL}/api/chat",
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )

        post_heartbeat(
            self.persona,
            self.session_id,
            "connected",
            "Düşünüyor..." if self.active_lang == "tr" else "Thinking...",
        )
        full_reply = ""
        buf = ""

        try:
            with urllib.request.urlopen(req, timeout=45.0) as resp:
                for raw_line in resp:
                    if self.stop_ev.is_set():
                        break
                    if not raw_line.strip():
                        continue
                    chunk = json.loads(raw_line.decode("utf-8"))
                    tok = chunk.get("message", {}).get("content", "")
                    if tok:
                        full_reply += tok
                        buf += tok
                        if self.persona == "local":
                            m = re.search(r"([.!?]+)\s+", buf)
                            if m and len(buf[: m.end()].strip()) >= 12:
                                sent = buf[: m.end()].strip()
                                buf = buf[m.end() :]
                                post_heartbeat(
                                    self.persona,
                                    self.session_id,
                                    "connected",
                                    "Konuşuyor..." if self.active_lang == "tr" else "Speaking...",
                                )
                                self.speak(sent)
                    if chunk.get("done"):
                        break
        except Exception as e:
            log(f"Ollama error: {e}")
            if not full_reply:
                full_reply = (
                    "Hat bir saniye karıştı kanka, bir daha söyle."
                    if self.active_lang == "tr"
                    else "Line glitched for a second. Say that again."
                )

        tail = buf.strip() if self.persona == "local" else full_reply.strip()
        if tail and not self.stop_ev.is_set():
            who = (
                "Senin sesinle konuşuyor..."
                if (self.persona == "eviltwin" and self.ref_16k and self.active_lang == "tr")
                else "Speaking in your voice..."
                if (self.persona == "eviltwin" and self.ref_16k)
                else "Konuşuyor..."
                if self.active_lang == "tr"
                else "Speaking..."
            )
            post_heartbeat(self.persona, self.session_id, "connected", who)
            self.speak(tail)

        if full_reply.strip():
            self.history.append({"role": "assistant", "content": full_reply.strip()})
            log(f"AI ({self.active_lang}): {full_reply.strip()}")

    def transcribe(self, pcm_f32: np.ndarray) -> str:
        assert self.whisper is not None
        t0 = time.monotonic()
        hint = None if self.lang_mode == "auto" else self.lang_mode
        segs, info = self.whisper.transcribe(
            pcm_f32,
            language=hint,
            beam_size=1,
            vad_filter=False,
            condition_on_previous_text=False,
        )
        text = " ".join(s.text.strip() for s in segs).strip()
        ms = (time.monotonic() - t0) * 1000.0
        detected = (info.language or self.active_lang).lower()
        if self.lang_mode == "auto":
            if detected == "tr" or looks_turkish(text):
                self.active_lang = "tr"
            elif detected == "en":
                self.active_lang = "en"
        log(f"STT (det={detected}, active={self.active_lang}, {ms:.0f}ms): {text!r}")
        return text

    def run(self) -> None:
        self.init_models()
        if self.stop_ev.is_set():
            return

        is_tr = self.active_lang == "tr"
        ready_msg = (
            f"Evil Twin ({len(self.ref_16k)/SAMPLE_RATE:.1f}s klon · {self.lang_mode.upper()})"
            if (self.persona == "eviltwin" and self.ref_16k)
            else "Evil Twin · Sesini klonlamak için konuş!"
            if (self.persona == "eviltwin" and is_tr)
            else "Evil Twin live — speak to clone your voice!"
            if self.persona == "eviltwin"
            else f"Local AI ({self.lang_mode.upper()} · {self.model_name.split(':')[0]})"
        )
        post_heartbeat(self.persona, self.session_id, "connected", ready_msg)
        time.sleep(0.35)

        if not self.stop_ev.is_set():
            if self.persona == "eviltwin":
                if self.ref_16k:
                    if is_tr:
                        opener = (
                            "Sonunda açtın demek. Kendi sesini karşı taraftan duymak baya garip, değil mi?"
                            if self.mode == "receive"
                            else "Kendini mi arıyorsun şimdi? Anlat bakalım yine neyi bozdun."
                        )
                    else:
                        opener = (
                            "Finally you picked up. Funny hearing your own voice on the other end, isn't it?"
                            if self.mode == "receive"
                            else "Well well, calling yourself now? Go on, tell me what you broke."
                        )
                    post_heartbeat(self.persona, self.session_id, "connected", "Evil Twin konuşuyor..." if is_tr else "Evil Twin speaking...")
                    self.speak_cloned(opener)
                else:
                    msg = (
                        "Bir şeyler söyle kanka, sesini ödünç alayım önce."
                        if is_tr
                        else "Say something. Let me borrow your voice for a second."
                    )
                    post_heartbeat(
                        self.persona,
                        self.session_id,
                        "connected",
                        "Sesini klonlamak için bir cümle söyle..." if is_tr else "Speak a sentence to clone your voice...",
                    )
                    self.speak_piper(msg, self.active_lang)
            elif self.mode == "receive":
                opener = "Alo, sonunda açtın! Naber, ne var ne yok?" if is_tr else "Hey, glad you picked up. What's going on?"
                post_heartbeat(self.persona, self.session_id, "connected", "Konuşuyor..." if is_tr else "Speaking...")
                self.speak_piper(opener, self.active_lang)

        rec_cmd = [
            "parecord",
            "--raw",
            f"--rate={SAMPLE_RATE}",
            "--channels=1",
            "--format=s16le",
            "--latency-msec=30",
        ]
        if self.source:
            rec_cmd.append(f"--device={self.source}")

        log(f"Starting mic capture: {' '.join(rec_cmd)}")
        self.rec_proc = subprocess.Popen(
            rec_cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        assert self.rec_proc.stdout is not None

        frame_bytes = FRAME_SAMPLES * 2
        pre_roll: list[np.ndarray] = []
        speech_frames: list[np.ndarray] = []
        in_speech = False
        voiced_streak = 0
        silence_frames = 0
        max_speech_frames = int(12.0 * SAMPLE_RATE / FRAME_SAMPLES)
        end_silence_frames = int(0.75 * SAMPLE_RATE / FRAME_SAMPLES)

        def listen_label() -> str:
            if self.persona == "eviltwin" and not self.ref_16k:
                return "Sesini klonlamak için konuş..." if self.active_lang == "tr" else "Speak 3-5s to clone your voice..."
            return f"Dinliyor ({self.active_lang.upper()})..." if self.active_lang == "tr" else f"Listening ({self.active_lang.upper()})..."

        post_heartbeat(self.persona, self.session_id, "connected", listen_label())

        while not self.stop_ev.is_set():
            raw = self.rec_proc.stdout.read(frame_bytes)
            if not raw or len(raw) < frame_bytes:
                break
            if self.speaking_ev.is_set():
                in_speech = False
                speech_frames.clear()
                pre_roll.clear()
                voiced_streak = 0
                silence_frames = 0
                continue

            frame = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
            prob = self.vad.score(frame)

            if not in_speech:
                pre_roll.append(frame)
                if len(pre_roll) > 8:
                    pre_roll.pop(0)
                if prob >= 0.52:
                    voiced_streak += 1
                    if voiced_streak >= 3:
                        in_speech = True
                        speech_frames = list(pre_roll)
                        silence_frames = 0
                        post_heartbeat(
                            self.persona,
                            self.session_id,
                            "connected",
                            "Seni duyuyor..." if self.active_lang == "tr" else "Hearing you...",
                        )
                else:
                    voiced_streak = 0
            else:
                speech_frames.append(frame)
                if prob >= 0.38:
                    silence_frames = 0
                else:
                    silence_frames += 1

                if silence_frames >= end_silence_frames or len(speech_frames) >= max_speech_frames:
                    utterance = np.concatenate(speech_frames)
                    in_speech = False
                    speech_frames = []
                    pre_roll = []
                    voiced_streak = 0
                    silence_frames = 0
                    self.vad.reset()

                    if len(utterance) < int(SAMPLE_RATE * 0.45):
                        continue

                    if self.persona == "eviltwin":
                        cloned_now = self._update_voice_ref_from_utterance(utterance)
                        if cloned_now:
                            post_heartbeat(
                                self.persona,
                                self.session_id,
                                "connected",
                                f"Sesin klonlandı ({len(self.ref_16k)/SAMPLE_RATE:.1f}s)!"
                                if self.active_lang == "tr"
                                else f"Voice cloned ({len(self.ref_16k)/SAMPLE_RATE:.1f}s)!",
                            )

                    text = self.transcribe(utterance)
                    if not text or len(text) < 2:
                        post_heartbeat(self.persona, self.session_id, "connected", listen_label())
                        continue

                    self.stream_ollama_and_speak(text)
                    if not self.stop_ev.is_set():
                        post_heartbeat(self.persona, self.session_id, "connected", listen_label())

        if self.cloner:
            self.cloner.close()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--persona", choices=("local", "eviltwin"), required=True)
    ap.add_argument("--mode", choices=("call", "receive"), default="call")
    ap.add_argument("--lang", choices=("tr", "auto", "en"), default="tr")
    ap.add_argument("--sink", default="")
    ap.add_argument("--source", default="")
    ap.add_argument("--session", default="")
    args = ap.parse_args()

    runner = LocalCallRunner(
        persona=args.persona,
        mode=args.mode,
        lang_mode=args.lang,
        sink=args.sink or None,
        source=args.source or None,
        session_id=args.session,
    )

    def handle_sig(*_a: object) -> None:
        runner.stop()

    signal.signal(signal.SIGTERM, handle_sig)
    signal.signal(signal.SIGINT, handle_sig)

    try:
        runner.run()
    finally:
        runner.stop()


if __name__ == "__main__":
    main()
