package ai.jarvislibre.ui.common

import ai.jarvislibre.data.remote.ApiClient
import ai.jarvislibre.data.tools.ToolRegistry
import ai.jarvislibre.domain.model.AppSettings
import ai.jarvislibre.domain.model.VoiceCallPhase
import ai.jarvislibre.ui.common.playWaitingCue
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one voice-call engine the whole app shares.
 *
 * Both ways in - the mic button in the chat screen and the wake phrase from
 * the background service - lead to the same pipeline: one persistent
 * microphone capture ([StreamingCallCapture], scored by the bundled Silero
 * network), complete utterances cut on the network's own speech span, and each
 * utterance carried through STT, the LLM and synchronous TTS by [runExchange].
 *
 * Everything the pipeline needs to know about "may I hear?" is derived from
 * ONE state machine: [micState]. There are no latched flags to forget to
 * reset - the capture gate, the per-exchange aborts and the conversation
 * store are all projections of the state, computed at read time. A teardown
 * path that misses a reset ceremony is unrepresentable by construction,
 * because there are no reset ceremonies and nothing to miss.
 *
 * A mode says who gets the utterances:
 * - [MicState.APP]: the chat screen is in a call; every utterance becomes one
 *   exchange rendered through the screen's host.
 * - [MicState.WAKE]: the background service is listening for the phrase; every
 *   utterance is handed to it through [nextUtterance], so it can match the
 *   wake word on-device exactly as before - only on clips the shared capture
 *   already produced.
 * - [MicState.IDLE]: nothing owns the microphone; the capture is closed.
 *
 * Every state change goes through [transitionFromTo], which is the single
 * writer of the state and performs the transition's side effects (capture
 * open/close, channel drain, exchange-scope cancel, store publication) under
 * one lock. Callers pass their own sense of the current state, so a caller
 * acting on stale information is detected instead of silently mis-transitioning.
 */
class VoiceCallEngine private constructor(context: Context?) {

    companion object {
        @Volatile
        private var INSTANCE: VoiceCallEngine? = null

        // The tippy taps are not a metronome: a fast reply gets its grace
        // period of silence (most local exchanges answer well inside it),
        // and once the wait is real the ticks repeat slowly enough that
        // they read as status, not as an alarm.
        private const val WAITING_CUE_GRACE_MS = 1200L
        private const val WAITING_CUE_PERIOD_MS = 2500L

        // A handoff is a moment, not a state: if the wake service does not
        // adopt the released call within this window, the handoff dies and
        // the next wake phrase starts a fresh conversation as usual.
        private const val HANDOFF_TTL_MS = 5000L

        /** Initializes or returns the singleton, pinned to the application context. */
        fun instance(context: Context): VoiceCallEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VoiceCallEngine(context.applicationContext).also { INSTANCE = it }
            }
        }

        // Whether the app's own activity is in the foreground. The wake
        // service reads this to stay quiet while the app is on screen: the
        // wake word must not start an exchange, and the popup must not be
        // drawn over an app the user is looking at. MainActivity keeps it
        // current from its own lifecycle callbacks.
        @Volatile
        var isAppOnScreen: Boolean = false

        /**
         * Test-only construction: the engine's state machine is android-free,
         * so the unit suite builds instances without a context and never
         * opens a real capture (the engine's testMode seam keeps the
         * recorder and the VAD untouched). Production code always goes
         * through [instance].
         */
        internal fun createUnattached(): VoiceCallEngine = VoiceCallEngine(null)

        // Test seams: production never touches these; the unit suite swaps
        // them so the exchange state machine runs without a network.
        internal var testMode = false
        internal var sttRunner: suspend (ByteArray, AppSettings) -> String = { wav, s ->
            ApiClient.transcribeAudio(
                baseUrl = s.sttBaseUrl,
                apiKey = if (s.sttApiKey.isBlank()) null else s.sttApiKey,
                audioData = wav,
                model = s.sttModelName,
                timeoutSeconds = s.httpTimeoutSeconds.toLong()
            )
        }
        internal var llmRunner: suspend (
            AppSettings,
            List<Map<String, String>>,
            ((String) -> Unit)?,
            java.util.concurrent.atomic.AtomicBoolean?,
            (() -> Unit)?
        ) -> String = { s, messages, onChunk, abort, onEndCall ->
            ApiClient.chatCompletionWithTools(
                baseUrl = s.llmBaseUrl,
                apiKey = if (s.llmApiKey.isBlank()) null else s.llmApiKey,
                messages = messages,
                model = s.llmModelName,
                stream = true,
                maxTokens = s.llmMaxTokens,
                reasoningBudgetTokens = s.llmReasoningBudgetTokens,
                chatTemplateKwargs = s.llmChatTemplateKwargs,
                systemPrompt = s.systemPrompt,
                // Every tool - internal handlers, search engines and MCP
                // servers - flows through the one registry seam.
                toolDescriptors = ToolRegistry.advertisedTools(s, onEndCall),
                runTool = { name: String, argumentsJson: String ->
                    ToolRegistry.runNamedTool(s, name, argumentsJson, onEndCall)
                },
                abortSignal = abort,
                timeoutSeconds = s.httpTimeoutSeconds.toLong(),
                onChunk = onChunk
            // No `?: ""` here: a reply the server did not produce is an
            // error condition, not an empty success. A failure throws to the
            // exchange, which reports it through the host - the caller is
            // never handed a blank string dressed up as an answer.
            ).content
        }
        internal var ttsRunner: suspend (
            String,
            AppSettings,
            java.util.concurrent.atomic.AtomicBoolean?,
            (() -> Unit)?
        ) -> Unit =
            { text, s, abort, onTtsTooSlow ->
                playTTSAudio(
                    text,
                    Pair(s.ttsBaseUrl, if (s.ttsApiKey.isBlank()) null else s.ttsApiKey),
                    model = s.ttsModelName,
                    voice = s.ttsVoiceName,
                    responseFormat = s.ttsResponseFormat,
                    enableStreaming = s.enableTtsStreaming,
                    timeoutSeconds = s.httpTimeoutSeconds.toLong(),
                    forbidNonLatin = s.forbidNonLatinTts,
                    abortSignal = abort,
                    onTtsTooSlow = onTtsTooSlow
                )
            }
        internal var waitingCueRunner: suspend () -> Boolean = { playWaitingCue() }
    }

    /**
     * The one mic state: who owns the microphone right now. Every public
     * entry point of the engine is a thin adapter over [transitionFromTo],
     * so this enum - not a scatter of booleans - is the whole truth about
     * hearing.
     */
    enum class MicState {
        /** Nothing owns the microphone; the capture is closed. */
        IDLE,
        /** The wake service slumbers or listens for the phrase on the shared capture. */
        WAKE,
        /** The chat screen is in a voice call on the shared capture. */
        APP
    }

    /**
     * The outcome of a [transitionFromTo] attempt. [ok] is true only for the
     * outcomes that leave the engine in [MicState] `newState` (or already
     * there, for the no-op).
     */
    enum class TransitionOutcome(val ok: Boolean) {
        /** The transition ran; the state is now newState. */
        COMMITTED(true),
        /** Already in newState; the request was valid but had nothing to do. */
        NOOP(true),
        /** The caller's sense of the current state did not match reality. */
        STALE_EXPECTATION(false),
        /** The (currentState, newState) pair is not in the legal table. */
        ILLEGAL_TRANSITION(false),
        /** The transition was committed but the capture would not open; the
         * state was rolled back so the caller's next attempt retries. */
        CAPTURE_FAILED(false)
    }

    /**
     * What a voice-call host must provide: the engine runs the exchange and
     * reports every step through these hooks, so the screen, the wake service
     * or any other surface can render the same exchange its own way.
     */
    interface ExchangeHost {
        /** Whether the voice call this exchange belongs to is still open. */
        fun isActive(): Boolean

        /** The settings the exchange reads its endpoints and limits from. */
        fun settings(): AppSettings

        /** The conversation so far, in the role/content form the LLM accepts. */
        fun history(): List<Map<String, String>>

        /** The user's words, as heard by STT. */
        fun onUserMessage(text: String)

        /** The assistant's complete reply, thinking included. */
        fun onAssistantMessage(text: String, reasoning: String?)

        /** A phase transition, so the host can label what is happening. */
        fun onPhase(phase: VoiceCallPhase)

        /** Streaming partials while the LLM answers. */
        fun onStreamingContent(text: String)

        /** Streaming reasoning partials, separate from the content. */
        fun onStreamingReasoning(text: String)

        /** A display-only note (never sent to the LLM). */
        fun onSystemNote(text: String)

        /** The exchange failed; the host shows why its own way. */
        fun onError(message: String)
    }

    /**
     * One exchange's own abort/discard pair. These flags used to be
     * engine-wide latches reset by hand at every mode entry - a ceremony
     * each new entry point could forget. They now live and die with the
     * exchange that owns them: [teardownCurrentExchange] marks the live
     * one, and a new exchange simply carries fresh flags.
     */
    private class Exchange(externalAbort: java.util.concurrent.atomic.AtomicBoolean?) {
        // Cut the reply short when the call ends mid-speech. The external
        // abort (the wake service's interrupt signal) is folded in by the
        // watcher launched in runExchange, so the TTS runner polls ONE flag.
        val ttsAbort = java.util.concurrent.atomic.AtomicBoolean(false)

        // Drop a half-heard utterance when the call ends mid-capture.
        val discard = java.util.concurrent.atomic.AtomicBoolean(false)

        val externalAbort: java.util.concurrent.atomic.AtomicBoolean? = externalAbort
    }

    private val appContext: Context? = context?.applicationContext

    // One scope for every exchange coroutine the engine carries. Exchanges
    // are cancelled wholesale when the mic state drops, so nothing can
    // answer or speak after the call ended.
    private var jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Opens and closes the AudioRecord. Deliberately NOT the exchange scope:
    // cancelling exchanges must not orphan a recorder mid-open.
    private val captureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // The one state. Read freely; written only by transitionFromTo under its
    // own lock.
    @Volatile
    var micState: MicState = MicState.IDLE
        private set

    // The one call. True from the moment a conversation opens (wake phrase,
    // mic button, or the screen adopting a live exchange) until [hangUp]
    // ends it; the surfaces read this through ConversationStore to decide
    // what to draw, and the engine reads it to decide what still speaks.
    // Maintained only by the state machine's side effects - never written
    // from the outside.
    @Volatile
    var callActive: Boolean = false
        private set

    // Marks the moment a call was deliberately ended, so a capture the
    // engine stopped is never mistaken for a call that is still live when
    // the wake loop re-arms its windows.
    internal var lastCallEndAt: Long = 0
        private set

    // The bundled Silero network, loaded once per process - the same model
    // whether the request came from the screen or the wake word.
    private val vad by lazy { SileroVad(appContext!!) }

    // Settings, read fresh when a headset leaves so the disconnect policy is
    // whatever the user last chose. Null in unit tests (no context).
    private val settingsRepository by lazy {
        appContext?.let { ai.jarvislibre.data.settings.SettingsRepository(it) }
    }

    // Headset watch: the privacy half only. The capture itself rides the
    // framework's default input, which follows a headset joining or leaving
    // on its own - nothing here steers a device. A headset leaving ends the
    // call for privacy unless the user opted into continuing on the phone.
    private val headsetWatch: android.media.AudioDeviceCallback? = if (appContext != null) {
        object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<android.media.AudioDeviceInfo>) {
                // The framework re-routes the default input itself; nothing
                // to do but note it.
                log("Headset connected; the capture follows the default input", level="d")
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<android.media.AudioDeviceInfo>) {
                val headsetGone = removedDevices.any {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                }
                if (!headsetGone || !callActive) return
                val keep = runCatching {
                    settingsRepository?.settingsFlow?.value?.continueCallsOnPhoneWhenHeadsetDisconnects
                }.getOrNull() ?: false
                if (keep) {
                    log("Headset disconnected; keeping the call on the phone (user preference)", level="d")
                } else {
                    log("Headset disconnected; hanging up for privacy", level="w")
                    hangUp("headset disconnected")
                }
            }
        }
    } else null

    // The persistent capture. One AudioRecord for the whole voice session,
    // whoever owns it.
    private var capture: StreamingCallCapture? = null

    // The live exchange's own abort/discard pair, or null when no exchange
    // runs. The capture gate is DERIVED from this: the mic hears only when
    // a state is active and no exchange is in flight. There is no gate
    // boolean anywhere - so none can latch closed behind a teardown path.
    @Volatile
    private var currentExchange: Exchange? = null

    // One exchange at a time - a second utterance while one runs is either
    // self-echo or talk-over, and both are dropped. A mutex whose lock is
    // taken inside the exchange coroutine can never leak: a scope cancelled
    // before the body runs holds nothing, and the body's own finally always
    // releases what it took.
    private val exchangeMutex = kotlinx.coroutines.sync.Mutex()

    // Utterances in WAKE mode travel to the service through this channel.
    private val utteranceChannel = Channel<ByteArray>(Channel.UNLIMITED)

    // Live 32ms chunks (float samples), for listeners that watch the raw
    // stream - the wake service's barge-in matcher follows the microphone
    // through a spoken reply this way, without opening a second recorder.
    @Volatile
    var chunkListener: ((FloatArray) -> Unit)? = null

    private var appHost: ExchangeHost? = null

    /**
     * True when [host] is the surface the APP state currently routes the mic
     * to. The always-listening assistant service checks this before hanging
     * up on a stop: it may only end a call it owns, never the screen's or
     * the wake service's.
     */
    fun isAppHost(host: ExchangeHost): Boolean =
        micState == MicState.APP && appHost === host

    // A screen call that ended while it was still live - the user hung up,
    // or the window that hosted it was swiped away - hands the microphone
    // back to the wake service, and the wake service adopts the running
    // conversation instead of making the user say the wake phrase again.
    // The flag is consumed once by [WakeChatService] when its loop re-enters
    // wake mode, because a handoff is a one-shot event.
    @Volatile
    private var pendingHandoff = false

    // When the handoff was latched. A handoff the wake service never picks
    // up (it was stopped, say) must not lie in wait forever and open a
    // conversation nobody asked for when the service comes back - so it
    // expires.
    @Volatile
    private var handoffAt = 0L

    // android.util.Log is not mockable in a plain JVM unit suite, so the
    // engine's own logging goes through this seam: in testMode the calls
    // vanish, everywhere else they are the ordinary logcat lines.
    private fun log(message: String, level: String = "d", error: Throwable? = null) {
        if (testMode) return
        when (level) {
            "w" -> android.util.Log.w("VoiceCallEngine", message)
            "e" -> android.util.Log.e("VoiceCallEngine", message, error)
            else -> android.util.Log.d("VoiceCallEngine", message)
        }
    }

    // ==================== the one state transition ====================

    // The legal (currentState, newState) pairs. Every pair not listed here -
    // including any pair a future caller invents - is refused. The table is
    // exhaustive for the three-state machine: same-state is the no-op case
    // handled before the table check.
    private val legalTransitions: Set<Pair<MicState, MicState>> = setOf(
        MicState.IDLE to MicState.WAKE,
        MicState.IDLE to MicState.APP,
        MicState.WAKE to MicState.APP,   // the screen takes over a slumbering wake listener
        MicState.WAKE to MicState.IDLE,  // the wake service stops
        MicState.APP to MicState.WAKE,   // handoff: the screen released a live call
        MicState.APP to MicState.IDLE    // deliberate hang-up from any surface
    )

    /**
     * The single writer of [micState] and of every side effect a state
     * change implies.
     *
     * [currentState] is the CALLER'S sense of what the state is right now.
     * The engine checks it against reality under its own lock: a mismatch
     * is [TransitionOutcome.STALE_EXPECTATION] - the caller acted on stale
     * information and must re-read [micState] instead of having pushed the
     * engine somewhere it did not mean to go. The pair itself is checked
     * against the legal table, so an invented transition is refused rather
     * than improvised.
     *
     * Same-state requests are a valid no-op ([TransitionOutcome.NOOP]) with
     * one exception kept: re-entering a hearing state re-arms the capture,
     * because the wake loop's every pass relies on that idempotent re-arm
     * bringing a dead capture back.
     *
     * Side effects by target state, all under one lock:
     * - into WAKE/APP: drain stale utterances, (re)open the capture. A
     *   capture that cannot open rolls the state back, so the caller's next
     *   attempt is a genuine retry; the wake loop re-polls every 500 ms.
     * - into APP: [beginCall] - a new conversation when none was live.
     * - APP to WAKE: the handoff - the capture and the call survive, the
     *   vanished screen's exchange is cancelled, the wake loop adopts.
     * - into IDLE: full teardown - capture closed, exchanges cancelled,
     *   stale utterances drained, call flag dropped (and published to the
     *   shared store, which the engine alone writes).
     */
    @Synchronized
    fun transitionFromTo(currentState: MicState, newState: MicState): TransitionOutcome {
        val actual = micState
        if (currentState != actual) {
            log("Transition refused (stale): caller said $currentState, reality is $actual", level="w")
            return TransitionOutcome.STALE_EXPECTATION
        }
        if (newState == actual) {
            // Idempotent re-entry: hearing states re-arm the capture (a dead
            // one is reopened by the loop's next pass), IDLE has nothing to do.
            if (newState == MicState.WAKE || newState == MicState.APP) {
                if (!ensureCapture()) return TransitionOutcome.CAPTURE_FAILED
            }
            return TransitionOutcome.NOOP
        }
        if ((currentState to newState) !in legalTransitions) {
            log("Transition refused (illegal): $currentState -> $newState", level="w")
            return TransitionOutcome.ILLEGAL_TRANSITION
        }

        val from = actual
        when (newState) {
            MicState.WAKE, MicState.APP -> {
                // A session entered fresh must never inherit stale clips or
                // a latched exchange: drain the channel and make sure the
                // capture is live. (Per-exchange flags cannot leak across -
                // they are born with the exchange.)
                drainUtteranceChannel()
                if (!ensureCapture()) {
                    // Roll back so the caller's next attempt is a real retry
                    // instead of a state that claims to hear through a dead mic.
                    micState = from
                    if (newState == MicState.APP) {
                        callActive = false
                        if (!testMode) ConversationStore.setCallActive(false)
                    }
                    return TransitionOutcome.CAPTURE_FAILED
                }
                if (newState == MicState.APP) beginCall()
            }
            MicState.IDLE -> {
                teardownFor(from)
            }
        }
        micState = newState
        log("Mic state: $from -> $newState")
        return TransitionOutcome.COMMITTED
    }

    /**
     * The live mic state, read at decision time: pass it back as the
     * [MicState] argument of [transitionFromTo] (via [startAppMode],
     * [startWakeMode] or a direct call) so a caller that held the belief
     * across a wait is told it went stale instead of mis-transitioning.
     */

    /**
     * Teardown belonging to leaving a hearing state for IDLE: close the
     * capture, cancel every exchange the scope still carries (and start a
     * fresh scope, so the next session cannot inherit a cancelled-scope
     * error), drain a stale utterance, drop the host, and drop the call
     * flag - published to the shared store here and nowhere else.
     */
    private fun teardownFor(from: MicState) {
        if (from == MicState.APP) {
            // A handoff is a moment, not a state: leaving APP for IDLE
            // kills any handoff the screen latched on its way out.
            pendingHandoff = false
        }
        appHost = null
        teardownCurrentExchange()
        capture?.stop()
        capture = null
        chunkListener = null
        if (!testMode && headsetWatch != null) {
            runCatching {
                (appContext!!.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
                    .unregisterAudioDeviceCallback(headsetWatch)
            }
        }
        drainUtteranceChannel()
        cancelExchangeScope()
        callActive = false
        lastCallEndAt = System.currentTimeMillis()
        if (!testMode) ConversationStore.setCallActive(false)
    }

    /** Mark the live exchange (if any) as discarded and abortable. */
    private fun teardownCurrentExchange() {
        currentExchange?.let { ex ->
            ex.discard.set(true)
            ex.ttsAbort.set(true)
        }
        currentExchange = null
    }

    /** Cancel every exchange the scope still carries and start fresh, so
     * the next session cannot inherit a cancelled-scope error. */
    private fun cancelExchangeScope() {
        val old = jobScope
        synchronized(this) {
            val fresh = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            jobScope = fresh
            old.cancel()
        }
    }

    /** Empty the utterance channel, so a stale clip can never reach the
     * next session (as a wake phrase scored minutes late, or worse, as a
     * request delivered from a conversation that already ended). */
    private fun drainUtteranceChannel() {
        while (true) {
            val got = runCatching { utteranceChannel.tryReceive().getOrNull() }.getOrNull() ?: break
        }
    }

    /**
     * Deliberate hang-ups are announced, so every surface that draws the
     * call can drop its own view of it (the exchange itself is torn down
     * inside the transition to IDLE).
     */
    val hangUpEvents = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Mark the start of a call. When no call was live, this is a NEW
     * conversation: the old turns are cleared, because a fresh wake phrase
     * or a fresh mic-button call must not inherit them (no history carryover
     * until the sidebar drawer exists). When a call is already live - the
     * wake service adopting a released screen call, or the screen joining
     * the popup's conversation - the same conversation continues.
     *
     * Called by the state machine on entering APP; also called by the wake
     * service on hearing the phrase, which marks the wake conversation live.
     *
     * @return true when this call began a fresh conversation.
     */
    fun beginCall(): Boolean {
        // Whether this call began a fresh conversation is the conversation's
        // own lifetime, not the call's: a call that resumes while a surface
        // still shows the transcript continues the same conversation, and
        // only a conversation that has truly ended (the model's end_call, or
        // every surface closed with no call live) makes the next call a
        // fresh one. See [ConversationStore] for the lifetime rules.
        val fresh = if (testMode) {
            !callActive
        } else {
            !callActive && ConversationStore.conversationEnded
        }
        callActive = true
        if (fresh) {
            if (!testMode) {
                ConversationStore.clear()
                ConversationStore.markConversationOpen()
                ConversationStore.setCallActive(true)
            }
            log("New call: fresh conversation (previous turns cleared)")
        } else if (!testMode) {
            // The same conversation continues; make sure the ended mark a
            // previous lifecycle left behind is not carried into it.
            ConversationStore.markConversationOpen()
            ConversationStore.setCallActive(true)
        }
        return fresh
    }

    /**
     * The exchange finished on its own (the close clock declared silence):
     * the call is over, but the wake capture stays live for slumbering - only
     * the call flag drops, so the next wake phrase begins a fresh
     * conversation. Deliberate hang-ups go through [hangUp] instead.
     */
    fun endCallKeepCapture() {
        callActive = false
        lastCallEndAt = System.currentTimeMillis()
        if (!testMode) ConversationStore.setCallActive(false)
        log("Call finished; capture stays for slumbering")
    }

    /**
     * The one deliberate hang-up, from any surface: the notification's End
     * call, the card, the chat screen, the service. The running exchange is
     * cancelled and cut short (TTS stops immediately), the capture closes,
     * and the call is marked ended - the wake service goes back to
     * slumbering. The conversation itself is NOT cleared here: while the
     * fullscreen chat view is still open the transcript stays, and text
     * interaction (with the option to resume as a voice call) continues.
     * Clearing happens when a NEW conversation starts.
     */
    fun hangUp(reason: String) {
        if (!callActive && micState == MicState.IDLE) {
            log("hangUp ignored (no call): $reason")
            return
        }
        log("hangUp: $reason", level="w")
        // The model ending the call itself is a true end of the conversation
        // (see ConversationStore), not just the end of this exchange: the
        // next wake phrase or mic button starts a new one, even with a
        // surface still on screen showing the transcript.
        if (reason.contains("model asked to end")) {
            ConversationStore.markConversationEndedByModel()
        }
        callActive = false
        lastCallEndAt = System.currentTimeMillis()
        hangUpEvents.tryEmit(Unit)
        // The transition to IDLE tears the exchange and the capture down
        // under the state lock.
        transitionFromTo(micState, MicState.IDLE)
    }

    /**
     * Leave whatever hearing state is active: the capture closes, running
     * exchanges are cancelled and cut short, and the mic is released for
     * whoever wants it next.
     */
    fun stop() {
        transitionFromTo(micState, MicState.IDLE)
    }

    /**
     * Start (or re-enter) app mode: the chat screen owns the microphone and
     * every utterance becomes an exchange through [host].
     *
     * [expectedState] is the caller's sense of the mic state at the moment
     * it decided to act (read it with [micState] or track it across turns);
     * a stale sense is refused with [TransitionOutcome.STALE_EXPECTATION]
     * rather than mis-transitioning. Passing null means "use the live
     * state", which is right when the caller has no better information.
     */
    fun startAppMode(host: ExchangeHost, expectedState: MicState? = null) {
        val expected = expectedState ?: micState
        appHost = host
        val outcome = when {
            // Re-entering a call the screen already owns is a no-op re-arm.
            micState == MicState.APP && (expected == MicState.APP || expected == null) ->
                transitionFromTo(MicState.APP, MicState.APP)
            micState == MicState.APP -> TransitionOutcome.STALE_EXPECTATION
            else -> transitionFromTo(expected, MicState.APP)
        }
        if (!outcome.ok) {
            appHost = null
            log("startAppMode failed: $outcome")
        }
    }

    /**
     * Start (or re-enter) wake mode: the service listens for the phrase and
     * collects utterances through [nextUtterance].
     *
     * [expectedState] is the wake loop's own belief of the mic state, so a
     * loop acting on stale information is detected (and its next 500 ms
     * retry fixes itself) instead of silently stealing a state.
     *
     * Refuses when the chat screen is in a voice call: a wake listener and a
     * call are different states, the microphone belongs to the call, and
     * stealing it here would both deafen the call and let the wake matcher
     * hear the exchange it must not hear. The caller waits and tries again.
     *
     * @return whether wake mode now owns the capture.
     */
    fun startWakeMode(expectedState: MicState? = null): Boolean {
        if (micState == MicState.APP) return false
        val expected = expectedState ?: micState
        return transitionFromTo(expected, MicState.WAKE).ok
    }

    /**
     * Whether APP mode just released a live call. The wake service reads and
     * clears this when it regains the capture, so the same conversation can
     * continue in the popup without a fresh wake phrase.
     */
    fun consumeHandoff(): Boolean {
        val handoff = pendingHandoff && System.currentTimeMillis() - handoffAt < HANDOFF_TTL_MS
        pendingHandoff = false
        return handoff
    }

    /** Whether a handoff is pending, without consuming it. */
    fun hasHandoff(): Boolean =
        pendingHandoff && System.currentTimeMillis() - handoffAt < HANDOFF_TTL_MS

    /**
     * Leave APP mode: [handoffToWake] says whether the call was released
     * because its window vanished (a handoff the wake service adopts - the
     * conversation continues in the popup) or because the user hung up
     * deliberately (no handoff - the call is over and the wake service goes
     * back to listening for the wake phrase).
     */
    fun stopAppMode(handoffToWake: Boolean) {
        if (micState != MicState.APP) {
            // The screen's window closed while the call was already
            // elsewhere (the wake service owns it, or it was hung up):
            // nothing of APP mode is left to release.
            appHost = null
            return
        }
        if (handoffToWake) {
            // The call itself survives: the engine keeps the capture and the
            // conversation; only the surface changes. The running exchange
            // (whose host was the vanished window) is cancelled like in
            // stop(), but the capture stays and the gate - derived from the
            // state and the live exchange - reopens on its own.
            pendingHandoff = true
            handoffAt = System.currentTimeMillis()
            transitionFromTo(MicState.APP, MicState.WAKE)
        } else {
            hangUp("ChatScreen endVoiceCall")
        }
    }

    /**
     * The next complete utterance from the shared capture, or null when
     * nothing speech-like arrived within [timeoutMs]. The wake service waits
     * in here for the phrase clip, then again for the request clip - the same
     * two windows its own recorder used to serve, now with Silero deciding
     * where speech starts and ends instead of an energy gate.
     */
    suspend fun nextUtterance(timeoutMs: Long): ByteArray? {
        return withTimeoutOrNull(timeoutMs) { utteranceChannel.receive() }
    }

    /**
     * Carry one complete utterance through STT -> LLM -> TTS as a voice job,
     * reporting every step to [host]. Serialized: a second utterance while
     * one exchange runs is dropped, because it is either self-echo or the
     * user talking over the reply. [externalAbort] lets the owner cut the
     * reply short (the wake service's interrupt signal, for one). Returns the
     * job carrying the exchange, so a caller that must not race ahead can
     * join it.
     */
    fun runExchange(
        wavBytes: ByteArray,
        host: ExchangeHost,
        externalAbort: java.util.concurrent.atomic.AtomicBoolean?
    ): kotlinx.coroutines.Job? {
        // One exchange at a time is enforced by a mutex taken *inside* the
        // coroutine rather than a CAS before the launch: with the CAS, a
        // scope cancelled between compareAndSet and the body's first line
        // left the flag latched forever, and every later utterance was
        // silently dropped.
        val exchange = Exchange(externalAbort)
        val job = jobScope.launch {
            // Declared before the try so the finally can reach it: a Job
            // cannot complete while an active child runs, so an infinite
            // waiting-cue that is never cancelled holds the whole exchange
            // alive - and kept the ticks playing long after a failed tool
            // round had ended the real work.
            var waitingCue: kotlinx.coroutines.Job? = null
            var abortWatcher: kotlinx.coroutines.Job? = null
            if (!exchangeMutex.tryLock()) {
                log("Exchange already running, dropping the new utterance", level="d")
                return@launch
            }
            // The exchange owns the gate by existing: the capture reads
            // `currentExchange == null` to decide what may become an
            // utterance, so the gate closes here and reopens in the finally
            // with no latch to forget.
            currentExchange = exchange
            try {
                // Fold the owner's external abort (the wake interrupt) into
                // the exchange's own flag, so the TTS runner polls one
                // AtomicBoolean regardless of who asked to stop.
                val ext = exchange.externalAbort
                if (ext != null) {
                    // A child of the exchange job: cancelled with it, so the
                    // loop needs no liveness check of its own.
                    abortWatcher = launch {
                        while (!ext.get()) delay(50)
                        if (ext.get()) exchange.ttsAbort.set(true)
                    }
                }

                host.onPhase(VoiceCallPhase.TRANSCRIBING)

                // The call ended while this take was still being heard - the
                // half-captured words are the user's to drop, not to send on
                // to STT.
                if (exchange.discard.get() || !host.isActive()) {
                    return@launch
                }

                val settings = host.settings()
                val transcribedText = withContext(Dispatchers.IO) {
                    sttRunner(wavBytes, settings)
                }

                // whisper.cpp answers silence with markers like [BLANK_AUDIO];
                // they must not become a user message or reach the LLM.
                val nonSpeech = isNonSpeechTranscript(transcribedText)
                if (nonSpeech) {
                    log("Ignoring non-speech transcript: $transcribedText", level="d")
                }

                if (!nonSpeech && transcribedText.isNotBlank()) {
                    log("STT result: $transcribedText", level="d")
                    host.onUserMessage(transcribedText)
                    host.onPhase(VoiceCallPhase.THINKING)

                    // The tippy taps: the audio feedback that tells the user the
                    // assistant took the request and is working on it, before
                    // the reply can reach their ears. The loop is a child of
                    // this exchange, so it dies with the exchange - cancelled
                    // the moment speech starts (or the exchange fails), and
                    // silent for fast replies inside the grace window.
                    waitingCue = launch {
                        delay(WAITING_CUE_GRACE_MS)
                        while (true) {
                            waitingCueRunner()
                            delay(WAITING_CUE_PERIOD_MS)
                        }
                    }

                    val fullResponse = StringBuilder()
                    val reasoningContent = StringBuilder()

                    // Tools are offered only when at least one instance is
                    // enabled and configured, because discovery sits on the
                    // critical path of the request: an install that never
                    // enabled a tool pays nothing. The same call serves both
                    // owners, so the wake service keeps its tool access and
                    // the screen keeps its plain path. The model can end the
                    // call itself ("end_call"), and it goes through the same
                    // deliberate hang-up every surface uses.
                    withContext(Dispatchers.IO) {
                        llmRunner(settings, host.history(), { chunk: String ->
                            if (chunk.startsWith("\u0001")) {
                                reasoningContent.append(chunk.substring(1))
                                host.onStreamingReasoning(reasoningContent.toString())
                            } else {
                                fullResponse.append(chunk)
                                host.onStreamingContent(fullResponse.toString())
                            }
                        }, exchange.ttsAbort) { hangUp("model asked to end the call") }
                    }

                    var finalText = fullResponse.toString().trim()
                    val reasoningText = reasoningContent.toString().trim()
                    // Hands-free: the user cannot read a screen, so an answer
                    // worth nothing on the display is still worth hearing
                    // something from. Speech filtering (forbidden characters,
                    // emoji) can also leave an otherwise fine reply empty -
                    // either way, the silence gets a voice.
                    // The same filter the synthesis itself applies, so the
                    // "nothing speakable" check agrees with what the TTS will
                    // actually be handed - an answer that survives the speech
                    // whitelist is spoken, one that does not is replaced.
                    val speakableText = if (settings.forbidNonLatinTts) {
                        ai.jarvislibre.ui.common.speakableText(finalText)
                    } else finalText
                    if (finalText.isEmpty() || speakableText.isEmpty()) {
                        log("LLM gave nothing speakable (${finalText.length} chars raw), filling with a spoken fallback")
                        finalText = "Hmm, I'm not sure what to say to that."
                    }
                    log("LLM response: ${finalText.length} chars, reasoning: ${reasoningText.length} chars", level="d")

                    host.onAssistantMessage(finalText, if (reasoningText.isNotEmpty()) reasoningText else null)
                    host.onStreamingContent("")
                    host.onStreamingReasoning("")

                    // The reply is spoken synchronously inside the exchange:
                    // the gate must not reopen until the speaker has actually
                    // finished, so the capture can never hear its own output.
                    if (host.isActive()) {
                        // Speech has arrived, so the thinking ticks have done
                        // their job - they stop before the first syllable,
                        // never over the reply.
                        waitingCue?.cancel()
                        host.onPhase(VoiceCallPhase.SPEAKING)
                        try {
                            exchange.ttsAbort.set(false)
                            ttsRunner(finalText, settings, exchange.ttsAbort) {
                                // The callback runs on the audio thread, so
                                // the tick is carried on the engine's scope.
                                if (host.isActive()) jobScope.launch { waitingCueRunner() }
                            }
                        } finally {
                            host.onPhase(VoiceCallPhase.IDLE)
                        }
                    }
                } else {
                    // Nothing intelligible came back: hands-free means spoken,
                    // not printed - the screen may be off or away, so a toast
                    // would never be seen. Spoken inside the exchange, like
                    // the reply: the gate stays shut until the prompt has
                    // finished.
                    if (host.isActive()) {
                        host.onPhase(VoiceCallPhase.SPEAKING)
                        try {
                            exchange.ttsAbort.set(false)
                            ttsRunner("Sorry, I misheard you. Please repeat.", settings, exchange.ttsAbort, null)
                        } finally {
                            host.onPhase(VoiceCallPhase.IDLE)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The call ended and this job was cancelled on purpose.
                throw e
            } catch (e: Exception) {
                log("Voice interaction failed", level="e", error=e)
                host.onPhase(VoiceCallPhase.IDLE)
                host.onError(e.message ?: "unknown error")
            } finally {
                // The cue dies with the exchange on every path, not only the
                // one that reaches speech: a swallowed failure must never
                // leave the ticks playing after the work is gone.
                waitingCue?.cancel()
                abortWatcher?.cancel()
                // The gate reopens here by construction: the exchange stops
                // existing, so the capture's derived read turns true again -
                // a hung-up call has a closed capture anyway, and a call that
                // continues hears its next turn immediately.
                if (currentExchange === exchange) currentExchange = null
                exchangeMutex.unlock()
            }
        }
        return job
    }

    /**
     * Opens the persistent capture if it is not already live. Where each
     * completed utterance goes is read from the live [micState] at utterance
     * time, not captured at construction: the capture is shared, so a wake
     * session that opened it and a chat call that inherited it must both see
     * their own routing. Reading the state once was the bug that deafened the
     * chat screen - the capture opened in WAKE mode kept sending every
     * utterance to the wake channel nobody drains after the screen took
     * over, so the mic button produced no transcript at all.
     *
     * Returns false when the recorder would not open: the state machine
     * rolls the just-committed state back, so a transient failure (the mic
     * busy, initialization refused) becomes the caller's next retry rather
     * than a capture slot cached on a dead recorder. A failed start is
     * logged loudly - it used to be swallowed, and the engine went on
     * "listening" through a recorder that never opened.
     */
    @Synchronized
    private fun ensureCapture(): Boolean {
        if (testMode) return true // unit tests drive the state machine only
        capture?.let { return true }
        val gateOpen: () -> Boolean = {
            // Derived, never latched: the mic hears while a hearing state is
            // active and no exchange is in flight. A hung-up call is IDLE -
            // closed. A call between turns is APP/WAKE with no exchange -
            // open. Mid-exchange - closed, so the speakers are never heard.
            micState != MicState.IDLE && currentExchange == null
        }
        val cap = StreamingCallCapture(
            appContext!!,
            java.util.concurrent.atomic.AtomicBoolean(false),
            gateOpen,
            vad
        ) { clip ->
            when (micState) {
                MicState.WAKE -> utteranceChannel.trySend(clip)
                MicState.APP -> {
                    val host = appHost ?: return@StreamingCallCapture
                    jobScope.launch { runExchange(clip, host, null) }
                }
                MicState.IDLE -> {}
            }
        }
        // Chunks flow to the barge-in matcher regardless of the gate, so a
        // phrase said over a spoken reply can still be heard.
        cap.chunkListener = { chunk -> chunkListener?.invoke(chunk) }
        capture = cap
        // Opening a recorder is blocking audio-framework work: it belongs
        // off whatever thread called this. The result decides whether the
        // capture slot stays or is handed back for a retry.
        captureScope.launch {
            val started = cap.start()
            if (started) {
                log("Capture started state=$micState", level="d")
            } else {
                synchronized(this@VoiceCallEngine) {
                    if (capture === cap) {
                        capture = null
                        log("Capture failed to open; capture slot released for retry", level="w")
                        if (micState != MicState.IDLE) {
                            // Roll the state back: the engine must not claim
                            // to hear through a recorder that never opened.
                            val from = micState
                            micState = MicState.IDLE
                            if (from == MicState.APP) {
                                callActive = false
                                if (!testMode) ConversationStore.setCallActive(false)
                            }
                        }
                    }
                }
            }
        }
        return true
    }
}
