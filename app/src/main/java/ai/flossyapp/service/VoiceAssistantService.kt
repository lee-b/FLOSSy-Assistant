package ai.flossyapp.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.Builder
import ai.flossyapp.FlossyApplication
import ai.flossyapp.data.remote.ApiClient
import ai.flossyapp.domain.model.ChatMessage
import ai.flossyapp.ui.common.ConversationStore
import ai.flossyapp.ui.common.preferredCaptureDevice
import ai.flossyapp.ui.common.playListenEndCue
import ai.flossyapp.ui.common.playListenStartCue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that handles the complete voice assistant pipeline:
 * 1. Audio capture with noise suppression
 * 2. Speech recognition via local STT endpoint  
 * 3. LLM chat completion
 * 4. TTS audio playback via AudioTrack
 * 
 * Runs continuously in the background with a persistent notification.
 */
class VoiceAssistantService : Service() {

    companion object Constants {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "voice_assistant_channel"
        
        // Action constants for service commands
        const val ACTION_START = "ai.flossyapp.START"
        const val ACTION_STOP = "ai.flossyapp.STOP"
        const val ACTION_PAUSE = "ai.flossyapp.PAUSE"
        const val ACTION_RESUME = "ai.flossyapp.RESUME"
        // Ends the call from the notification: the same stop path a mic tap
        // takes, with the end-listen plop and the route closed.
        const val ACTION_END_CALL = "ai.flossyapp.END_CALL"

        // Raised when the notification's End Call is tapped. During a call the
        // screen owns the microphone, so the service cannot hang up alone - it
        // raises the event and the chat screen (collecting it while visible)
        // ends the call through the same path a mic tap takes.
        val hangUpEvents = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        
        // Service states - the same typed phases the chat screen uses, so the
        // background pipeline and the UI can never drift into different words
        // for the same thing. There is no ERROR phase: a failure is a state of
        // the whole pipeline, reported in the notification with its message,
        // and the recovery below re-arms the loop from IDLE.
        val STATE_IDLE = ai.flossyapp.domain.model.VoiceCallPhase.IDLE
        val STATE_LISTENING = ai.flossyapp.domain.model.VoiceCallPhase.LISTENING
        val STATE_PROCESSING = ai.flossyapp.domain.model.VoiceCallPhase.TRANSCRIBING
        val STATE_SPEAKING = ai.flossyapp.domain.model.VoiceCallPhase.SPEAKING
        
        // Sample rate used while the battery is low
        private const val ENERGY_SAVING_SAMPLE_RATE = 8000

        // Longest utterance kept before it is sent to STT
        private const val MAX_UTTERANCE_SECONDS = 60

        // The latest turns the exchange sends to the LLM - the service runs
        // hands-free all day, so an unbounded history would overflow context.
        private const val MAX_LLM_CONTEXT = 8
        
        /**
         * Build an intent for this service with the given action.
         */
        fun buildIntent(context: android.content.Context, action: String): Intent {
            return Intent(context, VoiceAssistantService::class.java).apply {
                this.action = action
            }
        }

        /**
         * True while the process hosts a live service. Callers that would only
         * pause an already-running service check this first, because sending a
         * command intent to a stopped service starts it fresh - and raising
         * its foreground notification just to pause it would be absurd.
         */
        @JvmStatic var isRunning = false
    }

    // Service state management - exposed as StateFlow for UI observation
    private val _state = MutableStateFlow(STATE_IDLE)
    val state: StateFlow<ai.flossyapp.domain.model.VoiceCallPhase> = _state.asStateFlow()
    // Kept out of the phase: a failure is a state of the whole pipeline, and
    // the notification needs the message, not the phase. Non-null marks an
    // error recovery as pending.
    private var errorText: String? = null
    
    private val _currentMessage = MutableStateFlow("")
    val currentMessage: StateFlow<String> = _currentMessage.asStateFlow()
    
    // Settings repository for dynamic configuration
    private val settingsRepository by lazy {
        ai.flossyapp.data.settings.SettingsRepository(
            FlossyApplication.getInstance().applicationContext
        )
    }
    // Coroutine scope for background tasks. cancel() permanently finishes a scope,
    // so stopListening() replaces it with a fresh one before the next start.
    @Volatile
    private var serviceScope = newServiceScope()

    private fun newServiceScope() = CoroutineScope(Dispatchers.Default + Job())
    
    // Notification manager
    private lateinit var notificationManager: NotificationManager

    // No recorder here: the engine's StreamingCallCapture owns the one
    // AudioRecord (Silero VAD, device routing, headset handling included).
    // This service used to open its own recorder alongside the engine's -
    // two AudioRecords fought for the mic input and left both paths deaf.

    // Watches the API client's rate-limit waiting state for the whole service
    // lifetime; a cancelled serviceScope would kill the watch on every restart.
    private val connectionWaitingScope = CoroutineScope(Dispatchers.Default + Job())

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(STATE_IDLE))
        isRunning = true

        // One "Awaiting connection..." per rate-limit episode, not per retry: the
        // client retries 429s internally on an exponential backoff, so the
        // notification only has to note that the request is still working.
        connectionWaitingScope.launch {
            ApiClient.connectionWaiting.collect { waiting ->
                when {
                    waiting -> updateNotification("Awaiting connection...")
                    _state.value == STATE_PROCESSING -> updateNotification("Processing...")
                }
            }
        }

    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // Both paths block (audio route polling, cue playback, TTS wait,
            // thread joins), so neither may run on the main thread - doing
            // that froze input dispatch long enough to ANR the app.
            ACTION_START -> serviceScope.launch { startListening() }
            ACTION_STOP -> serviceScope.launch { stopListening() }
            ACTION_PAUSE -> serviceScope.launch { pauseService() }
            ACTION_RESUME -> serviceScope.launch { resumeService() }
            ACTION_END_CALL -> endCallAndStop()
        }

        return START_STICKY
    }

    /**
     * The one deliberate end point of this service's interaction, shared by
     * the notification action and the model's own "end_call" tool.
     *
     * Off the main thread: stopListening() waits for the engine's hang-up,
     * and doing that here froze input dispatch long enough to ANR the app.
     */
    private fun endCallAndStop() {
        serviceScope.launch {
            // One deliberate hang-up, one arbiter: the engine ends
            // the call wherever it lives (TTS stops immediately),
            // and the event still reaches the screen's view.
            ai.flossyapp.ui.common.VoiceCallEngine.instance(applicationContext)
                .hangUp("assistant service End call")
            hangUpEvents.tryEmit(Unit)
            stopListening()
            stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * What the model's "end_call" tool does: the same end point, invoked
     * from inside a running exchange instead of from the notification.
     */
    private fun endCallFromTool() {
        endCallAndStop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== Lifecycle ====================

    /**
     * Start listening: hand the microphone to the engine in APP mode and let
     * the engine's StreamingCallCapture own it. Accepted utterances flow into
     * [assistantExchangeHost] automatically; this service never opens an
     * AudioRecord of its own.
     */
    private suspend fun startListening() {
        // A previous stopListening() cancelled the scope; resumeService()/the
        // home-screen widget can start again without the service being recreated.
        if (!serviceScope.isActive) serviceScope = newServiceScope()

        try {
            // The service is a listening surface of the one conversation: while
            // it is live, a hang-up may end the call but not the transcript.
            ConversationStore.setAssistantSurfaceOpen(true)
            val engine = ai.flossyapp.ui.common.VoiceCallEngine.instance(applicationContext)
            // The start cue blocks, so it plays before the mic opens.
            playListenStartCue()
            engine.startAppMode(assistantExchangeHost)
            _state.value = STATE_LISTENING
            updateNotification("Listening...")
        } catch (e: Exception) {
            ConversationStore.setAssistantSurfaceOpen(false)
            handleError(e.message ?: "Failed to start listening")
        }
    }

    /**
     * Stop listening: release the mic back to the engine and drop this
     * service's surface claim on the conversation.
     */
    private suspend fun stopListening() {
        val engine = ai.flossyapp.ui.common.VoiceCallEngine.instance(applicationContext)
        ConversationStore.setAssistantSurfaceOpen(false)

        // If an exchange the engine is carrying belongs to this service, end
        // it here: the engine's hang-up cancels the running exchange and
        // stops the TTS immediately. When the screen or the wake service owns
        // the mic, the engine keeps their call - this service only stops
        // raising its own notification.
        runCatching {
            if (engine.isAppHost(assistantExchangeHost) ||
                engine.mode == ai.flossyapp.ui.common.VoiceCallEngine.Mode.IDLE) {
                engine.hangUp("assistant service stopped")
            }
        }

        // Cancel the coroutine scope, then start a fresh one: a cancelled
        // scope can never launch again.
        serviceScope.cancel()
        serviceScope = newServiceScope()

        // The matched downbeat closes the listening window.
        playListenEndCue()

        _state.value = STATE_IDLE
        updateNotification("Stopped")
    }

    private suspend fun pauseService() {
        stopListening()
    }

    private suspend fun resumeService() {
        startListening()
    }

    private val assistantExchangeHost = object : ai.flossyapp.ui.common.VoiceCallEngine.ExchangeHost {
        // The service is mid-exchange from PROCESSING until the engine
        // reports the phase back to IDLE, which is exactly when the exchange
        // is still worth reporting to.
        override fun isActive(): Boolean = _state.value != STATE_IDLE

        override fun settings() = settingsRepository.loadSettings()

        // The service runs hands-free all day, so only the latest turns are
        // sent - an unbounded history would eventually overflow the model's
        // context. The one conversation store is the source, exactly as for
        // the screen and the wake service.
        override fun history(): List<Map<String, String>> =
            ConversationStore.messages.value.takeLast(MAX_LLM_CONTEXT).map { msg ->
                mapOf("role" to msg.role.name.lowercase(), "content" to msg.content)
            }

        override fun onUserMessage(text: String) {
            _currentMessage.value = text
            ConversationStore.addMessage(ChatMessage.user(text))
        }

        override fun onAssistantMessage(text: String, reasoning: String?) {
            ConversationStore.addMessage(ChatMessage.assistant(text, reasoning))
        }

        override fun onPhase(phase: ai.flossyapp.domain.model.VoiceCallPhase) {
            when (phase) {
                ai.flossyapp.domain.model.VoiceCallPhase.TRANSCRIBING -> {
                    _state.value = STATE_PROCESSING
                    updateNotification("Processing...")
                }
                ai.flossyapp.domain.model.VoiceCallPhase.THINKING -> {
                    _state.value = STATE_PROCESSING
                    updateNotification("Thinking...")
                }
                ai.flossyapp.domain.model.VoiceCallPhase.SPEAKING -> {
                    _state.value = STATE_SPEAKING
                    updateNotification("Speaking...")
                }
                // The exchange is over: back to listening, unless the service
                // itself is stopping (IDLE).
                else -> if (_state.value != STATE_IDLE) {
                    _state.value = STATE_LISTENING
                    updateNotification("Listening...")
                }
            }
        }

        override fun onStreamingContent(text: String) {
            _currentMessage.value = text
        }

        override fun onStreamingReasoning(text: String) {}

        override fun onSystemNote(text: String) {
            android.util.Log.d("VoiceAssistantService", "System note: $text")
        }

        override fun onError(message: String) {
            handleError(message)
        }
    }

    // ==================== Notification Management ====================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice Assistant",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Local AI Voice Assistant - Always listening mode"
            setShowBadge(false)
            setSound(null, null)
        }
        
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: ai.flossyapp.domain.model.VoiceCallPhase): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FLOSSy Assistant")
            .setContentText(getStateText(state))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        // An idle service is not a call; hanging up is meaningless then.
        if (state != STATE_IDLE) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Call", endCallPendingIntent)
        }
        return builder.build()
    }

    private fun updateNotification(text: String) {
        val notification = Builder(this, CHANNEL_ID)
            .setContentTitle("FLOSSy Assistant")
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
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        if (_state.value != STATE_IDLE) {
            notification.addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Call", endCallPendingIntent)
        }
        notificationManager.notify(NOTIFICATION_ID, notification.build())
    }

    // Built once: the end-call action is the same PendingIntent from the
    // notification built at startup to every update that follows.
    private val endCallPendingIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this, 1,
            buildIntent(this, ACTION_END_CALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getStateText(state: ai.flossyapp.domain.model.VoiceCallPhase): String = when (state) {
        STATE_LISTENING -> "Listening..."
        STATE_PROCESSING -> "Processing..."
        STATE_SPEAKING -> "Speaking..."
        else -> "Idle"
    }

    // ==================== Error Handling ====================

    private fun handleError(message: String) {
        updateNotification("Error: $message")
        scheduleErrorRecovery()
    }

    /**
     * Schedule the recovery after an error. The engine keeps the capture live
     * through an exchange failure, so the service goes back to LISTENING -
     * recovering to IDLE would leave the foreground service dead until the
     * user restarts it.
     */
    private fun scheduleErrorRecovery() {
        if (!serviceScope.isActive) return
        serviceScope.launch {
            delay(3000L)
            // Only a pipeline that just failed is recovered - a plain IDLE
            // means nothing went wrong and there is nothing to re-arm.
            if (_state.value == STATE_IDLE && errorText != null) {
                val engine = runCatching {
                    ai.flossyapp.ui.common.VoiceCallEngine.instance(applicationContext)
                }.getOrNull()
                val resume = engine != null &&
                    (engine.mode == ai.flossyapp.ui.common.VoiceCallEngine.Mode.IDLE ||
                     engine.isAppHost(assistantExchangeHost)) && engine.callActive
                _state.value = if (resume) STATE_LISTENING else STATE_IDLE
                updateNotification(if (resume) "Listening..." else "Idle")
                errorText = null
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // A voice assistant lives through the app being swiped away - the
        // hands-free path is the whole point of a foreground service. The user
        // can ask for the opposite with the swipe-away switch in the settings,
        // and then the task being removed ends the listening instead.
        val stop = runCatching { settingsRepository.loadSettings().stopServicesOnTaskRemoved }
            .getOrDefault(false)
        if (stop) {
            android.util.Log.d("VoiceAssistantService", "Task removed: stopping the service as the settings ask")
            // The service may be dying with no scope to launch on: keep the
            // stop work on a detached coroutine so the join and TTS wait
            // never touch the main thread.
            kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                stopListening()
                stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Called when the service is being destroyed: releases the mic back to
     * the engine and updates the notification to the stopped state.
     */
    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("VoiceAssistantService", "Service destroying - performing graceful shutdown")
        isRunning = false
        connectionWaitingScope.cancel()
        // onDestroy may be called on the main thread: the stop waits on the
        // engine's hang-up, so it must not block here.
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { stopListening() }
    }
    
    // ==================== Wake Word Detection ====================
    //
    // This service has no wake matching of its own - the hands-free pipeline lives in
    // WakeChatService, which matches clips on-device against the enrolled references
    // (see WakeWordDetector). An on-device keyword runtime would plug in there, and its
    // licence and on-device footprint must be checked before adding one.

}
