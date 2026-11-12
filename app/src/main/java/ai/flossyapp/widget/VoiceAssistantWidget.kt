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
import ai.flossyapp.service.VoiceAssistantService

/**
 * Home screen widget for quick voice assistant activation.
 * 
 * Shows a large microphone button that starts/stops the VoiceAssistantService.
 * Updates status text based on service state.
 */
class VoiceAssistantWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_WIDGET_MIC_CLICK = "ai.flossyapp.WIDGET_MIC_CLICK"
        
        // Track widget state across updates
        @Volatile
        private var isServiceRunning = false
        
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
                    
                    // Update status text based on service state
                    val statusText = if (isServiceRunning) {
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
         * Toggle the voice assistant service and update all widgets.
         */
        fun toggleService(context: Context) {
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
                // Toggle the service on mic button click
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
                val statusText = if (isServiceRunning) {
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
