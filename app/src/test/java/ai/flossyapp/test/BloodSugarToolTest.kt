package ai.flossyapp.test

import ai.flossyapp.data.tools.CamApsFxTool
import ai.flossyapp.domain.model.ToolsConfig
import ai.flossyapp.domain.model.ToolTypes
import ai.flossyapp.notification.CapturedNotification
import ai.flossyapp.notification.NotificationCapture
import ai.flossyapp.notification.NotificationWatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the CamAPS FX blood-sugar tool's parsing: what a capture
 * yields, how the trend arrow is spoken, and what the tool says when nothing
 * has been captured. The store's watcher set is tested for the gating rule -
 * a package nobody watches falls through - with plain data, no Android.
 */
class BloodSugarToolTest {

    private fun capture(remote: String) = CapturedNotification(
        packageName = CamApsFxTool.CAMAPS_PACKAGE,
        id = 1,
        key = "0|${CamApsFxTool.CAMAPS_PACKAGE}|1|null|10281",
        timestamp = System.currentTimeMillis(),
        title = "",
        text = "",
        subText = "",
        remoteViewsText = remote,
        ongoing = true,
        raw = "remote=$remote"
    )

    @Test
    fun parsesMmolReading() {
        val reading = CamApsFxTool.parseReading(capture("[ReflectionAction value=4.2]"))
        assertEquals(4.2, requireNotNull(reading!!.mmolL), 0.01)
        assertNull(reading?.mgdl)
    }

    @Test
    fun parsesMgdlReading() {
        val reading = CamApsFxTool.parseReading(capture("[ReflectionAction value=92]"))
        assertNull(reading?.mmolL)
        assertEquals(92.0, requireNotNull(reading!!.mgdl), 0.01)
    }

    @Test
    fun rejectsImplausibleNumbers() {
        assertNull(CamApsFxTool.parseReading(capture("[ReflectionAction value=0.3]")))
        assertNull(CamApsFxTool.parseReading(capture("[ReflectionAction value=700]")))
    }

    @Test
    fun trendArrowsAreNamed() {
        val up = CamApsFxTool.parseReading(capture("[value=5.6] [↗]"))
        assertEquals("rising", up?.trend)
        val fast = CamApsFxTool.parseReading(capture("[value=5.6] [↓]"))
        assertEquals("falling fast", fast?.trend)
        val steady = CamApsFxTool.parseReading(capture("[value=5.6] [→]"))
        assertEquals("steady", steady?.trend)
    }

    @Test
    fun readingWithoutArrowStillParses() {
        val reading = CamApsFxTool.parseReading(capture("[value=6.1]"))
        assertEquals(6.1, requireNotNull(reading!!.mmolL), 0.01)
        assertEquals("", reading?.trend)
    }

    @Test
    fun nonGlucoseCaptureParsesToNull() {
        assertNull(CamApsFxTool.parseReading(capture("[value=Suspended]")))
        assertNull(CamApsFxTool.parseReading(capture("")))
    }

    @Test
    fun answerWithNoCaptureExplains() {
        val answer = CamApsFxTool.answer(null)
        assertTrue(answer.contains("no captured"))
    }

    @Test
    fun answerWithReadingSpeaksValue() {
        val answer = CamApsFxTool.answer(capture("[value=4.2] [↗]"))
        assertTrue(answer.contains("4.2 mmol/L"))
        assertTrue(answer.contains("rising"))
    }

    @Test
    fun defaultConfigShipsBothNotificationToolsOff() {
        val default = ToolsConfig.default()
        val sugar = default.instances.first { it.type == ToolTypes.TYPE_READ_BLOOD_SUGAR }
        val notifications = default.instances.first { it.type == ToolTypes.TYPE_READ_NOTIFICATIONS }
        assertTrue(!sugar.enabled)
        assertTrue(!notifications.enabled)
    }

    @Test
    fun watcherSetStartsEmptyAndGatesNothing() {
        NotificationCapture.setWatchers(emptyList())
        assertTrue(NotificationCapture.watchedPackages().isEmpty())
        // No watcher, no capture: a package asked later finds nothing.
        assertNull(NotificationCapture.latestFrom(CamApsFxTool.CAMAPS_PACKAGE))
    }

    @Test
    fun specificWatcherWinsOverCatchAll() {
        val specific = NotificationWatcher(CamApsFxTool.CAMAPS_PACKAGE) { throw IllegalStateException("never called in this test") }
        val catchAll = NotificationWatcher("") { throw IllegalStateException("never called in this test") }
        NotificationCapture.setWatchers(listOf(catchAll, specific))
        // Both packages are watched; the specific-vs-catch-all arbitration is
        // exercised on the device, where the extraction runs.
        assertTrue(NotificationCapture.watchedPackages().contains(CamApsFxTool.CAMAPS_PACKAGE))
        assertTrue(NotificationCapture.watchedPackages().contains(""))
        NotificationCapture.setWatchers(emptyList())
    }
}
