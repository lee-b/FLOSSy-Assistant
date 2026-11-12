package ai.flossyapp.ui.common

import ai.flossyapp.data.remote.ApiClient
import ai.flossyapp.data.tools.ToolRegistry
import ai.flossyapp.domain.model.AppSettings
import ai.flossyapp.domain.model.VoiceCallPhase
import ai.flossyapp.ui.common.playWaitingCue
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
 * The engine owns the microphone for as long as a voice mode is active, so
 * there is never a second recorder fighting for it - the wake service's old
 * per-window [recordAudioWithVAD] recorder and the screen's capture used to
 * run side by side, and one of them read a silent device (the "Could not hear
 * the request" that appeared as soon as both paths were live).
 *
 * A mode says who gets the utterances:
 * - [Mode.APP]: the chat screen is in a call; every utterance becomes one
 *   exchange rendered through the screen's host.
 * - [Mode.WAKE]: the background service is listening for the phrase; every
 *   utterance is handed to it through [nextUtterance], so it can match the
 *   wake word on-device exactly as before - only on clips the shared capture
 *   already produced.
 *
 * The host interfaces below are the only surface each caller implements, which
 * keeps the engine free of Compose state and free of the session-window
 * plumbing: the screen draws bubbles, the service draws its card, and both
 * hear about the same exchange the same way.
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
     * Where a completed utterance goes right now: the wake service's
     * matcher, the chat screen's exchange pipeline, or nowhere. This is
     * mic *routing*, not call ownership - there is one call, and the
     * surfaces that draw it are views, not owners.
     */
    enum class Mode { IDLE, APP, WAKE }

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

    private val appContext: Context? = context?.applicationContext

    // One scope for every exchange coroutine the engine carries. Exchanges
    // are cancelled wholesale when the mode drops, so nothing can answer or
    // speak after the call ended.
    private var jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var mode: Mode = Mode.IDLE
        private set

    // The one call. True from the moment a conversation opens (wake phrase,
    // mic button, or the screen adopting a live exchange) until [hangUp]
    // ends it; the surfaces read this through ConversationStore to decide
    // what to draw, and the engine reads it to decide what still speaks.
    // Volatile: the notification thread hangs up while an exchange runs.
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

    // The persistent capture. One AudioRecord for the whole voice session,
    // whoever owns it.
    private var capture: StreamingCallCapture? = null

    // While the assistant speaks the capture must not hear its own reply as
    // an utterance; the gate is closed for the whole exchange and reopened
    // on its exit.
    private val captureGate = java.util.concurrent.atomic.AtomicBoolean(true)

    // One exchange at a time - a second utterance while one runs is either
    // self-echo or talk-over, and both are dropped. A mutex whose lock is
    // taken inside the exchange coroutine can never leak: a scope cancelled
    // before the body runs holds nothing, and the body's own finally always
    // releases what it took.
    private val exchangeMutex = kotlinx.coroutines.sync.Mutex()

    // Cutting the reply short when the call ends mid-speech.
    private val ttsAbortSignal = java.util.concurrent.atomic.AtomicBoolean(false)

    // Dropping a half-heard utterance when the call ends mid-capture.
    private val discardCurrentUtterance = java.util.concurrent.atomic.AtomicBoolean(false)

    // Utterances in WAKE mode travel to the service through this channel.
    private val utteranceChannel = Channel<ByteArray>(Channel.UNLIMITED)

    // Live 32ms chunks (float samples), for listeners that watch the raw
    // stream - the wake service's barge-in matcher follows the microphone
    // through a spoken reply this way, without opening a second recorder.
    @Volatile
    var chunkListener: ((FloatArray) -> Unit)? = null

    private var appHost: ExchangeHost? = null

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


    /**
     * Start (or re-enter) app mode: the chat screen owns the microphone and
     * every utterance becomes an exchange through [host].
     */
    fun startAppMode(host: ExchangeHost) {
        appHost = host
        mode = Mode.APP
        beginCall()
        ttsAbortSignal.set(false)
        discardCurrentUtterance.set(false)
        ensureCapture()
    }

    /**
     * Start (or re-enter) wake mode: the service listens for the phrase and
     * collects utterances through [nextUtterance].
     *
     * Refuses when the chat screen is in a voice call: a wake listener and a
     * call are different states, the microphone belongs to the call, and
     * stealing it here would both deafen the call and let the wake matcher
     * hear the exchange it must not hear. The caller waits and tries again.
     *
     * @return whether wake mode now owns the capture.
     */
    fun startWakeMode(): Boolean {
        if (mode == Mode.APP) return false
        mode = Mode.WAKE
        if (callActive) {
            // A live call (the screen released it, or the wake loop adopted
            // it) continues: this re-entry is the wake service re-arming as
            // the utterance router, not a new conversation.
            return true
        }
        // Per-exchange state must be cleared on this path too, not only in
        // startAppMode: stop() latches discardCurrentUtterance (and the tts
        // abort) on, and a wake session entered afterwards would otherwise
        // return every exchange's body before STT - the wake word is heard,
        // the chime plays, and no reply is ever spoken, with no error
        // anywhere. This flag belongs to a session, not to the screen.
        ttsAbortSignal.set(false)
        discardCurrentUtterance.set(false)
        ensureCapture()
        return true
    }

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
        pendingHandoff = handoffToWake && mode == Mode.APP
        handoffAt = System.currentTimeMillis()
        if (handoffToWake) {
            // The call itself survives: the engine keeps the capture and the
            // conversation; only the surface changes. The wake loop adopts
            // it on its next pass. The running exchange (whose host was the
            // vanished window) is cancelled like in stop(), but the capture
            // stays and the gate reopens so the wake loop can hear the user.
            appHost = null
            mode = Mode.WAKE
            discardCurrentUtterance.set(true)
            ttsAbortSignal.set(true)
            cancelExchangeScope()
            ttsAbortSignal.set(false)
            discardCurrentUtterance.set(false)
            captureGate.set(true)
        } else {
            hangUp("ChatScreen endVoiceCall")
        }
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

    /**
     * Deliberate hang-ups are announced, so every surface that draws the
     * call can drop its own view of it (the exchange itself is torn down
     * inside [hangUp]).
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
     * @return true when this call began a fresh conversation.
     */
    fun beginCall(): Boolean {
        val fresh = !callActive
        callActive = true
        if (fresh) {
            if (!testMode) {
                ConversationStore.clear()
                ConversationStore.setCallActive(true)
            }
            log("New call: fresh conversation (previous turns cleared)")
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
        if (!callActive && mode == Mode.IDLE) {
            log("hangUp ignored (no call): $reason")
            return
        }
        log("hangUp: $reason", level="w")
        callActive = false
        lastCallEndAt = System.currentTimeMillis()
        hangUpEvents.tryEmit(Unit)
        stop()
    }

    /**
     * Leave whatever mode is active: the capture closes, running exchanges
     * are cancelled and cut short, and the mic is released for whoever wants
     * it next.
     */
    fun stop() {
        mode = Mode.IDLE
        appHost = null
        discardCurrentUtterance.set(true)
        ttsAbortSignal.set(true)
        capture?.stop()
        capture = null
        chunkListener = null
        // Drain so a stale utterance can never reach the next session.
        utteranceChannel.tryReceive() // single-shot drain of one pending clip
        while (true) {
            val got = runCatching { utteranceChannel.tryReceive().getOrNull() }.getOrNull()
            if (got == null) break
        }
        // Cancel every exchange the old scope still carries and start fresh,
        // so the next session cannot inherit a cancelled-scope error.
        cancelExchangeScope()
        if (!testMode) ConversationStore.setCallActive(false)
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
     * user talking over the reply. [ttsAbort] lets the owner cut the reply
     * short (the wake service's interrupt signal, for one). Returns the job
     * carrying the exchange, so a caller that must not race ahead can join it.
     */
    fun runExchange(
        wavBytes: ByteArray,
        host: ExchangeHost,
        ttsAbort: java.util.concurrent.atomic.AtomicBoolean
    ): kotlinx.coroutines.Job? {
        // One exchange at a time is enforced by a mutex taken *inside* the
        // coroutine rather than a CAS before the launch: with the CAS, a
        // scope cancelled between compareAndSet and the body's first line
        // left the flag (and the closed capture gate) latched forever, and
        // every later utterance was silently dropped.
        val job = jobScope.launch {
            // Declared before the try so the finally can reach it: a Job
            // cannot complete while an active child runs, so an infinite
            // waiting-cue that is never cancelled holds the whole exchange
            // alive - and kept the ticks playing long after a failed tool
            // round had ended the real work.
            var waitingCue: kotlinx.coroutines.Job? = null
            if (!exchangeMutex.tryLock()) {
                log("Exchange already running, dropping the new utterance", level="d")
                return@launch
            }
            try {
                // The gate is closed here, inside the accepted exchange, so
                // a dropped utterance never closes the microphone behind it.
                captureGate.set(false)
                host.onPhase(VoiceCallPhase.TRANSCRIBING)

                // The call ended while this take was still being heard - the
                // half-captured words are the user's to drop, not to send on
                // to STT.
                if (discardCurrentUtterance.get() || !host.isActive()) {
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
                        }, ttsAbort) { hangUp("model asked to end the call") }
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
                        ai.flossyapp.ui.common.speakableText(finalText)
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
                            ttsAbortSignal.set(false)
                            ttsRunner(finalText, settings, ttsAbort) {
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
                            ttsAbortSignal.set(false)
                            ttsRunner("Sorry, I misheard you. Please repeat.", settings, ttsAbort, null)
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
                // Reopen only if the call is still on; a hung-up call has a
                // closed capture, and a closed gate cannot hear anything.
                // The engine's own call flag is the arbiter here, not the
                // surface's dialog state, so a call handed between surfaces
                // keeps its gate consistent.
                captureGate.set(callActive)
                exchangeMutex.unlock()
            }
        }
        return job
    }

    /**
     * Opens the persistent capture if it is not already live. Where each
     * completed utterance goes is read from the live [mode] at utterance
     * time, not captured at construction: the capture is shared, so a wake
     * session that opened it and a chat call that inherited it must both see
     * their own routing. Reading the mode once was the bug that deafened the
     * chat screen - the capture opened in WAKE mode kept sending every
     * utterance to the wake channel nobody drains after the screen took
     * over, so the mic button produced no transcript at all.
     */
    @Synchronized
    private fun ensureCapture() {
        if (testMode) return // unit tests drive the state machine only
        if (capture != null) return
        val cap = StreamingCallCapture(
            appContext!!,
            java.util.concurrent.atomic.AtomicBoolean(false),
            captureGate,
            vad
        ) { clip ->
            when (mode) {
                Mode.WAKE -> utteranceChannel.trySend(clip)
                Mode.APP -> {
                    val host = appHost ?: return@StreamingCallCapture
                    jobScope.launch { runExchange(clip, host, ttsAbortSignal) }
                }
                Mode.IDLE -> {}
            }
        }
        // Chunks flow to the barge-in matcher regardless of the gate, so a
        // phrase said over a spoken reply can still be heard.
        cap.chunkListener = { chunk -> chunkListener?.invoke(chunk) }
        capture = cap
        // The capture opens its own recorder and route; that blocking work
        // belongs off whatever thread called this.
        Thread {
            val started = cap.start()
            log("Capture started=$started mode=$mode", level="d")
        }.start()
    }
}
