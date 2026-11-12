# The wake-word pipeline

Read this before touching any part of the wake path. Every stage below feeds the next one, and the
stages are calibrated **to each other** — a change in one is only correct if the same change is
mirrored in the others. The single most expensive bug in this area (fixed 2026-10) was a stage that
cut clips on an absolute loudness while the next stage normalised loudness away: the two were on
different scales and every sensitivity setting missed, forever, with nothing wrong-looking in either
stage alone.

```
microphone ──▶ recordAudioWithVAD ──▶ extractWakeFeatures ──▶ wakeWordHeard ──▶ STT → LLM → TTS
              (CommonUtilities)      (WakeWordDetector)       (WakeWordDetector)   (WakeChatService)
                     ▲                      ▲                       ▲
        enrollment ──┘──────────────────────┘───────────────────────┘   SettingsViewModel
        (the same capture path, the same feature path, the same comparison)
```

## Where each stage lives

| Stage | File | Entry points |
| --- | --- | --- |
| Capture + cut to the utterance | `ui/common/CommonUtilities.kt` | `recordAudioWithVAD`, `voicedSpan`, `VOICED_TRIM_FRACTION` |
| Enrollment (record, save, clear) | `ui/settings/SettingsViewModel.kt` | `recordWakeClip`, `pauseWakeLoopMic`, `resumeWakeLoopMic` |
| Features (MFCC) | `ui/common/WakeWordDetector.kt` | `extractWakeFeatures`, `mfcc`, `prepareSignal`, `normalizeGain`, `containsSpeech` |
| Matching | `ui/common/WakeWordDetector.kt` | `bestMatchCost`, `confirmingWindows`, `wakeWordHeard`, `wakeAgreement`, `windowCost`, `frameDistance`, `aggregateCost` |
| Threshold | `ui/common/WakeWordDetector.kt` | `wakeCostThreshold`, `referenceSpread` |
| Enrollment file | `ui/common/WakeWordDetector.kt` | `loadWakeReferences`, `saveWakeReference`, `clearWakeReferences`, `pathForReferences` |
| Idle listening loop | `service/WakeChatService.kt` | `startListening`, `stopListening`, `waitForWake` |
| Drawing the exchange | `service/WakeChatSessionService.kt` | (see the deep-dive skill, not this doc) |
| Settings UI for the section | `ui/settings/SettingsScreen.kt` | sensitivity slider, "Record Reference", "Test Wake Word" |

## 1. Capture — `recordAudioWithVAD`

- 16 kHz, mono, `ENCODING_PCM_16BIT`, `MediaRecorder.AudioSource.VOICE_COMMUNICATION`. Not
  `MediaRecorder` — `AudioRecord` throughout.
- Chunks are 640 samples (0.04 s). The loop's absolute RMS floor `minEnergy = 800` answers exactly
  two questions: **did anything happen at all**, and **has the silence break been reached**. It must
  never decide where the utterance starts or ends.
- The cut is `voicedSpan(audioBuffers, buffer.size, VOICED_TRIM_FRACTION)`: window RMS keyed to the
  **clip's own loudest window** (`VOICED_TRIM_FRACTION = 0.25` of that peak), first and last window
  above the gate. Then margins: `leadSamples = sampleRate * 3 / 10` (0.3 s) and
  `trailSamples = sampleRate / 2` (0.5 s), which carry the plosives at the edges of the phrase.
- Ambient-only windows have no relative cut (the room noise *is* the peak), so an idle window keeps
  nearly its whole length and is rejected by the matcher at ~0.5 — the absolute `minEnergy` gate is
  what answers silence. That is expected behaviour, not a bug.

## 2. Enrollment — `SettingsViewModel`

- `recordWakeClip()` passes the recorder windows explicitly: `enrollmentClipSeconds = 10`,
  `enrollmentSilenceMs = 1000L`, `enrollmentNoSpeechMs = 5000L`. The defaults are not usable: a
  no-speech timeout of zero disables the give-up and the default cap is 60 s, which is what held the
  screen at "Recording…" for a full minute on a starved mic.
- The always-on wake loop holds an `AudioRecord` essentially all of the time and the platform runs
  **only one at a time**. Opening a second instance starves the first — the wake loop's own chunks
  fell to RMS 50 while the enrollment mic heard the room. So the loop is paused for the duration of
  the clip (`pauseWakeLoopMic`) and handed the mic back afterwards (`resumeWakeLoopMic`), leaving
  exactly one capture live.
- The clip goes through `extractWakeFeatures` — the identical feature path the live pass uses — and
  is stored by `saveWakeReference`. At most `MAX_REFERENCES = 8` takes; `MAX_REFERENCE_FRAMES = 500`
  (5 s at this stride) per take.
- **References are always recorded on device.** Never seed, overwrite, or push enrollment from
  `test_audio/`: those files are algorithm-test fixtures, not the user's voice.

## 3. The enrollment file

- `pathForReferences(context)` → `context.filesDir` + `wake_references.txt`, i.e.
  `/data/user/0/ai.flossyapp/files/wake_references.txt`. That directory is **invisible to
  adbd** — the app has to report its own enrollment through logs (see §7).
- One reference per line: `<something> <float> <float> …`, row-major `[cepstral1..4, delta1..4]` per
  frame. A line whose value count is not a multiple of `MFCC_FEATURES` is rejected on load, so the
  count the screen shows and the count the matcher can use stay the same number.
- Enrollment used to swallow write failures; that is exactly how a broken file went invisible — the
  screen kept showing the old count while every new take failed silently. Both sides name the
  resolved path in the log now.

## 4. Features — `extractWakeFeatures`

Order matters, and enrollment and detection both go through this function:

1. `pcmToFloats` — 16-bit LE → `[-1, 1]`, resampling to 16 kHz when the header says otherwise
   (the energy-saving mode drops the mic to 8 kHz). Products here are `Long`: a few seconds at
   48 kHz overflows Int32.
2. `containsSpeech` — reject before any normalisation. The features are normalised per dimension, so
   a clip of room noise gets scaled up to look exactly as loud as speech; the one test that survives
   that normalisation, and is gain-invariant, is voiced frames (`VOICED_FRACTION = 0.35` of the
   loudest frame) sitting next to quiet ones (`QUIET_FRACTION = 0.12`), with
   `MIN_SPEECH_FRAMES = 3` and `MIN_QUIET_FRAMES = 2`.
3. `prepareSignal` — a 3-tap moving average (`LOWPASS_WINDOW = 3`), a low-pass keeping roughly
   everything below ~2.7 kHz at 16 kHz, where the phrase's formants live and room noise does not.
4. `normalizeGain` — the loudest sample becomes `TARGET_PEAK = 0.9`. **This is why the capture cut
   must be relative, not absolute.**
5. MFCC — `FFT_SIZE = 1024` over `MFCC_FRAME = 640`-sample frames stepped by `MFCC_SHIFT = 160`
   (10 ms), `MFCC_CEPSTRAL = 4` cepstral bands plus 4 first differences → `MFCC_FEATURES = 8` per
   frame; `MIN_FRAMES = 5` minimum.
6. `normalizePerFeature` — each dimension to unit spread, then `gaussianSmooth`.

A cost is therefore "standard deviations apart" in a per-dimension-normalised space — which is what
makes it independent of volume, phrase length and microphone.

## 5. Matching — the comparison, not a score

`bestMatchCost(features, references)` is **not** whole-clip-against-whole-reference. Warping the
whole clip onto the whole reference is what made the trigger miss when the user says the phrase and
the request in one breath: the request has nowhere to go and its cost gets folded into the answer.

- The **longer** sequence is the one that gets sliced, so an enrolled take that outlasts the live
  clip is compared against the whole clip exactly the way a longer clip is compared against a whole
  reference. Slicing only the clip leaves one position for every longer reference, and the band then
  has to warp the whole length difference into the cost.
- `windowCost` is band-limited DTW: the path may stray `BAND_SIZE = 5` frames off the diagonal,
  widened to `max(BAND_SIZE, abs(length - m)) + 1` — the `+1` is load-bearing, a length difference of
  exactly `BAND_SIZE` otherwise leaves the bottom-right column one step out of reach and the warp
  reports no path at all instead of its cost. Without the band, a short reference can be stretched
  across an unrelated clip and still find a cheap path.
- `limit` prunes: costs only grow along a path, so a row whose cheapest cell already exceeds a known
  good cost cannot answer better. The threshold doubles as this limit in `confirmingWindows`.
- `frameDistance` is a **cosine** distance expressed as a Euclidean one (capped at 2): only the
  direction of a frame's cepstral signature carries its shape, which removes the residual loudness
  that survives clip-wide gain normalisation. A frame with no signal has no direction — it matches
  nothing, except another frame with no signal.
- `aggregateCost` takes the **median** across references (`REFERENCE_PERCENTILE = 0.5`), not the
  best: the best-of-references reading becomes more permissive as the user enrolls more takes, and
  with enough of them one of them flatters any clip.
- `wakeWordHeard` requires a **majority of the enrolled references** to confirm, and each reference
  confirms by having `min(MIN_CONFIRMING_WINDOWS, positions)` window positions at or below the
  threshold. Taking the best of every position is biased — an unrelated clip gets as many chances to
  land one cheap window as there are positions. The `min(…, positions)` is load-bearing too: the
  idle pass trims to the utterance, so a live clip is about as long as the references and there is a
  **single** position to agree over; demanding two of it made every tight clip miss, forever.

## 6. The threshold — `wakeCostThreshold`

```
threshold = (MATCH_COST * (0.925 + 0.175 * (1 - sensitivity)) + min(spread, 1) * SPREAD_ALLOWANCE)
            .coerceAtLeast(MIN_MATCH_THRESHOLD)
```

`MATCH_COST = 0.40`, `SPREAD_ALLOWANCE = 0.02`, `MIN_MATCH_THRESHOLD = 0.2`. The slider therefore
spans roughly **0.38 → 0.45** — a deliberately narrow band, because the boundary is already known.

The calibration, measured over the user's own recordings (not synthetic fixtures — sine fixtures
share too much structure for a threshold to separate):

- five genuine takes of the phrase sit 0.30–0.40 apart;
- the phrase inside a 24 s recording costs 0.35–0.37 against them;
- unrelated speech from another recording costs 0.48–0.53.

So recall needs ~0.36 and precision gives back at ~0.55, and the whole slider has to live inside
that gap. **Do not "fix" a miss by moving `MATCH_COST`, `SPREAD_ALLOWANCE` or
`MIN_MATCH_THRESHOLD`** — widening the allowance for a loose enrollment is what pushes the bar past
the precision cliff, and the cliff is only 0.15 above the recall floor. A spread-scaled multiple of
the threshold does not work on real speech at all: takes of different sentences sit ~0.6 apart while
the phrase inside a longer clip reaches 0.18.

## 7. Reading the logs — the diagnostic entry point

Tags: `WakeEnroll` (enrollment read/write), `WakeMatch` (the Settings "Test Wake Word" verdict),
`WakeChatService` (the idle loop), `VAD` (the recorder), `FLOSSyAssistant` (enrollment write failure).

```bash
adb -s <serial> logcat -d -s WakeChatService:V WakeEnroll:V WakeMatch:V
```

`-s` matches **exact** tags; `logcat -d Tag:V` *without* `-s` returns unfiltered system logs. The
main ring buffer is 256 KiB and rotates in ~26 s, so for a live trace capture in the background
instead of dumping after the fact. `logcat -G 4096` is refused on this device.

What the loop prints, once per listening session (the enrollment is fixed for a session and the
pairwise costs are the same DTW the wake pass runs, so repeating them per idle clip is waste):

```
Enrollment: 6 references, frames 133,133,133,137,137,115, median pairwise cost 0.31
```

On a miss, the same breakdown the Settings test prints:

```
Wake clip rejected: clip 141 frames, takes 133 133 133 137 137 115 frames,
costs 0.3,0.3,0.3,0.3,0.2,0.3, agreement 9/2 9/2 9/2 5/2 5/2 0/2, threshold 0.39
```

Healthy shape, measured on the tab: **115–137 frames per take, median pairwise cost ~0.31**, live
costs in the 0.3s against a ~0.39 bar. An enrollment whose references are near-identical to one
another (pairwise ~0.02 where genuine takes spread 0.25–0.45) is several captures of one audio
source, not several human takes.

Costs print to **two decimals** in both `WakeChatService` and `SettingsViewModel` — the whole
slider spans 0.377–0.448, so a one-decimal truncation prints both ends as "0.3" and destroys the
comparison the costs above it have to be read against.

## 8. The idle loop — `WakeChatService`

- `waitForWake` calls `recordAudioWithVAD(context, stopSignal = wakeAbortSignal,
  maxSeconds = WAKE_CLIP_SECONDS, noSpeechTimeoutMs = WAKE_IDLE_SECONDS * 1000L)` with
  `WAKE_IDLE_SECONDS = 4` and `WAKE_CLIP_SECONDS = 10`. The ceiling is load-bearing: the idle pass
  only gives up while **no speech** has been heard, so a room whose ambient noise clears the energy
  threshold never looks silent and the clip would otherwise run to the recorder's default and the
  matcher would score a minute of room.
- A raw idle window is ~10 s ≈ 997 MFCC frames.
- On a match: record the request → STT → LLM → TTS, drawn by `WakeChatSessionService`; the mic
  re-arms after each reply so one wake word opens a conversation, and the close clock counts only
  real silence (`wakeChatCloseSeconds`).
- `wakeWordHeard` itself is **log-free** — `android.util.Log` is not mocked in the unit tests, so
  logging inside it breaks `:app:testDebugUnitTest`. Logging belongs in the callers.

## 9. Traps

- **Enrollment recorded on an older recorder build is not comparable to live clips.** The cut is
  part of what is stored. After changing the capture path, the existing enrollment is stale: the
  tab's old-cut references measured 135–223 frames and a genuine phrase cost 0.41–0.59 against them
  at a 0.38 bar — every sensitivity missed, and no amount of re-tuning could fix it. Re-record on
  the new build; the same enrollment became 115–137 frames at ~0.31 and fired.
- `MAX_REFERENCE_FRAMES = 500` is 5 s at this stride, not 500 seconds.
- A matcher bug that cost `MAX_VALUE` when `abs(length - m) == BAND_SIZE` looked identical to "no
  match" and is the reason the band widening exists.
- Never trigger the wake decision from the STT transcript — the trigger is on-device
  (`WakeWordDetector`); STT may only transcribe the request *after* the phrase matched.
- No external wake-word runtime (Porcupine/Snowboy/rustpotter as a native library): this toolchain
  has **no C FFI**, so a Rust `.so` cannot be loaded. The matcher is a pure-Kotlin port.

## 10. The oracles

| Test | What it pins |
| --- | --- |
| `WakeWordUtteranceTrimTest` | The cut is gain-invariant, removes the silent room, keeps the phrase rather than its loudest syllable, leaves a clip the length a healthy enrollment holds, and invents nothing out of silence. |
| `WakeWordRealEnrollmentTest` | The matcher against the user's own voice: five hand-recorded takes as enrollment, a 24 s recording of the phrase five times as the idle clip, unrelated speech as the negative. |
| `WakeWordDeviceEnrollmentTest` | The matcher against enrollment **dumps read off the tab**, in the exact line format `loadWakeReferences` parses — the only place the test process and the device can disagree, since the matcher and threshold are the same code either way. |
| `WakeWordDetectorTest` | The synthetic fixtures: MFCC shape, band limits, aggregation, threshold monotonicity. |

The fixture recordings live under `test_audio/`, which is **git-ignored**, so the two real-voice
oracles only run on the machine that holds them. A missing fixture fails loudly rather than passing
vacuously. Those files are inputs to the algorithm tests and enrollment dumps for diagnosis — they
are never written back to the device.

Run the whole suite: `./gradlew test` (`--tests` is not a supported option here). Gradle truncates
assertion messages in the console; the full text is in
`app/build/reports/tests/testDebugUnitTest/ai.flossyapp.test.<ClassName>/<testName>.html`.
