#!/usr/bin/env python3
"""Standalone Python ctypes binding for libaudiocpp.so (ZONOS3-Mini voice cloning).
Uses copied model files in ~/Documents/redphone/models/ without touching Metatron.
"""
from __future__ import annotations

import ctypes
import math
import os
import struct
import time
import wave

ROOT = os.path.dirname(os.path.abspath(__file__))
MODELS_DIR = os.path.join(ROOT, "models")
LIB_PATH = os.path.join(MODELS_DIR, "libaudiocpp.so")
ZONOS_MODEL = os.path.join(MODELS_DIR, "ZONOS3-Mini-GGUF", "backbone-ptq.gguf")
MODEL_SPECS_DIR = os.path.join(MODELS_DIR, "model_specs")

REF_SAMPLE_RATE = 16000


class AudioCppModelConfig(ctypes.Structure):
    _fields_ = [
        ("family_hint", ctypes.c_char_p),
        ("config_id", ctypes.c_char_p),
        ("weight_id", ctypes.c_char_p),
        ("model_spec_override", ctypes.c_char_p),
    ]


class AudioCppBackendConfig(ctypes.Structure):
    _fields_ = [
        ("backend", ctypes.c_char_p),
        ("device", ctypes.c_int),
        ("threads", ctypes.c_int),
    ]


def read_wav_mono_float(path: str) -> tuple[list[float], int]:
    with wave.open(path, "rb") as w:
        ch = w.getnchannels()
        sw = w.getsampwidth()
        sr = w.getframerate()
        n = w.getnframes()
        raw = w.readframes(n)
    if sw != 2:
        raise ValueError(f"Unsupported sample width {sw} (expected 16-bit PCM)")
    total = len(raw) // 2
    vals = struct.unpack(f"<{total}h", raw)
    if ch == 1:
        samples = [v / 32768.0 for v in vals]
    else:
        samples = []
        for i in range(0, total, ch):
            samples.append(sum(vals[i : i + ch]) / (ch * 32768.0))
    return samples, sr


def resample_linear(samples: list[float], src_sr: int, dst_sr: int) -> list[float]:
    if not samples or src_sr == dst_sr:
        return list(samples)
    ratio = src_sr / float(dst_sr)
    out_len = max(1, int(round(len(samples) / ratio)))
    out = [0.0] * out_len
    n_minus_1 = len(samples) - 1
    for i in range(out_len):
        pos = i * ratio
        idx = int(pos)
        frac = pos - idx
        if idx >= n_minus_1:
            out[i] = samples[n_minus_1]
        else:
            out[i] = samples[idx] * (1.0 - frac) + samples[idx + 1] * frac
    return out


def prepare_reference_16k(samples: list[float], sr: int, max_sec: float = 6.0) -> list[float]:
    pcm = resample_linear(samples, sr, REF_SAMPLE_RATE)
    # Trim leading/trailing near-silence frames (20ms chunks)
    hop = REF_SAMPLE_RATE // 50
    voiced = []
    for i in range(0, len(pcm), hop):
        chunk = pcm[i : i + hop]
        if not chunk:
            break
        rms = math.sqrt(sum(x * x for x in chunk) / len(chunk))
        if rms > 0.012:
            voiced.extend(chunk)
    if len(voiced) >= REF_SAMPLE_RATE * 1.5:
        pcm = voiced
    max_samples = int(max_sec * REF_SAMPLE_RATE)
    if len(pcm) > max_samples:
        pcm = pcm[:max_samples]
    peak = max((abs(x) for x in pcm), default=0.0)
    if peak > 1e-4:
        gain = min(4.0, 0.85 / peak)
        pcm = [max(-1.0, min(1.0, x * gain)) for x in pcm]
    return pcm


def write_wav_pcm16(path: str, samples: list[float], sr: int) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        buf = bytearray(len(samples) * 2)
        for i, s in enumerate(samples):
            v = int(max(-1.0, min(1.0, s)) * 32767.0)
            struct.pack_into("<h", buf, i * 2, v)
        w.writeframes(bytes(buf))


class ZonosVoiceCloner:
    def __init__(self, prefer_cuda: bool = True, cpu_threads: int = 8) -> None:
        if not os.path.isfile(LIB_PATH):
            raise FileNotFoundError(f"Missing {LIB_PATH}")
        if not os.path.isfile(ZONOS_MODEL):
            raise FileNotFoundError(f"Missing {ZONOS_MODEL}")

        self.lib = ctypes.CDLL(LIB_PATH)
        self._bind()

        ver = self.lib.audiocpp_abi_version()
        major = (ver >> 16) & 0xFF
        minor = (ver >> 8) & 0xFF
        if major != 0 or minor < 4:
            raise RuntimeError(f"Unsupported libaudiocpp ABI {major}.{minor}")

        self.registry = ctypes.c_void_p()
        self.model = ctypes.c_void_p()
        self.session = ctypes.c_void_p()
        self.backend = "none"

        st = self.lib.audiocpp_registry_create(None, ctypes.byref(self.registry))
        if st != 0:
            raise RuntimeError(self._err("registry_create", st))

        mcfg = AudioCppModelConfig(
            b"zonos3",
            None,
            None,
            MODEL_SPECS_DIR.encode("utf-8"),
        )
        st = self.lib.audiocpp_model_load(
            self.registry,
            ZONOS_MODEL.encode("utf-8"),
            ctypes.byref(mcfg),
            None,
            ctypes.byref(self.model),
        )
        if st != 0:
            self.close()
            raise RuntimeError(self._err("model_load", st))

        opened = False
        if prefer_cuda and os.environ.get("REDPHONE_CLONE_CPU") != "1":
            if self._open_session(b"cuda", cpu_threads):
                self.backend = "CUDA"
                opened = True
        if not opened:
            if not self._open_session(b"cpu", cpu_threads):
                self.close()
                raise RuntimeError(self._err("session_create(cpu)", -1))
            self.backend = "CPU"

    def _bind(self) -> None:
        L = self.lib
        L.audiocpp_abi_version.restype = ctypes.c_uint32
        L.audiocpp_last_error.restype = ctypes.c_char_p

        L.audiocpp_options_create.restype = ctypes.c_void_p
        L.audiocpp_options_set.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p]
        L.audiocpp_options_set.restype = ctypes.c_int
        L.audiocpp_options_free.argtypes = [ctypes.c_void_p]

        L.audiocpp_registry_create.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p)]
        L.audiocpp_registry_create.restype = ctypes.c_int
        L.audiocpp_registry_free.argtypes = [ctypes.c_void_p]

        L.audiocpp_model_load.argtypes = [
            ctypes.c_void_p,
            ctypes.c_char_p,
            ctypes.POINTER(AudioCppModelConfig),
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_void_p),
        ]
        L.audiocpp_model_load.restype = ctypes.c_int
        L.audiocpp_model_free.argtypes = [ctypes.c_void_p]

        L.audiocpp_session_create.argtypes = [
            ctypes.c_void_p,
            ctypes.c_char_p,
            ctypes.c_char_p,
            ctypes.POINTER(AudioCppBackendConfig),
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_void_p),
        ]
        L.audiocpp_session_create.restype = ctypes.c_int
        L.audiocpp_session_free.argtypes = [ctypes.c_void_p]

        L.audiocpp_session_run.argtypes = [
            ctypes.c_void_p,
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_void_p),
        ]
        L.audiocpp_session_run.restype = ctypes.c_int

        L.audiocpp_request_create.restype = ctypes.c_void_p
        L.audiocpp_request_free.argtypes = [ctypes.c_void_p]
        L.audiocpp_request_set_text.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p]
        L.audiocpp_request_set_text.restype = ctypes.c_int
        L.audiocpp_request_set_voice_audio.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_float),
            ctypes.c_size_t,
            ctypes.c_int,
            ctypes.c_int,
        ]
        L.audiocpp_request_set_voice_audio.restype = ctypes.c_int
        L.audiocpp_request_set_option.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p]
        L.audiocpp_request_set_option.restype = ctypes.c_int

        L.audiocpp_result_audio.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.POINTER(ctypes.c_float)),
            ctypes.POINTER(ctypes.c_size_t),
            ctypes.POINTER(ctypes.c_int),
            ctypes.POINTER(ctypes.c_int),
        ]
        L.audiocpp_result_audio.restype = ctypes.c_int
        L.audiocpp_result_free.argtypes = [ctypes.c_void_p]

    def _err(self, where: str, code: int) -> str:
        raw = self.lib.audiocpp_last_error()
        detail = raw.decode("utf-8", errors="replace") if raw else "unknown"
        return f"{where} failed ({code}): {detail}"

    def _open_session(self, backend: bytes, threads: int) -> bool:
        sopts = self.lib.audiocpp_options_create()
        if not sopts:
            return False
        try:
            if backend == b"cuda":
                self.lib.audiocpp_options_set(sopts, b"zonos3.encoder_backend", b"same")
            bcfg = AudioCppBackendConfig(backend, 0, max(1, threads))
            st = self.lib.audiocpp_session_create(
                self.model,
                b"clon",
                b"offline",
                ctypes.byref(bcfg),
                sopts,
                ctypes.byref(self.session),
            )
            return st == 0 and bool(self.session)
        finally:
            self.lib.audiocpp_options_free(sopts)

    def synthesize(
        self,
        text: str,
        ref_pcm_16k: list[float],
        seed: int = 42,
    ) -> tuple[bytes, int, float]:
        """Returns (float32le_mono_bytes, sample_rate, gen_ms)."""
        if not self.session:
            raise RuntimeError("session not loaded")
        req = self.lib.audiocpp_request_create()
        if not req:
            raise RuntimeError("request_create returned null")
        try:
            st = self.lib.audiocpp_request_set_text(req, text.encode("utf-8"), None)
            if st != 0:
                raise RuntimeError(self._err("request_set_text", st))

            arr_type = ctypes.c_float * len(ref_pcm_16k)
            ref_arr = arr_type(*ref_pcm_16k)
            st = self.lib.audiocpp_request_set_voice_audio(
                req, ref_arr, len(ref_pcm_16k), REF_SAMPLE_RATE, 1
            )
            if st != 0:
                raise RuntimeError(self._err("request_set_voice_audio", st))

            self.lib.audiocpp_request_set_option(req, b"text_normalization", b"false")
            self.lib.audiocpp_request_set_option(req, b"seed", str(seed).encode("utf-8"))

            res = ctypes.c_void_p()
            t0 = time.monotonic()
            st = self.lib.audiocpp_session_run(self.session, req, ctypes.byref(res))
            gen_ms = (time.monotonic() - t0) * 1000.0
            if st != 0 or not res:
                raise RuntimeError(self._err("session_run", st))

            try:
                ptr = ctypes.POINTER(ctypes.c_float)()
                frames = ctypes.c_size_t()
                sr = ctypes.c_int()
                ch = ctypes.c_int()
                st = self.lib.audiocpp_result_audio(
                    res,
                    ctypes.byref(ptr),
                    ctypes.byref(frames),
                    ctypes.byref(sr),
                    ctypes.byref(ch),
                )
                if st != 0 or frames.value == 0 or sr.value <= 0:
                    raise RuntimeError(self._err("result_audio", st))
                n_frames = frames.value
                n_ch = max(1, ch.value)
                raw = ctypes.string_at(ptr, n_frames * n_ch * 4)
                if n_ch == 1:
                    return raw, sr.value, gen_ms
                # Downmix multi-channel float32 to mono
                vals = struct.unpack(f"<{n_frames * n_ch}f", raw)
                mono = [
                    sum(vals[i : i + n_ch]) / n_ch
                    for i in range(0, n_frames * n_ch, n_ch)
                ]
                return struct.pack(f"<{n_frames}f", *mono), sr.value, gen_ms
            finally:
                self.lib.audiocpp_result_free(res)
        finally:
            self.lib.audiocpp_request_free(req)

    def close(self) -> None:
        if getattr(self, "session", None):
            self.lib.audiocpp_session_free(self.session)
            self.session = ctypes.c_void_p()
        if getattr(self, "model", None):
            self.lib.audiocpp_model_free(self.model)
            self.model = ctypes.c_void_p()
        if getattr(self, "registry", None):
            self.lib.audiocpp_registry_free(self.registry)
            self.registry = ctypes.c_void_p()


if __name__ == "__main__":
    import sys

    if len(sys.argv) < 3:
        print("usage: voice_clone.py <ref.wav> <text>", file=sys.stderr)
        sys.exit(2)
    ref, sr = read_wav_mono_float(sys.argv[1])
    ref16 = prepare_reference_16k(ref, sr)
    cloner = ZonosVoiceCloner(prefer_cuda=True)
    print(f"Loaded ZONOS3-Mini on {cloner.backend}, ref={len(ref16)/REF_SAMPLE_RATE:.2f}s")
    pcm_bytes, out_sr, ms = cloner.synthesize(sys.argv[2], ref16)
    dur = (len(pcm_bytes) / 4) / out_sr
    print(f"Synthesized {dur:.2f}s @ {out_sr}Hz in {ms:.0f}ms (RTF={ms/1000/dur:.2f})")
    cloner.close()
