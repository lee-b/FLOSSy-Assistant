# FLOSSyAssistant - AI Agent Guide

## Project Overview

FLOSSyAssistant is a local-first Android voice assistant: it captures audio on-device and calls
user-configured local endpoints for LLM, STT and TTS. Nothing is sent to a cloud service and no
endpoint is hardcoded.

Two voice paths coexist: the in-app pipeline (`VoiceAssistantService`, mic button on the chat
screen) and the hands-free wake chat (`WakeChatService`), a foreground service that matches an
enrolled phrase on-device, runs record → STT → LLM → TTS, keeps the mic live between turns, and
draws the exchange in a platform `VoiceInteractionSession` window - the only rendering that exists
while the app is minimized.

## Local Development Setup

Machine-specific facts (local endpoints, adb device, build/deploy commands, the vendor assistant
picker) live in `AGENTS.local.md`, which is **git-ignored**. Read it when working on that machine;
never commit it and never copy its specifics into this file.

## Tech Stack
- Kotlin 1.9+, JVM target 17; Jetpack Compose Material3 + Navigation Compose; MVVM (`AndroidViewModel` + `StateFlow`)
- Room (single table `chat_messages`) · SharedPreferences for settings, not DataStore · kotlinx.serialization on `ChatMessage`, org.json for API bodies
- OkHttp + SSE streaming · `AudioRecord` for capture, `AudioTrack` for playback (no `MediaRecorder`)
- compileSdk/minSdk/targetSdk = 35 (Android 12); packages under `ai.flossyapp.*`

## Architecture
- `domain/model/` - `ChatMessage`, `MessageRole`, `AppSettings` (every config field)
- `data/local/` - `FlossyDatabase`, `ChatMessageDao`
- `data/remote/` - `ApiClient`: `chatCompletion` (SSE), `transcribeAudio`, `synthesizeSpeech`, `fetchModels`, `fetchVoices`
- `data/settings/` - `SettingsRepository` (SharedPreferences; presets are hand-built JSON)
- `service/` - `VoiceAssistantService` (in-app pipeline) · `WakeChatService` (hands-free pipeline; **is** an `android.service.voice.VoiceInteractionService`) · `WakeChatSessionService` (its drawing half: window choreography only - surface, scale, fade - while the card's content is a ComposeView rendering `WakeCardContent` through the shared bubble kit) · `WakeChatRecognitionService` (hollow `RecognitionService` stub the manifest resource requires)
- `ui/chat/` - `ChatViewModel` + `ChatScreen` (the only conversation history; carries `VoiceControlsBar`)
- `ui/wake/` - `WakeChatViewModel` (thin forwarder of the service's `StateFlow`s and start/stop intents) and `WakeCardContent.kt` (the session card's Compose content: bubbles, scroll, Open app/End chat buttons, driven by a `WakeCardSnapshot` the session paints).
- `ui/settings/` - `SettingsViewModel` + `SettingsScreen` (presets, model fetching, wake enrollment, wake-chat section, adb debug trigger)
- `ui/common/` - `CommonUtilities.kt` (VAD recording, WAV, TTS playback) · `BubbleKit.kt` (**one shared bubble render**: `ChatBubble` shell + `BubblePinEffect` bottom-pinning scroll, used by both `ChatScreen` and the wake card) · `CallAvatar.kt` (**one shared animated bot face** both voice surfaces ride) · `WakeWordDetector.kt` (MFCC + DTW matcher against enrolled references) · `DefaultAssistant.kt` (assistant-role oracle + picker intents)
- `ui/common/VoiceCallEngine.kt` - the **one shared voice-call engine**: a single persistent `StreamingCallCapture` (Silero-scored) owned by whichever voice mode is active - the chat screen's mic button (`APP`) or the wake service (`WAKE`) - and one serialized STT → LLM (MCP tools included) → synchronous TTS pipeline per utterance, reported to each owner through an `ExchangeHost` interface. The old per-window energy recorder in the wake service read a silent device whenever both paths were live; there is now never a second recorder.
- `ui/theme/` - `Theme.kt` Gruvbox schemes · `receiver/` boot + network restarters · `widget/` home-screen mic control
- `ui/MainActivity.kt` - entry point and navigation graph; **do not edit**

## Coding Standards & Conventions
- `StateFlow` for all reactive UI state (`asStateFlow()` when mutable internally); ViewModels extend `AndroidViewModel` and run on `viewModelScope`
- Audio is 16 kHz mono 16-bit PCM (`ENCODING_PCM_16BIT`, `CHANNEL_IN_MONO`); WAV headers are written manually with `writeIntLE`/`writeShortLE`
- TTS plays through `AudioTrack` with a sample-rate fallback loop; never rely on playback completion listeners
- Every OkHttp call runs on `Dispatchers.IO` inside `withContext()` - never block the main thread
- Settings are re-read from SharedPreferences before each service operation, never cached across one
- KDoc on public functions and classes; `android.util.Log.d/w/e`, quiet when `debugLoggingEnabled=false`

## Common Commands
```bash
./gradlew assembleDebug          # debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # unit tests
./gradlew connectedAndroidTest   # instrumentation tests
./gradlew clean && ./gradlew build --refresh-dependencies
```
Device launch/install commands are in `AGENTS.local.md`.

## Important Patterns & Idioms
- **Streaming**: `ApiClient.chatCompletion()` delivers deltas through `onChunk`; the full text is finalised after the `[DONE]` marker.
- **One capture per call, gated by Silero VAD**: `StreamingCallCapture` (`CommonUtilities.kt`) holds one persistent `AudioRecord` for the whole voice call; the mic icon never re-arms. Speech is detected by `SileroVad` (`ui/common/SileroVad.kt`), which runs the bundled `res/raw/silero_vad.onnx` (loaded from the resource, never downloaded) through ONNX Runtime with the NNAPI provider + FP16 so a Pixel's NPU/GPU does the 32ms-chunk inference. Energy gates cannot tell a word from a tap or a fan - never replace the neural detector with an RMS threshold. The utterance cut is on the network's own span (`trimToWav` keeps 300ms of lead before the onset, 500ms of decay after); the energy span is a fallback only. The in-progress utterance buffer is a bounded rolling window (30s ceiling, 20s kept, trimmed at chunk granularity) - an unbounded list OOMed the process after ~30 idle wake minutes, killing the wake service mid-exchange.
- **One call, one mic, one conversation**: mic ownership is arbitrated by `VoiceCallEngine` (APP vs WAKE vs IDLE) - the screen never sends start/stop commands to the wake service, it hands the mic to the engine, and the wake loop (which keeps running underneath, retrying `startWakeMode()` every 500ms) re-enters wake mode the moment the engine releases the capture. When APP mode releases a call because its *window vanished* (`stopAppMode(handoffToWake = true)` from ChatScreen's `DisposableEffect` onDispose), the engine latches a handoff and the wake service adopts the running conversation - the same call continues in the popup, with End-call buttons on the card and in the notification. A deliberate hang-up uses `stopAppMode(handoffToWake = false)`. The conversation text lives in `ui/common/ConversationStore.kt` (`messages`, `callActive`, `phase`), the one store both surfaces render: a wake conversation is visible in the fullscreen log the moment the app is opened mid-call, and outside an active call (`callActive=false`) no call UI or avatar is drawn anywhere.
- **Tippy taps are owned by the engine, not the surface**: the exchange coroutine starts a `playWaitingCue()` loop after `onPhase(THINKING)` - a 1.2s grace for fast replies, then a tick every 2.5s until SPEAKING begins, cancelled explicitly before the first syllable and automatically when the exchange ends or is aborted. `onTtsTooSlow` (the starving-reply path) plays the same tick from the engine now, so wake/popup mode sounds like the screen instead of only noting the stall in the notification. Do not move the ticks into the services: the loop is a child of the exchange, so it dies with it by structured concurrency.
- **One exchange at a time**: exchanges are serialized by a `Mutex` taken inside the exchange coroutine in `VoiceCallEngine` (an atomic CAS before `launch` once leaked latched after a scope cancel); a second utterance is dropped, never overlaid. `discardCurrentUtterance`/`ttsAbortSignal` are per-session state and are cleared on every mode entry (`startAppMode` and `startWakeMode`) - `stop()` latches them on, and a wake session entered without clearing returned every exchange before STT with no error. The VAD gate (`captureGate`) is set on the exchange's own entry/exit, not on recomposition, and the exchange speaks its own reply synchronously - the auto-TTS effect does not fire in a voice call, because it would double-speak and reopen the gate while the speaker is still audible (that race was real self-hearing).
- **Never block the main thread with playback**: `playTTSAudio` runs its whole body on `Dispatchers.IO`; an ANR trace caught the main thread parked inside `AudioTrack.write` once. Every blocking audio call (writes, drain, joins) belongs on IO.
- **Playback is one AudioTrack per reply**: `ReplyAudioPlayer` (`CommonUtilities.kt`) carries every TTS sentence on a single track created on first write; blocking `write()` throttles the producer, the ring is `minBufferSize * 16`, and `finish()` drains once against the frame count actually received - never drain per sentence and never guess the rate (the decoder names it). Starvation (lead < 250ms) fires the player's `onStarving` callback, rate-limited to one warning per 3 seconds; ChatScreen fills that gap with `playWaitingCue()` and appends a `MessageRole.SYSTEM` note that renders in the chat but is never sent to the LLM. Reasoning is separated by a `\u0001` chunk separator and by `parseReasoningAndContent()` (`<think>`/`<reasoning>`/`<thinking>`, plus OpenAI `reasoning_content`).
- **The call avatar never sits still while busy**: `CallAvatar` (`ui/common/CallAvatar.kt`) is the one animated bot face both voice surfaces ride - head + two eyes, no mouth, transform-only animation (scale/rotation/translation/alpha on two shapes; nothing redrawn, so it cannot glitch). Poses: CONNECTING breathes, LISTENING leans in, THINKING scans (head tilt + gliding eyes), SPEAKING pulses - one oscillator per pose, amplitude is a set of numbers per pose. The rule is **never fully still while busy**: thinking has no streamed tokens, so the scan is the only sign of life - a still card reads as broken. Chat screen: bot at top while a call is live, log fading into its space below; wake card: smaller bot between the status and the bubbles, with `AVATAR_HEIGHT` accounted in `neededHeight`.
- **Wake chat holds one exchange, not a log**: turns accumulate while the card is open and are cleared when the exchange ends.
- **The wake card is Compose drawn from the shared bubble kit**: `WakeChatSessionService` keeps only the window choreography (surface, scale, alpha fade) and paints a `WakeCardSnapshot`; the exchange itself is rendered by `WakeCardContent` through `ui/common/BubbleKit.kt` (`ChatBubble` + `BubblePinEffect`) - the same bubble shell and the same bottom-pinning scroll the chat screen runs, at the session's measured scale, so the card is a smaller version of the chat view by construction. Bubbles wrap themselves: there is no `LINE_CHARS` constant and no hand-rolled line cutting. A voice-interaction window builds no lifecycle of its own, so the session constructs `SessionComposeOwner` (a `LifecycleOwner`/`SavedStateRegistryOwner`) and wires it onto **every view from the leaf to the window's decor** - the recomposer resolves from the root, an owner set only on the leaf left the card throwing "ViewTreeLifecycleOwner not found". The wiring is reflection (the `ViewTree*Owner.set` actuals are android-only sources invisible to the compiler), and the saved-state controller must attach during the owner's own initialization (a field initialiser, not an init block - "Restarter must be created only during the owner's initialization stage"). The card carries an "Open app" button (`startAssistantActivity`, the sanctioned in-session activity launch) and a red "End chat" button wired to `ACTION_CLOSE_CHAT`. Wake notifications speak in assistant states ("Slumbering; awaiting wake word", "Awake; awaiting instructions", "Responding...", "Sleeping; listening OFF"), never microphone jargon. Poll slices: utterance 100ms, barge-in 250ms; rise/sink 4 frames x 40ms.
- **Close clock**: `touchChat()` stamps activity (each streamed chunk, the user's tap, the start of an exchange); the service waits until real silence reaches `wakeChatCloseSeconds`.
- **Retry**: `executeWithRetry(operationName, maxRetries = 3, delayMs = 1000)` wraps API calls in `VoiceAssistantService`.
- **Room DAO is not wired to the ViewModels** - message lists are `StateFlow<List<ChatMessage>>`.

## Things the Agent Should NEVER Do
- Never hardcode API endpoints - always read them from `AppSettings` via `SettingsRepository`
- Never make blocking network calls on the main thread - OkHttp calls belong in coroutines on `Dispatchers.IO`
- Never add an external wake-word runtime (Porcupine/Snowboy/rustpotter as a native library) - this toolchain has **no C FFI**, so a Rust `.so` cannot be loaded; the matcher is the pure-Kotlin port in `ui/common/WakeWordDetector.kt`
- Never trigger the wake word from the STT transcript - the trigger is on-device (`WakeWordDetector`); STT may only transcribe the request after the phrase matched
- Never swap the bundled Silero model for a download-at-runtime scheme - it is a raw resource on purpose
- Never remove the foreground-service notifications - required by API 35+ and declared in the manifest
- Never commit API keys or base URLs that carry credentials
- Never use `kotlinx.serialization` for preset JSON - presets are hand-built in `SettingsRepository`
- Never call `AudioManager.setCommunicationDevice`/`clearCommunicationDevice` - reading the device (`preferredCaptureDevice`) and steering the recorder with `AudioRecord.setPreferredDevice` is all routing the app does; setting the global route toggles the SCO link, which changes the headset's volume and stutters media playback

## Testing Guidelines
- Unit tests in `app/src/test/java/`, instrumentation tests in `app/src/androidTest/java/`
- Mock the OkHttp client (or wiremock) for network paths; test TTS playback across sample rates
- Validate `AudioRecord` buffer sizing on low-end devices

## Configuration
- Settings persist under SharedPreferences key `"settings"` (MODE_PRIVATE); presets are `preset_<uuid>` entries plus a `presets_list` name list
- Every endpoint URL is configurable at runtime through `SettingsScreen`

## Deep details — read on demand (not loaded with this file)
These are deliberately **not** inlined here. Read them with file tools when the task needs them;
never paste them back into this guide.
- `docs/wake-word-pipeline.md` - the wake path end to end: capture → cut → MFCC → band-limited DTW →
  threshold → idle loop, every constant and why it has that value, the log tags and what a healthy
  enrollment looks like in them, and the traps. **Read this before touching any part of the wake
  path** - the stages are calibrated to each other, so a change in one is only correct if it is
  mirrored in the others.
- `.qwen/skills/flossyassistant-deep-dive/SKILL.md` - the device-verified gotcha log: the wake matcher,
  the `VoiceInteractionService`/`VoiceInteractionSessionService` platform path, session window sizing,
  TTS and SSE pitfalls, and this toolchain's API limits. Auto-loaded when Kotlin files are touched.
- `AGENTS.local.md` - machine endpoints, adb device and deploy commands. Git-ignored, per-machine.
