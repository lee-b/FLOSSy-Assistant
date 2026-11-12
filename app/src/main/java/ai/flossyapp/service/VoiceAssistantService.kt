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
import ai.flossyapp.data.tools.ToolRegistry
import ai.flossyapp.ui.common.preferredCaptureDevice
import ai.flossyapp.ui.common.isNonSpeechTranscript
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
        
        // Retry configuration for API calls
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L

        // Sample rate used while the battery is low
        private const val ENERGY_SAVING_SAMPLE_RATE = 8000

        // Longest utterance kept before it is sent to STT
        private const val MAX_UTTERANCE_SECONDS = 60
        
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
    
    // Audio level for notification indicator (0-100)
    @Volatile
    private var currentAudioLevel = 0

    // Audio recording configuration (16kHz, mono, 16-bit PCM)
    private var sampleRate = 16000
    private var channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    // Rate the device accepted at startup - energy saving restores to this, not to 16kHz.
    private var startupSampleRate = 16000
    
    // Energy-saving mode state
    @Volatile
    private var energySavingMode = false
    
    // VAD (Voice Activity Detection) configuration - loaded from settings on start
    private var vadSensitivity: Float = 0.4f
    private var vadMinSilenceDurationMs: Long = 800L
    
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
    
    // Audio recording
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    
    // Buffer overflow protection
    @Volatile
    private var framesDroppedCount = 0
    private val maxPendingFrames = 5  // Max frames to wait for processing before dropping old ones
    
    // Audio playback
    private var audioTrack: android.media.AudioTrack? = null
    
    @Volatile
    private var isTtsPlaying = false
    
    // Notification manager
    private lateinit var notificationManager: NotificationManager

    // Audio manager, for the capture-route watch.
    private lateinit var audioManager: AudioManager

    // The restore runnable for a communication capture route opened in
    // startListening(); closed again in stopListening().
    // Watches the input devices while listening, so pulling or connecting a
    // headset mid-utterance re-routes capture instead of leaving the recorder
    // on a device that has gone away. One instance, registered per listen.
    private val captureDeviceCallback = object : AudioDeviceCallback() {
        private fun rerouteIfListening() {
            if (_state.value == STATE_LISTENING) {
                serviceScope.launch { rerouteCapture() }
            }
        }

        // The capture device the route is currently on, tracked so a loss can
        // be told apart from any other device change. Null means the built-in
        // mic - there is nothing to lose there, and losing it re-routes.
        @Volatile
        private var routedDeviceType: Int = AudioDeviceInfo.TYPE_BUILTIN_MIC

        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            rerouteIfListening()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            val wasRouted = removedDevices.any { it.type == routedDeviceType }
            if (!wasRouted) {
                rerouteIfListening()
                return
            }
            // The device capture rides went away mid-listen.
            if (routedDeviceType == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                // The phone's own mic cannot vanish; a callback naming it is a
                // framework hiccup, not a lost device - re-establish the route.
                rerouteIfListening()
                return
            }
            if (routedDeviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                // A private headset conversation must never continue on the
                // loudspeaker: the headset going away while it carried the call
                // is the user hanging up, so the call ends - it does not fall
                // back to the phone mic and speaker. A switch TO a headset is
                // a connection, not a loss, and the re-route above serves it.
                android.util.Log.d("VoiceAssistantService", "Bluetooth capture device lost: hanging up the call")
                serviceScope.launch {
                    stopListening()
                    stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return
            }
            // A wired or USB headset that vanished: the phone mic is private
            // enough, so re-establish and carry on.
            rerouteIfListening()
        }

        // Called from rerouteCapture() once the recorder swap has settled, so
        // the next device-change event is judged against the new device.
        fun noteRoutedDevice(info: AudioDeviceInfo?) {
            routedDeviceType = info?.type ?: AudioDeviceInfo.TYPE_BUILTIN_MIC
        }
    }

    // Watches the API client's rate-limit waiting state for the whole service
    // lifetime; a cancelled serviceScope would kill the watch on every restart.
    private val connectionWaitingScope = CoroutineScope(Dispatchers.Default + Job())

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        
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

        // Register battery receiver for energy-saving mode
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val initialIntent = registerReceiver(batteryReceiver, filter)
            // registerReceiver() returns the current battery intent, so the level can be
            // read once here without registering a second anonymous receiver.
            checkBatteryLevel(initialIntent)
        } catch (e: Exception) {
            android.util.Log.w("VoiceAssistantService", "Failed to register battery receiver: ${e.message}")
        }
    }

    // The battery-optimization exemption used to be requested here. The platform rejects
    // startActivity() from a service process, so the Settings screen owns that request now
    // that it runs in an app context.
    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (VERSION.SDK_INT >= VERSION_CODES.M) {
                // Use reflection to check battery optimization status
                val method = pm.javaClass.getMethod("isIgnoringBatteryOptimations", String::class.java)
                val isExempted = method.invoke(pm, packageName) as Boolean
                
                if (!isExempted) {
                    android.util.Log.w(
                        "VoiceAssistantService",
                        "Battery optimization is not exempted - Doze may suspend this service mid-listen. " +
                            "The Settings screen offers the exemption request, which the platform accepts only from an app context.",
                    )
                } else {
                    android.util.Log.d("VoiceAssistantService", "Already exempted from battery optimizations")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("VoiceAssistantService", "Battery optimization check failed: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
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
     * Off the main thread: stopListening() waits for TTS and joins the
     * recording thread, and doing that here froze the input dispatch long
     * enough to ANR the app.
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

    /**
     * Validate that the device supports the required audio configuration.
     * If not supported, log warnings and fall back to lower quality settings.
     * Only mono PCM is negotiated - convertToWav and the STT endpoint both assume a
     * single 16-bit channel, so a stereo stream would be transcribed from interleaved samples.
     */
    private fun validateAudioConfiguration() {
        val originalSampleRate = 16000
        val originalChannelConfig = AudioFormat.CHANNEL_IN_MONO
        
        // Check if the preferred sample rate is supported
        var minBufferSize = AudioRecord.getMinBufferSize(
            originalSampleRate, originalChannelConfig, audioFormat
        )
        
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
            android.util.Log.w("VoiceAssistantService", "Preferred sample rate ${originalSampleRate}Hz not supported. Falling back...")
            
            // Try 11kHz as fallback
            minBufferSize = AudioRecord.getMinBufferSize(
                11025, originalChannelConfig, audioFormat
            )
            if (minBufferSize > 0) {
                sampleRate = 11025
                startupSampleRate = 11025
                android.util.Log.w("VoiceAssistantService", "Falling back to 11kHz sample rate")
            } else {
                // Try 8kHz as last resort
                minBufferSize = AudioRecord.getMinBufferSize(
                    8000, originalChannelConfig, audioFormat
                )
                if (minBufferSize > 0) {
                    sampleRate = 8000
                    startupSampleRate = 8000
                    android.util.Log.w("VoiceAssistantService", "Falling back to 8kHz sample rate")
                } else {
                    android.util.Log.e("VoiceAssistantService", "No supported sample rate found for mono PCM")
                    throw RuntimeException("No supported audio configuration found")
                }
            }
        }
    }

    /**
     * Rebuild the AudioRecord at a different sample rate. The recorder is created once
     * at startup, so changing sampleRate alone would make convertToWav stamp a header
     * that does not match the captured stream.
     */
    private fun applySampleRate(newRate: Int) {
        if (newRate == sampleRate) return
        val previous = audioRecord ?: return
        try {
            val bufferSize = AudioRecord.getMinBufferSize(newRate, channelConfig, audioFormat)
            if (bufferSize <= 0) {
                android.util.Log.w("VoiceAssistantService", "Rate ${newRate}Hz not supported, keeping ${sampleRate}Hz")
                return
            }
            val replacement = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                newRate, channelConfig, audioFormat, bufferSize * 4
            )
            previous.stop()
            previous.release()
            replacement.startRecording()
            audioRecord = replacement
            sampleRate = newRate
        } catch (e: Exception) {
            android.util.Log.w("VoiceAssistantService", "Could not switch to ${newRate}Hz: ${e.message}")
        }
    }

    // ==================== Lifecycle Methods ====================

    /**
     * Start the voice assistant pipeline.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun startListening() {
        if (_state.value == STATE_IDLE) {
            _state.value = STATE_LISTENING
            // A previous stopListening() cancelled the scope; resumeService()/the
            // home-screen widget can start again without the service being recreated.
            if (!serviceScope.isActive) serviceScope = newServiceScope()
            
            // Load VAD settings from repository before starting
            try {
                val currentSettings = settingsRepository.loadSettings()
                vadSensitivity = currentSettings.vadSensitivity
                vadMinSilenceDurationMs = currentSettings.vadMinSilenceDurationMs.toLong()
            } catch (e: Exception) {
                android.util.Log.w("VoiceAssistantService", "Failed to load VAD settings, using defaults")
            }
            
            try {
                // Validate and potentially adjust audio configuration for device compatibility
                validateAudioConfiguration()

                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate, channelConfig, audioFormat
                ) ?: throw RuntimeException("Cannot get min buffer size")
                
                android.util.Log.d("VoiceAssistantService", "Using audio config: ${sampleRate}Hz, " +
                    "channels=${if (channelConfig == AudioFormat.CHANNEL_IN_MONO) "mono" else "stereo"}, " +
                    "format=${if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) "16-bit PCM" else "8-bit PCM"}")
                
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate, channelConfig, audioFormat, bufferSize * 4
                )

                // The recorder is steered at the headset input when one exists;
                // no communication route is set or cleared - that toggled the
                // SCO link on every listen, yanking the headset's volume and
                // stuttering whatever media was playing. The start cue blocks,
                // so it plays before the mic opens.
                playListenStartCue()
                // The device the call starts on decides what "the device went
                // away" means later: a Bluetooth loss hangs up, anything else
                // re-establishes.
                captureDeviceCallback.noteRoutedDevice(preferredCaptureDevice(applicationContext))
                audioManager.registerAudioDeviceCallback(captureDeviceCallback, android.os.Handler(android.os.Looper.getMainLooper()))

                if (audioRecord != null) {
                    audioRecord?.startRecording()
                    
                    recordingThread = Thread(this::processAudioLoop)
                    recordingThread?.start()
                    
                    // Start audio level updater coroutine
                    serviceScope.launch {
                        while (_state.value == STATE_LISTENING && serviceScope.isActive) {
                            updateNotification("Listening... ${currentAudioLevel}%")
                            delay(500L)
                        }
                    }
                } else {
                                        throw RuntimeException("Failed to initialize audio recorder")
                }
            } catch (e: Exception) {
                // The AudioRecord may already exist when a later step failed - native
                // resources must be released or they stay alive until the process dies.
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                                handleError(e.message ?: "Audio initialization failed")
            }
        }
    }

    /**
     * Stop the voice assistant with graceful shutdown.
     * Waits for current TTS playback to finish, then properly cleans up resources.
     */
    private suspend fun stopListening() {
        currentAudioLevel = 0
        
        // If TTS is currently playing, wait briefly for it to finish before cancelling
        if (isTtsPlaying) {
            android.util.Log.d("VoiceAssistantService", "Waiting for TTS playback to complete...")
            try {
                Thread.sleep(2000L) // Wait up to 2 seconds for current speech to finish
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        
        // Cancel the coroutine scope to stop all background tasks, then start a fresh
        // one: a cancelled scope can never launch again, so the next startListening()
        // would otherwise throw IllegalStateException.
        serviceScope.cancel()
        serviceScope = newServiceScope()
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignore release errors
        }
        audioRecord = null
        audioTrack = null
        audioManager.unregisterAudioDeviceCallback(captureDeviceCallback)

        // The matched downbeat closes the listening window.
        playListenEndCue()

        recordingThread?.join(5000)
        recordingThread = null
        
        _state.value = STATE_IDLE
        updateNotification("Stopped")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun resumeService() {
        startListening()
    }

    /**
     * Re-resolves the capture route after the input devices changed while
     * listening - a headset was pulled or connected mid-utterance.
     *
     * The recorder swap follows the same pattern as [applySampleRate]: the new
     * AudioRecord is started before the old one is released, so the audio loop
     * that reads [audioRecord] keeps going without a gap to notice.
     */
    private suspend fun rerouteCapture() {
        // The new recorder may land on a different device than the old one;
        // remember which, so the next removal is judged against the device
        // capture actually rides now.
        val routedNow = preferredCaptureDevice(applicationContext)
        captureDeviceCallback.noteRoutedDevice(routedNow)
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) return

        try {
            val replacement = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 4
            )
            if (replacement.state != AudioRecord.STATE_INITIALIZED) {
                replacement.release()
                return
            }
            // Steer the replacement recorder at the headset input like the
            // original was, without touching any global routing or volume.
            preferredCaptureDevice(applicationContext)?.let { device ->
                runCatching { replacement.setPreferredDevice(device) }
            }
            replacement.startRecording()
            val previous = audioRecord
            try {
                previous?.stop()
                previous?.release()
            } catch (_: Exception) {}
            audioRecord = replacement
            android.util.Log.d("VoiceAssistantService", "Capture re-routed after audio device change")
        } catch (e: Exception) {
            android.util.Log.w("VoiceAssistantService", "Could not re-route capture: ${e.message}")
        }
    }

    private suspend fun pauseService() {
        stopListening()
    }

    /**
     * Check if the device has an active network connection with internet access.
     * Returns true only if there's a working network (WiFi or mobile data).
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        // VALIDATED only says the stack probed the network (captive portal), not that
        // requests can reach the local endpoints, so it must not gate an API call.
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /**
     * Check network availability before making API calls.
     * Shows an error and returns early if offline.
     */
    private fun checkNetworkAndHandle(): Boolean {
        if (!isNetworkAvailable()) {
                        updateNotification("⚠️ No network connection. Check your internet.")
            android.util.Log.w("VoiceAssistantService", "API call attempted but no network available")
            scheduleErrorRecovery()
            return false
        }
        return true
    }

    // ==================== Audio Processing Loop ====================

    /**
     * Main audio processing loop that runs in a background thread.
     * Wrapped with crash recovery - if the loop crashes unexpectedly, it will restart automatically.
     * Includes buffer overflow protection to drop frames when processing is slower than recording.
     */
    private fun processAudioLoop() {
        val buffer = ShortArray(4096)
        // Accumulate the full utterance so the complete recording can be sent to STT
        val speechBuffer = ArrayDeque<Short>()
        // Same 60s bound CommonUtilities.recordAudioWithVAD uses - without it a
        // continuous noise source grows the deque until the process runs out of memory.
        val maxUtteranceSamples = sampleRate * MAX_UTTERANCE_SECONDS
        var inSpeech = false
        var silenceStart = 0L
        
        while (_state.value != STATE_IDLE && serviceScope.isActive) {
            try {
                // Buffer overflow protection: drop frames if processing is slower than recording.
                // This prevents AudioRecord's internal buffer from overflowing when the device
                // can't keep up with audio capture (e.g., during heavy LLM/TTS workloads).
                if (shouldDropFrame()) {
                    android.util.Log.d("VoiceAssistantService", "Dropping frame - processing backlog")
                    Thread.sleep(50) // Brief pause to let processing catch up
                    continue
                }

                val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                
                if (readCount > 0) {
                    // Mean-square energy, the same scale CommonUtilities.recordAudioWithVAD
                    // uses - averaging absolute amplitudes would cap RMS at ~181 and never
                    // reach the vadSensitivity threshold.
                    var sumOfSquares = 0L
                    for (i in 0 until readCount) {
                        val sample = buffer[i].toLong()
                        sumOfSquares += sample * sample
                    }
                    val rms = kotlin.math.sqrt(sumOfSquares.toDouble() / readCount).toFloat()
                    
                    // Convert RMS to 0-100% audio level indicator
                    val levelPercent = ((rms / Short.MAX_VALUE.toFloat()) * 100f).toInt().coerceIn(0, 100)
                    currentAudioLevel = levelPercent
                    
                    val isVoice = rms > vadSensitivity * Short.MAX_VALUE.toFloat()
                    
                    if (isVoice) {
                        // Start (or continue) capturing the utterance
                        if (!inSpeech) {
                            inSpeech = true
                            speechBuffer.clear()
                            android.util.Log.d("VoiceAssistantService", "Speech started")
                        }
                        for (i in 0 until readCount) {
                            speechBuffer.add(buffer[i])
                        }
                        while (speechBuffer.size > maxUtteranceSamples) speechBuffer.removeFirst()
                        silenceStart = 0L
                    } else if (inSpeech) {
                        // Keep trailing silence in the buffer for natural transcription
                        for (i in 0 until readCount) {
                            speechBuffer.add(buffer[i])
                        }
                        while (speechBuffer.size > maxUtteranceSamples) speechBuffer.removeFirst()
                        if (silenceStart == 0L) {
                            silenceStart = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - silenceStart > vadMinSilenceDurationMs) {
                            // End of speech detected - process the complete utterance
                            val utterance = speechBuffer.toShortArray()
                            speechBuffer.clear()
                            inSpeech = false
                            silenceStart = 0L
                            currentAudioLevel = 0
                            // A quarter-second is the shortest span that can
                            // carry a word; shorter blips are clicks and echoes
                            // that STT only answers with a blank transcript.
                            if (utterance.size >= sampleRate / 4) {
                                processUtterance(utterance)
                            }
                        }
                    }
                } else if (readCount == 0) {
                    // No data available - brief sleep to avoid busy-waiting
                    Thread.sleep(10)
                }
            } catch (e: Exception) {
                                android.util.Log.e("VoiceAssistantService", "Audio processing error, restarting loop...", e)
                updateNotification("Error: ${e.message}. Restarting...")
                
                // Brief pause before restart to avoid rapid crash loops
                try {
                    Thread.sleep(2000L)
                } catch (_: InterruptedException) {
                    return
                }
                
                // Reset state and continue listening if not explicitly stopped
                if (_state.value != STATE_IDLE && serviceScope.isActive) {
                    _state.value = STATE_LISTENING
                    updateNotification("Listening... (recovered)")
                    silenceStart = 0L
                    currentAudioLevel = 0
                    // Continue loop - the while condition will re-evaluate
                } else {
                    break
                }
            }
        }
    }

    /**
     * Process a complete utterance: convert to WAV, transcribe via the STT
     * endpoint, respond with the LLM and speak the response with TTS.
     *
     * @param buffer The full accumulated speech samples (16-bit mono PCM).
     */
    private fun processUtterance(buffer: ShortArray) {
        if (buffer.isEmpty()) return
        
        _state.value = STATE_PROCESSING
        updateNotification("Processing...")
        
        serviceScope.launch {
            try {
                // Convert the full utterance to WAV bytes for the STT endpoint
                val wavBytes = convertToWav(buffer, buffer.size)
                
                // Get settings from SettingsRepository
                val currentSettings = settingsRepository.loadSettings()
                val sttBaseUrl = currentSettings.sttBaseUrl
                val apiKey = currentSettings.sttApiKey
                val model = currentSettings.sttModelName
                
                // Check network before API call
                if (!checkNetworkAndHandle()) return@launch
                
                // Send to STT endpoint with retry logic
                val transcribedText = try {
                    executeWithRetry(
                        operationName = "STT",
                        maxRetries = MAX_RETRIES,
                        delayMs = RETRY_DELAY_MS
                    ) {
                        ApiClient.transcribeAudio(
                            baseUrl = sttBaseUrl,
                            apiKey = apiKey,
                            audioData = wavBytes,
                            model = model,
                            timeoutSeconds = currentSettings.httpTimeoutSeconds.toLong()
                        )
                    }
                } catch (e: Exception) {
                    handleError("STT failed after $MAX_RETRIES retries: ${e.message}")
                    return@launch
                }
                
                if (isNonSpeechTranscript(transcribedText)) {
                    android.util.Log.d("VoiceAssistantService", "Ignoring non-speech transcript: $transcribedText")
                    _state.value = STATE_LISTENING
                    updateNotification("Listening...")
                    return@launch
                }

                _currentMessage.value = transcribedText
                processWithLLMAndRespond(transcribedText)
            } catch (e: Exception) {
                handleError("Voice processing error: ${e.message}")
            }
        }
    }

    /**
     * Check if the audio processing pipeline is falling behind.
     * Returns true if we should drop this frame to avoid buffer overflow.
     */
    private fun shouldDropFrame(): Boolean {
        // If service is already processing and we have many pending frames, drop new ones
        if (_state.value == STATE_PROCESSING) {
            framesDroppedCount++
            if (framesDroppedCount >= maxPendingFrames) {
                android.util.Log.w("VoiceAssistantService", "Audio buffer overflow: dropped $framesDroppedCount frames. Processing is slower than recording.")
                return true
            }
        } else {
            // Reset counter when not processing
            if (framesDroppedCount > 0) {
                android.util.Log.d("VoiceAssistantService", "Recovered from buffer overflow, dropped $framesDroppedCount frames")
            }
            framesDroppedCount = 0
        }
        return false
    }

    /**
     * Process user input through LLM and respond with TTS.
     */
    private suspend fun processWithLLMAndRespond(userInput: String) {
        _state.value = STATE_PROCESSING
        
        // Get settings from SettingsRepository
        val currentSettings = settingsRepository.loadSettings()
        val llmBaseUrl = currentSettings.llmBaseUrl
        val apiKey = currentSettings.llmApiKey
        val model = currentSettings.llmModelName
        
        try {
            // Build conversation history for context
            val messagesList = listOf(
                mapOf("role" to "user", "content" to userInput)
            )
            
            // Check network before LLM call
            if (!checkNetworkAndHandle()) return
            
            var responseText = ""
            try {
                val response = executeWithRetry(
                    operationName = "LLM",
                    maxRetries = MAX_RETRIES,
                    delayMs = RETRY_DELAY_MS
                ) {
                    ApiClient.chatCompletionWithTools(
                        baseUrl = llmBaseUrl,
                        apiKey = apiKey,
                        messages = messagesList,
                        model = model,
                        stream = false,
                        maxTokens = currentSettings.llmMaxTokens,
                        reasoningBudgetTokens = currentSettings.llmReasoningBudgetTokens,
                        chatTemplateKwargs = currentSettings.llmChatTemplateKwargs,
                        systemPrompt = currentSettings.systemPrompt,
                        // Every tool - internal handlers, search engines and
                        // MCP servers - flows through the one registry seam;
                        // "end_call" ends this interaction the same way the
                        // notification's End call does.
                        toolDescriptors = ToolRegistry.advertisedTools(currentSettings) { endCallFromTool() },
                        // The answer to a tool call is fetched here so the loop in the API client
                        // can hand the result back to the model and ask again in the same exchange.
                        runTool = { name: String, argumentsJson: String ->
                            ToolRegistry.runNamedTool(currentSettings, name, argumentsJson) { endCallFromTool() }
                        },
                        timeoutSeconds = currentSettings.httpTimeoutSeconds.toLong()
                    )
                }
                responseText = response.content
            } catch (e: Exception) {
                throw RuntimeException("LLM call failed after $MAX_RETRIES retries: ${e.message}")
            }
            
            if (responseText.isNotEmpty()) {
                _currentMessage.value = responseText
                
                // Synthesize speech and play it back
                _state.value = STATE_SPEAKING
                updateNotification("Speaking...")
                
                // Check network before TTS call
                if (!checkNetworkAndHandle()) return
                
                // One speech seam for the whole app: playTTSAudio applies the
                // same speakableText filter (markdown asterisks, emoji, foreign
                // scripts) the engine and the chat screen speak through, so no
                // path can hand the TTS characters it reads out loud.
                try {
                    ai.flossyapp.ui.common.playTTSAudio(
                        responseText,
                        Pair(currentSettings.ttsBaseUrl, if (currentSettings.ttsApiKey.isBlank()) null else currentSettings.ttsApiKey),
                        model = currentSettings.ttsModelName,
                        voice = currentSettings.ttsVoiceName,
                        responseFormat = currentSettings.ttsResponseFormat,
                        enableStreaming = currentSettings.enableTtsStreaming,
                        timeoutSeconds = currentSettings.httpTimeoutSeconds.toLong(),
                        forbidNonLatin = currentSettings.forbidNonLatinTts
                    )
                } catch (e: Exception) {
                    handleError("TTS failed: ${e.message}")
                }
                
                // Return to listening mode if continuous conversation is enabled
                if (_state.value != STATE_IDLE) {
                    _state.value = STATE_LISTENING
                    updateNotification("Listening...")
                }
            } else {
                handleError("Empty LLM response")
            }
        } catch (e: Exception) {
            handleError("Response processing error: ${e.message}")
        }
    }

    // ==================== Audio Helpers ====================

    /**
     * Convert short buffer to WAV format bytes.
     */
    private fun convertToWav(buffer: ShortArray, count: Int): ByteArray {
        val byteCount = count * 2 // 16-bit samples
        val wavHeader = ByteArray(44)
        
        // Write RIFF header
        var offset = 0
        writeString(wavHeader, "RIFF", offset); offset += 4
        intToLittleEndian(byteCount + 36, wavHeader, offset); offset += 4
        writeString(wavHeader, "WAVE", offset); offset += 4
        
        // Write fmt chunk
        writeString(wavHeader, "fmt ", offset); offset += 4
        intToLittleEndian(16, wavHeader, offset); offset += 4 // Subchunk size
        shortToLittleEndian(1, wavHeader, offset); offset += 2 // PCM format
        shortToLittleEndian(1, wavHeader, offset); offset += 2 // Mono
        intToLittleEndian(sampleRate, wavHeader, offset); offset += 4
        intToLittleEndian(sampleRate * 2, wavHeader, offset); offset += 4 // Byte rate
        shortToLittleEndian(2, wavHeader, offset); offset += 2 // Block align
        shortToLittleEndian(16, wavHeader, offset); offset += 2 // Bits per sample
        
        // Write data chunk
        writeString(wavHeader, "data", offset); offset += 4
        intToLittleEndian(byteCount, wavHeader, offset)
        
        // Convert shorts to bytes (little-endian format for WAV)
        val audioBytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val sample = buffer[i].toInt()
            audioBytes[i * 2] = sample.toByte()           // Low byte
            audioBytes[i * 2 + 1] = (sample ushr 8).toByte() // High byte
        }
        
        return wavHeader + audioBytes
    }
    
    private fun writeString(bytes: ByteArray, s: String, pos: Int) {
        for (i in s.indices) bytes[pos + i] = s[i].code.toByte()
    }
    
    private fun intToLittleEndian(value: Int, bytes: ByteArray, pos: Int) {
        bytes[pos] = value.toByte()
        bytes[pos + 1] = (value shr 8).toByte()
        bytes[pos + 2] = (value shr 16).toByte()
        bytes[pos + 3] = (value shr 24).toByte()
    }
    
    private fun shortToLittleEndian(value: Int, bytes: ByteArray, pos: Int) {
        bytes[pos] = value.toByte()
        bytes[pos + 1] = (value shr 8).toByte()
    }

    /**
     * Play audio bytes through the system speaker using AudioTrack.
     * TTS endpoints return WAV files - the header is parsed to get the real
     * sample rate and stripped before playback.
     */
    private suspend fun playAudio(audioBytes: ByteArray) {
        // stopListening() waits while this flag is set so an in-flight response
        // finishes before the foreground service tears down.
        isTtsPlaying = true
        try {
            ai.flossyapp.ui.common.playAudioBytes(audioBytes)
        } finally {
            isTtsPlaying = false
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
     * Schedule the recovery after an error. The audio loop ends the session on
     * STATE_IDLE, so a live recording thread must go back to LISTENING - recovering
     * to IDLE would leave the foreground service dead until the user restarts it.
     */
    private fun scheduleErrorRecovery() {
        if (!serviceScope.isActive) return
        serviceScope.launch {
            delay(3000L)
            // Only a pipeline that just failed is recovered - a plain IDLE
            // means nothing went wrong and there is nothing to re-arm.
            if (_state.value == STATE_IDLE && errorText != null) {
                val resume = recordingThread?.isAlive() == true
                _state.value = if (resume) STATE_LISTENING else STATE_IDLE
                updateNotification(if (resume) "Listening..." else "Idle")
                errorText = null
            }
        }
    }

    /**
     * Execute a suspend operation with retry logic for transient failures.
     *
     * @param operationName Name of the operation (for logging)
     * @param maxRetries Maximum number of retry attempts
     * @param delayMs Delay in milliseconds between retries
     * @param block The suspend function to execute
     * @return The result of the successful execution
     * @throws Exception if all retries are exhausted
     */
    private suspend fun <T> executeWithRetry(
        operationName: String,
        maxRetries: Int = MAX_RETRIES,
        delayMs: Long = RETRY_DELAY_MS,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    _state.value = STATE_PROCESSING
                    updateNotification("$operationName retry $attempt/$maxRetries...")
                    delay(delayMs)
                }
            }
        }
        throw RuntimeException("$operationName failed after $maxRetries attempts: ${lastException?.message}", lastException)
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
     * Called when the service is being destroyed.
     * Performs graceful shutdown: stops recording, cancels TTS playback,
     * and updates notification to "Stopped" state.
     */
    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("VoiceAssistantService", "Service destroying - performing graceful shutdown")
        isRunning = false
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Receiver may not have been registered
        }
        connectionWaitingScope.cancel()
        // onDestroy may be called on the main thread: the stop waits on TTS
        // and joins the recording thread, so it must not block here.
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { stopListening() }
    }
    
    // ==================== Wake Word Detection ====================
    //
    // This service has no wake matching of its own - the hands-free pipeline lives in
    // WakeChatService, which matches clips on-device against the enrolled references
    // (see WakeWordDetector). An on-device keyword runtime would plug in there, and its
    // licence and on-device footprint must be checked before adding one.

    // ==================== Energy-Saving Mode ====================

    /**
     * BroadcastReceiver that monitors battery level changes.
     * Automatically reduces audio quality when battery is low (<20%).
     */
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                checkBatteryLevel(intent)
            }
        }
    }

    /**
     * Check battery level and adjust audio quality accordingly.
     * When battery < 20%: reduce sample rate to 8kHz for energy saving.
     * When charging or battery >= 20%: restore the rate negotiated at startup.
     */
    private fun checkBatteryLevel(intent: Intent?) {
        try {
            if (intent == null) return
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 100)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING

            val batteryPercent = (level * 100 / scale.toFloat()).toInt()

            if (isCharging) {
                // Restore normal settings when charging
                if (energySavingMode) {
                    energySavingMode = false
                    applySampleRate(startupSampleRate)
                    android.util.Log.d("VoiceAssistantService", "Battery charging - restoring normal audio quality")
                    updateNotification("Normal mode - Charging")
                }
            } else if (batteryPercent < 20 && !energySavingMode) {
                // Enter energy-saving mode
                energySavingMode = true
                applySampleRate(ENERGY_SAVING_SAMPLE_RATE)
                android.util.Log.d("VoiceAssistantService", "Low battery ($batteryPercent%) - entering energy-saving mode (${ENERGY_SAVING_SAMPLE_RATE}Hz)")
                updateNotification("Energy saving mode - $batteryPercent%")
            } else if (!energySavingMode && batteryPercent >= 20) {
                // Normal operation
                android.util.Log.d("VoiceAssistantService", "Battery level: $batteryPercent% - normal operation")
            }
        } catch (e: Exception) {
            android.util.Log.w("VoiceAssistantService", "Failed to check battery level: ${e.message}")
        }
    }
}
