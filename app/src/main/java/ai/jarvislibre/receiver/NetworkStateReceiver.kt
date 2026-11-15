package ai.jarvislibre.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ai.jarvislibre.service.VoiceAssistantService
import ai.jarvislibre.data.settings.SettingsRepository

/**
 * Broadcast receiver that monitors network connectivity.
 * Restarts the voice assistant service when network becomes available.
 *
 * The restart only reaches the assistant when wake detection is off: with
 * the wake chat enabled the wake loop is the listener, and an assistant
 * restarted on a network flap would take APP mode away from it - the wake
 * word then stayed dead for as long as the assistant kept listening. The
 * wake service is START_STICKY and its loop retries its endpoints on its
 * own, so a network flap needs no restart on that path.
 */
class NetworkStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Check if network is available and connected
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val network = connectivityManager.activeNetwork ?: return
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {

            // Network is available - optionally restart service to reconnect
            // Note: This is optional and can be disabled in settings
            if (SettingsRepository(context).settingsFlow.value.enableWakeWordDetection) {
                // The wake chat owns the microphone; restarting the assistant
                // here would steal APP mode and mute the wake word.
                return
            }
            val startIntent = VoiceAssistantService.buildIntent(
                context.applicationContext,
                VoiceAssistantService.ACTION_START
            )

            context.startForegroundService(startIntent)
        }
    }
}