# JARVIS V1.1 — Android voice assistant

Korean voice in → AI → calm English voice out, with Korean subtitles on a black futuristic HUD.

```
"JARVIS" ─▶ wake word ─▶ LISTENING ─▶ Korean STT ─▶ AI (JSON) ─▶ Command ─▶ English TTS + 한국어 자막
```

* Kotlin · Jetpack Compose · Room · OkHttp · coroutines · Gradle Kotlin DSL
* `minSdk 29` (Android 10) · `compileSdk/targetSdk 35`
* No Google Play Services, no proprietary SDKs

## What's new in 1.1

* **Better wake word** – fuzzy matching of mis-hearings (Jervis, 자비스, 저비스, 차비스 …), three sensitivity levels,
  English added to the Korean recognizer, shorter listening windows, haptic tick on wake.
* **Air gestures** – wave over the phone (proximity sensor, works with the screen off while JARVIS is active;
  single / double wave) and camera hand swipes (left / right / up / down, only on the JARVIS screen, with a
  visible "GESTURE CAM" label, nothing is recorded). Every gesture can be mapped to an action in Settings.
* **New commands** – timer, call / text a contact (opens the dialer / composer, you press send), navigation and map
  search, calendar event, camera, copy / share text, private voice **notes**, Wi-Fi / Bluetooth / display … settings.
* **Convenience** – quick-action chips on the HUD, Quick Settings tile and launcher shortcut ("Talk to JARVIS"),
  notes screen, configurable form of address ("Sir", "Ma'am", your name).
* **Updates install over v1.0** – same application ID and the same fixed signing key (`keystore/jarvis.keystore`),
  `versionCode` 2. Conversation history and settings are migrated, not reset.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # signed with the debug key so it installs directly
./gradlew testDebugUnitTest      # parser, pipeline and weather tests
```

Open the folder in Android Studio (Ladybug or newer) and press Run, or let the **Android CI**
GitHub Action build both APKs on every push (download them from the run's *jarvis-apk* artifact).

## First run

1. Open the app, grant **Microphone** (and **Notifications** for standby).
2. *Settings → AI CORE*: choose a provider, endpoint, model and paste your API key.
   Without a key JARVIS still handles the built-in Korean commands (time, battery, flashlight,
   volume, alarm, apps, YouTube, weather, music) with a rule-based fallback.
3. Tap **SPEAK**, or switch **STANDBY** on and say "JARVIS" (자비스).

## Architecture

| Package | Role | Swap point |
|---|---|---|
| `ai/` | `AIProvider` → `OpenAICompatibleProvider`, `GeminiProvider`, `OllamaProvider`; system prompt; JSON reply parser with fallback | add a class, register in `AppContainer` |
| `speech/` | `SpeechRecognizerEngine` (Android `SpeechRecognizer`), `WakeWordEngine` (`SpeechWakeWordEngine`) | implement the interface (Whisper, Porcupine, Vosk …) |
| `tts/` | `TTSProvider` → Android system, OpenAI-compatible, ElevenLabs; `PcmPlayer` derives the real amplitude envelope for the HUD | add a provider |
| `command/` | `Command` allow-list, `CommandRouter`, `CommandExecutor`, `ActionResult`, offline `LocalIntentParser` | add a `Command` |
| `search/`, `weather/` | `WebSearchProvider` (browser / keyless instant answers), `WeatherProvider` (Open-Meteo), `LocationProvider` | add a provider |
| `core/` | `JarvisController` state machine (IDLE → LISTENING → THINKING → EXECUTING → SPEAKING, ERROR) | |
| `service/` | `JarvisForegroundService` (type *microphone*) hosting the wake-word loop | |
| `data/` | Room (`ConversationEntity`, `MessageEntity`, `SettingsEntity`), repositories | |
| `security/` | `SecureStorage`: AES-256-GCM key in the Android Keystore for API keys | |
| `ui/` | boot sequence, HUD home, memory log, settings, `JarvisCore` canvas | |

### AI reply contract

```json
{ "speech": "Opening YouTube.", "subtitle": "유튜브를 엽니다.",
  "action": { "type": "OPEN_APP", "package": "com.google.android.youtube" } }
```

The model can only request actions registered in `CommandRouter`; anything else is refused.
Tool output (weather, web snippets) is fed back to the model as data for a second, spoken answer.

### Commands

`OPEN_APP` `OPEN_URL` `OPEN_SETTINGS` `SEARCH_WEB` `WEB_ANSWER` `SEARCH_YOUTUBE` `WEATHER`
`SET_ALARM` `GET_TIME` `GET_BATTERY` `SET_VOLUME` `VOLUME_UP/DOWN` `FLASHLIGHT_ON/OFF`
`MUSIC_PLAY/PAUSE/NEXT/PREVIOUS` `NOTIFICATION`

## What Android does and does not allow (read this)

* **The microphone is only used while the foreground service is running**, and its ongoing
  notification ("Microphone active", with a *Stop* button) is always visible. There is no hidden
  listening, and nothing restarts the service after a force-stop or reboot.
* Android 14+ only lets a microphone foreground service start while the app is visible, so start
  **STANDBY** from the app (or enable *Background Assistant* to start it automatically on app open).
* **Wake word:** the default engine runs short on-device (Android 13+) / offline-preferred
  recognition windows and matches "JARVIS" / "자비스". It is reliable but not as frugal as a
  dedicated keyword spotter; expect noticeable battery use during long standby. For production,
  implement `WakeWordEngine` with Porcupine / openWakeWord and register it in `AppContainer`.
* **Opening apps from the background** is restricted by Android 10+. With *Display over other apps*
  granted JARVIS launches directly; otherwise it posts a notification you tap to continue.
* Alarms use the system clock app (`ACTION_SET_ALARM`): you give a time, the clock app owns the
  date, so "tomorrow 7am" sets the next 7:00.
* Background location needs extra permission, so weather without a live fix falls back to the last
  known location, then to your *default city* setting.

## Security & privacy

* API keys live only in Keystore-encrypted storage; they are never logged (`JLog` redacts key
  patterns) and are not shown again after saving. Cloud backup is disabled.
* Audio is processed by the system recognizer and is never recorded or stored by the app.
* `usesCleartextTraffic` is enabled only so a LAN Ollama server (`http://192.168.x.x:11434`) works;
  all built-in endpoints use HTTPS.

## Real-device test checklist

- [ ] Install APK, boot animation, HUD shows `SYSTEM READY`
- [ ] Microphone / notification permissions
- [ ] Say "JARVIS" with STANDBY on → "Yes, sir?" + 네, 말씀하세요
- [ ] Korean STT: "지금 시간 알려줘", "볼륨 50퍼센트로 설정해"
- [ ] AI answer with your key; English voice + Korean subtitle; waveform follows the voice
- [ ] Commands: YouTube open/search, alarm, battery, flashlight, volume, music, weather, web search
- [ ] Airplane mode → "Connection unavailable." and local commands still work
- [ ] Lock screen / app in background with STANDBY on
- [ ] Settings persist after restart; API key not visible; history can be deleted
