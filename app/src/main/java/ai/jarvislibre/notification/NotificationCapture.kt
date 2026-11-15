package ai.jarvislibre.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One watcher: a package whose notifications a currently-enabled tool needs,
 * plus the extraction the tool's parser wants applied to each capture.
 *
 * The registry holds the live set of these and rebuilds it from the enabled
 * tools whenever the tool list changes; the service consults it on every post.
 * A package with no watcher falls through untouched - the listener never
 * extracts or stores what nobody has asked for.
 */
data class NotificationWatcher(
    val packageName: String,
    val extract: (StatusBarNotification) -> CapturedNotification
)

/**
 * The one passive seam notifications flow through: the listener service asks
 * the registry which packages matter, writes only those in, and the tools read
 * the latest snapshots out.
 *
 * Nothing is stored to disk and nothing survives the process: a tool answers
 * from what has been seen since its watcher was registered, which is minutes
 * old at most in practice. The most recent snapshot per watched package is
 * kept, plus a short history per package for readings that carry a trend
 * direction - the CamAPS FX glucose notification updates every minute, so a
 * delta between two captures needs the previous one kept around.
 */
/**
 * One captured notification, as the tools will read it.
 *
 * [title], [text] and [subText] come from the notification's standard extras;
 * [remoteViewsText] is what could be pulled out of a custom view when the app
 * paints its content only there (CamAPS FX does: its "My diabetes info"
 * notification carries the glucose reading in a RemoteViews, with null extras).
 * [raw] holds the untouched dump that fed those fields, so the parsing can be
 * audited after the fact.
 */
data class CapturedNotification(
    val packageName: String,
    val id: Int,
    val key: String,
    val timestamp: Long,
    val title: String,
    val text: String,
    val subText: String,
    val remoteViewsText: String,
    val ongoing: Boolean,
    val raw: String
)

/**
 * The one passive seam notifications flow through: the listener service writes
 * every posted notification in, and the tools read the latest snapshots out.
 *
 * Nothing is stored to disk and nothing survives the process: a tool answers
 * from what has been seen since the listener connected, which is minutes old at
 * most in practice. The most recent snapshot per package is kept, plus a short
 * history per package for readings that carry a trend direction - the CamAPS FX
 * glucose notification updates every minute, so a delta between two captures
 * needs the previous one kept around.
 */
object NotificationCapture {

    /** Newest capture per package, keyed by package name. */
    private val latest = ConcurrentHashMap<String, CapturedNotification>()

    /** The last few captures per package, newest first, for trend deltas. */
    private val history = ConcurrentHashMap<String, MutableList<CapturedNotification>>()

    private val historyCap = 24

    private val connected = AtomicBoolean(false)

    /** Fires once per captured update, for surfaces that want to react live. */
    val captureEvents = MutableSharedFlow<CapturedNotification>(extraBufferCapacity = 8)

    /**
     * The live watcher set, rebuilt wholesale from the enabled tools.
     * Read-write under [watchersLock] so the service and the settings screen
     * never race on it.
     */
    private val watchers = mutableListOf<NotificationWatcher>()
    private val watchersLock = Any()

    /** Whether the platform currently has notification access granted and the service bound. */
    val listenerConnected: Boolean get() = connected.get()

    /**
     * Replaces the watcher set with [newWatchers]. Called by the tools layer
     * whenever the enabled tool list changes: a tool that lost its enable
 * switch loses its watcher with it, and packages nobody asks for fall
     * through untouched from then on.
     */
    fun setWatchers(newWatchers: List<NotificationWatcher>) {
        synchronized(watchersLock) {
            watchers.clear()
            watchers.addAll(newWatchers)
            // Watchers nobody wants any more leave their snapshots behind:
            // drop them too, so a stale capture cannot answer a later run.
            val keep = newWatchers.mapTo(mutableSetOf()) { it.packageName }
            // A catch-all watcher (blank package) keeps everything captured so
            // far; only when no watcher at all covers a package is it dropped.
            val catchAll = newWatchers.any { it.packageName.isBlank() }
            if (!catchAll) {
                latest.keys.removeAll { it !in keep }
                history.keys.removeAll { it !in keep }
            }
        }
    }

    /**
     * The watcher for one package, or null when nothing wants it.
     *
     * A specific watcher always wins over the catch-all: a watcher whose
     * package is empty matches every package, which is how the general
     * notification reader registers, but the blood-sugar tool's extraction
     * must never be displaced by it for the package both care about.
     */
    private fun watcherFor(packageName: String): NotificationWatcher? {
        synchronized(watchersLock) {
            return watchers.firstOrNull { it.packageName == packageName }
                ?: watchers.firstOrNull { it.packageName.isBlank() }
        }
    }

    /** Every package any watcher wants right now; blank means the catch-all. */
    fun watchedPackages(): Set<String> {
        synchronized(watchersLock) {
            return watchers.map { it.packageName }.toSet()
        }
    }

    /**
     * The most recent capture from one package, or null when nothing has been
     * seen (the app silent, or notification access not granted).
     */
    fun latestFrom(packageName: String): CapturedNotification? = latest[packageName]

    /**
     * The recent captures from one package, newest first. Callers that want a
     * rate of change take two entries and subtract.
     */
    fun historyFrom(packageName: String): List<CapturedNotification> =
        history[packageName] ?: emptyList()

    /** All currently captured packages, newest capture first. */
    fun knownPackages(): List<String> =
        latest.values.sortedByDescending { it.timestamp }.map { it.packageName }

    /**
     * Whether the platform lists our listener among the enabled notification
     * listeners. Read live from secure settings, so the Tools tab and the
     * tools themselves can ghost or refuse a run without the service knowing.
     */
    fun isListenerEnabled(context: Context): Boolean {
        return try {
            val component = ComponentName(context, NotificationCaptureService::class.java)
            // The constant is not part of the public Settings.Secure surface
            // on every API level this project builds against, so the key
            // literal is used; the value is a colon-separated component list.
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""
            enabled.split(":").any {
                it.equals(component.flattenToString(), ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Records one posted notification, only when a watcher wants it.
     * Everything else falls through: the service calls this for every post and
     * this is where the not-interested case returns without touching anything.
     */
    internal fun record(sbn: StatusBarNotification) {
        val watcher = watcherFor(sbn.packageName) ?: return
        val captured = try {
            watcher.extract(sbn)
        } catch (e: Throwable) {
            Log.w("NotifCapture", "extraction for ${sbn.packageName} failed: ${e.message}")
            return
        }
        latest[captured.packageName] = captured
        val ring = history.getOrPut(captured.packageName) { mutableListOf() }
        synchronized(ring) {
            // An update reposts under the same key; when nothing changed in
            // its content keep only the newer timestamp instead of stacking
            // a ring full of near-identical entries.
            if (ring.isNotEmpty() && ring.first().key == captured.key && ring.first().raw == captured.raw) {
                ring[0] = captured
            } else {
                ring.add(0, captured)
                while (ring.size > historyCap) ring.removeAt(ring.size - 1)
            }
        }
        captureEvents.tryEmit(captured)
    }

    /** Drops one removed notification, so "latest" never answers from a dead post. */
    internal fun remove(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        latest.remove(packageName)
        history[packageName]?.let { ring ->
            synchronized(ring) { ring.removeAll { it.key == sbn.key } }
        }
    }

    /**
     * A default extraction for watchers that only want the standard fields:
     * title, text, subText and ticker, with a raw dump for auditing. Packages
     * whose apps paint into custom views need their own extractors instead.
     */
    val standardExtract: (StatusBarNotification) -> CapturedNotification = ::describeStandard

    /** The standard-fields extraction itself. */
    private fun describeStandard(sbn: StatusBarNotification): CapturedNotification {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras?.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras?.getString(Notification.EXTRA_TEXT) ?: ""
        val subText = extras?.getString(Notification.EXTRA_SUB_TEXT) ?: ""
        val raw = buildString {
            append("extras={")
            extras?.keySet()?.sorted()?.forEach { key ->
                append(key).append('=').append(extras.get(key)?.toString() ?: "null").append(", ")
            }
            append("} ticker=").append(notification.tickerText?.toString() ?: "null")
        }
        if (JarvisLog.debug) {
            Log.d("NotifCapture", "captured ${sbn.packageName} id=${sbn.id} title=$title text=$text raw=$raw")
        }
        return CapturedNotification(
            packageName = sbn.packageName,
            id = sbn.id,
            key = sbn.key,
            timestamp = notification.`when`,
            title = title,
            text = text,
            subText = subText,
            remoteViewsText = "",
            ongoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            raw = raw
        )
    }
}

/**
 * Debug switch holder so the capture can log without reaching the settings
 * repository from a service context every update.
 */
object JarvisLog {
    @Volatile
    var debug: Boolean = false
}
