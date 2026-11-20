# JARVIS AI

A native Android personal assistant (Kotlin, Jetpack Compose, Material 3, MVVM). Chat or talk to an
OpenAI-compatible AI, hear replies in a British male voice, and control parts of your phone.

## Features
- Streaming chat (SSE) with any OpenAI-compatible endpoint, conversation memory (Room), Arabic + English
  (Iraqi / Saudi dialect aware via the system prompt and `ar-IQ` / `ar-SA` speech recognition).
- Brain: Groq (OpenAI-compatible, default `openai/gpt-oss-20b`) - or any OpenAI-compatible endpoint.
- Voice out: **Fish Audio** with the community voice model "Jarvis (MCU) J.A.R.V.I.S" (editable ID), with automatic
  fallback to Android TextToSpeech (en-GB male). Voice in: Android SpeechRecognizer. Speed/pitch/voice settings,
  replay button, stop/interrupt, waveform + listening/speaking animations, hands-free mode.
- Replies are spoken sentence-by-sentence while they stream in.
- Settings: endpoint, API key (EncryptedSharedPreferences, Android Keystore-backed), model, voice, speed, pitch,
  language, theme, auto-voice, permissions, clear history. Preferences in DataStore.
- Local notifications and timed reminders.

## Chat, sessions and code
- Multiple **chat sessions** (drawer): new chat, auto-titled, rename, delete; each keeps its own context.
- Voice and text are **in sync**: each sentence's text types out as you hear it (Fish Audio synthesises the next
  sentence while the current one plays).
- Replies render markdown. **Code goes in boxed blocks with a Copy button** and is never read aloud; headings, lists,
  bold and quotes are formatted. Long-press text to select.

## Hey JARVIS (wake word + assistant panel)
Offline wake word using **Sherpa-ONNX keyword spotting** (3.3M-parameter streaming model, int8, bundled in
`assets/kws/`; no account, no API key, no audio leaves the phone). Turn on *Listen for "Jarvis"* in Settings, allow
*Display over other apps* and set battery to unrestricted. Say "Hey Jarvis" from any app: a bottom panel opens and
listens. Phrases live in `assets/kws/keywords.txt` (BPE tokens made with `sherpa-onnx-cli text2token`; tune `:boost`
and `#threshold` per phrase if it triggers too easily or misses you). The model is generic English, not trained on
"Jarvis", so expect to tune the thresholds on a real device.
Android 14+ only lets a microphone service start while the app is open, so open JARVIS once after a reboot.

**Panel behaviour:** the panel is its own session (your main chat is restored when it closes), stays open after each
answer and listens again by itself (it retries a few times on silence; mute with the mic-off button, close with X). It
steps aside only while an agent task has to tap the screen, then comes back with the result. The core animation follows
the real state (listening level, thinking, speaking, error).

## Agent mode (the lightning button)
The bolt forces every message to be treated as an agent task (otherwise JARVIS decides automatically). Tell JARVIS what to do ("open WhatsApp and message Sara hello"). It reads the screen through the
Android **Accessibility Service**, then taps, types, scrolls and opens apps step by step (max 20 steps).
Safety: you enable the service yourself in Android settings; it only runs for tasks you give it; passwords are never
read or typed; sensitive taps (send, pay, buy, delete, post...) need your Allow/Deny on a notification; a Stop button is
in the "JARVIS is working" notification. Ask "what's on my screen?" to have it read/describe the screen (optional
screenshots via a vision model, off by default). Screen text is sent to your AI provider to decide each step.
On Android 13+ sideloaded apps need: App info > menu > **Allow restricted settings** before the service can be enabled.

## Voice commands (handled on-device, extensible)
Open any app ("open YouTube"), YouTube search, Google search, flashlight, alarm ("set an alarm for 7 AM"),
battery, volume, open settings, read notifications (needs Notification Access), reminder ("send me a notification in 10 minutes").
Arabic equivalents work too (e.g. "افتح يوتيوب", "شغل الفلاش"). Anything else goes to the AI.

Add a command: create a class extending `RegexAction` in `actions/Actions.kt` and add it to `ActionRegistry.actions`.

## FreeLLMAPI backend (primary AI)
JARVIS now defaults to [FreeLLMAPI](https://github.com/tashfeenahmed/freellmapi): one OpenAI-compatible endpoint that routes
across many free-tier providers and fails over between them when one is rate-limited. Model `auto` lets its router pick.
Any other OpenAI-compatible URL (Groq, OpenAI, Ollama...) still works from Settings.

FreeLLMAPI is a Node.js + SQLite service, so **it cannot be bundled inside the APK**. Two ways to run it:
1. **PC / Raspberry Pi / VPS / Docker (recommended):** `HOST=0.0.0.0 bash scripts/install-freellmapi.sh`, then set the JARVIS
   endpoint to `http://<that-machine-ip>:3001/v1`. JARVIS monitors it and reconnects automatically; Start/Stop/Restart do not apply.
2. **On the phone under Termux (experimental):** copy `scripts/freellmapi-ctl.sh` to `~/`, run `./freellmapi-ctl.sh setup` once
   (compiles a native SQLite module; may fail on some devices), enable `allow-external-apps=true` in `~/.termux/termux.properties`,
   grant JARVIS the *Run commands in Termux* permission. Then Settings > Start / Stop / Restart and *Auto-start and recover* work.

**You must supply:** a dashboard account (first run), at least one provider API key (added in the FreeLLMAPI dashboard, never in the
app), and the unified `freellmapi-...` key pasted into JARVIS Settings > API key. Without a provider key the server answers
HTTP 503 `no_providers_configured`, which JARVIS shows as-is. Check everything with
`bash scripts/verify-freellmapi.sh http://<host>:3001 <unified-key>` (health, auth, /v1/models, chat, real SSE streaming).

In the app: server status (ping latency) in Settings, model chips loaded from `/v1/models`, retry with backoff on network drops
(only before any text was spoken), automatic fall back to `auto` if a chosen model disappears.

## Wake greeting and loudness
- The wake greeting is **text spoken live by the normal JARVIS voice** (Fish Audio, or the on-device voice) - the prerecorded MP3 was
  removed. Edit it in Settings; placeholders `{greeting}` `{time}` `{date}`; optional "Let the AI write the greeting" (2.5 s limit,
  then the template). The notification action *Wake JARVIS* runs the identical activation. Repeat wake events within 4 s are ignored.
- Loudness: *Voice volume* (0-1) and *Extra gain* (0-12 dB via Android LoudnessEnhancer; high values can distort) are saved. They are
  software only: Android does not let apps exceed the phone's volume, so a button sets the media stream to its maximum.

## Honest limitations
- **Voice:** no Marvel audio is bundled. The JARVIS voice is a community voice model hosted by Fish Audio and generated
  with your own Fish API key (credits/limits apply). Without a key, the app uses your phone's British male voice.
- **Wake word, greeting, loudness, Termux control:** written without an Android SDK available, so **not compiled or run on a device** - build via CI and test on your phone. Wake word also still unverified on a device; accuracy and
  battery use need real-world checking.
- **Android restrictions:** other apps' notifications are only readable after you enable Notification Access;
  Android cannot set the volume above hardware limits or silently start activities from the background;
  reminders use inexact alarms (can drift by a minute or so in Doze). The Accessibility Service is only used in Agent mode / screen questions.
- Speech recognition normally uses Google's recognizer and may need internet.
- The font is the system serif/sans pairing (a Claude-like feel); no proprietary fonts are bundled.

## Build
The wake-word library is not on Maven Central: run `bash scripts/fetch_sherpa.sh` once locally (CI does it itself).
GitHub: push to `main`, open **Actions -> Android CI -> Artifacts -> jarvis-ai-debug-apk**.
Locally: open in Android Studio (JDK 17) and run, or `gradle assembleDebug` (Gradle 8.7+).
APK: `app/build/outputs/apk/debug/app-debug.apk`. Unit tests: `gradle testDebugUnitTest`.

## Install and configure
1. Download the APK artifact, unzip, copy `app-debug.apk` to your phone and open it (allow "install unknown apps").
2. On first launch the endpoint is pre-filled with Groq (`https://api.groq.com/openai/v1`). Paste your Groq key and optional Fish Audio key, tap **Test connection**.
   If you installed an earlier build, uninstall it first (debug builds from different CI runs have different signatures).
3. Tap the mic, grant microphone permission when asked, and talk. Settings (gear icon) edits everything later.

## Security
No keys are in the repo. The API key is encrypted on-device. `.gitignore` excludes keystores, `.env`, `local.properties`.
Cleartext HTTP is allowed so you can use a local server (e.g. Ollama on your LAN); prefer HTTPS otherwise.
