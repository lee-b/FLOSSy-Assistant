package ai.flossyapp.test

import ai.flossyapp.ui.common.playOutUntilHead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drain discipline that plays a streaming AudioTrack out.
 *
 * The device-verified truncation came from stopping the track first and
 * polling the playback head afterwards: streaming stop() resets the head to
 * zero, the poll then read 0 forever, the stall guard fired, and the reply's
 * tail was still unplayed when the track was released. These tests pin the
 * two behaviours the fix lives on: the loop waits while the head is behind
 * the written frames, and a head that can never advance does not hang the
 * caller forever.
 */
class DrainDisciplineTest {

    @Test
    fun theDrainWaitsUntilTheHeadReachesTheWrittenFrames() {
        // A mixer that consumes frames as the poll runs (one read = ~20ms of
        // playback at the test rate): the drain must not stop the track
        // before the head caught up with the write count.
        val head = java.util.concurrent.atomic.AtomicLong(0)
        val totalFrames = 100_000L
        var stopCalls = 0
        val observed = playOutUntilHead(
            head = { head.addAndGet(2_000) },
            totalFrames = totalFrames,
            rate = 24_000,
            onDone = { stopCalls++ }
        )
        assertEquals(1, stopCalls)
        assertTrue(
            "drain returned before the head reached the written frames " +
                "(head=$observed of $totalFrames)",
            observed >= totalFrames
        )
    }

    @Test
    fun aHeadThatCannotAdvanceDoesNotHangTheCaller() {
        // The underrun-disabled track: the head is frozen below the written
        // count forever. The stall guard must break the wait and still stop
        // the track exactly once - a caller must never hang past a bounded
        // deadline, and a frozen head must not be mistaken for progress.
        val totalFrames = 100_000L
        var stopCalls = 0
        val observed = playOutUntilHead(
            head = { 0L },
            totalFrames = totalFrames,
            rate = 24_000,
            onDone = { stopCalls++ },
            stallLimitMs = 60L
        )
        assertEquals(1, stopCalls)
        assertTrue("a frozen head must be reported as it stood", observed == 0L)
    }

    @Test
    fun aHeadAlreadyAtTheWrittenFramesStopsAtOnce() {
        // The mixer finished before the drain started: the loop must not
        // wait out any deadline and must stop the track on the first read.
        var stopCalls = 0
        val startedAt = System.currentTimeMillis()
        val observed = playOutUntilHead(
            head = { 50_000L },
            totalFrames = 50_000L,
            rate = 24_000,
            onDone = { stopCalls++ }
        )
        assertEquals(1, stopCalls)
        assertEquals(50_000L, observed)
        assertTrue(
            "a finished track must not be waited on (${System.currentTimeMillis() - startedAt}ms)",
            System.currentTimeMillis() - startedAt < 1_000
        )
    }
}
