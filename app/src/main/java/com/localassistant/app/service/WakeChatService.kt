package com.localassistant.app.service

import android.Manifest
import android.app.*
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.service.voice.VoiceInteractionService
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.localassistant.app.LocalAssistantApplication
import com.localassistant.app.data.remote.ApiClient
import com.localassistant.app.data.remote.McpClient
import com.localassistant.app.data.settings.SettingsRepository
import com.localassistant.app.domain.model.ChatMessage
import com.localassistant.app.ui.common.bestMatchCost
import com.localassistant.app.ui.common.extractWakeFeatures
import com.localassistant.app.ui.common.isNonSpeechTranscript
import com.localassistant.app.ui.common.loadWakeReferences
import com.localassistant.app.ui.common.playWakeCue
import com.localassistant.app.ui.common.playTTSAudio
import com.localassistant.app.ui.common.recordAudioWithVAD
import com.localassistant.app.ui.common.referenceFrameCount
import com.localassistant.app.ui.common.wakeAgreement
import com.localassistant.app.ui.common.wakeCostThreshold
import com.localassistant.app.ui.common.wakeWordHeard
import com.localassistant.app.ui.common.voiceInteractionBound
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/**
 * The hands-free wake-word chat, as a foreground service.
 *
 * The whole pipeline lives here - record, decide "was the wake phrase spoken?"
 * on-device (MFCC + DTW against the enrolled references, see WakeWordDetector),
 * transcribe the request that follows it, ask the LLM, and speak the reply - so
 * an exchange can complete while the app is minimized. The persistent
 * notification carries the reply and its launch intent reopens the app, which is
 * how the other voice assistants behave when you talk to them from the home
 * screen.
 *
 * The state the overlay draws lives in [Constants] because a service instance is
 * created outside the app's view-model tree; the ViewModel only forwards it.
 *
 * The class implements [VoiceInteractionService], the system's own hook for hands-free
 * conversations. When the platform hosts one (Live Voice), it detects the hotword,
 * records and transcribes the request itself and calls the callbacks below, so the
 * exchange is drawn over the system UI and whatever app the device is showing.
 * Where the platform has no such service, the callbacks are never called and the
 * app's own loop below is the one that runs.
 */
class WakeChatService : VoiceInteractionService() {

    companion object Constants {
        const val NOTIFICATION_ID = 3001
        const val CHANNEL_ID = "wake_chat_channel"

        // Action constants for service commands
        const val ACTION_START = "com.localassistant.START_WAKE_CHAT"
        const val ACTION_STOP = "com.localassistant.STOP_WAKE_CHAT"
        // The action the system itself uses to start a voice-interaction service -
        // the same one the manifest declares, so a platform that hosts hands-free
        // conversations can open this service without the app being involved.
        const val ACTION_VOICE_INTERACTION = "android.service.voice.VoiceInteractionService"
        // Closing an open chat from outside the app (the system, or a stale intent
        // left in the queue). Nothing in the app draws the card any more, so this
        // action is the only outside way to end an exchange; the idle timeout ends
        // it through the service's endExchange().
        const val ACTION_CLOSE_CHAT = "com.localassistant.CLOSE_WAKE_CHAT"
        // Runs one exchange without waiting for the wake phrase, so the pipeline
        // can be exercised from Settings.
        const val ACTION_TRIGGER_EXCHANGE = "com.localassistant.TRIGGER_WAKE_CHAT"

        // Phases an exchange is in
        // The wake chat's exchange phases are the same typed phases the chat
        // screen and the assistant service use, so all three pipelines speak
        // one language. DETECTING maps to LISTENING (the mic is up, waiting
        // for speech) and LISTENING maps to HEARD (the phrase matched, the
        // request is being waited for); there is no ERROR phase - failures
        // ride the status message, and the phase keeps doing its job.
        val PHASE_IDLE = com.localassistant.app.domain.model.VoiceCallPhase.IDLE
        val PHASE_DETECTING = com.localassistant.app.domain.model.VoiceCallPhase.LISTENING
        val PHASE_LISTENING = com.localassistant.app.domain.model.VoiceCallPhase.HEARD
        val PHASE_THINKING = com.localassistant.app.domain.model.VoiceCallPhase.THINKING
        val PHASE_SPEAKING = com.localassistant.app.domain.model.VoiceCallPhase.SPEAKING

        // How many of the newest wake-chat turns are sent to the LLM as context.
        // Deliberately small: a follow-up turn re-sends the whole history, so the
        // prompt is paid again on every turn of a conversation, and the local
        // model is small enough that a long prompt is mostly latency.
        private const val MAX_LLM_CONTEXT = 8
        // How long the "nothing is enrolled yet" hint stays on the overlay.
        private const val REFERENCE_HINT_MS = 3000L
        // How long the "could not hear the request" hint stays readable before
        // the card closes. Long enough to read, short enough that a silent room
        // does not look like the assistant is still waiting for a request.
        private const val CLOSED_HINT_MS = 1200L
        // How long the microphone stays down between a spoken reply and the next
        // turn. The speakers sit beside the microphone, so the tail of the reply
        // that was just voiced would otherwise be recorded as the next question.
        private const val MIC_REARM_MS = 350L
        // How often the idle-close watchdog re-reads the activity stamp. The
        // length of the wait itself is the user's setting, never a constant.
        private const val TOUCH_POLL_MS = 200L
        // How long the microphone may hear nothing before the wake-detection
        // pass gives up on a clip. The recorder's own default is unbounded, which
        // makes it hold a minute of quiet room and run the wake matcher over all
        // of it - work that buys nothing, because silence cannot match. Speech
        // still ends a clip on its own, so this only caps silence: it is also how
        // long a manually triggered exchange may wait for the clip already in
        // flight, which is why the debug trigger opens within a beat.
        private const val WAKE_IDLE_SECONDS = 4
        // Hard ceiling on one wake clip. The idle pass only gives up while NO
        // speech has been heard, so a room whose ambient noise clears the energy
        // threshold never looks silent: the clip then runs to the recorder's own
        // default and the matcher scores a minute of room. That clip is both
        // expensive and useless - the phrase is a beat long - so the ceiling is
        // what keeps the loop honest.
        private const val WAKE_CLIP_SECONDS = 10
        // How long one frame of the card's climb from the bottom edge lasts, and
        // how many frames that climb is. The session is handed a position along the
        // climb under SESSION_ANIM_KEY rather than a frame count, so these two live
        // on this side alone and cannot drift out of step with the session; the round
        // trip to the session's process per show is what bounds how many frames are
        // worth asking for.
        private const val RISE_FRAME_MS = 45L
        private const val RISE_FRAMES = 9
        // The same ladder run backwards when the exchange closes, so the card sinks
        // and fades out the way it came in rather than blinking out.
        private const val SINK_FRAME_MS = 45L
        private const val SINK_FRAMES = 7
        // How often a reply that is still arriving is re-shown, so the answer
        // bubble grows with the stream without a round trip per token.
        private const val SESSION_REPAINT_MS = 300L

        /**
         * Build an intent for this service with the given action.
         */
        fun buildIntent(context: android.content.Context, action: String): Intent {
            return Intent(context, WakeChatService::class.java).apply {
                this.action = action
            }
        }

        // ==================== State shared with the app ====================
        //
        // A foreground service runs in the same process as the app, so the
        // session window's own half cannot read these flows - it runs elsewhere -
        // and the notification below carries the same exchange.

        private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        private val _phase = MutableStateFlow(PHASE_IDLE)
        private val _statusMessage = MutableStateFlow("")
        // When the chat last saw activity. The idle-close watchdog compares this
        // against the configured close timeout, so anything that counts as
        // activity - a streamed token, a spoken reply, the user tapping the card
        // - only has to move this stamp to keep the chat open.
        private val _lastActivity = MutableStateFlow(System.currentTimeMillis())

        val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
        val phase: StateFlow<com.localassistant.app.domain.model.VoiceCallPhase> = _phase.asStateFlow()
        val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

        /**
         * Record that the open chat saw activity, so the close timer restarts.
         *
         * Both halves of the pipeline call this - the service at every stage and
         * the overlay when the user touches it - because the timer belongs to the
         * exchange, not to whichever of the two is running.
         */
        fun touchChat() {
            _lastActivity.value = System.currentTimeMillis()
        }

        /**
         * Close the chat: the exchange is over, so its history is dropped and the
         * card slides away.
         *
         * A wake chat holds one conversation, not a log - nothing here is
         * persisted, and the next wake phrase starts an empty card. The history
         * only ever lives as long as the chat is open, which is what lets the
         * LLM keep the turns of an exchange as context without the app becoming
         * a second conversation history the user never sees.
         */
        fun closeChat() {
            _messages.value = emptyList()
            _phase.value = PHASE_DETECTING
            _lastActivity.value = System.currentTimeMillis()
        }
    }

    // The app's context - the recording and settings helpers expect an
    // android.content.Context, which a Service (an android.app.Service) is not.
    private val appContext by lazy {
        LocalAssistantApplication.getInstance().applicationContext
    }

    private val settingsRepository by lazy {
        SettingsRepository(appContext)
    }

    private val stopSignal = AtomicBoolean(false)

    // Aborts the clip the idle wake pass is holding without closing the chat, so a
    // manually triggered exchange does not have to wait for that recording to reach
    // its silence cap before the card can be raised.
    private val wakeAbortSignal = AtomicBoolean(false)

    // Raised while a turn is in flight when the user says the phrase again: the
    // answer stops being thought and the reply stops being spoken, so the
    // interruption takes over instead of queueing behind the reply.
    private val wakeInterruptSignal = AtomicBoolean(false)

    // Asks the beside-the-turn recorder to let go of the microphone. The platform
    // hosts one AudioRecord at a time, so this is raised and the watcher awaited
    // before the next turn opens its own window.
    private val bargeInAbortSignal = AtomicBoolean(false)

    @Volatile
    private var listening = false

    // Set by Settings to run one exchange without the wake match; the loop reads
    // and clears it, so a trigger can never repeat itself.
    @Volatile
    private var manualTrigger = false

    // How many session requests have failed in a row. A platform that refuses
    // sessions answers on every request, and the reply is re-shown as it streams,
    // so the refusal is reported once and then dropped rather than spamming the
    // notification for the whole answer.
    @Volatile
    private var sessionFailures = 0

    // The enrollment is described once per listening session: its references are
    // fixed for that session, and the pairwise costs it needs are the same DTW
    // the wake pass runs, so repeating them on every idle clip is pure waste.
    @Volatile
    private var enrollmentDiagnosed = false

    // Coroutine scope for the listening loop. cancel() permanently finishes a
    // scope, so stopListening() replaces it with a fresh one before the next start.
    @Volatile
    private var serviceScope = newServiceScope()

    private fun newServiceScope() = CoroutineScope(Dispatchers.Default + Job())

    // Watches the API client's rate-limit waiting state for the whole service
    // lifetime; a cancelled serviceScope would kill the watch on every restart.
    private val connectionWaitingScope = CoroutineScope(Dispatchers.Default + Job())

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(PHASE_IDLE))

        // One "Awaiting connection..." per rate-limit episode, not per retry: the
        // client retries 429s internally on an exponential backoff, so the overlay
        // and the notification only have to note that the exchange is still working.
        connectionWaitingScope.launch {
            ApiClient.connectionWaiting.collect { waiting ->
                when {
                    waiting -> {
                        _statusMessage.value = "Awaiting connection..."
                        updateNotification("Awaiting connection...")
                    }
                    _statusMessage.value == "Awaiting connection..." -> {
                        _statusMessage.value = ""
                        updateNotification(getPhaseText(_phase.value))
                    }
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // The app's own hands-free switch, and the action the system uses when
            // it opens this service as its voice interaction service - both mean
            // "listen", and startListening() is idempotent so the two cannot stack.
            ACTION_START, ACTION_VOICE_INTERACTION -> startListening()
            ACTION_STOP -> stopListening()
            // The sink is a run of delayed shows, so it runs off the notification
            // thread exactly as the listening loop does.
            ACTION_CLOSE_CHAT -> serviceScope.launch { endExchange() }
            ACTION_TRIGGER_EXCHANGE -> triggerExchange()
        }

        return START_STICKY
    }

    // onBind() is deliberately NOT overridden. The base implementation answers the
    // interface action ("android.service.voice.VoiceInteractionService") with that
    // intent's binder, and that binder is the IPC channel the system server uses to
    // call onReady(), onPrepareToShowSession() and onShowSessionFailed() in this
    // process. An override that returns null for every intent keeps the system's
    // voice interaction manager unresolved - onReady() is its only assignment and
    // showSession() throws until it exists - so no exchange is ever drawn outside
    // the app's own window.

    // ==================== Voice interaction (system-driven path) ====================
    //
    // A platform that hosts hands-free conversations owns the always-on hotword
    // detector, the microphone and the conversation UI that is drawn over every
    // other app; this class is its only window into our app, so the exchange is
    // raised, lowered and reported here. Where the platform has no such
    // infrastructure, none of these callbacks run and the app's own loop below is
    // the one that keeps the device hands-free.
    //
    // The platform only reaches this class once the user has picked LocalAssistant
    // as the system's assistant, so onReady() is the proof of that choice: the
    // base class resolves the system's voice interaction manager here, and every
    // session request below depends on it having resolved.

    override fun onReady() {
        // The base resolves the system's voice interaction manager here - it is the
        // only assignment of it, and every session request below goes through
        // showSession(), which throws "Not available until onReady() is called"
        // until that resolution has happened. So this call must come first and must
        // not be skipped: an override that never chains to it leaves the whole
        // system-driven path dead even on a platform that has bound this app as its
        // assistant.
        super.onReady()
        // Reaching this line at all is the platform's own proof that it resolved
        // this app as its voice interaction manager: the base above resolves it or
        // throws, and a vendor picker that records the choice in Settings.Secure
        // without granting the assistant role leaves the role query in
        // DefaultAssistant.kt answering "not held" for a user who has set the
        // picker. Recording the binding here is what lets the settings page tell
        // the two cases apart.
        voiceInteractionBound = true
        android.util.Log.i(
            "WakeChatService",
            "onReady: the system has bound this app as its voice interaction service"
        )
        // The system's hotword detector is ready, which is its own signal that
        // listening may begin. Starting here is idempotent, so the app can still
        // start the loop itself on a platform that never calls this.
        startListening()
    }

    override fun onShutdown() {
        // The system is standing its voice infrastructure down, so the loop that
        // feeds it stops with it and the card slides away.
        stopListening()
        lowerCard()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        // From the lock screen there is nothing of ours to draw over, so the
        // notification is the channel that carries the state.
        _phase.value = PHASE_LISTENING
        updateNotification(getPhaseText(PHASE_LISTENING))
    }

    /**
     * The system is about to draw a voice session over whatever the device is
     * showing, so raise our own card for the same moment. The bundle carries the
     * system's own session arguments, which we never read - we only mirror the
     * state into the notification.
     */
    override fun onPrepareToShowSession(args: android.os.Bundle, flags: Int) {
        raiseCard("")
        _phase.value = PHASE_LISTENING
        updateNotification(getPhaseText(PHASE_LISTENING))
    }

    override fun onShowSessionFailed(args: android.os.Bundle) {
        // Failures ride the status message; the phase keeps doing its job.
        _statusMessage.value = "The voice session could not be shown"
        updateNotification("The voice session could not be shown")
    }

    /**
     * Report which of the system's voice actions this app can serve. Every request
     * is answered as a chat turn by the local LLM, so any action offered is one we
     * can take.
     */
    override fun onGetSupportedVoiceActions(voiceActions: Set<String>): Set<String> {
        return voiceActions
    }

    override fun onTimeout(timeoutMillis: Int) {
        _statusMessage.value = "The request timed out"
        updateNotification("The request timed out")
    }

    override fun onTimeout(timeoutMillis: Int, flags: Int) {
        _statusMessage.value = "The request timed out"
        updateNotification("The request timed out")
    }

    /**
     * Start the always-on listening loop. Idempotent so re-entry cannot stack
     * several loops on one service instance.
     */
    private fun startListening() {
        if (listening) return
        listening = true
        stopSignal.set(false)
        wakeAbortSignal.set(false)
        _phase.value = PHASE_DETECTING

        val scope = serviceScope
        scope.launch { wakeLoop() }
    }

    private fun stopListening() {
        listening = false
        stopSignal.set(true)
        wakeAbortSignal.set(true)
        // The mid-turn watcher is holding the microphone when a stop arrives
        // between turns, so it has to be told to let go as well - otherwise the
        // next exchange opens a second recorder beside the one still running.
        bargeInAbortSignal.set(true)
        // A stale interrupt must not carry into the next exchange and cut its first
        // reply short.
        wakeInterruptSignal.set(false)
        // Stopping the service closes the chat, so its history goes with it -
        // a wake chat never carries a conversation into the next exchange.
        // The sink is a run of delayed shows, so it runs on the scope this loop
        // was already on, captured before the next exchange replaces it.
        val scope = serviceScope
        scope.launch { endExchange() }
        _phase.value = PHASE_IDLE
        serviceScope = newServiceScope()
        enrollmentDiagnosed = false
    }

    /**
     * Run the next exchange without waiting for the wake phrase.
     *
     * The debug path for the whole pipeline - record the request, transcribe, ask
     * the LLM, speak the reply - so a user testing endpoints never has to get the
     * wake word recognised first. The loop clears the flag as it reads it, so one
     * trigger means one exchange.
     */
    fun triggerExchange() {
        if (!listening) startListening()
        manualTrigger = true
        // The idle wake pass may be holding a clip of quiet room when the trigger
        // arrives. Letting go of that clip is the difference between the card
        // opening on a beat and opening up to the idle cap later, so the abort is
        // requested here rather than left to the loop that reads the flag.
        wakeAbortSignal.set(true)
    }

    /**
     * Raises the card with an optional line to show on it.
     *
     * Raising the card is itself activity, so it stamps the close clock.
     */
    private fun raiseCard(message: String) {
        _statusMessage.value = message
        touchChat()
    }

    /**
     * Lowers the card and returns to listening, so an app that is merely
     * listening never covers the screen it is on.
     */
    private fun lowerCard() {
        _phase.value = PHASE_DETECTING
    }

    /**
     * Let the open chat stay on screen while it is quiet for [idleMs], and return
     * as soon as it has been.
     *
     * Every stage of an exchange stamps the activity clock - each streamed token,
     * the spoken reply, and the user tapping the card from the overlay - so a
     * reply that is still working, or a user still reading one, never has the
     * card taken away. Only genuine silence lets this hold go, and the caller
     * decides what to do then: a conversation is kept open by the live
     * microphone, so the hold only ever carries a short readable beat.
     */
    private suspend fun holdWhileQuiet(idleMs: Long) {
        // The window measures silence, not work. It is started here rather than
        // inherited from the last stamp inside the exchange, because the stages
        // that end an exchange - recording the request, STT, the LLM, speaking
        // the reply - are the system thinking, and a slow round trip to any of
        // them would otherwise spend the reading time the user asked for before
        // this hold is even entered.
        touchChat()
        while (true) {
            delay(TOUCH_POLL_MS)
            // Stopping the service has already closed the chat, so the hold only
            // has to let go of it.
            if (!listening) break
            if (System.currentTimeMillis() - _lastActivity.value >= idleMs) break
        }
    }

    /**
     * Listen for the wake phrase and answer whether it was heard.
     *
     * Silence, missing enrollment and a poor match all answer false and leave the
     * loop listening: only a clip that matches the enrolled references starts an
     * exchange, and the wake clip itself is never transcribed.
     */
    private suspend fun waitForWake(
        currentSettings: com.localassistant.app.domain.model.AppSettings
    ): Boolean {
        val clip = withContext(Dispatchers.IO) {
            recordAudioWithVAD(
                context = appContext,
                // The idle pass watches its own abort signal rather than the service
                // stop signal, so letting go of a clip of quiet room does not look
                // like stopping the service.
                stopSignal = wakeAbortSignal,
                maxSeconds = WAKE_CLIP_SECONDS,
                noSpeechTimeoutMs = WAKE_IDLE_SECONDS * 1000L
            )
        }
        if (!listening) return false

        if (clip == null || clip.isEmpty()) {
            // The recorder answers with nothing when no speech was heard, which
            // is the common case while the app simply listens. Silence must never
            // raise the overlay or report an error.
            _statusMessage.value = ""
            _phase.value = PHASE_DETECTING
            return false
        }

        val references = loadWakeReferences(appContext)
        if (references.isEmpty()) {
            // Nothing can match yet, so this is the one case worth showing.
            val hint = "No wake-word references recorded - open Settings and speak the phrase"
            raiseCard(hint)
            updateNotification(hint)
            delay(REFERENCE_HINT_MS)
            lowerCard()
            return false
        }

        if (!enrollmentDiagnosed) {
            enrollmentDiagnosed = true
            // What the enrollment actually is, before any clip is scored against it.
            // Genuine takes of one phrase sit a third of the scale apart; references
            // that are near-identical are several captures of one audio source, and
            // references from an older feature layout cost MAX_VALUE everywhere.
            val pairwise = mutableListOf<Float>()
            for (i in references.indices) {
                for (j in (i + 1)..<references.size) {
                    pairwise += bestMatchCost(references[i], listOf(references[j]))
                }
            }
            pairwise.sort()
            val spread = if (pairwise.isEmpty()) Float.NaN else pairwise[pairwise.size / 2]
            android.util.Log.d(
                "WakeChatService",
                "Enrollment: ${references.size} references, " +
                    "frames " + references.map { referenceFrameCount(it) }.joinToString(",") + ", " +
                    "median pairwise cost ${formatCost(spread)}"
            )
        }

        val features = extractWakeFeatures(clip)
        if (!wakeWordHeard(features, references, currentSettings.wakeWordSensitivity)) {
            // The idle pass gets the same breakdown the Settings test prints. A single
            // aggregate number cannot say which input is at fault: an enrollment whose
            // references all cost the same high value is a different problem from one
            // bad take, and both look identical from the median alone.
            android.util.Log.d(
                "WakeChatService",
                "Wake clip rejected: clip ${referenceFrameCount(features)} frames, " +
                    "takes " + references.map { referenceFrameCount(it) }.joinToString(",") + " frames, " +
                    "costs " + references.map { formatCost(bestMatchCost(features, listOf(it))) }.joinToString(",") + ", " +
                    "agreement " + wakeAgreement(features, references, currentSettings.wakeWordSensitivity).joinToString(" ") + ", " +
                    "threshold ${formatCost(wakeCostThreshold(currentSettings.wakeWordSensitivity, references))}"
            )
            _phase.value = PHASE_DETECTING
            return false
        }
        return true
    }

    /**
     * Hold the microphone beside an in-flight turn so a second wake phrase can cut
     * that turn short rather than queue behind it.
     *
     * The phrase is matched on-device exactly as the idle pass does it, so the
     * interruption cannot be triggered by the assistant's own reply being
     * transcribed - only by the enrolled voice saying the phrase.
     *
     * One window is not enough: a quiet room ends a window in a few seconds while a
     * turn may run for tens of them, so the watcher refills its window until the
     * caller raises [bargeInAbortSignal]. That keeps the microphone live across the
     * whole turn - the LLM call *and* the reply being voiced - which is what lets a
     * phrase said over a spoken reply cut it short.
     *
     * @return whether the phrase was heard.
     */
    private suspend fun bargeInWatcher(
        currentSettings: com.localassistant.app.domain.model.AppSettings
    ): Boolean {
        while (!bargeInAbortSignal.get()) {
            val clip = withContext(Dispatchers.IO) {
                recordAudioWithVAD(
                    context = appContext,
                    stopSignal = bargeInAbortSignal,
                    maxSeconds = WAKE_CLIP_SECONDS,
                    noSpeechTimeoutMs = WAKE_IDLE_SECONDS * 1000L
                )
            }
            // The caller released the watcher, or the window timed out - either way
            // there is nothing to match, and the loop re-checks the release before
            // opening another window.
            if (clip == null || clip.isEmpty()) continue

            val references = loadWakeReferences(appContext)
            if (references.isEmpty()) continue

            val features = extractWakeFeatures(clip)
            if (!wakeWordHeard(features, references, currentSettings.wakeWordSensitivity)) continue

            wakeInterruptSignal.set(true)
            android.util.Log.w(
                "WakeChatService",
                "Wake phrase heard mid-turn: clip ${referenceFrameCount(features)} frames, " +
                    "threshold ${formatCost(wakeCostThreshold(currentSettings.wakeWordSensitivity, references))}"
            )
            return true
        }
        return false
    }

    private suspend fun wakeLoop() {
        while (listening) {
            var currentSettings = settingsRepository.settingsFlow.value
            // A manual trigger from Settings runs the exchange without the wake
            // match, so the pipeline can be exercised on demand.
            val forced = manualTrigger
            manualTrigger = false
            if (!forced) {
                // A fresh idle window: the abort flag is only a request to stop
                // waiting for the phrase, so it must not stay set for the next one.
                wakeAbortSignal.set(false)
                if (!waitForWake(currentSettings)) continue
            }

            // The wake clip is never transcribed: the phrase has already been
            // recognised on-device, so sending it to STT would put the wake word in
            // front of the request.
            raiseCard("")
            _phase.value = PHASE_LISTENING
            updateNotification(getPhaseText(PHASE_LISTENING))
            // The window is raised the moment the phrase is heard, before the
            // request is recorded. A session that only appears once the reply is
            // ready tells the user nothing about the exchange they are in - they
            // would be speaking into a screen that shows nothing.
            //
            // It is raised before the chime on purpose: the chime blocks until it
            // has finished, so a card raised after it would appear a beat *behind*
            // the sound the user already heard. Only the first frame is on the
            // critical path, because the card is on screen from the first show.
            //
            // The climb up from the bottom edge is a run of shows rather than an
            // animation, because a session has no animation API - see SESSION_ANIM_KEY
            // in the session. Each show is handed how far through the climb it is, so
            // the card translates up and fades in across them. The last show settles
            // the card and is the one that carries the "I'm listening" text.
            raiseSessionWindow()
            requestSession(SESSION_STATE_LISTENING, "", "")
            // The chime still has to finish before the microphone starts, because
            // it is the user's cue that they can speak now - it no longer delays
            // the card.
            playWakeCue()

            // One wake phrase opens a conversation rather than a single question.
            // After every reply the microphone is re-armed straight away, so a
            // follow-up is just the next sentence: making the user say the phrase
            // again to ask anything else is the friction this mode exists to
            // remove. Silence, not the wake word, is what ends a conversation.
            var silenceWindowMs = currentSettings.wakeReplySeconds.coerceIn(1, 300) * 1000L
            // The exchange the card is showing. The session draws one user and one
            // reply bubble from the bundle it is handed, so a show that carries
            // nothing would wipe the conversation the user is still looking at -
            // every open show re-sends what is already on the card instead.
            var lastRequest = ""
            var lastReply = ""
            var firstTurn = true
            while (listening) {
                // Settings are re-read per turn, so a user changing the reply or
                // close window mid-conversation is honoured by the next turn.
                currentSettings = settingsRepository.settingsFlow.value
                if (!firstTurn) {
                    // The reply has been heard and the microphone is live again, so
                    // the card has to say so: without this the user has no cue that
                    // the next sentence is already being recorded, and the mode
                    // looks like it wants the wake phrase again. The last exchange
                    // travels with the show, so the card keeps the conversation the
                    // user is still reading rather than blanking under a new
                    // question.
                    _phase.value = PHASE_LISTENING
                    updateNotification(getPhaseText(PHASE_LISTENING))
                    requestSession(SESSION_STATE_LISTENING, lastRequest, lastReply)
                    // The chime belongs to the microphone, not to the first turn: it
                    // is what tells the user a follow-up is being heard. Without it
                    // a continuous conversation is silent about having re-armed, and
                    // the user has to guess whether to speak.
                    playWakeCue()
                }
                firstTurn = false
                // The window is the user's setting: enough time to say the request,
                // and nothing heard within it ends the conversation instead of
                // holding the microphone open for a minute. The silence that *ends*
                // an utterance stays the configured VAD behaviour, so a request
                // that begins late is still recorded in full.
                val requestClip = withContext(Dispatchers.IO) {
                    recordAudioWithVAD(
                        context = appContext,
                        stopSignal = stopSignal,
                        silenceThresholdMs = currentSettings.vadMinSilenceDurationMs
                            .coerceIn(200, 5000).toLong(),
                        noSpeechTimeoutMs = silenceWindowMs
                    )
                }
                if (!listening) break
                // Whether the window holds speech at all, kept apart from whether
                // that speech became usable words: the two end the conversation
                // differently, and only one of them is silence.
                val heardSomething = requestClip != null && requestClip.isNotEmpty()
                val request = if (!heardSomething) {
                    ""
                } else {
                    // A request clip that only holds whisper.cpp's bracketed markers is
                    // not speech, so it must not reach the LLM either.
                    val requestText = transcribe(currentSettings, requestClip) ?: ""
                    if (isNonSpeechTranscript(requestText)) "" else requestText
                }

                if (request.isBlank()) {
                    _statusMessage.value = "Could not hear the request"
                    updateNotification("Could not hear the request")
                    if (!heardSomething) {
                        // Nothing at all inside the window is the end of the
                        // conversation rather than a failed turn. The reason is shown
                        // for a readable beat and then the card closes: holding it up
                        // for the whole close window here would make a silent room look
                        // like an assistant that is still waiting for a request.
                        holdWhileQuiet(CLOSED_HINT_MS)
                        break
                    }
                    // Speech was heard but it is not usable words - the marker-only
                    // transcript whisper.cpp answers with, or a clip that is all noise.
                    // The user is mid-conversation and clearly still talking, so this
                    // is a failed turn, not the end of one: closing here would end a
                    // conversation on the first mumbled request. The reason is shown
                    // and the microphone is re-armed under the user's close window,
                    // which is what ends the conversation if they really have stopped.
                    silenceWindowMs = currentSettings.wakeChatCloseSeconds.coerceIn(1, 300) * 1000L
                    continue
                }

                lastRequest = request
                lastReply = answer(request, currentSettings)
                if (wakeInterruptSignal.get()) {
                    // The user said the phrase during this turn, so the turn is over
                    // and the next one starts straight away: waiting for silence here
                    // would make an interruption look like the end of the
                    // conversation. The window is the fresh "just heard the phrase"
                    // one, not the close window.
                    silenceWindowMs = currentSettings.wakeReplySeconds.coerceIn(1, 300) * 1000L
                    delay(MIC_REARM_MS)
                    continue
                }
                // The reply has just been voiced through speakers beside the
                // microphone, so the mic is given a beat before it is live again -
                // otherwise the tail of that reply becomes the next question.
                delay(MIC_REARM_MS)
                // The reply has been heard, so the next window is the user's close
                // timeout: say nothing for that long and the conversation ends, say
                // anything at all and it continues.
                silenceWindowMs = currentSettings.wakeChatCloseSeconds.coerceIn(1, 300) * 1000L
            }
            endExchange()
        }
        _phase.value = PHASE_IDLE
    }

    /**
     * Answer a request the pipeline has settled on - whether the words came from
     * our own recorder or from the system's voice interaction service - by asking
     * the LLM and speaking the reply. Both paths share this so the exchange
     * behaves identically whichever one heard the user.
     */
    private suspend fun answer(
        request: String,
        currentSettings: com.localassistant.app.domain.model.AppSettings
    ): String {
        _messages.update { current ->
            current + listOf(ChatMessage.user(request))
        }
        _phase.value = PHASE_THINKING
        touchChat()
        // The window is already up from the moment the phrase was heard, so the
        // words the user said replace the "I'm listening" card as soon as they
        // are known - the user can see what was heard while the answer is being
        // produced.
        requestSession(SESSION_STATE_REQUEST, request, "")

        // A second wake phrase heard while this turn is in flight cuts it short, so
        // the microphone is held beside the LLM and the speakers rather than only
        // between turns. The flags are cleared per turn: a previous interruption
        // must not be read as a current one.
        wakeInterruptSignal.set(false)
        bargeInAbortSignal.set(false)
        // The watcher is a child of the listening scope, so it is cancelled with
        // the service. Its own failure must not cancel that scope, because an
        // interrupted recording is not the end of the assistant's whole session.
        val interruptWatcher = serviceScope.async {
            try {
                bargeInWatcher(currentSettings)
            } catch (e: Exception) {
                android.util.Log.w("WakeChatService", "Barge-in watcher failed: ${e.message}")
                false
            }
        }

        val reply = complete(currentSettings, request, wakeInterruptSignal)

        if (reply.isNotBlank() && !wakeInterruptSignal.get()) {
            _messages.update { current ->
                current + listOf(ChatMessage.assistant(reply))
            }
            // The reply has to reach the user while the app is minimized, so it
            // travels in the notification as well as on the overlay.
            updateNotification(reply)
            // Ask the system to draw the exchange over whatever the device is
            // showing, before the reply is spoken - the user can read it while
            // they listen.
            requestSession(SESSION_STATE_REPLY, request, reply)
            _phase.value = PHASE_SPEAKING
            // The card says "Speaking…" while the reply is voiced, so the label
            // matches the audio rather than the full reply appearing before it has
            // been heard.
            requestSession(SESSION_STATE_SPEAKING, request, reply)
            // Speaking the reply is activity right up to its last syllable, so
            // the close clock is stamped before it starts - the next turn's
            // microphone window is measured from here, never from mid-audio.
            touchChat()
            playTTSAudio(
                text = reply,
                ttsSettings = Pair(
                    currentSettings.ttsBaseUrl,
                    if (currentSettings.ttsApiKey.isBlank()) null else currentSettings.ttsApiKey
                ),
                model = currentSettings.ttsModelName,
                voice = currentSettings.ttsVoiceName,
                responseFormat = currentSettings.ttsResponseFormat,
                enableStreaming = currentSettings.enableTtsStreaming,
                timeoutSeconds = currentSettings.httpTimeoutSeconds.toLong(),
                // The phrase can be said again while the reply is still being
                // voiced, and the rest of it must not keep talking over the user.
                abortSignal = wakeInterruptSignal
            )
            // The reply has been spoken, so the card settles on the two bubbles
            // with no status label - a settled exchange is just the conversation.
            requestSession(SESSION_STATE_REPLY, request, reply)
        } else if (wakeInterruptSignal.get()) {
            // The turn was cut short, so the half an answer that arrived is not
            // kept as a reply the user never heard, and nothing is spoken over the
            // phrase they are already saying.
            android.util.Log.w(
                "WakeChatService",
                "Turn interrupted by the wake word - ${reply.length} chars of answer dropped"
            )
        }
        // The platform hosts one microphone at a time, so the beside-the-turn
        // recorder has to have let go before the next turn opens its own window.
        // It is released here rather than right after the LLM call so the phrase can
        // still be said over a reply that is being voiced.
        bargeInAbortSignal.set(true)
        interruptWatcher.await()
        return reply
    }

    /**
     * Raise the session window with the card climbing up from the bottom edge.
     *
     * A session has no animation API - [View.startAnimation] and
     * [VoiceInteractionSession.setContentView] are the only motion hooks and both
     * need an [android.animation.Animation], which this toolchain cannot name or
     * construct - so the climb is a run of shows and the session draws the card
     * higher each time. Each show is handed how far through the climb it is under
     * [SESSION_ANIM_KEY], so the session owns the easing and the geometry and this
     * service only supplies the progress; [RISE_FRAMES] lives here alone.
     *
     * The frames are short on purpose: each one is a round trip to the session's
     * process and the window is on screen from the first, so the user only sees
     * the card move.
     */
    private suspend fun raiseSessionWindow() {
        for (frame in 0 until RISE_FRAMES) {
            requestSession(
                SESSION_STATE_RISE,
                "",
                "",
                (frame + 1) / RISE_FRAMES.toFloat())
            delay(RISE_FRAME_MS)
        }
    }

    /**
     * Ask the platform to show a voice session carrying this exchange.
     *
     * The session is drawn by [WakeChatSessionService] in its own process, so it
     * cannot read this service's state and the text has to be handed over in the
     * bundle. [state] says which half of the exchange the user is in - the
     * window is raised as soon as the phrase is heard, before any words exist,
     * and shown again once the reply is ready. The platform only accepts a
     * request once [onReady] has finished, and a platform may refuse sessions
     * altogether, so a failure here is logged and the exchange still reaches the
     * user through the notification and TTS.
     */
    private fun requestSession(
        state: String,
        request: String,
        reply: String,
        progress: Float = 1f
    ) {
        if (sessionFailures >= 3) return
        try {
            val args = android.os.Bundle()
            args.putString(SESSION_STATE_KEY, state)
            args.putString(SESSION_REQUEST_KEY, request)
            args.putString(SESSION_REPLY_KEY, reply)
            // Only the climb and the sink read this: every other state is settled,
            // and the default above says so without spelling 1.0 at every call site.
            args.putString(SESSION_ANIM_KEY, progress.toString())
            android.util.Log.i("WakeChatService", "Asking the system to draw this exchange")
            showSession(args, 0)
            sessionFailures = 0
        } catch (e: Exception) {
            sessionFailures++
            // The reason is surfaced in the notification rather than only logged:
            // Kotlin's log never reaches logcat here, and the refusal is the one
            // thing the user can read to tell whether the platform hosts the
            // exchange at all (a session that failed to bind reports through
            // onShowSessionFailed instead, and never reaches this catch).
            _statusMessage.value = "The system would not draw this exchange: ${e.message}"
            updateNotification("The system would not draw this exchange")
        }
    }

    /**
     * End the open exchange everywhere: the state this service holds, and the
     * window the system drew over whatever the device is showing.
     *
     * [closeChat] clears the state both halves share, but the session runs in
     * another process and only sees the bundles it is handed, so the end of the
     * exchange has to be sent to it as well - otherwise the card stays on the
     * home screen after the chat has been closed.
     *
     * The close is a run of shows rather than one, and each is handed a lower
     * position along the climb, so the card sinks and fades out the way it came
     * in instead of blinking out. The state is cleared first so nothing the user
     * can act on survives the animation.
     */
    private suspend fun endExchange() {
        closeChat()
        for (frame in SINK_FRAMES - 1 downTo 0) {
            requestSession(
                SESSION_STATE_CLOSE,
                "",
                "",
                (frame + 1) / SINK_FRAMES.toFloat())
            delay(SINK_FRAME_MS)
        }
        // The card is fully sunk; this show is what releases the window, because
        // it is the only one the session sees at zero height.
        requestSession(SESSION_STATE_CLOSE, "", "", 0f)
    }

    /**
     * Transcribe a recorded clip with the configured STT endpoint, returning null
     * on failure and surfacing the reason in [statusMessage].
     */
    private suspend fun transcribe(
        currentSettings: com.localassistant.app.domain.model.AppSettings,
        wavBytes: ByteArray
    ): String? {
        return try {
            withContext(Dispatchers.IO) {
                ApiClient.transcribeAudio(
                    baseUrl = currentSettings.sttBaseUrl,
                    apiKey = if (currentSettings.sttApiKey.isBlank()) null else currentSettings.sttApiKey,
                    audioData = wavBytes,
                    model = currentSettings.sttModelName,
                    timeoutSeconds = currentSettings.httpTimeoutSeconds.toLong()
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("WakeChatService", "STT failed: ${e.message}")
            _statusMessage.value = "STT failed: ${e.message}"
            // The reason has to reach the user while the app is minimized, so it
            // travels in the notification as well as on the overlay.
            updateNotification("STT failed: ${e.message}")
            null
        }
    }

    /**
     * Ask the LLM endpoint for a reply to [request], using the recent wake-chat history as context.
     */
    private suspend fun complete(
        currentSettings: com.localassistant.app.domain.model.AppSettings,
        request: String,
        abortSignal: java.util.concurrent.atomic.AtomicBoolean? = null
    ): String {
        // The service runs hands-free all day, so only the latest turns are sent -
        // an unbounded history would eventually overflow the model's context.
        val history = _messages.value.takeLast(MAX_LLM_CONTEXT).map { msg ->
            mapOf("role" to msg.role.name.lowercase(), "content" to msg.content)
        }
        // Tools are offered only when the user opted in, because discovery sits on the critical
        // path of the request: an install that never enabled MCP pays nothing.
        val toolDescriptors =
            if (currentSettings.mcpReady) McpClient.advertisedTools(currentSettings.mcpServerUrls, currentSettings.mcpLazyTools)
            else emptyList()
        // The answer as it has arrived so far, and when it was last drawn.
        var streamed = ""
        var lastPaintMs = 0L
        return try {
            ApiClient.chatCompletionWithTools(
                baseUrl = currentSettings.llmBaseUrl,
                apiKey = if (currentSettings.llmApiKey.isBlank()) null else currentSettings.llmApiKey,
                messages = history,
                model = currentSettings.llmModelName,
                maxTokens = currentSettings.llmMaxTokens,
                reasoningBudgetTokens = currentSettings.llmReasoningBudgetTokens,
                chatTemplateKwargs = currentSettings.llmChatTemplateKwargs,
                systemPrompt = currentSettings.systemPrompt,
                toolDescriptors = toolDescriptors,
                // The answer to a tool call is fetched here so the loop in the API client can
                // hand the result back to the model and ask again in the same exchange.
                runTool = { name: String, argumentsJson: String ->
                    McpClient.runNamedTool(currentSettings.mcpServerUrls, name, argumentsJson)
                },
                // Text arriving on the stream is activity on the chat, so the
                // close clock follows the answer as it is written rather than
                // waiting for it to finish.
                onChunk = { chunk ->
                    touchChat()
                    // Reasoning deltas carry the separator marker and are never
                    // drawn; content deltas grow the answer bubble as they arrive,
                    // throttled to [SESSION_REPAINT_MS] so the card is not
                    // re-shown once per token.
                    if (!chunk.startsWith("\u0001")) {
                        streamed += chunk
                        val now = System.currentTimeMillis()
                        if (now - lastPaintMs >= SESSION_REPAINT_MS) {
                            lastPaintMs = now
                            requestSession(SESSION_STATE_STREAMING, request, visibleReply(streamed))
                        }
                    }
                },
                timeoutSeconds = currentSettings.httpTimeoutSeconds.toLong(),
                // The phrase can be said again while the answer is still being
                // thought, and the deltas after it are not wanted any more.
                abortSignal = abortSignal
            ).content
        } catch (e: Exception) {
            android.util.Log.w("WakeChatService", "LLM failed: ${e.message}")
            _statusMessage.value = "LLM failed: ${e.message}"
            updateNotification("LLM failed: ${e.message}")
            ""
        }
    }

    /**
     * The part of a reply that is safe to draw while it is still arriving.
     *
     * A reasoning model can open a thinking tag inside the content deltas before
     * the answer itself starts; the final reply is cleaned by
     * [ApiClient.parseReasoningAndContent], but a partial that is painted mid-stream
     * would show the raw tag, so the tags are dropped here as well.
     */
    private fun visibleReply(text: String): String {
        var visible = text
        for (tag in listOf("<think>", "</think>", "<thinking>", "</thinking>", "<reasoning>", "</reasoning>")) {
            visible = visible.replace(tag, "")
        }
        return visible
    }

    // ==================== Notification Management ====================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wake Chat",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Hands-free wake-word assistant - always listening"
            setShowBadge(false)
            setSound(null, null)
        }

        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(phase: com.localassistant.app.domain.model.VoiceCallPhase): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wake Chat")
            .setContentText(getPhaseText(phase))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .build()
    }

    /**
     * Replace the notification's text, keeping the launch intent so tapping it
     * still reopens the app.
     */
    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wake Chat")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
                PendingIntent.getActivity(
                    this, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            })
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun getPhaseText(phase: com.localassistant.app.domain.model.VoiceCallPhase): String = when (phase) {
        PHASE_DETECTING -> "Listening for the wake word"
        PHASE_LISTENING -> "Heard it - go ahead"
        PHASE_THINKING -> "Thinking..."
        PHASE_SPEAKING -> "Speaking..."
        else -> "Idle"
    }

    // Two decimals, not one: the whole sensitivity slider spans 0.377 to 0.448, and a
    // one-decimal truncation prints both ends as "0.3" - exactly the comparison the
    // costs above it have to be read against.
    private fun formatCost(value: Float): String =
        if (value >= Float.MAX_VALUE / 2.0f) "no match"
        else ((value * 100.0f).toInt() / 100.0f).toString()

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Minimizing the app must not stop the conversation - that is the whole
        // point of this service - so the task being removed is ignored unless
        // the user asked for swipe-away to close the services too.
        val stop = runCatching { SettingsRepository(applicationContext).loadSettings().stopServicesOnTaskRemoved }
            .getOrDefault(false)
        if (stop) {
            android.util.Log.d("WakeChatService", "Task removed: stopping the service as the settings ask")
            stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("WakeChatService", "Service destroying - stopping the listening loop")
        connectionWaitingScope.cancel()
        stopListening()
    }
}
