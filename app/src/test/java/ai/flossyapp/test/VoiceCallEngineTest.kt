package ai.flossyapp.test

import ai.flossyapp.domain.model.AppSettings
import ai.flossyapp.domain.model.VoiceCallPhase
import ai.flossyapp.ui.common.VoiceCallEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The engine's state machine, on the fake capture seam: no microphone, no
 * network, no Android audio - the STT, LLM, TTS and waiting-cue steps are
 * swapped through the engine's test seams, so the tests below verify the
 * one-call model, the single hang-up entry point, the overlap guard and the
 * fresh-conversation rule without a user touching the app.
 */
class VoiceCallEngineTest {

    private lateinit var engine: VoiceCallEngine

    /** Records what a surface saw, standing in for the screen or the popup. */
    private class FakeHost(var active: Boolean = true) : VoiceCallEngine.ExchangeHost {
        val phases = mutableListOf<VoiceCallPhase>()
        val notes = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var sttText: String = "hello"
        override fun isActive() = active
        override fun settings() = AppSettings()
        override fun history() = listOf(mapOf("role" to "user", "content" to "hi"))
        override fun onUserMessage(text: String) { sttText = text }
        override fun onAssistantMessage(text: String, reasoning: String?) {}
        override fun onPhase(phase: VoiceCallPhase) { phases.add(phase) }
        override fun onStreamingContent(text: String) {}
        override fun onStreamingReasoning(text: String) {}
        override fun onSystemNote(text: String) { notes.add(text) }
        override fun onError(message: String) { errors.add(message) }
    }

    @Before
    fun setUp() {
        VoiceCallEngine.testMode = true
        engine = VoiceCallEngine.createUnattached()
    }

    @After
    fun tearDown() {
        VoiceCallEngine.testMode = false
    }

    // ==================== one call ====================

    @Test
    fun beginCallMarksCallActiveAndIsFreshOnlyOnce() {
        assertTrue(engine.beginCall())
        assertTrue(engine.callActive)
        // The same conversation continues while the call is live: adopting
        // it from another surface is not a new conversation.
        assertFalse(engine.beginCall())
        assertTrue(engine.callActive)
    }

    @Test
    fun hangUpEndsTheCallFromAnyState() = runBlocking {
        // The hang-up is announced, so every surface can drop its view.
        val event = CompletableDeferred<Unit>()
        // Deterministic subscription: wait until the collector below is
        // actually registered before the hang-up emits (a lazily dispatched
        // collector may otherwise miss the one-shot event entirely).
        launch(kotlinx.coroutines.Dispatchers.Default) {
            engine.hangUpEvents.first<Unit>()
            event.complete(Unit)
        }
        withTimeout(2000) { engine.hangUpEvents.subscriptionCount.first { it >= 1 } }
        engine.beginCall()
        engine.hangUp("test")
        withTimeout(10000) { event.await() }
        assertFalse(engine.callActive)
        assertEquals(VoiceCallEngine.Mode.IDLE, engine.mode)
        // After the call ended a new call is fresh again.
        assertTrue(engine.beginCall())
    }

    @Test
    fun hangUpWithNoCallIsANoOp() {
        engine.hangUp("nothing live")
        assertFalse(engine.callActive)
        assertEquals(VoiceCallEngine.Mode.IDLE, engine.mode)
    }

    // ==================== termination paths ====================

    @Test
    fun hangUpMidExchangeCutsTtsImmediately() = runBlocking {
        val ttsStarted = CompletableDeferred<Unit>()
        VoiceCallEngine.ttsRunner = { _, _, _, _ ->
            ttsStarted.complete(Unit)
            // The reply would play forever on a real device; the fake just
            // keeps "playing" until the exchange is torn down under it.
            kotlinx.coroutines.delay(10000)
        }
        VoiceCallEngine.sttRunner = { _, _ -> "hello" }
        VoiceCallEngine.llmRunner = { _, _, _, _, _ -> "a reply" }
        VoiceCallEngine.waitingCueRunner = { true }

        val host = FakeHost()
        engine.startAppMode(host)
        val job = engine.runExchange(ByteArray(1), host, AtomicBoolean(false))
        withTimeout(5000) { ttsStarted.await() }

        // The reply is mid-playback; the hang-up must cut it.
        engine.hangUp("test mid-tts")
        withTimeout(5000) { job?.join() }
        assertFalse(engine.callActive)
        assertEquals(VoiceCallEngine.Mode.IDLE, engine.mode)
    }

    @Test
    fun hangUpBeforeLlmCompletesNeverSpeaks() = runBlocking {
        val llmStarted = CompletableDeferred<Unit>()
        VoiceCallEngine.ttsRunner = { _, _, _, _ ->
            throw AssertionError("TTS must not run after the call was hung up")
        }
        VoiceCallEngine.sttRunner = { _, _ -> "hello" }
        VoiceCallEngine.llmRunner = { _, _, _, _, _ ->
            llmStarted.complete(Unit)
            kotlinx.coroutines.delay(5000) // hang-up cancels the exchange here
            "a reply"
        }
        VoiceCallEngine.waitingCueRunner = { true }

        val host = FakeHost()
        engine.startAppMode(host)
        val job = engine.runExchange(ByteArray(1), host, AtomicBoolean(false))
        withTimeout(5000) { llmStarted.await() }
        engine.hangUp("test mid-llm")
        withTimeout(5000) { job?.join() }
        assertTrue("The cancelled exchange must not reach SPEAKING", VoiceCallPhase.SPEAKING !in host.phases)
    }

    @Test
    fun stopThenRestartResetsSessionFlags() = runBlocking {
        // The documented trap: stop() latches the discard/abort flags on,
        // and a session entered afterwards silently returned every exchange
        // before STT. A restart must clear them.
        engine.beginCall()
        engine.hangUp("reset test")
        val sttRan = CompletableDeferred<Unit>()
        VoiceCallEngine.sttRunner = { _, _ -> sttRan.complete(Unit); "hello" }
        VoiceCallEngine.llmRunner = { _, _, _, _, _ -> "ok" }
        VoiceCallEngine.ttsRunner = { _, _, _, _ -> }
        VoiceCallEngine.waitingCueRunner = { true }

        engine.startWakeMode()
        assertTrue(engine.startWakeMode())
        engine.runExchange(ByteArray(1), FakeHost(), AtomicBoolean(false))
        withTimeout(5000) { sttRan.await() }
    }

    // ==================== no overlapping TTS ====================

    @Test
    fun secondExchangeWhileOneRunsIsDropped() = runBlocking {
        val firstInLlm = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        VoiceCallEngine.sttRunner = { _, _ -> "hello" }
        VoiceCallEngine.llmRunner = { _, _, _, _, _ ->
            firstInLlm.complete(Unit)
            releaseFirst.await()
            "a reply"
        }
        VoiceCallEngine.ttsRunner = { _, _, _, _ -> }
        VoiceCallEngine.waitingCueRunner = { true }

        val host1 = FakeHost()
        engine.startAppMode(host1)
        engine.runExchange(ByteArray(1), host1, AtomicBoolean(false))
        withTimeout(5000) { firstInLlm.await() }

        // A second utterance while the first exchange runs is self-echo or
        // talk-over: it is dropped, never queued behind the speaker.
        val host2 = FakeHost()
        engine.runExchange(ByteArray(2), host2, AtomicBoolean(false))
        kotlinx.coroutines.delay(300)
        assertTrue(
            "The dropped exchange must not even start transcribing",
            VoiceCallPhase.TRANSCRIBING !in host2.phases
        )
        releaseFirst.complete(Unit)
        Unit
    }

    // ==================== handoff (surface change, not call end) ====================

    @Test
    fun stopAppModeWithHandoffKeepsTheCallAlive() {
        engine.startAppMode(FakeHost())
        // The window vanished mid-call: the call itself survives, the wake
        // service adopts it.
        engine.stopAppMode(handoffToWake = true)
        assertTrue(engine.callActive)
        assertEquals(VoiceCallEngine.Mode.WAKE, engine.mode)
        assertTrue(engine.hasHandoff())
        assertTrue(engine.consumeHandoff())
        assertFalse("A handoff is a one-shot event", engine.consumeHandoff())
    }

    @Test
    fun stopAppModeWithoutHandoffHangsUp() = runBlocking {
        // A deliberate hang-up is announced to every surface.
        val event = CompletableDeferred<Unit>()
        // Deterministic subscription: wait until the collector below is
        // actually registered before the hang-up emits.
        launch(kotlinx.coroutines.Dispatchers.Default) {
            engine.hangUpEvents.first<Unit>()
            event.complete(Unit)
        }
        withTimeout(2000) { engine.hangUpEvents.subscriptionCount.first { it >= 1 } }
        engine.startAppMode(FakeHost())
        engine.stopAppMode(handoffToWake = false)
        withTimeout(10000) { event.await() }
        assertFalse(engine.callActive)
        assertEquals(VoiceCallEngine.Mode.IDLE, engine.mode)
    }

    // ==================== fresh conversation ====================

    @Test
    fun freshConversationStartsAfterTheCallEnds() {
        assertTrue(engine.beginCall())
        engine.endCallKeepCapture()
        assertFalse(engine.callActive)
        // The capture stays (the wake loop keeps slumbering) but the call
        // flag dropped, so the next call is a NEW conversation.
        assertTrue(engine.beginCall())
    }
}
