# JARVIS AI

A native Android personal assistant (Kotlin, Jetpack Compose, Material 3, MVVM). Chat or talk to an
OpenAI-compatible AI, hear replies in a British male voice, and control parts of your phone.

## Features
- Streaming chat (SSE) with any OpenAI-compatible endpoint, conversation memory (Room), Arabic + English
  (Iraqi / Saudi dialect aware via the system prompt and `ar-IQ` / `ar-SA` speech recognition).
- Voice in and out: Android SpeechRecognizer (STT), TextToSpeech (en-GB male voice preferred, low pitch),
  optional premium cloud voice via your endpoint's `/audio/speech`, speed/pitch/voice settings,
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
- **Voice:** the original Marvel voice is copyrighted and is not included. JARVIS uses the best British male voice
  installed on your phone (install "English (UK)" voice data in Android TTS settings). For higher quality, enable
  *Premium cloud voice* in Settings and use a provider with an OpenAI-compatible `/audio/speech` route.
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
2. On first launch enter your endpoint (e.g. `https://api.openai.com/v1`), API key and model, tap **Test connection**.
3. Tap the mic, grant microphone permission when asked, and talk. Settings (gear icon) edits everything later.

## Security
No keys are in the repo. The API key is encrypted on-device. `.gitignore` excludes keystores, `.env`, `local.properties`.
Cleartext HTTP is allowed so you can use a local server (e.g. Ollama on your LAN); prefer HTTPS otherwise.
