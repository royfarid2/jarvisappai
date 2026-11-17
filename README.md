# JARVIS AI

A native Android personal assistant (Kotlin, Jetpack Compose, Material 3, MVVM). Chat or talk to an
OpenAI-compatible AI, hear replies in a British male voice, and control parts of your phone.

## Features
- Streaming chat (SSE) with any OpenAI-compatible endpoint, conversation memory (Room), Arabic + English
  (Iraqi / Saudi dialect aware via the system prompt and `ar-IQ` / `ar-SA` speech recognition).
- Brain: Groq (OpenAI-compatible, default `llama-3.3-70b-versatile`) - or any OpenAI-compatible endpoint.
- Voice out: **Fish Audio** with the community voice model "Jarvis (MCU) J.A.R.V.I.S" (editable ID), with automatic
  fallback to Android TextToSpeech (en-GB male). Voice in: Android SpeechRecognizer. Speed/pitch/voice settings,
  replay button, stop/interrupt, waveform + listening/speaking animations, hands-free mode.
- Replies are spoken sentence-by-sentence while they stream in.
- Settings: endpoint, API key (EncryptedSharedPreferences, Android Keystore-backed), model, voice, speed, pitch,
  language, theme, auto-voice, permissions, clear history. Preferences in DataStore.
- Local notifications and timed reminders.

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
  reminders use inexact alarms (can drift by a minute or so in Doze). No AccessibilityService is used.
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
