package ai.flossyapp.data.tools

import ai.flossyapp.notification.CapturedNotification
import ai.flossyapp.notification.NotificationCapture
import ai.flossyapp.notification.NotificationWatcher
import android.service.notification.StatusBarNotification
import android.widget.RemoteViews

/**
 * The CamAPS FX blood-sugar tool.
 *
 * CamAPS FX publishes an ongoing "My diabetes info" notification once a
 * minute; it carries the sensor reading, a trend arrow (the same one the app
 * shows: up, down or diagonal, plus the fast variants) and the timestamp. The
 * standard extras are null - the content lives in the notification's custom
 * RemoteViews - so the extraction walks the RemoteViews' own action list and
 * reads the strings it applies.
 *
 * Nothing here invents a second path: notifications are the one route, exactly
 * how xDrip+'s documented "Companion App" mode reads CamAPS FX. When the
 * notification is absent (app stopped, notification access ungranted) the tool
 * answers in words and never throws.
 */
object CamApsFxTool {

    /** The package the tool reads. */
    const val CAMAPS_PACKAGE = "com.camdiab.fx_alert.mmoll"

    /** The tool name the model sees. */
    const val TOOL_NAME = "read_blood_sugar"

    /**
     * The watcher this tool registers when enabled: only this package, with
     * this extraction. The registry holds it; the service consults it per post.
     */
    fun watcher(): NotificationWatcher =
        NotificationWatcher(CAMAPS_PACKAGE, ::extract)

    // ---------- Extraction ----------

    /**
     * One capture, extracted. Starts from the standard extraction (extras,
     * ticker, raw audit dump) and adds the custom view's own strings, which is
     * where CamAPS FX hides the reading.
     */
    fun extract(sbn: StatusBarNotification): CapturedNotification {
        val base = NotificationCapture.standardExtract(sbn)
        val remote = sbn.notification.contentView?.let { extractRemoteViews(it) } ?: ""
        return base.copy(
            remoteViewsText = remote,
            raw = base.raw + " remote=" + remote
        )
    }

    /**
     * Reads the strings a custom notification view applies.
     *
     * RemoteViews keeps its pending actions in a hidden field; each action's
     * toString names the operation and the value it will apply to the view.
     * Walking that list is the only way to see a custom view's content without
     * inflating it, and it is stable across Android versions in practice -
     * when it ever is not, this returns empty and the tool says so in words.
     */
    private fun extractRemoteViews(view: RemoteViews): String {
        return try {
            val actionsField = RemoteViews::class.java.getDeclaredField("mActions")
            actionsField.isAccessible = true
            val actions = actionsField.get(view) as? List<*> ?: return ""
            actions.joinToString(" ") { action ->
                "[${action?.javaClass?.simpleName ?: "?"} ${action?.toString() ?: ""}]"
            }
        } catch (_: Throwable) {
            ""
        }
    }

    // ---------- Parsing ----------

    /**
     * One parsed reading.
     *
     * @field mmolL the level in mmol/L when the notification showed it
     * @field mgdl the level in mg/dL when the notification showed it; one of
     *              the two is null because the tool never converts, it only
     *              reports what the app itself showed
     * @field arrow the trend arrow glyph as CamAPS FX painted it ("↗" and
     *              friends), or empty when the notification carried none
     * @field trend a spoken description of [arrow], or empty
     */
    data class Reading(
        val mmolL: Double?,
        val mgdl: Double?,
        val arrow: String,
        val trend: String,
        val ageSeconds: Long?
    )

    /**
     * The trend arrows CamAPS FX shows and what they mean. The diagonals are
     * the common case - a CGM reading changes slowly, so the gentle arrows
     * dominate and the vertical (fast) ones are rare - and the parsing names
     * them all so the model can speak the direction and the rate together.
     */
    val ARROW_TRENDS: Map<String, String> = mapOf(
        "↑" to "rising fast", "⇧" to "rising fast",
        "↗" to "rising", "→" to "steady", "↘" to "falling",
        "↓" to "falling fast", "⇩" to "falling fast"
    )

    /**
     * Parses one capture into a reading, or null when the capture does not
     * carry a glucose reading (the notification was an alert or a status, not
     * the diabetes info card).
     *
     * The number is found first, then the arrow glyph is matched against
     * [ARROW_TRENDS]; a capture with a number but no arrow still parses - the
     * trend is reported as unknown rather than guessed.
     */
    fun parseReading(capture: CapturedNotification): Reading? {
        val haystack = buildString {
            append(capture.remoteViewsText)
            append(' ')
            append(capture.title)
            append(' ')
            append(capture.text)
            append(' ')
            append(capture.subText)
        }
        val number = findGlucoseNumber(haystack) ?: return null
        val arrow = ARROW_TRENDS.keys.firstOrNull { haystack.contains(it) } ?: ""
        // Notification timestamps are wall-clock 'when'; age against the wall
        // clock now, floored at zero for the clock-slop between processes.
        val ageSeconds = ((System.currentTimeMillis() - capture.timestamp) / 1000)
            .coerceAtLeast(0)
        return Reading(
            mmolL = number.mmol,
            mgdl = number.mgdl,
            arrow = arrow,
            trend = if (arrow.isBlank()) "" else ARROW_TRENDS[arrow] ?: "",
            ageSeconds = ageSeconds
        )
    }

    private data class GlucoseNumber(val mmol: Double?, val mgdl: Double?)

    /**
     * The number itself, with its unit decided by the decimal marker: CamAPS FX
     * shows one decimal place when configured in mmol/L (4.2) and whole numbers
     * when configured in mg/dL (92). The arrow glyph and any percentage signs
     * in the same string are ignored.
     */
    private fun findGlucoseNumber(haystack: String): GlucoseNumber? {
        // The RemoteViews action dump carries text values; the reading is the
        // first number-like token with a plausible glucose magnitude. Values
        // outside 1.0-34.0 mmol/L or 20-600 mg/dL are not glucose readings.
        val pattern = Regex("(\\d+(?:[.,]\\d{1,2})?)")
        for (match in pattern.findAll(haystack)) {
            val token = match.groupValues[1].replace(',', '.')
            val value = token.toDoubleOrNull() ?: continue
            when {
                value in 1.0..34.0 && token.contains('.') -> return GlucoseNumber(value, null)
                value in 20.0..600.0 && !token.contains('.') -> return GlucoseNumber(null, value)
            }
        }
        return null
    }

    // ---------- Readiness ----------

    /**
     * Whether the tool can actually run on this device: CamAPS FX installed
     * (the manifest's queries declaration makes the check answerable without
     * package visibility) and notification access granted, which is what
     * feeds the capture. The Tools tab ghosts the enable check when either
     * is missing, and the reason names the gap so the user knows what to fix.
     */
    fun readiness(context: android.content.Context): Pair<Boolean, String> {
        val installed = try {
            context.packageManager.getPackageInfo(CAMAPS_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
        if (!installed) {
            return Pair(false, "CamAPS FX is not installed on this device")
        }
        return if (NotificationCapture.isListenerEnabled(context)) {
            Pair(true, "")
        } else {
            Pair(false, "Notification access has not been granted to FLOSSyAssistant")
        }
    }

    // ---------- Tool answer ----------

    /**
     * The tool's answer, as the model speaks it: the latest reading with its
     * trend and age, or a clear word when nothing is visible. The history is
     * consulted only when the newest capture carries no arrow - a delta
     * between the two newest readings stands in for the arrow then, and is
     * labelled as computed rather than shown.
     */
    fun answer(capture: CapturedNotification?): String {
        if (capture == null) {
            return "I have no captured CamAPS FX notification. Make sure CamAPS FX is running and showing its diabetes info notification, and that notification access is granted."
        }
        val reading = parseReading(capture)
        if (reading == null) {
            return "The last CamAPS FX notification I saw carries no glucose reading. It may be an alert or a status message."
        }
        val parts = mutableListOf<String>()
        val value = when {
            reading.mmolL != null -> "%.1f mmol/L".format(reading.mmolL)
            reading.mgdl != null -> "%d mg/dL".format(reading.mgdl.toLong())
            else -> "unknown value"
        }
        parts.add(value)
        when {
            reading.trend.isNotBlank() -> parts.add(reading.trend)
            reading.mmolL != null || reading.mgdl != null -> {
                // No arrow in the newest capture: compute a trend from the
                // history and say it is computed, never shown.
                val trend = trendFromHistory()
                if (trend.isNotBlank()) parts.add(trend)
            }
        }
        reading.ageSeconds?.let { age ->
            when {
                age < 90 -> parts.add("current")
                age < 600 -> parts.add("from ${age / 60} minutes ago")
                else -> parts.add("from ${age / 3600} hours ago")
            }
        }
        return "Blood sugar: ${parts.joinToString(", ")}."
    }

    /**
     * The trend computed from the last two stored captures, as a spoken
     * phrase. Empty when the history has no pair to subtract.
     */
    private fun trendFromHistory(): String {
        val ring = NotificationCapture.historyFrom(CAMAPS_PACKAGE)
        if (ring.size < 2) return ""
        val newest = parseReading(ring[0]) ?: return ""
        val previous = parseReading(ring[1]) ?: return ""
        val newestValue = newest.mmolL ?: newest.mgdl ?: return ""
        val previousValue = previous.mmolL ?: previous.mgdl ?: return ""
        val delta = newestValue - previousValue
        return when {
            delta > 0.2 -> "and it was rising"
            delta < -0.2 -> "and it was falling"
            else -> "and it was steady"
        }
    }
}
