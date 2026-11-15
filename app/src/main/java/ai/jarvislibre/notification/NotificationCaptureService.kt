package ai.jarvislibre.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * The platform notification listener.
 *
 * The service itself stays thin: it answers only when
 * [NotificationCapture.watchedPackages] says some enabled tool wants a package,
 * so with the tools off the whole pipeline is a no-op and every post is
 * returned to the system untouched. When the system connects or reconnects the
 * listener after the user grants notification access, the current snapshot is
 * replayed through onListenerDisconnected/onListenerConnected pairs - the
 * active-on-connected hook fills the store once, so a tool can answer from a
 * notification posted before the listener started.
 *
 * The manifest entry declares the listener with the system's bind permission;
 * it stays dormant until the user grants notification access in the system
 * settings screen, which is what the tools' setup text points at.
 */
class NotificationCaptureService : NotificationListenerService() {

    /**
     * One post. The interesting case is decided by the watcher set: a package
     * no enabled tool wants falls through, and only the watchers' own
     * extraction runs - nothing here knows what a glucose reading looks like.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!NotificationCapture.watchedPackages().contains(sbn.packageName)) {
            return
        }
        NotificationCapture.record(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!NotificationCapture.watchedPackages().contains(sbn.packageName)) {
            return
        }
        NotificationCapture.remove(sbn)
    }

    /**
     * The system hands the current snapshot back when the listener reconnects
     * (after toggling notification access, after a reboot). One pass here
     * fills the store so a tool answers immediately, not only from the next
     * notification the watched app posts.
     */
    override fun onListenerConnected() {
        try {
            activeNotifications.forEach { sbn ->
                if (NotificationCapture.watchedPackages().contains(sbn.packageName)) {
                    NotificationCapture.record(sbn)
                }
            }
        } catch (e: Throwable) {
            Log.w("NotifCapture", "snapshot on connect failed: ${e.message}")
        }
        super.onListenerConnected()
    }
}
