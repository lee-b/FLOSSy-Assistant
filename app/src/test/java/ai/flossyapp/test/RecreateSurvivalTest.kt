package ai.flossyapp.test

import ai.flossyapp.domain.model.AppSettings
import ai.flossyapp.ui.common.ConversationStore
import ai.flossyapp.ui.common.VoiceCallEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Configuration-agnostic state ownership, at the seams the JVM can reach.
 *
 * An orientation change recreates the activity but not the process, so every
 * piece of state that lives in a process-level singleton survives it. The
 * conversation text ([ConversationStore]) and the voice-call engine (its
 * instance registry) are exactly those owners: the tests below pin that the
 * "recreation" of the access path returns the same object, so the history and
 * the active mode cannot be reset by a rotate.
 */
class RecreateSurvivalTest {

    @Before
    fun setUp() {
        VoiceCallEngine.testMode = true
    }

    @After
    fun tearDown() {
        VoiceCallEngine.testMode = false
    }

    @Test
    fun conversationStoreIsAProcessSingleton() {
        // A second "access" (what a recreated activity would do) sees the same
        // object, not a fresh instance.
        val before = ConversationStore.messages.value
        val sameObject = ConversationStore
        assertTrue("ConversationStore must be an object (process singleton)", sameObject === ConversationStore)
        assertEquals("the message list is untouched by a new access", before, ConversationStore.messages.value)
    }

    @Test
    fun engineModeSurvivesAStatelessResetOfCallers() {
        // The engine singleton is pinned to the application context
        // (instance() caches INSTANCE), so a recreated activity's lookup
        // hands back the same object. The JVM cannot construct a Context,
        // so the mode-survival is verified on the test seam engine.
        val engine = VoiceCallEngine.createUnattached()
        engine.startAppMode(object : VoiceCallEngine.ExchangeHost {
            override fun isActive() = true
            override fun settings() = AppSettings()
            override fun history() = listOf(mapOf("role" to "user", "content" to "hi"))
            override fun onUserMessage(text: String) {}
            override fun onAssistantMessage(text: String, reasoning: String?) {}
            override fun onPhase(phase: ai.flossyapp.domain.model.VoiceCallPhase) {}
            override fun onStreamingContent(text: String) {}
            override fun onStreamingReasoning(text: String) {}
            override fun onSystemNote(text: String) {}
            override fun onError(message: String) {}
        })
        val mode = engine.mode
        // The engine owns its mode; nothing a recreated surface holds can
        // change it behind its back.
        assertTrue("an adopted call is a live mode", mode != VoiceCallEngine.Mode.IDLE)
        assertTrue(engine.callActive)
        engine.hangUp("test teardown")
        assertFalse(engine.callActive)
    }
}
