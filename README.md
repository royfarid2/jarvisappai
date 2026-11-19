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

## Agent mode (the lightning button)
Turn it on, then tell JARVIS what to do ("open WhatsApp and message Sara hello"). It reads the screen through the
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

## Honest limitations
- **Voice:** no Marvel audio is bundled. The JARVIS voice is a community voice model hosted by Fish Audio and generated
  with your own Fish API key (credits/limits apply). Without a key, the app uses your phone's British male voice.
- **Wake word:** always-on "Hey JARVIS" is not implemented (battery/privacy limits, needs a dedicated engine).
  Use the mic button and Hands-free mode instead.
- **Android restrictions:** other apps' notifications are only readable after you enable Notification Access;
  Android cannot set the volume above hardware limits or silently start activities from the background;
  reminders use inexact alarms (can drift by a minute or so in Doze). The Accessibility Service is only used in Agent mode / screen questions.
- Speech recognition normally uses Google's recognizer and may need internet.
- The font is the system serif/sans pairing (a Claude-like feel); no proprietary fonts are bundled.

## Build
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
