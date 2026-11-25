# Red Phone for Android

The Android version of Red Phone. Plug the same `001f:0b21` AB13X USB-C handset into an Android phone,
tablet or Chromebook, and you get the same deck, the same Spacebar cradle hook-switch, CALL / RCV modes,
and the four voice backends.

| Desktop (Linux / Hyprland) | Android |
| --- | --- |
| `daemon.py` state machine | `Controller.java`: a line-by-line port (same states, timers and strings) |
| daetron netlink `1f/b21/` hotplug | `USB_DEVICE_ATTACHED` intent filter (`res/xml/device_filter.xml`) + `UsbMonitor` (250 ms debounce) |
| Floating 560×600 Chromium popup (`ui/index.html`) | `MainActivity`: the **same** `ui/index.html` in a WebView, plus Android additions injected at runtime |
| Laptop **Spacebar** cradle (evdev) | Hardware keyboard Spacebar (USB / Bluetooth / tablet cover / Chromebook), the on-screen **SPACEBAR · CRADLE** pad, or the proximity sensor (rest the handset over the top of the phone) |
| Handset `KEY_PLAYPAUSE` button | Same button via `MediaSession` + focused-activity key events |
| PipeWire sink/source routing to AB13X | `AudioManager` communication device = the USB handset, `setPreferredDevice` for tones |
| Laptop speakers ring in RCV mode | Phone loudspeaker rings (vibrates in silent/vibrate mode); full-screen incoming-call screen |
| Waybar `TEL:` chip | Ongoing notification with the same `TEL:` text and buttons + Quick Settings **TEL** tile (tap = CALL/RCV) |
| `bin/redphone-ctl` | `android/bin/redphone-ctl-adb` (adb broadcast, same commands) |
| `LOCAL AI`: Silero VAD + faster-whisper + Ollama + Piper | Android `SpeechRecognizer` (on-device when available) + **Ollama** (configurable URL) + Android `TextToSpeech`, played on the handset |
| `EVIL TWIN`: ZONOS3-Mini voice clone on CUDA | Same persona and prompts; the voice is **pitch-matched** to your 5 s sample (no neural cloning on-device) |
| `CHATGPT` / `GROK`: MV3 extension in a Chromium app window | A persistent WebView running the **same** `extension/content.js` + `page-hook.js` through a `chrome.runtime` shim, same orb-only crimson skin |

## Install

1. Build `android/dist/redphone.apk` (see [Build](#build)) or use the prebuilt APK in `android/dist/`.
2. Copy it to the phone and open it (allow "install unknown apps" for your file manager or browser), or run `adb install -r android/dist/redphone.apk`.
3. Open **Red Phone** once and allow **microphone** and **notifications**.
4. Plug in the red handset. Android asks *"Open Red Phone when AB13X USB Audio is connected?"* Tick **Always** and the deck pops up on every plug-in, like daetron.

No handset? Open ⚙ in the deck and enable **SIM PLUG**. The phone itself becomes the handset (earpiece + mic).

## The Spacebar cradle on Android

Every cradle source feeds the same state machine with the desktop thresholds:

- Lift the handset while it is **ringing** → answer.
- Lift after it rested **≥ 0.35 s** in CALL mode → dial.
- Rest it **≥ 0.45 s** during a call → hang up.

| Source | How |
| --- | --- |
| Keyboard Spacebar | Any hardware keyboard while the deck is focused. To make it work while Red Phone is in the background (like the desktop evdev reader), enable **Red Phone Spacebar Cradle** in *Settings → Accessibility* (⚙ → KEYBOARD CRADLE). It only looks at the Spacebar and media keys and passes every key through. |
| On-screen pad | Hold the **SPACEBAR · CRADLE** bar at the bottom of the deck. Holding it means the handset is on the cradle. |
| Proximity sensor | ⚙ → **PROX CRADLE**. Rest the handset over the top of the phone (covering the proximity sensor) to put it on the cradle. |

As on the desktop, plugging in during CALL mode with the handset *off* the cradle dials right away.

## Voice backends

- **LOCAL AI / EVIL TWIN** need an [Ollama](https://ollama.com) server. The default URL is
  `http://127.0.0.1:11434`, meaning Ollama running on the phone itself (for example in Termux). To use
  the laptop's Ollama instead, start it with `OLLAMA_HOST=0.0.0.0` and set ⚙ → **OLLAMA URL** to
  `http://<laptop-ip>:11434`. **MODEL** blank means auto-pick (qwen → llama → mistral → gemma, as on
  the desktop). TR / AUTO / EN work as on the desktop. Install a Turkish voice in *Settings → Text-to-speech*
  for Turkish replies, and the Turkish offline speech pack for fully offline recognition.
- **EVIL TWIN**: press **RECORD 5s** (or just start talking on the first call). Your sample is analysed
  and the twin speaks with your voice's pitch.
- **CHATGPT / GROK** run in a built-in browser window. Sign in once when the deck says **SIGN IN ONCE**:
  e-mail or password sign-in works, but Google blocks its sign-in inside embedded browsers.
  **SHOW ORB** / **HIDE WHOLE BROWSER** work as on the desktop.

## Optional permissions (⚙)

- **FULL-SCREEN** (Android 14+): lets an incoming RCV call take over the lock screen.
- **OVERLAY**: lets the deck pop up by itself (on plug-in it always does). Android blocks background
  pop-ups without it.
- **KEYBOARD CRADLE**: the accessibility service described above.

## adb control (`redphone-ctl`)

```bash
./android/bin/redphone-ctl-adb status
./android/bin/redphone-ctl-adb toggle-mode
./android/bin/redphone-ctl-adb set-delay 15
./android/bin/redphone-ctl-adb set-provider eviltwin
./android/bin/redphone-ctl-adb test-ring
./android/bin/redphone-ctl-adb sim-plug on
```

The receiver requires `android.permission.DUMP`, which only the adb shell holds, so other apps cannot
drive your red phone.

## Build

**Android Studio / Gradle** (needs the Android SDK, `compileSdk 34`):

```bash
cd android && ./gradlew assembleRelease   # signed with the debug key for sideloading
```

**Without the Android SDK** (JDK 17+ and Python 3 only; everything else comes from Maven Central):

```bash
python3 android/tools/build_apk.py --out android/dist/redphone.apk [--api-check]
```

`build_apk.py` does what AGP does for this app: it compiles the Java sources (Java 8 language level,
no lambdas) against Robolectric's `android-all` (API 34), dexes them with AOSP `dx`, and compiles the
manifest and `res/` into binary XML and `resources.arsc` (`tools/axml.py`, `tools/arsc.py`). It then
zip-aligns the APK and signs it with APK Signature Scheme v2 using Google's `apksig`.
`--api-check` resolves every framework call against the real API 26 framework (minSdk), like lint's NewApi check.
The signing key is generated once in `~/.cache/redphone-android/` (override with `REDPHONE_KEYSTORE`,
`REDPHONE_KEYSTORE_PASS` and `REDPHONE_KEY_ALIAS`). Keep it: Android only installs updates signed with
the same key.

Both builds ship the shared desktop files unchanged:

- `ui/index.html` → `assets/ui/index.html`
- `extension/content.js` and `extension/page-hook.js` → `assets/web/`
- `assets/*.wav` → `assets/sounds/`

If you downloaded `dialtone.wav` / `ringtone.wav` with yt-dlp (see the main README), they ship too.
Otherwise a ringback tone and a bell ring are synthesized.
