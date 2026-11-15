package ai.flossyapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import ai.flossyapp.R
import ai.flossyapp.data.settings.SettingsRepository
import ai.flossyapp.service.VoiceAssistantService
import ai.flossyapp.service.WakeChatService

/**
 * Home screen widget for quick voice assistant activation.
 *
 * Shows a large microphone button that starts/stops the listening. The
 * microphone has one idle owner: with wake detection on, the tap toggles the
 * wake chat - the hands-free listener that actually owns the mic - and with
 * it off, the always-listening assistant service. Toggling the assistant
 * while the wake chat runs would raise its notification and immediately
 * stand it down again, so the tap follows the arbitration instead.
 */
class VoiceAssistantWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_WIDGET_MIC_CLICK = "ai.flossyapp.WIDGET_MIC_CLICK"

        // Track assistant state across updates. With wake detection on the
        // status reads the wake chat's own flag instead.
        @Volatile
        private var isServiceRunning = false

        /** Whether wake detection is on - the tap follows the mic's owner. */
        private fun wakeEnabled(context: Context): Boolean =
            runCatching { SettingsRepository(context).settingsFlow.value.enableWakeWordDetection }
                .getOrDefault(true)

        /**
         * Update all instances of this widget with new views.
         */
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager) {
            val thisProvider = ComponentName(context, VoiceAssistantWidget::class.java)
            // getAppWidgetIds returns an IntArray on API 31+
            val widgetIds = appWidgetManager.getAppWidgetIds(thisProvider)

            for (widgetId in widgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_voice_button).apply {
                    // Set up click handler for microphone button
                    val micIntent = Intent(context, VoiceAssistantWidget::class.java).apply {
                        action = ACTION_WIDGET_MIC_CLICK
                        putExtra("widget_id", widgetId)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        0,
                        micIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    setOnClickPendingIntent(R.id.widget_mic_button, pendingIntent)

                    // Update status text based on service state: with wake
                    // detection on the listening is the wake chat's, so its
                    // own flag is what the text reads.
                    val running = if (wakeEnabled(context)) {
                        WakeChatService.isRunning
                    } else {
                        isServiceRunning
                    }
                    val statusText = if (running) {
                        context.getString(R.string.voice_assistant_active)
                    } else {
                        context.getString(R.string.voice_assistant_tap_to_activate)
                    }
                    setTextViewText(R.id.widget_status_text, statusText)
                }

                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }

        /**
         * Toggle the listening and update all widgets.
         */
        fun toggleService(context: Context) {
            if (wakeEnabled(context)) {
                // The wake chat is the mic's idle owner: the tap starts or
                // stops it, not the assistant.
                val wakeRunning = WakeChatService.isRunning
                val intent = WakeChatService.buildIntent(
                    context,
                    if (wakeRunning) WakeChatService.ACTION_STOP else WakeChatService.ACTION_START
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                return
            }
            isServiceRunning = !isServiceRunning

            val intent = VoiceAssistantService.buildIntent(
                context,
                if (isServiceRunning) VoiceAssistantService.ACTION_START else VoiceAssistantService.ACTION_STOP
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_WIDGET_MIC_CLICK -> {
                // Toggle the listening on mic button click
                toggleService(context)

                // Update all widgets after state change
                val appWidgetManager = AppWidgetManager.getInstance(context)
                updateWidget(context, appWidgetManager)
            }

            VoiceAssistantService.ACTION_START -> {
                isServiceRunning = true
                val appWidgetManager = AppWidgetManager.getInstance(context)
                updateWidget(context, appWidgetManager)
            }

            VoiceAssistantService.ACTION_STOP -> {
                isServiceRunning = false
                val appWidgetManager = AppWidgetManager.getInstance(context)
                updateWidget(context, appWidgetManager)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Update all widgets with current state
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_voice_button).apply {
                // Set up click handler for microphone button
                val micIntent = Intent(context, VoiceAssistantWidget::class.java).apply {
                    action = ACTION_WIDGET_MIC_CLICK
                    putExtra("widget_id", appWidgetId)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    micIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_mic_button, pendingIntent)

                // Update status text based on service state
                val running = if (wakeEnabled(context)) {
                    WakeChatService.isRunning
                } else {
                    isServiceRunning
                }
                val statusText = if (running) {
                    context.getString(R.string.voice_assistant_active)
                } else {
                    context.getString(R.string.voice_assistant_tap_to_activate)
                }
                setTextViewText(R.id.widget_status_text, statusText)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onEnabled(context: Context) {
        // Widget instances added - could start a foreground service to track state
        super.onEnabled(context)
    }

    override fun onDisabled(context: Context) {
        // Last widget instance removed - clean up
        isServiceRunning = false
        super.onDisabled(context)
    }
}