package ai.flossyapp.test

import ai.flossyapp.ui.common.voicedSpan
import ai.flossyapp.ui.common.VOICED_TRIM_FRACTION
import ai.flossyapp.ui.common.parseWavBytes
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The wake recorder's cut to the utterance has to be gain-independent.
 *
 * The matcher normalises gain before it compares anything, so the clip it is handed has to be
 * cut on the same scale. The recorder used to cut at an absolute RMS, and the tab showed what
 * that costs: the same phrase spoken at arm's length fell under the absolute cut and kept only
 * its loudest fraction of a second, so live clips of 71-139 frames were scored against enrolled
 * takes of 135-223, and that length gap alone put a genuine phrase at 0.41-0.59 against a 0.38
 * bar - every sensitivity missed, whatever the slider was set to.
 *
 * [voicedSpan] keys the cut to the clip's own loudest window, which makes the span invariant
 * under gain: enrollment and the live pass are one phrase at two loudnesses, and they have to
 * arrive at the same length.
 */
class WakeWordUtteranceTrimTest {

    private val takes = listOf(1, 2, 3).map { "test_audio/hey_qwen-references/$it.wav" }

    private fun fixture(asset: String): ByteArray {
        var path = java.io.File(".").absolutePath
        while (true) {
            val candidate = java.io.File("$path/$asset")
            if (candidate.exists()) return candidate.readBytes()
            val cut = path.lastIndexOf('/')
            if (cut <= 0) break
            path = path.substring(0, cut)
        }
        throw AssertionError(
            "wake fixture $asset is missing - this oracle needs test_audio/, which is git-ignored"
        )
    }

    // The recordings are 44.1 kHz stereo; the microphone hands over mono, so keep the left
    // channel and let the caller choose the window.
    private fun samples(asset: String): Pair<Int, MutableList<Short>> {
        val parsed = parseWavBytes(fixture(asset))
        if (parsed == null || parsed.first <= 0) return Pair(16000, mutableListOf<Short>())
        val payload = parsed.second
        val frames = payload.size / 4
        val out = mutableListOf<Short>()
        for (i in 0..<frames) {
            out.add(((payload[i * 4 + 1].toInt() shl 8) or (payload[i * 4].toInt() and 0xFF)).toShort())
        }
        return Pair(parsed.first, out)
    }

    private fun scaled(samples: MutableList<Short>, scale: Double): MutableList<Short> {
        val out = mutableListOf<Short>()
        for (v in samples) {
            val s = (v.toDouble() * scale).toInt()
            out.add(max(-32768, min(32767, s)).toShort())
        }
        return out
    }

    @Test
    fun theCutDoesNotMoveWithLoudness() {
        for (asset in takes) {
            val (rate, stream) = samples(asset)
            if (stream.isEmpty()) throw AssertionError("wake fixture $asset decoded to nothing")
            val window = max(1, rate / 100)

            val reference = voicedSpan(stream, window, VOICED_TRIM_FRACTION)
            // Eight times quieter is the arm's-length case that used to chop the clip: the
            // whole clip then sits near the recorder's absolute gate of 800 RMS.
            for (scale in listOf(0.125, 0.5, 8.0)) {
                val moved = voicedSpan(scaled(stream, scale), window, VOICED_TRIM_FRACTION)
                // Not exact equality: scaling a 16-bit stream quantizes it, so a window sitting
                // right on the limit can flip. The bound is that quantization and nothing more -
                // measured across these takes, the worst case is three windows at 0.125. The
                // absolute cut the recorder used before moved by hundreds of windows here.
                val drift = kotlin.math.max(
                    kotlin.math.abs(moved.first - reference.first),
                    kotlin.math.abs(moved.second - reference.second)
                )
                assert(drift <= 4 * window) {
                    "voicedSpan moved the cut for $asset by $drift samples when the clip was scaled " +
                        "by $scale: $reference -> $moved, $drift of a $window-sample window - that is " +
                        "more than 16-bit quantization at the limit, so the cut is still keyed to absolute loudness"
                }
            }
        }
    }

    @Test
    fun theCutRemovesTheSilenceAroundThePhrase() {
        for (asset in takes) {
            val (rate, stream) = samples(asset)
            if (stream.isEmpty()) throw AssertionError("wake fixture $asset decoded to nothing")
            val window = max(1, rate / 100)
            val pad = rate

            // One second of silent room on either side, which is what the microphone really
            // hears. The cut has to take it off, or the silent room is carried into the matcher
            // as noise and inflates every cost.
            val padded = mutableListOf<Short>()
            for (i in 0..<pad) padded.add(0.toShort())
            for (v in stream) padded.add(v)
            for (i in 0..<pad) padded.add(0.toShort())

            val (from, to) = voicedSpan(padded, window, VOICED_TRIM_FRACTION)
            assert(to > from) { "voicedSpan returned an empty span for $asset - the phrase would be dropped" }
            assert(from >= 0 && to <= padded.size) {
                "voicedSpan ran outside the buffer for $asset: $from..$to of ${padded.size}"
            }
            assert(from >= pad - window && to <= padded.size - pad + window) {
                "voicedSpan kept ${to - from} of ${padded.size} samples for $asset - a second of " +
                    "silent room on each side is being carried into the matcher as noise"
            }
        }
    }

    @Test
    fun theCutKeepsThePhraseRatherThanItsLoudestSyllable() {
        for (asset in takes) {
            val (rate, stream) = samples(asset)
            if (stream.isEmpty()) throw AssertionError("wake fixture $asset decoded to nothing")
            val window = max(1, rate / 100)

            // The ceiling has to sit above the room's noise floor, or it is not a ceiling on the
            // phrase at all: measured across these takes the silent room carries signal up to 5%
            // of the peak window (one of them stays above that gate for its whole 2.5 s), so a
            // 2% cut keeps the entire clip and says nothing about the utterance. A tenth of the
            // peak is above that floor and still well under the production fraction, so the span
            // it leaves is how much phrase a gain-independent cut can be asked to keep.
            val permissive = voicedSpan(stream, window, 0.10)
            val production = voicedSpan(stream, window, VOICED_TRIM_FRACTION)
            val kept = production.second - production.first
            val ceiling = permissive.second - permissive.first

            // Measured at these fixtures: 0.825, 0.875 and 0.879 of the 0.10 span. The failure
            // this oracle exists to catch is the recorder's old absolute cut, which kept 0.22.
            assert(kept >= 0.8 * ceiling) {
                "voicedSpan at the production fraction keeps only $kept of the $ceiling samples " +
                    "above the noise floor for $asset - the cut is chopping the phrase, not its margins"
            }
        }
    }

    @Test
    fun theCutLeavesAClipTheLengthTheEnrollmentActuallyHolds() {
        // The payoff the tab is waiting for, stated as a length rather than a cost. A healthy
        // enrollment measured on the device holds 121-131 MFCC frames per take, and at the
        // matcher's 16 kHz/160-sample stride that is 1.21-1.31 s of audio. The enrollment the
        // tab currently carries was cut by the old absolute gate and measures 135-223 frames,
        // which is why a genuine phrase costs 0.45-0.55 against it and never clears 0.39.
        // The new cut has to hand the enrollment the healthy shape, or re-recording cannot
        // help and the fix has bought nothing.
        for (asset in takes) {
            val (rate, stream) = samples(asset)
            if (stream.isEmpty()) throw AssertionError("wake fixture $asset decoded to nothing")
            val window = max(1, rate / 100)

            val (from, to) = voicedSpan(stream, window, VOICED_TRIM_FRACTION)
            // The recorder pads the span with these margins before it hands the clip over, and
            // they are what carries the plosives at the edges of the phrase.
            val cutFrom = max(0, from - rate * 3 / 10)
            val cutTo = min(stream.size, to + rate / 2)
            val seconds = (cutTo - cutFrom).toDouble() / rate

            assert(seconds >= 1.2 && seconds <= 1.4) {
                "the cut leaves $asset $seconds seconds long, outside the 1.21-1.31 s the healthy " +
                    "enrollment holds (121-131 frames) - a take of this length cannot be what the matcher learned"
            }
        }
    }

    @Test
    fun silenceHasNoSpanToCut() {
        val silent = mutableListOf<Short>()
        for (i in 0..<(16000 * 2)) silent.add(0.toShort())
        val span = voicedSpan(silent, 160, VOICED_TRIM_FRACTION)
        assert(span == Pair(0, silent.size)) {
            "voicedSpan invented an utterance out of a silent room: $span - the caller's " +
                "was-anything-heard gate is the thing that has to answer silence"
        }
    }
}
