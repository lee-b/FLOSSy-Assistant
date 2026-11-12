package ai.flossyapp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.composable
import ai.flossyapp.R
import ai.flossyapp.data.settings.SettingsRepository
import ai.flossyapp.ui.chat.ChatScreen
import ai.flossyapp.ui.settings.SettingsScreen
import ai.flossyapp.ui.theme.FLOSSyAssistantTheme
import ai.flossyapp.ui.wake.WakeChatViewModel
import android.view.KeyEvent
import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Main activity for the FLOSSy Assistant app.
 * 
 * Hosts the navigation graph and permission handling.
 */
class MainActivity : ComponentActivity() {
    
    companion object {
        const val VOICE_COMMAND_ACTION = "ai.flossyapp.VOICE_COMMAND"
        const val EXTRA_COMMAND_TEXT = "command_text"
        private const val PERMISSION_REQUEST_CODE = 1001
        
        // Holds the last voice command for MainNavigation to pick up
        var _lastVoiceCommand: MutableStateFlow<String?> = MutableStateFlow(null)
    }
    
    // Use Compose State so UI recomposes when permissions change
    var hasPermissions by mutableStateOf(false)
        private set
    
    /**
     * Check if all required runtime permissions are granted.
     */
    fun hasRequiredPermissions(): Boolean {
        val audioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        
        val notificationGranted = if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // POST_NOTIFICATIONS not required below API 33
        }
        
        return audioGranted && notificationGranted
    }
    
    /**
     * Request runtime permissions if not already granted.
     */
    fun requestPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()
        
        // Always need RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        
        // POST_NOTIFICATIONS required from API 33+
        if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }
    
    /**
     * Handle hardware key events for mic button shortcuts.
     * - Press and hold a media key (e.g., headset mic button) to start voice recording
     * - Release to send the recorded audio
     */
    override fun onKeyDown(requestCode: Int, event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            // Media key down - start listening after a short delay (to distinguish from tap)
            if (event.keyCode in listOf(
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_RECORD,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )) {
                android.util.Log.d("MainActivity", "Media key pressed, starting voice assistant...")
                // Start listening after 300ms hold to avoid accidental triggers
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                handler.postDelayed({
                    if (hasPermissions) {
                        startVoiceAssistant()
                    }
                }, 300L)
                return true
            }
        }
        if (event?.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
            // Media key up - stop listening and let the service handle sending
            if (event.keyCode in listOf(
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_RECORD,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )) {
                android.util.Log.d("MainActivity", "Media key released, stopping voice assistant...")
                stopVoiceAssistant()
                return true
            }
        }
        return super.onKeyDown(requestCode, event)
    }

    /**
     * Start the voice assistant service from MainActivity.
     */
    private fun startVoiceAssistant() {
        val intent = ai.flossyapp.service.VoiceAssistantService.buildIntent(
            this,
            ai.flossyapp.service.VoiceAssistantService.ACTION_START
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * Stop the voice assistant service from MainActivity.
     */
    private fun stopVoiceAssistant() {
        val intent = ai.flossyapp.service.VoiceAssistantService.buildIntent(
            this,
            ai.flossyapp.service.VoiceAssistantService.ACTION_STOP
        )
        startService(intent)
    }

    @Deprecated("Use Activity Result API instead")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Map grantResults to permission granted status
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            hasPermissions = allGranted && hasRequiredPermissions()
            if (!hasPermissions) {
                // User denied at least one required permission
                android.widget.Toast.makeText(
                    this,
                    "Required permissions were denied. Some features may not work.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Handle incoming intents with voice commands from other apps.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleVoiceCommandIntent(intent)
        handleWakeTriggerIntent(intent)
    }

    private fun handleVoiceCommandIntent(intent: Intent?) {
        if (intent?.action == VOICE_COMMAND_ACTION) {
            val commandText = intent.getStringExtra(EXTRA_COMMAND_TEXT)
            if (!commandText.isNullOrBlank()) {
                android.util.Log.d("MainActivity", "Received voice command: $commandText")
                _lastVoiceCommand.value = commandText
            }
        }
    }

    /**
     * Run one wake exchange straight away.
     *
     * This is the debug entry point: the shell cannot address the service itself,
     * because it declares android.permission.BIND_VOICE_INTERACTION and the
     * ServiceManager rejects a caller that does not hold that capability - which
     * rules out `am start-foreground-service`. An intent built here is sent by the
     * app, which does hold it, so the same action reaches the running service.
     */
    private fun handleWakeTriggerIntent(launchIntent: Intent?) {
        if (launchIntent?.action !=
            ai.flossyapp.service.WakeChatService.ACTION_TRIGGER_EXCHANGE
        ) return

        val wakeIntent = ai.flossyapp.service.WakeChatService.buildIntent(
            this,
            ai.flossyapp.service.WakeChatService.ACTION_TRIGGER_EXCHANGE
        )
        startForegroundService(wakeIntent)
    }

    /**
     * Detect the current system locale and return a user-friendly language name.
     */
    fun getCurrentLocaleName(): String {
        val locale = Locale.getDefault()
        return when (locale.language) {
            "es" -> getString(R.string.language_es)
            "fr" -> getString(R.string.language_fr)
            "de" -> getString(R.string.language_de)
            "zh" -> getString(R.string.language_zh)
            else -> getString(R.string.language_system_default)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check permissions on creation
        hasPermissions = hasRequiredPermissions()

        // Handle intent from launch (first start)
        handleVoiceCommandIntent(intent)
        handleWakeTriggerIntent(intent)

        // Observe settings so theme changes (e.g. dark mode toggle) apply live
        val settingsRepository = SettingsRepository(applicationContext)

        // Opening the app is a voice interaction, but the background service is
        // deliberately NOT started here: its replies never reach the chat screen
        // and it would fight the screen's own pipeline for the microphone. The
        // "Voice on Launch" switch is honoured by ChatScreen instead, which
        // opens a voice call with its own capture straight after composition.
        
        setContent {
            val showSplash = remember { mutableStateOf(true) }
            val settings by settingsRepository.settingsFlow.collectAsState()
            
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1000L)
                showSplash.value = false
            }
            
            AnimatedVisibility(
                visible = !showSplash.value,
                enter = fadeIn(tween(durationMillis = 300))
            ) {
                FLOSSyAssistantTheme(darkTheme = settings.darkModeEnabled) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainNavigation(hasPermissions = hasPermissions)
                        // The wake chat draws nothing here: its one rendering is the
                        // window the system shows over every screen, which covers
                        // the app's own surface too. This only keeps the service
                        // that feeds that window running.
                        WakeChatListening(
                            wakeEnabled = settings.enableWakeWordDetection,
                            hasPermissions = hasPermissions
                        )
                    }
                }
            }

            if (showSplash.value) {
                // Splash screen with fade-in animation
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                    content = {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(96.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                )
            }
        }
    }

}

/**
 * Loading screen shown while permissions are being checked.
 */
@Composable
private fun LoadingScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp),
            strokeWidth = 4.dp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = context.getString(R.string.loading_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics {
                heading()
            }
        )
    }
}

/**
 * Navigation container for the app.
 */
@Composable
fun MainNavigation(hasPermissions: Boolean) {
    val navController = androidx.navigation.compose.rememberNavController()
    val context = LocalContext.current
    val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<ai.flossyapp.ui.chat.ChatViewModel>()
    val chatSettings by viewModel.settings.collectAsState()

    // Observe incoming voice commands and add them to chat
    var lastCommand by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(Unit) {
        MainActivity._lastVoiceCommand.collect { cmd ->
            if (cmd != null) {
                lastCommand = cmd
                MainActivity._lastVoiceCommand.value = null
            }
        }
    }
    
    // Process voice command when received
    LaunchedEffect(lastCommand) {
        val command = lastCommand ?: return@LaunchedEffect
        MainActivity._lastVoiceCommand.value = null
        if (chatSettings.llmBaseUrl.isNotBlank()) {
            viewModel.sendMessage(
                text = command,
                llmBaseUrl = chatSettings.llmBaseUrl,
                apiKey = if (chatSettings.llmApiKey.isBlank()) null else chatSettings.llmApiKey,
                model = chatSettings.llmModelName
            )
        }
    }
    
    // Show loading screen briefly, then transition to main UI
    var showLoading by remember { mutableStateOf(true) }
    
    LaunchedEffect(Unit) {
        // Small delay to allow the loading screen to be visible
        delay(500L.milliseconds)
        showLoading = false
    }
    
    if (showLoading) {
        LoadingScreen()
    } else {
        androidx.navigation.compose.NavHost(
            navController = navController,
            startDestination = "chat",
            enterTransition = { fadeIn(animationSpec = tween(500)) }
        ) {
            composable("chat") {
                ChatScreen(
                    onNavigateToSettings = { 
                        performHapticFeedback(context)
                        navController.navigate("settings") 
                    },
                    hasPermissions = hasPermissions
                )
            }

            composable("settings") {
                SettingsScreen(
                    onNavigateBack = {
                        performHapticFeedback(context)
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}

/**
 * Keeps the wake-chat service running.
 *
 * It draws nothing. The wake chat has exactly one rendering - the voice-interaction
 * window the system shows over every screen, including this app's own surface - so
 * an app-level copy of the card would be a second, divergent UI. This composable
 * only starts the foreground service that feeds that window, which is why it stays
 * outside the navigation graph: the service must keep listening while the app is
 * minimized, and a screen inside the navigation graph would tear it down.
 */
@Composable
fun WakeChatListening(wakeEnabled: Boolean, hasPermissions: Boolean) {
    val wakeViewModel = androidx.lifecycle.viewmodel.compose.viewModel<ai.flossyapp.ui.wake.WakeChatViewModel>()

    LaunchedEffect(wakeEnabled, hasPermissions) {
        if (wakeEnabled && hasPermissions) {
            wakeViewModel.startListening()
        } else {
            wakeViewModel.stopListening()
        }
    }
}

/**
 * Perform haptic feedback on key UI interactions.
 */
private fun performHapticFeedback(context: android.content.Context) {
    try {
        // Check VIBRATE permission first
        @Suppress("DEPRECATION")
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.VIBRATE
            ) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator != null && vibrator.hasVibrator()) {
            // Use short haptic effect for API 26+
            @Suppress("MissingPermission")
            if (VERSION.SDK_INT >= VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else {
                @Suppress("DEPRECATION", "MissingPermission")
                vibrator.vibrate(50L)
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("MainActivity", "Haptic feedback failed: ${e.message}")
    }
}

/**
 * Get the minimum touch target size, scaled for accessibility.
 * Returns at least 48dp (Material Design standard) or larger if user has large font settings.
 */
@Composable
fun rememberMinTouchTargetSize(): Int {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    // Scale up touch targets if user has large accessibility settings
    return if (configuration.smallestScreenWidthDp <= 320) {
        with(density) { 56.dp.value.toInt() } // Larger targets for smaller screens
    } else {
        with(density) { 48.dp.value.toInt() }
    }
}
