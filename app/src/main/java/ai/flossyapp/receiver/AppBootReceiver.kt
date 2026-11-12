package ai.flossyapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.flossyapp.data.settings.SettingsRepository
import ai.flossyapp.service.VoiceAssistantService

/**
 * Broadcast receiver that starts the voice assistant service on device boot.
 */
class AppBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.REBOOT" -> {
                // The user can turn this off in Settings ("Auto-start on boot"), so the
                // setting has to be read here - starting unconditionally would ignore it.
                if (SettingsRepository(context).settingsFlow.value.autoStartOnBoot) {
                    val startIntent = VoiceAssistantService.buildIntent(
                        context.applicationContext,
                        VoiceAssistantService.ACTION_START
                    )

                    context.startForegroundService(startIntent)
                }
            }
        }
    }
}
