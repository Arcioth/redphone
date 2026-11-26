#!/usr/bin/env python3
"""Red Phone Daemon — USB-C AB13X handset controller + Local AI / Evil Twin / Browser Voice."""
from __future__ import annotations

import fcntl
import glob
import json
import os
import queue
import random
import select
import signal
import struct
import subprocess
import sys
import threading
import time
import uuid
import wave
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

ROOT = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(ROOT, "assets")
UI_HTML = os.path.join(ROOT, "ui", "index.html")
EXT_DIR = os.path.join(ROOT, "extension")
VENV_PYTHON = os.path.join(ROOT, ".venv", "bin", "python")
LOCAL_CALL_SCRIPT = os.path.join(ROOT, "local_call.py")

HOME = os.path.expanduser("~")
STATE_DIR = os.path.join(HOME, ".local", "state", "redphone")
DATA_DIR = os.path.join(HOME, ".local", "share", "redphone")
UI_PROFILE = os.path.join(DATA_DIR, "ui-profile")
VOICE_PROFILE = os.path.join(DATA_DIR, "voice-profile")
CLONE_REF_WAV = os.path.join(DATA_DIR, "my_voice.wav")
MODE_FILE = os.path.join(STATE_DIR, "mode")
PROVIDER_FILE = os.path.join(STATE_DIR, "provider")
DELAY_FILE = os.path.join(STATE_DIR, "rcv_delay")
LANG_FILE = os.path.join(STATE_DIR, "lang")
HIDE_VOICE_FILE = os.path.join(STATE_DIR, "hide_voice")
LOG_FILE = os.path.join(STATE_DIR, "daemon.log")

PORT = 47621
USB_VID = "001f"
USB_PID = "0b21"
WAYBAR_SIG = 12
AUDIO_WAYBAR_SIG = 9

PROVIDERS = {
    "local": {
        "id": "local",
        "label": "LOCAL AI",
        "name": "Local AI (Ollama + Piper)",
        "kind": "local",
        "url": "",
    },
    "eviltwin": {
        "id": "eviltwin",
        "label": "EVIL TWIN",
        "name": "Evil Twin (Your Cloned Voice)",
        "kind": "local",
        "url": "",
    },
    "chatgpt": {
        "id": "chatgpt",
        "label": "CHATGPT",
        "name": "ChatGPT Voice",
        "kind": "browser",
        "url": "https://chatgpt.com/",
    },
    "grok": {
        "id": "grok",
        "label": "GROK",
        "name": "Grok Voice",
        "kind": "browser",
        "url": "https://grok.com/",
    },
}
PROVIDER_ORDER = ["local", "eviltwin", "chatgpt", "grok"]

# Linux input ioctls
EVIOCGNAME = 0x80FF4506
EVIOCGKEY_96 = 0x80604518
EV_KEY = 1
KEY_SPACE = 57
KEY_PLAYPAUSE = 164
KEY_STOPCD = 166
INPUT_EVENT_FMT = "llHHI"
INPUT_EVENT_SIZE = struct.calcsize(INPUT_EVENT_FMT)

os.makedirs(STATE_DIR, exist_ok=True)
os.makedirs(UI_PROFILE, exist_ok=True)
os.makedirs(VOICE_PROFILE, exist_ok=True)


def log(msg: str) -> None:
    line = f"{time.strftime('%H:%M:%S')} {msg}"
    sys.stderr.write(line + "\n")
    sys.stderr.flush()
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as fp:
            fp.write(line + "\n")
    except OSError:
        pass


def load_str(path: str, default: str) -> str:
    try:
        v = open(path, "r", encoding="utf-8").read().strip()
        return v or default
    except OSError:
        return default


def save_str(path: str, val: str) -> None:
    try:
        with open(path, "w", encoding="utf-8") as fp:
            fp.write(val + "\n")
    except OSError:
        pass


def clone_wav_duration() -> float:
    if not os.path.isfile(CLONE_REF_WAV):
        return 0.0
    try:
        with wave.open(CLONE_REF_WAV, "rb") as w:
            sr = w.getframerate()
            return round(w.getnframes() / float(sr), 1) if sr > 0 else 0.0
    except Exception:
        return 0.0


def is_phone_plugged() -> bool:
    for vpath in glob.glob("/sys/bus/usb/devices/*/idVendor"):
        try:
            vid = open(vpath, "r", encoding="utf-8").read().strip().lower()
            if vid != USB_VID:
                continue
            ppath = vpath.replace("idVendor", "idProduct")
            pid = open(ppath, "r", encoding="utf-8").read().strip().lower()
            if pid == USB_PID:
                return True
        except OSError:
            continue
    return False


def env_wayland() -> dict[str, str]:
    env = os.environ.copy()
    rt = env.get("XDG_RUNTIME_DIR") or f"/run/user/{os.getuid()}"
    env["XDG_RUNTIME_DIR"] = rt
    if not env.get("WAYLAND_DISPLAY"):
        if os.path.exists(os.path.join(rt, "wayland-1")):
            env["WAYLAND_DISPLAY"] = "wayland-1"
        else:
            for sock in sorted(glob.glob(os.path.join(rt, "wayland-*"))):
                if not sock.endswith(".lock"):
                    env["WAYLAND_DISPLAY"] = os.path.basename(sock)
                    break
    if not env.get("DISPLAY"):
        x_socks = sorted(glob.glob("/tmp/.X11-unix/X*"))
        if x_socks:
            num = os.path.basename(x_socks[0]).lstrip("X")
            env["DISPLAY"] = f":{num}"
        else:
            env["DISPLAY"] = ":0"
    if not env.get("HYPRLAND_INSTANCE_SIGNATURE"):
        # Pick the newest active Hyprland socket if multiple dirs exist
        cands = [
            sig
            for sig in glob.glob(os.path.join(rt, "hypr", "*"))
            if os.path.exists(os.path.join(sig, ".socket.sock"))
        ]
        if cands:
            cands.sort(key=lambda p: os.path.getmtime(os.path.join(p, ".socket.sock")), reverse=True)
            env["HYPRLAND_INSTANCE_SIGNATURE"] = os.path.basename(cands[0])
    return env


def poke_waybar(sig: int = WAYBAR_SIG) -> None:
    try:
        subprocess.run(
            ["pkill", f"-SIGRTMIN+{sig}", "waybar"],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except OSError:
        pass


def pactl_output(*args: str) -> str:
    try:
        return subprocess.check_output(
            ["pactl", *args],
            text=True,
            stderr=subprocess.DEVNULL,
            timeout=2.0,
        ).strip()
    except Exception:
        return ""


def discover_audio() -> dict[str, str | None]:
    phone_sink = None
    speaker_sink = None
    phone_source = None

    for line in pactl_output("list", "short", "sinks").splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        name = parts[1]
        if "AB13X" in name:
            phone_sink = name
        elif "pci-" in name and "analog" in name:
            speaker_sink = name
        elif speaker_sink is None and "hdmi" not in name.lower():
            speaker_sink = name

    for line in pactl_output("list", "short", "sources").splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        name = parts[1]
        if "AB13X" in name and not name.endswith(".monitor"):
            phone_source = name

    return {
        "phone_sink": phone_sink,
        "speaker_sink": speaker_sink,
        "phone_source": phone_source,
    }


def find_keyboard_event() -> str | None:
    preferred = None
    fallback = None
    for path in sorted(glob.glob("/dev/input/event*")):
        try:
            fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK)
        except OSError:
            continue
        try:
            buf = bytearray(256)
            fcntl.ioctl(fd, EVIOCGNAME, buf)
            name = buf.split(b"\x00", 1)[0].decode(errors="replace")
            low = name.lower()
            if "at translated set 2 keyboard" in low:
                preferred = path
                break
            if "keyboard" in low and "ab13x" not in low and fallback is None:
                fallback = path
        except OSError:
            pass
        finally:
            os.close(fd)
    return preferred or fallback


def is_space_down(kbd_path: str | None) -> bool:
    if not kbd_path:
        return False
    try:
        fd = os.open(kbd_path, os.O_RDONLY | os.O_NONBLOCK)
        try:
            key_buf = bytearray(96)
            fcntl.ioctl(fd, EVIOCGKEY_96, key_buf)
            return bool(key_buf[KEY_SPACE // 8] & (1 << (KEY_SPACE % 8)))
        finally:
            os.close(fd)
    except OSError:
        return False


def find_handset_event() -> str | None:
    hits = sorted(glob.glob("/dev/input/by-id/*AB13X*event*"))
    return hits[0] if hits else None


class SoundPlayer:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._proc: subprocess.Popen | None = None
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def stop(self) -> None:
        with self._lock:
            self._stop.set()
            if self._proc and self._proc.poll() is None:
                try:
                    self._proc.terminate()
                    self._proc.wait(timeout=0.3)
                except Exception:
                    try:
                        self._proc.kill()
                    except Exception:
                        pass
            self._proc = None

    def play_loop(self, wav_path: str, sink: str | None) -> None:
        self.stop()
        if not os.path.isfile(wav_path):
            log(f"missing wav: {wav_path}")
            return
        with self._lock:
            self._stop = threading.Event()
            ev = self._stop

            def runner() -> None:
                while not ev.is_set():
                    cmd = (
                        ["paplay", f"--device={sink}", wav_path]
                        if sink
                        else ["paplay", wav_path]
                    )
                    try:
                        p = subprocess.Popen(
                            cmd,
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL,
                        )
                    except OSError as e:
                        log(f"paplay failed: {e}")
                        return
                    with self._lock:
                        if ev.is_set():
                            p.terminate()
                            return
                        self._proc = p
                    while p.poll() is None and not ev.is_set():
                        time.sleep(0.04)
                    if ev.is_set() and p.poll() is None:
                        p.terminate()
                        return

            self._thread = threading.Thread(target=runner, daemon=True)
            self._thread.start()

    def play_oneshot(self, wav_path: str, sink: str | None) -> None:
        if not os.path.isfile(wav_path):
            return
        cmd = ["paplay", f"--device={sink}", wav_path] if sink else ["paplay", wav_path]
        try:
            subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError:
            pass


class RedPhoneController:
    def __init__(self) -> None:
        self.lock = threading.RLock()
        self.sound = SoundPlayer()
        self.subscribers: list[queue.Queue[str]] = []

        self.mode: str = load_str(MODE_FILE, "call")
        if self.mode not in ("call", "receive"):
            self.mode = "call"

        self.provider: str = load_str(PROVIDER_FILE, "local")
        if self.provider not in PROVIDERS:
            self.provider = "local"
            save_str(PROVIDER_FILE, self.provider)

        raw_delay = load_str(DELAY_FILE, "random")
        self.rcv_delay: str | int = self._parse_delay(raw_delay)
        self.lang: str = load_str(LANG_FILE, "tr")
        if self.lang not in ("tr", "auto", "en"):
            self.lang = "tr"
        self.hide_voice: bool = load_str(HIDE_VOICE_FILE, "0") == "1"

        self.plugged: bool = is_phone_plugged()
        self.state: str = "idle" if self.plugged else "unplugged"
        self.status_detail: str = "Ready" if self.plugged else "Unplugged"
        self.session_id: str = ""
        self.want_active: bool = False
        self.recording_clone: bool = False

        self.dial_started_at: float = 0.0
        self.connected_at: float = 0.0
        self.ring_started_at: float = 0.0
        self.armed_until: float = 0.0
        self.armed_is_fixed: bool = False

        self.saved_sink: str | None = None
        self.saved_source: str | None = None

        self.rcv_timer: threading.Timer | None = None
        self.missed_timer: threading.Timer | None = None
        self.pickup_timer: threading.Timer | None = None

        self.popup_proc: subprocess.Popen | None = None
        self.voice_proc: subprocess.Popen | None = None
        self.local_proc: subprocess.Popen | None = None
        self._popup_spawn_at: float = 0.0
        self._voice_spawn_at: float = 0.0
        self._cradle_announced: bool = False

        self.kbd_path: str | None = find_keyboard_event()
        self.on_hook: bool = is_space_down(self.kbd_path)
        self.space_down_since: float = time.monotonic() if self.on_hook else 0.0
        self.shutdown_ev = threading.Event()

        log(
            f"init plugged={self.plugged} mode={self.mode} provider={self.provider} "
            f"delay={self.rcv_delay} kbd={self.kbd_path} on_hook={self.on_hook}"
        )

    @staticmethod
    def _parse_delay(val: object) -> str | int:
        s = str(val or "random").strip().lower()
        if s in ("random", "rand", "auto", ""):
            return "random"
        try:
            secs = int(round(float(s)))
            return max(1, min(3600, secs))
        except ValueError:
            return "random"

    def snapshot(self) -> dict:
        with self.lock:
            now = time.monotonic()
            call_secs = (
                int(now - self.connected_at)
                if (self.state == "connected" and self.connected_at)
                else 0
            )
            armed_remaining = None
            if self.state == "armed" and self.armed_is_fixed and self.armed_until > now:
                armed_remaining = max(0, int(round(self.armed_until - now)))
            clone_secs = clone_wav_duration()
            return {
                "plugged": self.plugged,
                "mode": self.mode,
                "provider": self.provider,
                "provider_info": PROVIDERS[self.provider],
                "providers": [PROVIDERS[k] for k in PROVIDER_ORDER],
                "state": self.state,
                "detail": self.status_detail,
                "session_id": self.session_id,
                "want_active": self.want_active,
                "on_hook": self.on_hook,
                "call_seconds": call_secs,
                "armed_pending": self.state == "armed",
                "rcv_delay": self.rcv_delay,
                "lang": self.lang,
                "hide_voice": self.hide_voice,
                "armed_remaining": armed_remaining,
                "clone_ready": clone_secs >= 1.5,
                "clone_seconds": clone_secs,
                "recording_clone": self.recording_clone,
            }

    def broadcast(self) -> None:
        payload = json.dumps(self.snapshot())
        dead: list[queue.Queue[str]] = []
        with self.lock:
            for q in self.subscribers:
                try:
                    q.put_nowait(payload)
                except queue.Full:
                    dead.append(q)
            for q in dead:
                if q in self.subscribers:
                    self.subscribers.remove(q)
        poke_waybar(WAYBAR_SIG)

    def _cancel_timers(self) -> None:
        for attr in ("rcv_timer", "missed_timer", "pickup_timer"):
            t = getattr(self, attr)
            if t is not None:
                t.cancel()
                setattr(self, attr, None)

    def _route_to_phone(self) -> dict[str, str | None]:
        audio = discover_audio()
        cur_sink = pactl_output("get-default-sink")
        cur_source = pactl_output("get-default-source")
        if cur_sink and "AB13X" not in cur_sink and not self.saved_sink:
            self.saved_sink = cur_sink
        if cur_source and "AB13X" not in cur_source and not self.saved_source:
            self.saved_source = cur_source

        if audio["phone_sink"]:
            subprocess.run(["pactl", "set-default-sink", audio["phone_sink"]], check=False)
            subprocess.run(["pactl", "set-sink-mute", audio["phone_sink"], "0"], check=False)
        if audio["phone_source"]:
            subprocess.run(["pactl", "set-default-source", audio["phone_source"]], check=False)
            subprocess.run(["pactl", "set-source-mute", audio["phone_source"], "0"], check=False)
            subprocess.run(["pactl", "set-source-volume", audio["phone_source"], "100%"], check=False)
        poke_waybar(AUDIO_WAYBAR_SIG)
        return audio

    def _restore_audio(self) -> None:
        if self.saved_sink:
            subprocess.run(["pactl", "set-default-sink", self.saved_sink], check=False)
            self.saved_sink = None
        if self.saved_source:
            subprocess.run(["pactl", "set-default-source", self.saved_source], check=False)
            self.saved_source = None
        poke_waybar(AUDIO_WAYBAR_SIG)

    def _move_chromium_streams_to_phone(self, audio: dict[str, str | None]) -> None:
        if audio.get("phone_sink"):
            raw = pactl_output("list", "sink-inputs")
            cur_id = None
            for line in raw.splitlines():
                s = line.strip()
                if s.startswith("Sink Input #"):
                    cur_id = s.split("#", 1)[1].strip()
                elif cur_id and "application.name" in s and "chromium" in s.lower():
                    subprocess.run(
                        ["pactl", "move-sink-input", cur_id, audio["phone_sink"]],
                        check=False,
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                    )
        if audio.get("phone_source"):
            raw = pactl_output("list", "source-outputs")
            cur_id = None
            for line in raw.splitlines():
                s = line.strip()
                if s.startswith("Source Output #"):
                    cur_id = s.split("#", 1)[1].strip()
                elif cur_id and "application.name" in s and "chromium" in s.lower():
                    subprocess.run(
                        ["pactl", "move-source-output", cur_id, audio["phone_source"]],
                        check=False,
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                    )

    # ---------- Hyprland Window Helpers ----------

    def _hypr_clients(self, env: dict[str, str]) -> list[dict]:
        try:
            out = subprocess.check_output(
                ["hyprctl", "clients", "-j"],
                text=True,
                env=env,
                stderr=subprocess.DEVNULL,
                timeout=1.5,
            )
            return json.loads(out)
        except Exception:
            return []

    def _active_workspace(self, env: dict[str, str]) -> int:
        try:
            out = subprocess.check_output(
                ["hyprctl", "activeworkspace", "-j"],
                text=True,
                env=env,
                stderr=subprocess.DEVNULL,
                timeout=1.5,
            )
            return int(json.loads(out).get("id", 1))
        except Exception:
            return 1

    def _find_windows(self, cls: str, env: dict[str, str]) -> list[dict]:
        return [c for c in self._hypr_clients(env) if c.get("class") == cls]

    def _hypr_lua(self, expr: str, env: dict[str, str]) -> None:
        subprocess.run(
            ["hyprctl", "dispatch", expr],
            check=False,
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def _dedupe_windows(self, cls: str, env: dict[str, str]) -> list[dict]:
        wins = self._find_windows(cls, env)
        if len(wins) > 1:
            for extra in wins[1:]:
                addr = extra.get("address")
                if addr:
                    self._hypr_lua(f'hl.dsp.window.close({{ window = "address:{addr}" }})', env)
            wins = wins[:1]
        return wins

    def open_popup(self) -> None:
        with self.lock:
            env = env_wayland()
            wins = self._dedupe_windows("redphone-popup", env)
            if wins:
                ws = self._active_workspace(env)
                addr = wins[0].get("address")
                if addr:
                    self._hypr_lua(
                        f'hl.dsp.window.move({{ workspace = {ws}, window = "address:{addr}" }})',
                        env,
                    )
                    self._hypr_lua(f'hl.dsp.window.center({{ window = "address:{addr}" }})', env)
                    self._hypr_lua(f'hl.dsp.focus({{ window = "address:{addr}" }})', env)
                return

            now = time.monotonic()
            if now - self._popup_spawn_at < 3.0:
                return
            self._popup_spawn_at = now

            cmd = [
                "chromium",
                f"--user-data-dir={UI_PROFILE}",
                "--class=redphone-popup",
                "--name=redphone-popup",
                f"--app=http://127.0.0.1:{PORT}/?view=popup",
                "--window-size=560,600",
                "--ozone-platform-hint=auto",
                "--test-type",
                "--disable-session-crashed-bubble",
                "--hide-crash-restore-bubble",
                "--noerrdialogs",
                "--no-first-run",
                "--no-default-browser-check",
            ]
            try:
                self.popup_proc = subprocess.Popen(
                    cmd,
                    env=env,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                log(f"opened redphone-popup window (DISPLAY={env.get('DISPLAY')})")
            except OSError as e:
                log(f"popup spawn failed: {e}")

    def close_popup(self) -> None:
        env = env_wayland()
        for w in self._find_windows("redphone-popup", env):
            addr = w.get("address")
            if addr:
                self._hypr_lua(f'hl.dsp.window.close({{ window = "address:{addr}" }})', env)
        self.popup_proc = None
        subprocess.run(
            ["pkill", "-f", f"--user-data-dir={UI_PROFILE}"],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def _close_special_voice_if_open(self, env: dict[str, str]) -> None:
        try:
            mons = json.loads(
                subprocess.check_output(
                    ["hyprctl", "monitors", "-j"],
                    text=True,
                    env=env,
                    stderr=subprocess.DEVNULL,
                    timeout=1.5,
                )
            )
            for m in mons:
                sw = (m.get("specialWorkspace") or {}).get("name", "")
                if sw == "special:redphone-voice":
                    self._hypr_lua('hl.dsp.workspace.toggle_special("redphone-voice")', env)
                    break
        except Exception:
            pass

    def _hide_voice_browser(self) -> None:
        env = env_wayland()
        for w in self._find_windows("redphone-voice", env):
            addr = w.get("address")
            if addr:
                self._hypr_lua(
                    f'hl.dsp.window.move({{ workspace = "special:redphone-voice", window = "address:{addr}" }})',
                    env,
                )
        self._close_special_voice_if_open(env)

    def _stage_voice_behind_popup(self) -> None:
        """Map redphone-voice on the active workspace BEHIND the opaque 560x600
        redphone-popup so Chromium/WebGL/WebRTC connects at full speed without
        unmapped special-workspace throttling or visual clutter."""
        env = env_wayland()
        wins = self._dedupe_windows("redphone-voice", env)
        if not wins:
            return
        ws = self._active_workspace(env)
        addr = wins[0].get("address")
        if addr:
            self._hypr_lua(
                f'hl.dsp.window.move({{ workspace = {ws}, window = "address:{addr}" }})',
                env,
            )
            self._hypr_lua(f'hl.dsp.window.center({{ window = "address:{addr}" }})', env)
        self._close_special_voice_if_open(env)
        # Immediately raise the larger opaque redphone-popup in front of it
        self.open_popup()

    def _show_voice_browser(self) -> None:
        env = env_wayland()
        wins = self._dedupe_windows("redphone-voice", env)
        if not wins:
            return
        ws = self._active_workspace(env)
        addr = wins[0].get("address")
        if addr:
            self._hypr_lua(
                f'hl.dsp.window.move({{ workspace = {ws}, window = "address:{addr}" }})',
                env,
            )
            self._hypr_lua(f'hl.dsp.window.center({{ window = "address:{addr}" }})', env)
            self._hypr_lua(f'hl.dsp.focus({{ window = "address:{addr}" }})', env)
        self._close_special_voice_if_open(env)

    # ---------- Voice Backends (Local Worker vs Browser) ----------

    def launch_local_worker(self, audio: dict[str, str | None]) -> None:
        self.stop_local_worker()
        py = VENV_PYTHON if os.path.isfile(VENV_PYTHON) else sys.executable
        cmd = [
            py,
            LOCAL_CALL_SCRIPT,
            "--persona",
            self.provider,
            "--mode",
            self.mode,
            "--lang",
            self.lang,
            "--sink",
            audio.get("phone_sink") or "",
            "--source",
            audio.get("phone_source") or "",
            "--session",
            self.session_id,
        ]
        try:
            log_fp = open(LOG_FILE, "a", encoding="utf-8")
            self.local_proc = subprocess.Popen(
                cmd,
                stdout=subprocess.DEVNULL,
                stderr=log_fp,
            )
            log(f"launched local worker persona={self.provider} pid={self.local_proc.pid}")
        except OSError as e:
            log(f"local worker launch failed: {e}")

    def stop_local_worker(self) -> None:
        if self.local_proc and self.local_proc.poll() is None:
            try:
                self.local_proc.terminate()
                self.local_proc.wait(timeout=1.2)
            except Exception:
                try:
                    self.local_proc.kill()
                except Exception:
                    pass
        self.local_proc = None

    def launch_voice_browser(self, focus: bool = False) -> None:
        if PROVIDERS[self.provider]["kind"] != "browser":
            return
        env = env_wayland()
        url = PROVIDERS[self.provider]["url"]
        wins = self._dedupe_windows("redphone-voice", env)
        if wins:
            if focus:
                self._show_voice_browser()
            else:
                self.open_popup()
            return

        now = time.monotonic()
        if now - self._voice_spawn_at < 1.5:
            return
        self._voice_spawn_at = now

        cmd = [
            "chromium",
            f"--user-data-dir={VOICE_PROFILE}",
            f"--load-extension={EXT_DIR}",
            "--enable-unsafe-extension-debugging",
            "--autoplay-policy=no-user-gesture-required",
            "--use-fake-ui-for-media-stream",
            "--test-type",
            "--disable-background-timer-throttling",
            "--disable-backgrounding-occluded-windows",
            "--disable-renderer-backgrounding",
            "--disable-features=CalculateNativeWinOcclusion,IntensiveWakeUpThrottling",
            "--disable-session-crashed-bubble",
            "--hide-crash-restore-bubble",
            "--noerrdialogs",
            "--class=redphone-voice",
            "--name=redphone-voice",
            f"--app={url}",
            "--window-size=340,340",
            "--ozone-platform-hint=auto",
            "--no-first-run",
            "--no-default-browser-check",
        ]
        try:
            self.voice_proc = subprocess.Popen(
                cmd,
                env=env,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            log(f"launched voice browser for {self.provider}: {url}")

            def arrange_later() -> None:
                for _ in range(30):
                    time.sleep(0.15)
                    if self._find_windows("redphone-voice", env):
                        if focus:
                            self._show_voice_browser()
                        elif self.want_active:
                            self._stage_voice_behind_popup()
                        else:
                            self._hide_voice_browser()
                        return

            threading.Thread(target=arrange_later, daemon=True).start()
        except OSError as e:
            log(f"voice browser spawn failed: {e}")

    def close_voice_browser(self) -> None:
        env = env_wayland()
        for w in self._find_windows("redphone-voice", env):
            addr = w.get("address")
            if addr:
                self._hypr_lua(f'hl.dsp.window.close({{ window = "address:{addr}" }})', env)
        self.voice_proc = None
        time.sleep(0.15)
        subprocess.run(
            ["pkill", "-f", f"--user-data-dir={VOICE_PROFILE}"],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def _start_backend(self, audio: dict[str, str | None]) -> None:
        if PROVIDERS[self.provider]["kind"] == "local":
            self.close_voice_browser()
            self.launch_local_worker(audio)
        else:
            self.stop_local_worker()
            self.launch_voice_browser(focus=False)
            # Bring the pre-warmed browser onto the active workspace BEHIND the
            # opaque 560x600 redphone-popup so WebRTC/React runs unthrottled!
            self._stage_voice_behind_popup()

    # ---------- High-level State Transitions ----------

    def on_plug(self) -> None:
        with self.lock:
            log("EVENT: USB plug")
            self.plugged = True
            self.kbd_path = self.kbd_path or find_keyboard_event()
            self.on_hook = is_space_down(self.kbd_path)
            self._cancel_timers()
            self.sound.stop()
            # Always pop up the single Red Phone applet on plug-in (CALL or RCV).
            self.open_popup()
            # Pre-warm browser voice tab in the background so calls connect instantly.
            if PROVIDERS[self.provider]["kind"] == "browser":
                self.launch_voice_browser(focus=False)

            if self.mode == "receive":
                self._arm_random_call()
            else:
                if self.on_hook:
                    self.state = "idle"
                    self.status_detail = "On cradle (Spacebar) — lift handset to dial"
                    self.broadcast()
                else:
                    self._start_outgoing_call()

    def on_unplug(self) -> None:
        with self.lock:
            log("EVENT: USB unplug")
            self.plugged = False
            self.want_active = False
            self._cancel_timers()
            self.sound.stop()
            self.stop_local_worker()
            self.state = "unplugged"
            self.status_detail = "Unplugged"
            self.session_id = ""
            self.connected_at = 0.0
            self.broadcast()
        threading.Thread(target=self._teardown_after_unplug, daemon=True).start()

    def _teardown_after_unplug(self) -> None:
        time.sleep(0.4)
        self.close_voice_browser()
        self.close_popup()
        self._restore_audio()

    def _arm_random_call(self) -> None:
        self._cancel_timers()
        self.sound.stop()
        self.want_active = False
        if self.rcv_delay == "random":
            delay = random.uniform(6.0, 56.0)
            self.armed_is_fixed = False
            self.status_detail = "Incoming call armed (random within 60s)"
        else:
            delay = float(self.rcv_delay)
            self.armed_is_fixed = True
            self.status_detail = f"Incoming call scheduled in {int(delay)}s"
        self.armed_until = time.monotonic() + delay
        self.state = "armed"
        log(f"RECEIVE armed ({self.rcv_delay}): ringing in {delay:.1f}s")
        self.rcv_timer = threading.Timer(delay, self.trigger_incoming_ring)
        self.rcv_timer.daemon = True
        self.rcv_timer.start()
        self.broadcast()

    def trigger_incoming_ring(self) -> None:
        with self.lock:
            if not self.plugged:
                return
            if self.state in ("connected", "dialing", "connecting"):
                return
            self._cancel_timers()
            audio = discover_audio()
            self.state = "ringing"
            self.ring_started_at = time.monotonic()
            self.armed_is_fixed = False
            self.status_detail = f"Incoming call · {PROVIDERS[self.provider]['name']} — lift handset!"
            log(f"RINGING on speaker={audio['speaker_sink']}")
            self.sound.play_loop(os.path.join(ASSETS, "ringtone.wav"), audio["speaker_sink"])
            self.open_popup()
            self.missed_timer = threading.Timer(35.0, self._on_missed_call)
            self.missed_timer.daemon = True
            self.missed_timer.start()
            self.broadcast()

    def _on_missed_call(self) -> None:
        with self.lock:
            if self.state != "ringing":
                return
            log("Missed incoming call")
            self.sound.stop()
            self.state = "idle"
            self.status_detail = "Missed call — click Ring Now or set timer"
            self.broadcast()

    def _start_outgoing_call(self) -> None:
        if not self.plugged:
            return
        self._cancel_timers()
        audio = self._route_to_phone()
        self.session_id = uuid.uuid4().hex[:10]
        self.want_active = True
        self.dial_started_at = time.monotonic()
        self.connected_at = 0.0
        self.state = "dialing"
        self.status_detail = f"Dialing {PROVIDERS[self.provider]['name']}..."
        log(f"DIALING session={self.session_id} provider={self.provider} sink={audio['phone_sink']}")
        self.sound.play_loop(os.path.join(ASSETS, "dialtone.wav"), audio["phone_sink"])
        self.open_popup()
        self._start_backend(audio)
        self.broadcast()

    def answer(self) -> None:
        with self.lock:
            if not self.plugged or self.state != "ringing":
                return
            self._cancel_timers()
            self.sound.stop()
            audio = self._route_to_phone()
            self.sound.play_oneshot(os.path.join(ASSETS, "pickup.wav"), audio["phone_sink"])
            self.session_id = uuid.uuid4().hex[:10]
            self.want_active = True
            self.dial_started_at = 0.0
            self.connected_at = 0.0
            self.state = "connecting"
            self.status_detail = f"Answered — connecting to {PROVIDERS[self.provider]['name']}..."
            log(f"ANSWERED session={self.session_id} provider={self.provider}")
            self.open_popup()
            self._start_backend(audio)
            self.broadcast()

    def decline(self) -> None:
        with self.lock:
            if self.state != "ringing":
                return
            self._cancel_timers()
            self.sound.stop()
            self.want_active = False
            self.state = "idle"
            self.status_detail = "Call declined"
            self.broadcast()

    def hangup(self) -> None:
        with self.lock:
            if self.state in ("unplugged", "idle"):
                return
            log(f"HANGUP from state={self.state}")
            ended_sid = self.session_id
            self._cancel_timers()
            self.sound.stop()
            self.stop_local_worker()
            audio = discover_audio()
            if self.plugged and self.state in ("dialing", "connecting", "connected", "auth_required"):
                self.sound.play_oneshot(os.path.join(ASSETS, "hangup.wav"), audio["phone_sink"])
            self.want_active = False
            self.connected_at = 0.0
            self.state = "idle" if self.plugged else "unplugged"
            self.status_detail = "Call ended" if self.plugged else "Unplugged"
            self.broadcast()
        threading.Thread(target=self._stop_voice_after_hangup, args=(ended_sid,), daemon=True).start()

    def _stop_voice_after_hangup(self, ended_sid: str) -> None:
        time.sleep(0.30)
        with self.lock:
            if self.want_active or self.session_id != ended_sid:
                return
            # Keep browser voice tab warm & hidden in special:redphone-voice (with overlay closed)
            if self.plugged and PROVIDERS[self.provider]["kind"] == "browser":
                self._hide_voice_browser()
            else:
                self.close_voice_browser()
            self._restore_audio()

    def set_mode(self, new_mode: str) -> None:
        with self.lock:
            if new_mode == "toggle":
                new_mode = "receive" if self.mode == "call" else "call"
            if new_mode not in ("call", "receive") or new_mode == self.mode:
                return
            self.mode = new_mode
            save_str(MODE_FILE, self.mode)
            log(f"MODE switched -> {self.mode}")
            if self.plugged and self.state in ("idle", "armed"):
                if self.mode == "receive":
                    self._arm_random_call()
                else:
                    self._cancel_timers()
                    self.state = "idle"
                    self.status_detail = "CALL mode — lift handset or press Dial"
                    self.broadcast()
            else:
                self.broadcast()

    def set_delay(self, raw_delay: object) -> None:
        with self.lock:
            parsed = self._parse_delay(raw_delay)
            self.rcv_delay = parsed
            save_str(DELAY_FILE, str(parsed))
            log(f"RCV_DELAY set -> {parsed}")
            if self.plugged and self.mode == "receive" and self.state in ("idle", "armed"):
                self._arm_random_call()
            else:
                self.broadcast()

    def set_lang(self, raw_lang: str) -> None:
        with self.lock:
            lang = raw_lang.strip().lower()
            if lang not in ("tr", "auto", "en"):
                return
            if lang == self.lang:
                return
            self.lang = lang
            save_str(LANG_FILE, self.lang)
            log(f"LANG set -> {self.lang}")
            self.broadcast()

    def set_provider(self, prov: str) -> None:
        with self.lock:
            if prov == "cycle":
                idx = PROVIDER_ORDER.index(self.provider)
                prov = PROVIDER_ORDER[(idx + 1) % len(PROVIDER_ORDER)]
            if prov not in PROVIDERS:
                return
            changed = prov != self.provider
            self.provider = prov
            save_str(PROVIDER_FILE, self.provider)
            if changed:
                log(f"PROVIDER switched -> {self.provider}")
                self.stop_local_worker()
                self.close_voice_browser()
                if self.want_active and self.state in ("dialing", "connecting", "auth_required"):
                    self.session_id = uuid.uuid4().hex[:10]
                    audio = self._route_to_phone()
                    self.status_detail = f"Connecting to {PROVIDERS[self.provider]['name']}..."
                    self._start_backend(audio)
                elif self.plugged and PROVIDERS[self.provider]["kind"] == "browser":
                    self.launch_voice_browser(focus=False)
            self.broadcast()

    def set_hide_voice(self, hide: object) -> None:
        with self.lock:
            if str(hide).lower() == "toggle":
                new_val = not self.hide_voice
            else:
                new_val = str(hide).lower() in ("1", "true", "yes", "on")
            self.hide_voice = new_val
            save_str(HIDE_VOICE_FILE, "1" if self.hide_voice else "0")
            log(f"HIDE_VOICE set -> {self.hide_voice}")
            if self.hide_voice:
                self._hide_voice_browser()
            elif self.state == "connected" and PROVIDERS[self.provider]["kind"] == "browser":
                self._show_voice_browser()
            self.broadcast()

    def reset_clone(self) -> None:
        with self.lock:
            try:
                os.remove(CLONE_REF_WAV)
            except FileNotFoundError:
                pass
            log("Cleared saved voice clone sample")
            if self.state == "idle":
                self.status_detail = "Voice clone cleared — speak or click Record 5s"
            self.broadcast()

    def record_clone_sample(self) -> None:
        with self.lock:
            if not self.plugged or self.recording_clone:
                return
            if self.state in ("dialing", "connecting", "connected"):
                return
            self.recording_clone = True
            self.status_detail = "Talk into the handset for 5 seconds to clone your voice..."
            self.broadcast()

        def worker() -> None:
            try:
                audio = self._route_to_phone()
                src = audio.get("phone_source")
                self.sound.play_oneshot(os.path.join(ASSETS, "pickup.wav"), audio.get("phone_sink"))
                time.sleep(0.15)
                cmd = [
                    "parecord",
                    "--raw",
                    "--rate=16000",
                    "--channels=1",
                    "--format=s16le",
                ]
                if src:
                    cmd.append(f"--device={src}")
                p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                assert p.stdout is not None
                want_bytes = 16000 * 2 * 5  # 5 seconds
                chunks = bytearray()
                t0 = time.monotonic()
                while len(chunks) < want_bytes and time.monotonic() - t0 < 6.0:
                    data = p.stdout.read(min(3200, want_bytes - len(chunks)))
                    if not data:
                        break
                    chunks.extend(data)
                p.terminate()
                p.wait(timeout=1.0)

                vals = struct.unpack(f"<{len(chunks) // 2}h", bytes(chunks))
                floats = [v / 32768.0 for v in vals]
                sys.path.insert(0, ROOT)
                from voice_clone import prepare_reference_16k, write_wav_pcm16

                clean = prepare_reference_16k(floats, 16000, max_sec=6.0)
                if len(clean) >= 16000 * 1.5:
                    write_wav_pcm16(CLONE_REF_WAV, clean, 16000)
                    msg = f"Voice cloned! ({len(clean)/16000:.1f}s ready for Evil Twin)"
                    log(msg)
                else:
                    msg = "Too quiet — talk closer to the handset and try Record 5s again"
                    log(msg)
                self.sound.play_oneshot(os.path.join(ASSETS, "hangup.wav"), audio.get("phone_sink"))
            except Exception as e:
                msg = f"Voice capture error: {e}"
                log(msg)
            finally:
                with self.lock:
                    self.recording_clone = False
                    if self.state in ("idle", "armed"):
                        self.status_detail = msg
                    self.broadcast()

        threading.Thread(target=worker, daemon=True).start()

    def on_ext_heartbeat(self, data: dict) -> None:
        status = data.get("status", "")
        detail = data.get("detail", "")
        prov = data.get("provider", "")
        with self.lock:
            if prov != self.provider or not self.want_active:
                return
            if not (status == "connected" and self.state == "connected" and detail == self.status_detail):
                log(f"EXT[{prov}]: {status} ({detail})")
            if status == "auth_required":
                if self.state != "auth_required":
                    self.sound.stop()
                    self.state = "auth_required"
                    self.status_detail = f"Sign in once to {PROVIDERS[self.provider]['name']}"
                    self.launch_voice_browser(focus=True)
                    self.broadcast()
            elif status == "connected":
                if self.state in ("dialing", "connecting", "auth_required"):
                    elapsed = (
                        time.monotonic() - self.dial_started_at if self.dial_started_at else 99.0
                    )
                    wait = max(0.0, 2.2 - elapsed) if self.state == "dialing" else 0.0
                    if wait > 0.05 and self.pickup_timer is None:
                        self.pickup_timer = threading.Timer(wait, self._finish_connect, args=(detail,))
                        self.pickup_timer.daemon = True
                        self.pickup_timer.start()
                    elif wait <= 0.05:
                        self._finish_connect(detail)
                elif self.state == "connected" and detail and detail != self.status_detail:
                    self.status_detail = detail
                    self.broadcast()
            elif status in ("loading", "connecting") and self.state in ("connecting", "auth_required", "dialing"):
                if detail:
                    self.status_detail = detail
                    self.broadcast()

    def _finish_connect(self, detail: str = "") -> None:
        with self.lock:
            self.pickup_timer = None
            if not self.want_active or not self.plugged:
                return
            was_dialing = self.state == "dialing"
            self.sound.stop()
            audio = self._route_to_phone()
            if PROVIDERS[self.provider]["kind"] == "browser":
                self._move_chromium_streams_to_phone(audio)
                if self.hide_voice:
                    # Keep mapped behind opaque redphone-popup during active call so WebRTC stays unthrottled
                    self._stage_voice_behind_popup()
                else:
                    self._show_voice_browser()
            if was_dialing:
                self.sound.play_oneshot(os.path.join(ASSETS, "pickup.wav"), audio["phone_sink"])
            self.state = "connected"
            self.connected_at = time.monotonic()
            self.status_detail = detail or f"Connected · {PROVIDERS[self.provider]['name']}"
            log(f"CONNECTED session={self.session_id} provider={self.provider}")
            self.broadcast()

    # ---------- Input Monitors (Spacebar Cradle + Handset Button) ----------

    def _on_space_changed(self, down: bool) -> None:
        with self.lock:
            prev = self.on_hook
            self.on_hook = down
            now = time.monotonic()
            if down and not prev:
                self.space_down_since = now
            elif not down and prev:
                held_for = now - self.space_down_since
                self.space_down_since = 0.0
                if self.plugged:
                    if self.state == "ringing":
                        log(f"CRADLE LIFTED (held {held_for:.2f}s) -> ANSWER")
                        self.answer()
                        return
                    if self.state == "idle" and self.mode == "call" and held_for >= 0.35:
                        log(f"CRADLE LIFTED (held {held_for:.2f}s) -> DIAL")
                        self._start_outgoing_call()
                        return
                if held_for >= 0.35:
                    self.broadcast()

    def _check_space_cradle_hangup(self) -> None:
        with self.lock:
            if not (self.plugged and self.on_hook and self.space_down_since > 0):
                return
            held = time.monotonic() - self.space_down_since
            if held >= 0.45:
                if self.state in ("dialing", "connecting", "connected", "auth_required"):
                    log("CRADLE DOWN >= 0.45s -> HANGUP")
                    self.hangup()
                elif not self._cradle_announced:
                    self._cradle_announced = True
                    self.broadcast()
            else:
                self._cradle_announced = False

    def _on_handset_button(self) -> None:
        with self.lock:
            log(f"HANDSET BUTTON pressed in state={self.state}")
            if not self.plugged:
                return
            if self.state == "ringing":
                self.answer()
            elif self.state in ("dialing", "connecting", "connected", "auth_required"):
                self.hangup()
            elif self.state == "idle":
                if self.mode == "call":
                    self._start_outgoing_call()
                else:
                    self.trigger_incoming_ring()
            elif self.state == "armed":
                self._start_outgoing_call()

    def run_keyboard_loop(self) -> None:
        while not self.shutdown_ev.is_set():
            path = find_keyboard_event()
            if not path:
                time.sleep(2.0)
                continue
            self.kbd_path = path
            try:
                fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK)
            except OSError as e:
                log(f"cannot open kbd {path}: {e}")
                time.sleep(3.0)
                continue
            log(f"watching Spacebar hook on {path}")
            try:
                while not self.shutdown_ev.is_set():
                    self._check_space_cradle_hangup()
                    r, _, _ = select.select([fd], [], [], 0.1)
                    if not r:
                        continue
                    data = os.read(fd, INPUT_EVENT_SIZE * 32)
                    for off in range(0, len(data) - INPUT_EVENT_SIZE + 1, INPUT_EVENT_SIZE):
                        _, _, ev_type, code, value = struct.unpack(
                            INPUT_EVENT_FMT, data[off : off + INPUT_EVENT_SIZE]
                        )
                        if ev_type == EV_KEY and code == KEY_SPACE:
                            if value == 1:
                                self._on_space_changed(True)
                            elif value == 0:
                                self._on_space_changed(False)
            except OSError:
                pass
            finally:
                try:
                    os.close(fd)
                except OSError:
                    pass
            time.sleep(0.5)

    def run_handset_loop(self) -> None:
        while not self.shutdown_ev.is_set():
            path = find_handset_event()
            if not path:
                time.sleep(0.8)
                continue
            try:
                fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK)
            except OSError:
                time.sleep(1.0)
                continue
            log(f"watching handset buttons on {path}")
            try:
                while not self.shutdown_ev.is_set():
                    r, _, _ = select.select([fd], [], [], 0.25)
                    if not r:
                        if not os.path.exists(path):
                            break
                        continue
                    data = os.read(fd, INPUT_EVENT_SIZE * 16)
                    if not data:
                        break
                    for off in range(0, len(data) - INPUT_EVENT_SIZE + 1, INPUT_EVENT_SIZE):
                        _, _, ev_type, code, value = struct.unpack(
                            INPUT_EVENT_FMT, data[off : off + INPUT_EVENT_SIZE]
                        )
                        if ev_type == EV_KEY and value == 1 and code in (KEY_PLAYPAUSE, KEY_STOPCD):
                            self._on_handset_button()
            except OSError:
                pass
            finally:
                try:
                    os.close(fd)
                except OSError:
                    pass
            time.sleep(0.5)


CTRL: RedPhoneController


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: object) -> None:
        return

    def _send_json(self, obj: dict, code: int = 200) -> None:
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(n).decode("utf-8"))
        except Exception:
            return {}

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path in ("/", "/popup"):
            try:
                body = open(UI_HTML, "rb").read()
            except OSError:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if path == "/api/state":
            self._send_json(CTRL.snapshot())
            return

        if path == "/api/ext/command":
            st = CTRL.snapshot()
            self._send_json(
                {
                    "want_active": st["want_active"] and st["provider_info"]["kind"] == "browser",
                    "mode": st["mode"],
                    "provider": st["provider"],
                    "session_id": st["session_id"],
                    "state": st["state"],
                }
            )
            return

        self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        data = self._read_json()

        if path == "/api/mode":
            CTRL.set_mode(str(data.get("mode") or "toggle"))
            self._send_json(CTRL.snapshot())
            return

        if path == "/api/delay":
            CTRL.set_delay(data.get("delay", "random"))
            self._send_json(CTRL.snapshot())
            return

        if path == "/api/lang":
            CTRL.set_lang(str(data.get("lang") or "tr"))
            self._send_json(CTRL.snapshot())
            return

        if path == "/api/hide_voice":
            CTRL.set_hide_voice(data.get("hide", "toggle"))
            self._send_json(CTRL.snapshot())
            return

        if path == "/api/provider":
            CTRL.set_provider(str(data.get("provider") or "cycle"))
            self._send_json(CTRL.snapshot())
            return

        if path == "/api/ext/heartbeat":
            CTRL.on_ext_heartbeat(data)
            self._send_json({"ok": True})
            return

        if path == "/api/action":
            act = str(data.get("action") or "")
            if act == "plug":
                CTRL.on_plug()
            elif act == "unplug":
                CTRL.on_unplug()
            elif act == "dial":
                with CTRL.lock:
                    CTRL._start_outgoing_call()
            elif act == "answer":
                CTRL.answer()
            elif act == "decline":
                CTRL.decline()
            elif act == "hangup":
                CTRL.hangup()
            elif act == "test_ring":
                CTRL.trigger_incoming_ring()
            elif act == "arm":
                with CTRL.lock:
                    if CTRL.plugged:
                        CTRL._arm_random_call()
            elif act == "open_ui":
                CTRL.open_popup()
            elif act == "focus_voice":
                CTRL.launch_voice_browser(focus=True)
            elif act == "manual_connected":
                CTRL._finish_connect()
            elif act == "reset_clone":
                CTRL.reset_clone()
            elif act == "record_clone":
                CTRL.record_clone_sample()
            self._send_json(CTRL.snapshot())
            return

        self.send_error(HTTPStatus.NOT_FOUND)


def main() -> None:
    global CTRL
    CTRL = RedPhoneController()

    threading.Thread(target=CTRL.run_keyboard_loop, daemon=True).start()
    threading.Thread(target=CTRL.run_handset_loop, daemon=True).start()

    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    server.daemon_threads = True

    def stop_handler(*_args: object) -> None:
        CTRL.shutdown_ev.set()
        CTRL.sound.stop()
        CTRL.stop_local_worker()
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop_handler)
    signal.signal(signal.SIGINT, stop_handler)

    log(f"redphone daemon listening on http://127.0.0.1:{PORT}")
    poke_waybar(WAYBAR_SIG)
    try:
        server.serve_forever()
    finally:
        CTRL.sound.stop()
        CTRL.stop_local_worker()


if __name__ == "__main__":
    main()
