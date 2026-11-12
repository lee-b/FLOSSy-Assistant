package ai.flossyapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ai.flossyapp.service.VoiceAssistantService

/**
 * Broadcast receiver that monitors network connectivity.
 * Restarts the voice assistant service when network becomes available.
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
            val startIntent = VoiceAssistantService.buildIntent(
                context.applicationContext,
                VoiceAssistantService.ACTION_START
            )

            context.startForegroundService(startIntent)
        }
    }
}
