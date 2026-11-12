package ai.flossyapp.test

import ai.flossyapp.domain.model.AppSettings
import ai.flossyapp.domain.model.VoiceCallPhase
import ai.flossyapp.ui.common.VoiceCallEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The waiting-cue's lifetime, on the engine's fake seams.
 *
 * A Job cannot complete while an active child coroutine runs, and the tippy
 * taps are an infinite child of the exchange. If the exchange ends without
 * cancelling the cue - a swallowed failure such as a tool round the server
 * refused - the child holds the whole exchange alive and the ticks play on
 * forever, with no surface left to stop them. That is the resource leak this
 * suite pins: the cue dies with the exchange on every path.
 */
class WaitingCueLifetimeTest {

    private lateinit var engine: VoiceCallEngine

    private class FakeHost(var active: Boolean = true) : VoiceCallEngine.ExchangeHost {
        val phases = mutableListOf<VoiceCallPhase>()
        val errors = mutableListOf<String>()
        override fun isActive() = active
        override fun settings() = AppSettings()
        override fun history() = listOf(mapOf("role" to "user", "content" to "hi"))
        override fun onUserMessage(text: String) {}
        override fun onAssistantMessage(text: String, reasoning: String?) {}
        override fun onPhase(phase: VoiceCallPhase) { phases.add(phase) }
        override fun onStreamingContent(text: String) {}
        override fun onStreamingReasoning(text: String) {}
        override fun onSystemNote(text: String) {}
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
        // Reset every seam the test swapped, so the next test never inherits it.
        VoiceCallEngine.waitingCueRunner = { true }
        VoiceCallEngine.llmRunner = { _, _, _, _, _ -> "ok" }
        VoiceCallEngine.sttRunner = { _, _ -> "hello" }
        VoiceCallEngine.ttsRunner = { _, _, _, _ -> }
    }

    @Test
    fun aFailedExchangeStopsTheCueInsteadOfHoldingItAlive() = runBlocking {
        val cueTicks = AtomicInteger(0)
        VoiceCallEngine.waitingCueRunner = { cueTicks.incrementAndGet(); true }
        VoiceCallEngine.sttRunner = { _, _ -> "hello" }
        // The LLM fails: exactly what a refused tool round did on the wire.
        VoiceCallEngine.llmRunner = { _, _, _, _, _ ->
            throw java.io.IOException("HTTP 500: simulated round failure")
        }
        VoiceCallEngine.ttsRunner = { _, _, _, _ -> }

        val host = FakeHost()
        engine.startAppMode(host)
        val job = engine.runExchange(ByteArray(1), host, java.util.concurrent.atomic.AtomicBoolean(false))
        // The exchange reports the failure and ends quickly.
        withTimeout(5000) { job?.join() }
        assertEquals(1, host.errors.size)

        // Long past the grace window and several cue periods: a leaked cue
        // would have ticked here. The grace is 1.2s and the period 2.5s, so
        // 4s after the exchange ended must show no further ticks.
        delay(4000)
        val afterEnd = cueTicks.get()
        delay(3000)
        assertEquals(
            "the cue must not tick after the exchange has ended",
            afterEnd, cueTicks.get()
        )
        assertTrue("the cue never started ticking at all is fine, but the count must not grow", true)
    }

    @Test
    fun hangUpDuringTheCueSilencesItImmediately() = runBlocking {
        val cueTicks = AtomicInteger(0)
        VoiceCallEngine.waitingCueRunner = { cueTicks.incrementAndGet(); true }
        VoiceCallEngine.sttRunner = { _, _ -> "hello" }
        // The LLM stalls like a hung tool round; the user hangs up mid-thought.
        val llmStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        VoiceCallEngine.llmRunner = { _, _, _, _, _ ->
            llmStarted.complete(Unit)
            delay(10000)
            "never reached"
        }
        VoiceCallEngine.ttsRunner = { _, _, _, _ -> }

        val host = FakeHost()
        engine.startAppMode(host)
        val job = engine.runExchange(ByteArray(1), host, java.util.concurrent.atomic.AtomicBoolean(false))
        withTimeout(5000) { llmStarted.await() }
        // Let the grace window pass so the cue is actually ticking.
        delay(2000)
        engine.hangUp("cue lifetime test")
        withTimeout(5000) { job?.join() }

        delay(3000)
        val afterHangUp = cueTicks.get()
        delay(3000)
        assertEquals(
            "after the hang-up the cue must be silent",
            afterHangUp, cueTicks.get()
        )
    }
}
