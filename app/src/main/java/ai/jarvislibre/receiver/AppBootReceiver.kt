package ai.jarvislibre.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.jarvislibre.data.settings.SettingsRepository
import ai.jarvislibre.service.VoiceAssistantService
import ai.jarvislibre.service.WakeChatService

/**
 * Broadcast receiver that starts the voice listening on device boot.
 *
 * The microphone has one idle owner, and boot decides who it is: when wake
 * detection is on, the wake chat is the hands-free path and it is started
 * here - the wake word then works from the first minute after a reboot,
 * without the app ever being opened. Only when wake detection is off does
 * the always-listening assistant start, because an assistant started beside
 * a live wake loop would hold APP mode and block the wake word for good.
 */
class AppBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.REBOOT" -> {
                val settings = SettingsRepository(context).settingsFlow.value
                if (settings.enableWakeWordDetection) {
                    // The wake chat owns the microphone; the assistant service
                    // stands down whenever the wake chat runs, so starting it
                    // too would only raise a notification that says so.
                    val startIntent = WakeChatService.buildIntent(
                        context.applicationContext,
                        WakeChatService.ACTION_START
                    )
                    context.startForegroundService(startIntent)
                } else if (settings.autoStartOnBoot) {
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