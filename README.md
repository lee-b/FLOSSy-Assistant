# FLOSSy Assistant - On-Device AI Voice Assistant

<img src="docs/images/app-icon.webp" alt="FLOSSy Assistant logo" width="128" align="left" />

A privacy-first, fully local AI voice assistant for Android that connects to your own running
services. No cloud dependencies, no telemetry, no data leaves your device.

## Differences from upstream (smoluu/LocalAssistant - hard-forked as FLOSSy Assistant)

FLOSSy Assistant is a hard fork of [smoluu/LocalAssistant](https://github.com/smoluu/LocalAssistant):
same project, different package and application id (`ai.flossyapp`), renamed because the voice
pipeline has diverged too far for a soft fork to claim. The changes, mostly around making voice
mode genuinely hands-free and resilient:

- **Rate limits wait instead of failing** - a 429 from an endpoint is retried on an exponential
  backoff (250ms doubling to 2s, then a steady 2s poll), with a soft footstep-like sound and an
  "Awaiting connection..." status in the chat, the notification and the settings test buttons,
  instead of an error ending the exchange
- **A streaming capture per call, gated by a neural VAD** - the microphone is one persistent
  stream for the whole call (not a fresh recorder per turn), so the next turn begins the instant
  the user starts speaking. Speech is detected by the bundled Silero VAD network (silero_vad.onnx,
  both voice entry points - the chat screen's mic button and the wake phrase - run through the same
  `VoiceCallEngine` singleton: one persistent capture, one exchange at a time, and the mic owned by
  whichever mode is active, so no recorder ever fights another for the device.
  2.3MB, run through ONNX Runtime with the NNAPI provider so a Pixel's NPU/GPU does the inference;
  CPU fallback elsewhere), which tells "human speaking" from "loud" - taps, fans and door slams
  are not utterances, whatever the device, the mic or the room. The utterance clip is cut on the
  network's own speech span, with 300ms of lead rising out of the noise and 500ms of decay kept
- **One exchange at a time, and never hearing its own voice** - replies are spoken inside the
  exchange that produced them, so the capture's phase gate stays shut until the speaker is
  actually silent; concurrent exchanges and self-echo cannot happen. Hanging up cancels every
  exchange mid-flight and closes the capture
- **Speech playback off the main thread** - TTS audio writes on Dispatchers.IO, so a stalled
  AudioTrack can never freeze input dispatch (an ANR trace once caught the main thread parked
  inside a playback write)
- **Voice calls keep going** - a misheard request is answered by voice ("Sorry, I misheard you.
  Please repeat.") rather than a toast a user with the screen off would never see
- **No broken speech, no silent stalls** - a reply's sentences play through one AudioTrack that
  stays fed ahead of the playback head (two TTS fetches in flight, a ~1-2s ring buffer), so
  sentence boundaries are inaudible; hanging up cuts sound out immediately instead of draining,
  and when the TTS server falls behind real-time speech, the waiting tippy-taps fill the gap and
  a display-only note in the chat says the server or connection is too slow
- **Listening is audible** - a matched pair of synthesized plop cues plays as the microphone opens
  and closes, in voice mode and hands-free alike
- **Bluetooth and wired headsets work** - each recorder is steered at the headset input with
  `AudioRecord.setPreferredDevice` (device read, never set), so the STT hears the device the user
  is actually holding while no global communication route is touched - no SCO toggling, no volume
  curve changes, no interruption to media playback - and a headset connected or pulled
  mid-utterance re-routes capture on the fly
- **Voice on launch** - opening the app starts the voice pipeline listening straight away,
  behind a new "Voice on Launch" switch
- **The big microphone is real** - the large mic on the empty-state screen starts recording like
  the small button next to the input, and both toggle an in-flight capture; focusing the message
  input ends it
- **Honest model lists** - settings list one full-width choice per line so long model ids fit,
  and ids with slashes ("local/medium") are kept whole instead of truncated
- **Navigation-bar aware chat bar** - the message input no longer renders under the system
  back/home/overview bar
- **The bot artwork is the app icon** and the README logo

## Features

### Core Capabilities
- **On-Device Wake Word Detection** - Matches your own recorded phrases against the microphone clip
  on-device (no built-in phrase, no cloud, no external runtime); detection never fires on silence or room noise
- **Voice Pipeline** - Complete voice interaction: STT → LLM → TTS
- **Continuous Conversation** - One wake word opens a conversation: after every reply the mic re-arms,
  so you keep talking to the assistant until there is real silence
- **Hands-Free Overlay Outside The App** - The exchange is drawn over the home screen or whatever the
  device is showing, through a voice interaction session service - it stays visible while the app is minimized
- **Chat History** - Timestamped conversation on the chat screen, with pinning and summarization

### Privacy & Security
- 100% local processing - all AI runs on your own hardware
- No cloud services, no analytics, no telemetry
- All data stays on your device
- OpenAI-compatible endpoints for maximum flexibility

### User Interface
- Clean Material 3 design with the Gruvbox palette
- A compact card that rises from the bottom edge, with the reply streaming into its bubble
- A status dot and label that name the current phase (Ready / Listening… / Thinking… / Answering… / Speaking…)
- Comprehensive settings for every configuration

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   UI Layer (Compose)                │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐  │
│  │ ChatScreen│ │Settings │  │ VoiceControlsBar │  │
│  └──────────┘  └──────────┘  └──────────────────┘  │
├─────────────────────────────────────────────────────┤
│                ViewModel Layer                      │
│  ┌──────────────┐  ┌──────────────────┐            │
│  │ ChatViewModel│  │SettingsViewModel │            │
│  └──────────────┘  └──────────────────┘            │
├─────────────────────────────────────────────────────┤
│                 Service Layer                       │
│  ┌──────────────────────────────────────────────┐  │
│  │ VoiceAssistantService (in-app pipeline)      │  │
│  │  audio capture → VAD → STT → LLM → TTS       │  │
│  ├──────────────────────────────────────────────┤  │
│  │ WakeChatService (hands-free, a               │  │
│  │   VoiceInteractionService)                   │  │
│  │  wake match → record → STT → LLM → TTS       │  │
│  │        ↓ showSession()          ↑ re-arm mic │  │
│  │ WakeChatSessionService (draws the card)      │  │
│  └──────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────┤
│                  Data Layer                         │
│  ┌──────────────┐  ┌──────────────────┐            │
│  │ Room DB      │  │ SharedPreferences│            │
│  │ (Chat History)│ │ (Settings)       │            │
│  └──────────────┘  └──────────────────┘            │
├─────────────────────────────────────────────────────┤
│           External Services (Local)                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │ LLM      │  │ STT      │  │ TTS      │          │
│  │ (OpenAI- │  │ (OpenAI  │  │ (OpenAI  │          │
│  │  compat) │  │  audio)  │  │  audio)  │          │
│  └──────────┘  └──────────┘  └──────────┘          │
└─────────────────────────────────────────────────────┘
```

## Prerequisites

### Android Device
- Android 12 (API 35) or higher - `minSdk` and `targetSdk` are both 35
- Microphone access
- Network access to your local AI services
- **To use the hands-free overlay**, the platform must host hands-free conversations: open the
  system's "Default digital assistant" page and select FLOSSy Assistant. Without that selection the
  app still runs its own pipeline, but the system refuses to draw the card outside the app.

### Local AI Services

You need to run these services on your local network. Any OpenAI-compatible server works; the app
is tested against the ones named below.

#### 1. LLM Service (OpenAI-compatible `/v1/chat/completions`)
Tested against [llama.cpp](https://github.com/ggml-org/llama.cpp) `server`;
[Ollama](https://ollama.ai/) and [vLLM](https://github.com/vllm-project/vllm) also work.

```bash
# llama.cpp
llama-server -m /models/Qwen3.5-4B-Q4_K_M.gguf --port 8086
# or Ollama
ollama pull llama3.1:8b && ollama serve
```

> Reasoning models stream their chain of thought before the answer, so keep `max_tokens` generous
> (256 or more) or the response comes back with empty `content`.

#### 2. STT Service (OpenAI-compatible `/v1/audio/transcriptions`)
Tested against [whisper.cpp](https://github.com/ggml-org/whisper.cpp) `server`;
[faster-whisper](https://github.com/SYSTRAN/faster-whisper) works too.

```bash
# whisper.cpp
./whisper-server -m ggml-medium.bin -t 8 --port 8088
```

#### 3. TTS Service (OpenAI-compatible `/v1/audio/speech`)
Tested against a `qwentts.cpp` server; [Piper](https://github.com/rhasspy/piper) and Coqui TTS work
behind an OpenAI-compatible shim.

```bash
# Always send response_format - it names the container the client receives
curl -X POST http://192.168.1.100:8087/v1/audio/speech \
  -H 'Content-Type: application/json' \
  -d '{"model":"<model>","input":"Hello","response_format":"wav"}'
```

> Never hardcode a voice name: read `GET /v1/audio/voices` and use what it advertises. A server
> that answers `{"voices":[]}` rejects every `voice` field, so omit it there.

## Setup Instructions

### 1. Clone and Build
```bash
cd /path/to/FLOSSyAssistant
./gradlew assembleDebug
```

### 2. Install on Device
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Configure Endpoints
Open the app → Settings:
- **LLM Base URL**: e.g. `http://192.168.1.100:8086`
- **STT Base URL**: e.g. `http://192.168.1.100:8088`
- **TTS Base URL**: e.g. `http://192.168.1.100:8087`

Base URLs may be given with or without the trailing route - the client strips a known suffix and
appends the correct one.

### 4. Grant Permissions
The app will request:
- Microphone access (for voice input)
- The persistent notification required for always-listening mode
- Battery optimization exemption (recommended for continuous operation)

## Usage

### Waking the Assistant
1. Open the app → Settings → Wake Word and record one or more references of the phrase you want to
   say ("Record Reference"). Detection only matches those recordings; with none enrolled the wake
   service reports that and never fires.
2. Start listening (mic button, or the home-screen widget). The service keeps running while the app
   is minimized.
3. Say the phrase you enrolled. The card rises from the bottom edge and the chime confirms it heard
   you - the card is raised first, so it is already up when the chime sounds.
4. Speak your request. The transcript appears in the user bubble, the reply streams into the answer
   bubble as it arrives, and TTS reads it aloud.
5. **Keep talking.** After the reply the mic re-arms and the card still shows the previous turn, so
   you can ask a follow-up without saying the wake word again. The card closes once real silence
   reaches the configured timeout - listening, thinking and speaking never count down against it.

### Settings
Configure all aspects of the assistant:
- API endpoints, model names and voices
- Wake word references (recorded on-device) and match sensitivity
- Voice activity detection (minimum silence, energy floor)
- System prompt for custom behavior
- Wake chat windows: the first-turn request window and the close timeout
- Generation controls: `max_tokens`, reasoning budget and `chat_template_kwargs`

### Debugging From adb
Trigger an exchange without going through the menus or saying the phrase:
```bash
adb shell "am start -W -a ai.flossyapp.TRIGGER_WAKE_CHAT ai.flossyapp/.ui.MainActivity"
```
The service logs its own phase, so `adb logcat` filtered on the class name is the running trace:
```bash
adb logcat | grep WakeChat
```

### Reading The Wake Path
The whole detection pipeline - microphone capture, the cut to the utterance, MFCC features, the
band-limited DTW match against your own recorded references, the threshold and the idle listening
loop - is documented in [`docs/wake-word-pipeline.md`](docs/wake-word-pipeline.md). It names every
constant and why it has that value, the log tags to filter on, and what a healthy enrollment looks
like when a wake word refuses to fire.

## Project Structure

```
app/
├── src/main/java/ai/flossyapp/
│   ├── FlossyApplication.kt            # Application class
│   ├── domain/
│   │   └── model/                      # Data models
│   │       ├── ChatMessage.kt          # Message entity
│   │       └── AppSettings.kt          # Settings data class
│   ├── data/
│   │   ├── local/                      # Room database
│   │   │   ├── FlossyDatabase.kt
│   │   │   └── ChatMessageDao.kt
│   │   ├── remote/                     # API clients
│   │   │   └── ApiClient.kt            # HTTP client for LLM/STT/TTS
│   │   └── settings/                   # Settings repository
│   │       └── SettingsRepository.kt
│   ├── service/                        # Foreground services
│   │   ├── VoiceAssistantService.kt    # Main in-app voice pipeline
│   │   ├── WakeChatService.kt          # Hands-free wake-word pipeline (a VoiceInteractionService)
│   │   ├── WakeChatSessionService.kt   # Draws the exchange over the system UI (a VoiceInteractionSessionService)
│   │   └── WakeChatRecognitionService.kt # Hollow RecognitionService stub required by the manifest resource
│   ├── ui/                             # Compose UI
│   │   ├── MainActivity.kt             # Entry point & navigation graph
│   │   ├── chat/                       # Chat screen components
│   │   │   ├── ChatScreen.kt           # Main chat interface & VoiceControlsBar
│   │   │   └── ChatViewModel.kt        # Chat state management
│   │   ├── wake/                       # Wake service plumbing (renderer only)
│   │   │   └── WakeChatViewModel.kt    # Forwards the service's state & start/stop intents
│   │   ├── settings/                   # Settings screen components
│   │   │   ├── SettingsScreen.kt       # Configuration UI
│   │   │   └── SettingsViewModel.kt    # Settings state management
│   │   ├── common/                     # Audio helpers, VAD, wake-word matcher
│   │   │   ├── CommonUtilities.kt
│   │   │   ├── WakeWordDetector.kt
│   │   │   └── DefaultAssistant.kt     # Assistant-role oracle & picker intents
│   │   └── theme/                      # Gruvbox Material3 colour schemes
│   │       └── Theme.kt
│   ├── receiver/                       # Broadcast receivers
│   │   ├── AppBootReceiver.kt          # Boot completed handler
│   │   └── NetworkStateReceiver.kt     # Network connectivity monitor
│   └── widget/                         # Home-screen widget
│       └── VoiceAssistantWidget.kt
├── src/main/res/
│   ├── values/                         # Light string resources & themes
│   │   ├── strings.xml                 # All string resources
│   │   ├── colors.xml                  # Light palette (mirrors Theme.kt)
│   │   └── themes.xml                  # App theme configuration
│   ├── values-night/                   # Dark variants of the same tokens
│   │   ├── colors.xml
│   │   └── themes.xml
│   └── xml/
│       ├── network_security_config.xml # Cleartext HTTP to local endpoints
│       ├── voice_interaction.xml       # Names the voice service and its session half
│       ├── widget_voice_assistant.xml  # Widget descriptor
│       ├── backup_rules.xml
│       └── data_extraction_rules.xml
├── src/main/assets/
│   └── voice_clone.wav                 # Bundled sample used by the TTS/STT tests
├── build.gradle.kts                    # Module-level Gradle config
└── AndroidManifest.xml                 # App manifest with permissions
```

## Troubleshooting

### Service Not Starting
- Check that all local services are running and accessible
- Verify network connectivity between device and services
- Ensure battery optimization is disabled for the app

### The Card Only Renders Inside the App
The session window is drawn by the platform, so the app has to hold the assistant role. Open the
system's "Default digital assistant" page and select FLOSSy Assistant, then start the service again -
`adb install` force-stops the running process. The service logs
`onReady: the system has bound this app as its voice interaction service` once the platform has
really bound it; if that line never appears, the selection is the problem, not the code.

### The Wake Card Draws Off the Screen Edge
The card's scale comes from the display, measured directly through the session's
`WindowManager.maximumWindowMetrics`. If the log line `Measured the display directly` never
appears, the exchange falls back to the shipped design (tuned for a 1840-px short edge) and
the card overruns a smaller screen - the fallback, not the rule, is the failure.

### Audio Issues
- Grant microphone permission in Settings → Permissions
- Check that audio format matches your STT service requirements (16 kHz, mono, 16-bit PCM)
- TTS sample rates are tried in a fallback loop - device support varies widely

### Network Errors
- Verify base URLs are correct and accessible from the device
- Check network security config allows cleartext HTTP to local IPs
- Ensure firewall rules allow incoming connections on service ports

### Wake Word Never Fires
- Enroll at least one reference first - the matcher has no built-in phrase
- Use the Settings "Test Wake Word" action, which prints the measured DTW cost against the current
  threshold, then tune the sensitivity slider

## Future Enhancements

- [ ] Replace the built-in energy VAD with a model-based detector (silero-vad)
- [ ] Add barge-in support: interrupt the assistant while it is speaking
- [ ] Play TTS audio through Media3 ExoPlayer instead of `AudioTrack`
- [ ] Add Hilt dependency injection for better testability
- [ ] Implement encrypted storage for API keys
- [ ] Support multiple wake phrases bound to different actions

## License

MIT License - See LICENSE file for details

## Acknowledgments

- [llama.cpp](https://github.com/ggml-org/llama.cpp), [Ollama](https://ollama.ai/) and
  [vLLM](https://github.com/vllm-project/vllm) for local LLM serving
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) and
  [faster-whisper](https://github.com/SYSTRAN/faster-whisper) for speech recognition
- [Piper](https://github.com/rhasspy/piper) and Coqui TTS for text-to-speech
- [Jetpack Compose](https://developer.android.com/jetpack/compose) for modern UI
