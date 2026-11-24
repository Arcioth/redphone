# Red Phone (`redphone`)

Turn a retro USB-C telephone handset (`001f:0b21` Generic AB13X USB Audio) — with your laptop's **Spacebar acting as the physical cradle hook-switch** — into a live AI hotline on Linux / Hyprland.

## What it does

- **Zero-poll USB-C hotplug via [daetron](https://github.com/Arcioth/daetron)**: plugging in the red phone triggers kernel netlink (`1f/b21/`), pops up a `TEL: CALL` / `TEL: RCV` chip on **Waybar**, and launches the floating `560×600` Red Phone deck. Unplugging hangs up, restores your previous PipeWire audio output, and hides the Waybar chip.
- **Spacebar Cradle Hook-Switch + Handset Button**:
  - Rest the red handset on your laptop's **Spacebar** → on-hook.
  - Lift the handset off the Spacebar when ringing → **instant answer** (cuts laptop speaker ringtone, plays mechanical `pickup.wav` click in the earpiece, routes PipeWire sink & mic to `AB13X`).
  - Set the handset back down on the Spacebar (`>= 0.45s`) → **hang up** (`hangup.wav` + teardown).
  - Also responds to the handset's physical `KEY_PLAYPAUSE` button.
- **Two Call Modes (`CALL` / `RCV`)**:
  - **`CALL`**: plays ringback/dial tone in the red phone earpiece while connecting, then cuts with a pickup click when the AI answers.
  - **`RCV` (Receive)**: rings your laptop speakers after either a **random delay within 60 s** (`RAND`) or any **custom timer** you set (`5s`, `15s`, `30s`, `60s`, or `1–3600s` with live countdown on UI & Waybar).
- **Four Voice Backends**:
  1. **`LOCAL AI`** — 100% offline: Silero VAD (CPU) + `faster-whisper` (CPU int8) + local Ollama + Piper TTS with **`TR` (default Turkish)** / **`AUTO`** (auto-detect TR/EN) / **`EN`** language switching.
  2. **`EVIL TWIN`** — 100% offline voice-cloning hotline: clones **your own voice** live (or via a 5-second handset sample) using `ZONOS3-Mini` (`libaudiocpp.so` via Python `ctypes` on CUDA) + uncensored Ollama Evil Twin persona, speaking back to you in your own voice.
  3. **`CHATGPT`** — browser-automated ChatGPT Voice via MV3 Chromium extension (`extension/`), skinned with the matching dark crimson Red Phone backdrop and stripping all UI chrome except the voice-reactive orb.
  4. **`GROK`** — browser-automated Grok Voice with the same orb-only Red Phone theme.

## Layout

```text
redphone/
├── daemon.py           # Local HTTP/state daemon, PipeWire routing, Spacebar/handset input, Hyprland control
├── local_call.py       # Offline Silero VAD + faster-whisper + Ollama + Piper / ZONOS3-Mini call worker
├── voice_clone.py      # Standalone ctypes binding for libaudiocpp.so (ZONOS3-Mini CUDA/CPU)
├── ui/index.html       # Vanilla HTML/CSS/JS floating Red Phone console
├── extension/          # Manifest V3 Chromium bridge + Red Phone orb-only skin for ChatGPT & Grok
├── bin/
│   ├── redphone-ctl    # CLI control (plug, unplug, toggle-mode, set-delay, set-lang, test-ring, ...)
│   ├── redphone-waybar # Auto-hiding Waybar JSON status chip
│   └── sync-dotfiles   # NixOS boot dotfile sync helper
├── assets/             # pickup.wav, hangup.wav (+ yt-dlp dialtone.wav / ringtone.wav)
└── contrib/            # Example daetron rule & systemd user units
```

## Quick Setup (Linux / PipeWire / Hyprland)

1. **Download the dial & ring audio clips**:
   ```bash
   yt-dlp -x --audio-format wav --audio-quality 0 -o assets/dialtone.%(ext)s "https://youtu.be/LxhRJD9KUq0"
   yt-dlp -x --audio-format wav --audio-quality 0 -o assets/ringtone.%(ext)s "https://youtu.be/CwnALwm7rl8"
   ```
2. **Create the Python venv for Local AI & Evil Twin**:
   ```bash
   uv venv .venv --python python3
   uv pip install --python .venv/bin/python faster-whisper numpy
   ```
3. **Download Turkish Piper voice** (for `LOCAL AI` in Turkish):
   ```bash
   mkdir -p models/piper
   curl -fL -o models/piper/tr_TR-fahrettin-medium.onnx \
     "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/tr/tr_TR/fahrettin/medium/tr_TR-fahrettin-medium.onnx"
   curl -fL -o models/piper/tr_TR-fahrettin-medium.onnx.json \
     "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/tr/tr_TR/fahrettin/medium/tr_TR-fahrettin-medium.onnx.json"
   ```
4. **Optional — Evil Twin voice cloning models**: place `libaudiocpp.so`, `ZONOS3-Mini-GGUF/`, `model_specs/zonos3.json`, and `silero_vad.onnx` in `models/`.
5. **Enable the user services & `daetron` rule** (see `contrib/`).

## CLI Examples

```bash
./bin/redphone-ctl status
./bin/redphone-ctl toggle-mode       # CALL <-> RCV
./bin/redphone-ctl set-delay 15      # exact 15s incoming call timer (or 'random')
./bin/redphone-ctl set-lang auto     # tr | auto | en
./bin/redphone-ctl set-provider eviltwin
./bin/redphone-ctl test-ring
```
