package ai.flossyapp.ui.common

import ai.flossyapp.data.remote.ApiClient
import ai.flossyapp.data.remote.McpClient
import ai.flossyapp.domain.model.AppSettings
import ai.flossyapp.domain.model.VoiceCallPhase
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
class VoiceCallEngine private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: VoiceCallEngine? = null

        /** Initializes or returns the singleton, pinned to the application context. */
        fun instance(context: Context): VoiceCallEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VoiceCallEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /** Who the microphone currently belongs to. */
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

    private val appContext: Context = context

    // One scope for every exchange coroutine the engine carries. Exchanges
    // are cancelled wholesale when the mode drops, so nothing can answer or
    // speak after the call ended.
    private var jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var mode: Mode = Mode.IDLE
        private set

    // The bundled Silero network, loaded once per process - the same model
    // whether the request came from the screen or the wake word.
    private val vad by lazy { SileroVad(appContext) }

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

    /**
     * Start (or re-enter) app mode: the chat screen owns the microphone and
     * every utterance becomes an exchange through [host].
     */
    fun startAppMode(host: ExchangeHost) {
        appHost = host
        mode = Mode.APP
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
        val old = jobScope
        synchronized(this) {
            val fresh = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            jobScope = fresh
            old.cancel()
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
            if (!exchangeMutex.tryLock()) {
                android.util.Log.d("VoiceCallEngine", "Exchange already running, dropping the new utterance")
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
                    ApiClient.transcribeAudio(
                        baseUrl = settings.sttBaseUrl,
                        apiKey = if (settings.sttApiKey.isBlank()) null else settings.sttApiKey,
                        audioData = wavBytes,
                        model = settings.sttModelName,
                        timeoutSeconds = settings.httpTimeoutSeconds.toLong()
                    )
                }

                // whisper.cpp answers silence with markers like [BLANK_AUDIO];
                // they must not become a user message or reach the LLM.
                val nonSpeech = isNonSpeechTranscript(transcribedText)
                if (nonSpeech) {
                    android.util.Log.d("VoiceCallEngine", "Ignoring non-speech transcript: $transcribedText")
                }

                if (!nonSpeech && transcribedText.isNotBlank()) {
                    android.util.Log.d("VoiceCallEngine", "STT result: $transcribedText")
                    host.onUserMessage(transcribedText)
                    host.onPhase(VoiceCallPhase.THINKING)

                    val fullResponse = StringBuilder()
                    val reasoningContent = StringBuilder()

                    // Tools are offered only when the user opted in, because
                    // discovery sits on the critical path of the request: an
                    // install that never enabled MCP pays nothing. The same
                    // call serves both owners, so the wake service keeps its
                    // tool access and the screen keeps its plain path.
                    val toolDescriptors =
                        if (settings.mcpReady) McpClient.advertisedTools(settings.mcpServerUrls, settings.mcpLazyTools)
                        else emptyList()
                    withContext(Dispatchers.IO) {
                        ApiClient.chatCompletionWithTools(
                            baseUrl = settings.llmBaseUrl,
                            apiKey = if (settings.llmApiKey.isBlank()) null else settings.llmApiKey,
                            messages = host.history(),
                            model = settings.llmModelName,
                            stream = true,
                            maxTokens = settings.llmMaxTokens,
                            reasoningBudgetTokens = settings.llmReasoningBudgetTokens,
                            chatTemplateKwargs = settings.llmChatTemplateKwargs,
                            systemPrompt = settings.systemPrompt,
                            toolDescriptors = toolDescriptors,
                            // The answer to a tool call is fetched here so the loop
                            // in the API client can hand the result back to the
                            // model and ask again in the same exchange.
                            runTool = { name: String, argumentsJson: String ->
                                McpClient.runNamedTool(settings.mcpServerUrls, name, argumentsJson)
                            },
                            // A fresh phrase said while the answer is arriving cuts
                            // it short, the same way it cuts the spoken reply.
                            abortSignal = ttsAbort,
                            timeoutSeconds = settings.httpTimeoutSeconds.toLong()
                        ) { chunk: String ->
                            if (chunk.startsWith("\u0001")) {
                                reasoningContent.append(chunk.substring(1))
                                host.onStreamingReasoning(reasoningContent.toString())
                            } else {
                                fullResponse.append(chunk)
                                host.onStreamingContent(fullResponse.toString())
                            }
                        }.content
                    }

                    var finalText = fullResponse.toString().trim()
                    val reasoningText = reasoningContent.toString().trim()
                    // Hands-free: the user cannot read a screen, so an answer
                    // worth nothing on the display is still worth hearing
                    // something from. Speech filtering (forbidden characters,
                    // emoji) can also leave an otherwise fine reply empty -
                    // either way, the silence gets a voice.
                    val speakableText = if (settings.forbidNonLatinTts) finalText.filter { c ->
                        c.isWhitespace() || c.code < 0x250
                    }.trim() else finalText
                    if (finalText.isEmpty() || speakableText.isEmpty()) {
                        android.util.Log.d("VoiceCallEngine", "LLM gave nothing speakable (${finalText.length} chars raw), filling with a spoken fallback")
                        finalText = "Hmm, I'm not sure what to say to that."
                    }
                    android.util.Log.d("VoiceCallEngine", "LLM response: ${finalText.length} chars, reasoning: ${reasoningText.length} chars")

                    host.onAssistantMessage(finalText, if (reasoningText.isNotEmpty()) reasoningText else null)
                    host.onStreamingContent("")
                    host.onStreamingReasoning("")

                    // The reply is spoken synchronously inside the exchange:
                    // the gate must not reopen until the speaker has actually
                    // finished, so the capture can never hear its own output.
                    if (host.isActive()) {
                        host.onPhase(VoiceCallPhase.SPEAKING)
                        try {
                            ttsAbortSignal.set(false)
                            playTTSAudio(
                                finalText,
                                Pair(settings.ttsBaseUrl, if (settings.ttsApiKey.isBlank()) null else settings.ttsApiKey),
                                model = settings.ttsModelName,
                                voice = settings.ttsVoiceName,
                                responseFormat = settings.ttsResponseFormat,
                                enableStreaming = settings.enableTtsStreaming,
                                timeoutSeconds = settings.httpTimeoutSeconds.toLong(),
                                forbidNonLatin = settings.forbidNonLatinTts,
                                // The owner's abort (the wake interrupt, the
                                // screen's hang-up) cuts the reply short.
                                abortSignal = ttsAbort,
                                onTtsTooSlow = {
                                    host.onSystemNote("The TTS server or connection is too slow for real-time speech.")
                                }
                            )
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
                            playTTSAudio(
                                "Sorry, I misheard you. Please repeat.",
                                Pair(settings.ttsBaseUrl, if (settings.ttsApiKey.isBlank()) null else settings.ttsApiKey),
                                model = settings.ttsModelName,
                                voice = settings.ttsVoiceName,
                                responseFormat = settings.ttsResponseFormat,
                                enableStreaming = settings.enableTtsStreaming,
                                timeoutSeconds = settings.httpTimeoutSeconds.toLong(),
                                forbidNonLatin = settings.forbidNonLatinTts,
                                abortSignal = ttsAbort
                            )
                        } finally {
                            host.onPhase(VoiceCallPhase.IDLE)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The call ended and this job was cancelled on purpose.
                throw e
            } catch (e: Exception) {
                android.util.Log.e("VoiceCallEngine", "Voice interaction failed", e)
                host.onPhase(VoiceCallPhase.IDLE)
                host.onError(e.message ?: "unknown error")
            } finally {
                // Reopen only if the call is still on; a hung-up call has a
                // closed capture, and a closed gate cannot hear anything.
                captureGate.set(host.isActive())
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
        if (capture != null) return
        val cap = StreamingCallCapture(
            appContext,
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
            android.util.Log.d("VoiceCallEngine", "Capture started=$started mode=$mode")
        }.start()
    }
}
