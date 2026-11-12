package ai.flossyapp.test

import ai.flossyapp.service.WakeChatService
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The notification's action must match the call's state at every render -
 * this is the pure mapping the notification builder uses, verified without
 * building a notification object.
 */
class NotificationActionMappingTest {
    @Test
    fun liveCallShowsEndCall() {
        val (label, action) = WakeChatService.notificationActionFor(callActive = true, sleeping = false)
        assertEquals("End call", label)
        assertEquals(WakeChatService.ACTION_END_CALL, action)
    }

    @Test
    fun slumberingShowsDisable() {
        // Slumbering = listening for the wake word: the loop can be disabled.
        val (label, action) = WakeChatService.notificationActionFor(callActive = false, sleeping = false)
        assertEquals("Disable", label)
        assertEquals(WakeChatService.ACTION_STOP, action)
    }

    @Test
    fun disabledShowsEnable() {
        // Disabled = the wake loop is fully off: only Enable can bring it back.
        val (label, action) = WakeChatService.notificationActionFor(callActive = false, sleeping = true)
        assertEquals("Enable", label)
        assertEquals(WakeChatService.ACTION_START, action)
    }
}
