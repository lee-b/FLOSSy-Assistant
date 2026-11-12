package ai.flossyapp.ui.wake

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import android.os.Build
import ai.flossyapp.service.WakeChatService

/**
 * Starts and stops the wake-word pipeline.
 *
 * The pipeline runs in [WakeChatService], a foreground service that keeps
 * listening while the app is minimized, so its state cannot live in a view model
 * that only exists while the app is open. This class holds none of it: it only
 * asks the service to start or stop.
 *
 * It renders nothing. The wake chat has one rendering - the voice-interaction
 * window the system draws over every screen, which covers this app's own surface
 * too - so there is no card here to forward the exchange to.
 */
class WakeChatViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    @Volatile
    private var serviceRunning = false

    /**
     * Start the wake-chat service. Idempotent so navigation re-entry cannot ask
     * for several instances.
     */
    fun startListening() {
        if (serviceRunning) return
        serviceRunning = true
        send(WakeChatService.ACTION_START)
    }

    fun stopListening() {
        if (!serviceRunning) return
        serviceRunning = false
        send(WakeChatService.ACTION_STOP)
    }

    private fun send(action: String) {
        val intent = WakeChatService.buildIntent(context, action)
        // A foreground service is the only way to keep the microphone loop alive
        // once the app is minimized, so the pre-API-23 path is a fallback only.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
