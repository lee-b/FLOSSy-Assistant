package ai.flossyapp.test

import ai.flossyapp.ui.common.bestMatchCost
import ai.flossyapp.ui.common.confirmingWindows
import ai.flossyapp.ui.common.referenceFrameCount
import ai.flossyapp.ui.common.wakeAgreement
import ai.flossyapp.ui.common.wakeCostThreshold
import ai.flossyapp.ui.common.wakeWordHeard
import ai.flossyapp.ui.common.convertPcmToWav
import ai.flossyapp.ui.common.extractWakeFeatures
import ai.flossyapp.ui.common.parseWavBytes
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The wake matcher against the enrollment the tab actually holds.
 *
 * [WakeWordRealEnrollmentTest] builds enrollment inside the test process from hand-recorded
 * takes and the matcher fires on it at every sensitivity. The device reports the opposite, so
 * the only place the two can disagree is the enrollment itself - the matcher, the threshold and
 * the aggregation are the same code either way. This oracle closes that gap by reading the
 * enrollment dumps captured off the tab (see [test_audio], read-only: they are never written
 * back to the device) in the exact line format [loadWakeReferences] parses, and scoring the
 * same real-phrase clip against them.
 *
 * The dumps separate cleanly. A healthy enrollment puts the phrase at ~0.36 and confirms over
 * tens of window positions; the tab's own three-reference file puts the phrase at ~0.48,
 * confirms on nothing at any sensitivity, and scores unrelated speech at ~0.16 - cheaper than
 * the phrase by a third of the scale. Its references are also near-identical to one another
 * (pairwise ~0.02 where five genuine takes spread 0.25-0.45), which is what three captures of
 * one and the same audio source look like, not three human takes.
 */
class WakeWordDeviceEnrollmentTest {

    private val referencePaths = listOf(1, 2, 3, 4, 5).map { "test_audio/hey_qwen-references/$it.wav" }
    private val phraseClip = "test_audio/hey_qwen_wakeword_test_5x.wav"
    private val unrelatedClip = "test_audio/voice-clone.wav"

    // The enrollment dumps are git-ignored captures off the tab. A missing one fails loudly
    // rather than passing vacuously, the same rule the fixture oracle applies.
    private val dumpPaths =
        listOf("test_audio/device_wake_references.txt", "test_audio/tab_wake_references_now.txt")

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

    // The recordings are 44.1 kHz stereo; the app gets mono 16 kHz off the microphone, so keep
    // the left channel and let the production resampling do the rate change.
    private fun monoPcm(asset: String): Pair<Int, ByteArray> {
        val parsed = parseWavBytes(fixture(asset))
        if (parsed == null || parsed.first <= 0) return Pair(16000, ByteArray(0))
        val payload = parsed.second
        val frames = payload.size / 4
        val mono = ByteArray(frames * 2)
        for (i in 0..<frames) {
            mono[i * 2] = payload[i * 4]
            mono[i * 2 + 1] = payload[i * 4 + 1]
        }
        return Pair(parsed.first, mono)
    }

    private fun features(asset: String): FloatArray =
        extractWakeFeatures(convertPcmToWav(monoPcm(asset).second, monoPcm(asset).first))

    // Enrollment goes through recordAudioWithVAD, which cuts the clip to the utterance: the
    // first and last frame above a fraction of the peak, padded by the same margins. A hand
    // recording carries the silence around the phrase, so an enrolled reference and a hand
    // recording are only comparable once this cut is applied.
    private fun enrolledFeatures(asset: String): FloatArray {
        val (rate, pcm) = monoPcm(asset)
        val samples = pcm.size / 2
        val win = kotlin.math.max(1, rate / 100)
        val levels = mutableListOf<Double>()
        var peak = 0.0
        for (i in 0..<(samples - win) step win) {
            var total = 0.0
            for (j in i..<(i + win)) {
                val lo = pcm[j * 2].toInt() and 0xFF
                val v = ((pcm[j * 2 + 1].toInt() shl 8) or lo).toDouble()
                total += v * v
            }
            val level = kotlin.math.sqrt(total / win)
            levels.add(level)
            if (level > peak) peak = level
        }
        val limit = 0.35 * peak
        var firstFrame = -1
        var lastFrame = -1
        for (i in levels.indices) {
            if (levels[i] > limit) {
                if (firstFrame < 0) firstFrame = i
                lastFrame = i
            }
        }
        if (firstFrame < 0) return features(asset)
        val from = kotlin.math.max(0, firstFrame - 30) * win
        val to = kotlin.math.min(samples, (lastFrame + 1 + 50) * win)
        val out = ByteArray((to - from) * 2)
        for (i in 0..<(to - from)) {
            out[i * 2] = pcm[(from + i) * 2]
            out[i * 2 + 1] = pcm[(from + i) * 2 + 1]
        }
        return extractWakeFeatures(convertPcmToWav(out, rate))
    }

    // loadWakeReferences' own reader: "<frameCount> <values...>", blank and '#' lines skipped,
    // a line whose value count is not a whole number of frames rejected. Reimplementing the
    // parse is deliberate - the dumps are diagnosis data, and the matcher is what gets asserted.
    private fun enrolledRefs(asset: String): List<FloatArray> {
        val references = mutableListOf<FloatArray>()
        for (line in fixture(asset).decodeToString().split('\n')) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(' ').filter { it.isNotEmpty() }
            if (parts.size < 2) continue
            val values = parts.drop(1).mapNotNull { it.toFloatOrNull() }
            if (values.size % 8 != 0) continue
            if (values.isNotEmpty()) references += values.toFloatArray()
        }
        return references
    }

    private fun framesOf(features: FloatArray): Int = referenceFrameCount(features)

    private fun c3(value: Float): String = "${kotlin.math.round(value * 1000.0) / 1000.0}"

    private fun costs(clip: FloatArray, references: List<FloatArray>): List<Float> =
        references.map { bestMatchCost(clip, listOf(it)) }

    // The median pairwise cost of an enrollment against itself: what distinguishes an
    // enrollment of several human takes from one of several captures of a single source.
    private fun selfSpread(references: List<FloatArray>): Float {
        val pairwise = mutableListOf<Float>()
        for (i in references.indices) {
            for (j in (i + 1)..<references.size) pairwise += bestMatchCost(references[i], listOf(references[j]))
        }
        if (pairwise.isEmpty()) return -1f
        pairwise.sort()
        return pairwise[pairwise.size / 2]
    }

    @Test
    fun theRealPhraseFiresAgainstEnrollmentBuiltFromTheReferenceClips() {
        // The oracle the whole report needs: the reference clips stand in for enrollment and the
        // test audio stands in for a live clip, and the algorithm has to fire on that pair at
        // every sensitivity. It does, which is why the matcher is not what the device is missing.
        val references = referencePaths.map { enrolledFeatures(it) }
        val clip = features(phraseClip)
        assert(references.all { framesOf(it) > 0 } && framesOf(clip) > 0) {
            "the reference clips or the test audio produced no features"
        }
        for (sensitivity in listOf(0.0f, 0.5f, 1.0f)) {
            val threshold = wakeCostThreshold(sensitivity, references)
            val perTake = costs(clip, references)
            // The production agreement, not a cost comparison: a reference votes when it
            // confirms over as many window positions as the clip leaves it.
            val votes = references.count {
                val positions = framesOf(clip) - min(framesOf(it), framesOf(clip)) + 1
                confirmingWindows(clip, it, threshold) >= min(2, positions)
            }
            assert(votes * 2 >= references.size) {
                "at sensitivity $sensitivity only $votes of ${references.size} reference clips agreed " +
                    "on the test audio, costs " + perTake.map { c3(it) }.joinToString(",") +
                    ", threshold ${c3(threshold)}"
            }
            assert(wakeWordHeard(clip, references, sensitivity)) {
                "the algorithm missed the real phrase at sensitivity $sensitivity, costs " +
                    perTake.map { c3(it) }.joinToString(",") + ", threshold ${c3(threshold)}"
            }
        }
    }

    @Test
    fun aHealthyEnrollmentScoresThePhraseAndConfirmsOverManyWindows() {
        // The dump that behaves: five takes, 121-131 frames apiece, the phrase at ~0.36 and
        // dozens of confirming window positions. This is the shape a working enrollment has,
        // pinned so the comparison below has something to be worse than.
        val references = enrolledRefs("test_audio/tab_wake_references_now.txt")
        assert(references.size == 5) { "the healthy enrollment dump holds ${references.size} references" }
        assert(references.all { framesOf(it) in 115..135 }) {
            "a reference in the healthy dump is not about two seconds: " +
                references.map { framesOf(it) }.joinToString(",")
        }
        val spread = selfSpread(references)
        assert(spread >= 0.25f && spread <= 0.45f) {
            "the healthy enrollment's takes spread ${c3(spread)}, off the 0.25-0.45 band real takes measure"
        }
        val clip = features(phraseClip)
        assert(framesOf(clip) > 0) { "the test audio produced no features" }
        for (sensitivity in listOf(0.0f, 0.5f, 1.0f)) {
            val threshold = wakeCostThreshold(sensitivity, references)
            val perTake = costs(clip, references)
            assert(perTake.all { it >= 0.30f && it <= 0.40f }) {
                "the test audio cost " + perTake.map { c3(it) }.joinToString(",") +
                    " against the healthy enrollment, off the measured 0.30-0.40"
            }
            assert(perTake.all { it <= threshold }) {
                "at sensitivity $sensitivity the healthy enrollment put the phrase at " +
                    perTake.map { c3(it) }.joinToString(",") + " over threshold ${c3(threshold)}"
            }
            assert(wakeWordHeard(clip, references, sensitivity)) {
                "the healthy enrollment missed the test audio at sensitivity $sensitivity"
            }
            val windows = references.map { confirmingWindows(clip, it, threshold) }
            assert(windows.all { it >= 2 }) {
                "at sensitivity $sensitivity a reference in the healthy enrollment confirmed on " +
                    windows.joinToString(",") + " window positions"
            }
        }
    }

    @Test
    fun theTabEnrollmentScoresThePhraseWhereNoSensitivityReaches() {
        // The device's own NO MATCH, reproduced inside the test process. Costs land at ~0.48
        // against a slider that tops out at ~0.45, and not one reference confirms on a single
        // window position - the same line the screen shows, computed here from the dump.
        val references = enrolledRefs("test_audio/device_wake_references.txt")
        assert(references.size == 3) { "the tab enrollment dump holds ${references.size} references" }
        val clip = features(phraseClip)
        assert(framesOf(clip) > 0 && references.all { framesOf(it) > 0 }) {
            "the tab enrollment dump or the test audio produced no features"
        }
        for (sensitivity in listOf(0.0f, 0.5f, 1.0f)) {
            val threshold = wakeCostThreshold(sensitivity, references)
            val perTake = costs(clip, references)
            assert(perTake.all { it >= 0.45f && it <= 0.60f }) {
                "the tab enrollment scored the test audio at " + perTake.map { c3(it) }.joinToString(",") +
                    ", off the 0.45-0.60 the device reports"
            }
            assert(perTake.all { it > threshold }) {
                "at sensitivity $sensitivity the tab enrollment cleared threshold ${c3(threshold)}"
            }
            val windows = references.map { confirmingWindows(clip, it, threshold) }
            assert(windows.all { it == 0 }) {
                "at sensitivity $sensitivity the tab enrollment confirmed on " + windows.joinToString(",") +
                    " window positions, expected none"
            }
            assert(!wakeWordHeard(clip, references, sensitivity)) {
                "the tab enrollment fired on the test audio at sensitivity $sensitivity"
            }
            assert(wakeAgreement(clip, references, sensitivity).all { it.startsWith("0/") }) {
                "the tab enrollment agreed on the test audio at sensitivity $sensitivity: " +
                    wakeAgreement(clip, references, sensitivity).joinToString(" ")
            }
        }
    }

    @Test
    fun theTabEnrollmentIsNotTakesOfThePhrase() {
        // What names the diverging input. Three references a third of a cost unit apart cannot
        // be three human takes of two words, and an enrollment that scores unrelated speech a
        // third of the scale cheaper than the phrase carries the wrong audio, not the right
        // words at a bad angle. Both halves have to hold for the diagnosis to stand.
        val references = enrolledRefs("test_audio/device_wake_references.txt")
        assert(references.size == 3) { "the tab enrollment dump holds ${references.size} references" }
        val spread = selfSpread(references)
        assert(spread <= 0.05f) {
            "the tab enrollment's references sit ${c3(spread)} apart, inside the near-identical band"
        }
        assert(references.all { framesOf(it) == framesOf(references[0]) }) {
            "the tab enrollment's references differ in length: " + references.map { framesOf(it) }.joinToString(",")
        }
        val unrelated = features(unrelatedClip)
        assert(framesOf(unrelated) > 0) { "the unrelated recording produced no features" }
        val againstPhrase = costs(features(phraseClip), references)
        val againstUnrelated = costs(unrelated, references)
        assert(againstUnrelated.all { it < 0.30f }) {
            "the tab enrollment scored unrelated speech at " + againstUnrelated.map { c3(it) }.joinToString(",")
        }
        assert(againstPhrase.all { it > 0.40f }) {
            "the tab enrollment scored the phrase at " + againstPhrase.map { c3(it) }.joinToString(",") +
                ", expected well above unrelated speech at " + againstUnrelated.map { c3(it) }.joinToString(",")
        }
        // The inversion is the whole point: the same enrollment that cannot see the phrase
        // recognises speech it was never asked to learn.
        assert(againstUnrelated.max() < againstPhrase.min()) {
            "the tab enrollment's cheapest phrase cost ${c3(againstPhrase.min())} is not above " +
                "its dearest unrelated cost ${c3(againstUnrelated.max())}"
        }
    }

    @Test
    fun enrollmentQualityIsTheOnlyThingThatDiffers() {
        // Both enrollments go through the same matcher, the same threshold and the same
        // majority. Scoring one clip against both in one test is what rules out the matcher,
        // the calibration and the aggregation as the device's fault.
        val healthy = enrolledRefs("test_audio/tab_wake_references_now.txt")
        val tab = enrolledRefs("test_audio/device_wake_references.txt")
        assert(healthy.size == 5 && tab.size == 3) {
            "the enrollment dumps hold ${healthy.size} and ${tab.size} references"
        }
        val clip = features(phraseClip)
        assert(framesOf(clip) > 0) { "the test audio produced no features" }
        for (sensitivity in listOf(0.0f, 0.5f, 1.0f)) {
            val healthyCosts = costs(clip, healthy)
            val tabCosts = costs(clip, tab)
            assert(healthyCosts.all { it < tabCosts.min() }) {
                "the healthy enrollment's dearest take ${c3(healthyCosts.max())} is not below " +
                    "the tab enrollment's cheapest ${c3(tabCosts.min())}"
            }
            assert(wakeWordHeard(clip, healthy, sensitivity) && !wakeWordHeard(clip, tab, sensitivity)) {
                "at sensitivity $sensitivity the two enrollments did not split: healthy " +
                    "${wakeWordHeard(clip, healthy, sensitivity)}, tab ${wakeWordHeard(clip, tab, sensitivity)}"
            }
        }
    }

    @Test
    fun anEnrolledTakeLongerThanTheLiveClipStillFires() {
        // The shape the tab actually runs. Its enrollment measures 139-227 frames while the
        // live clips the idle pass builds run 59-997, so most references OUTLAST the clip
        // they are scored against. Slicing windows only from the clip leaves every such
        // pair a single position to agree over, and the band then has to warp the whole
        // length difference into the path - which is where the 0.5-0.7 costs the device
        // reports come from. The window has to slice whichever side is longer.
        val references = referencePaths.drop(1).map { features(it) }
        val clip = enrolledFeatures(referencePaths.first())
        assert(references.all { framesOf(it) > framesOf(clip) }) {
            "the enrollment has to outlast the clip to exercise the mirrored window: " +
                references.map { framesOf(it) }.joinToString(",") + " frames against a clip of " + framesOf(clip)
        }
        for (sensitivity in listOf(0.0f, 0.5f, 1.0f)) {
            val threshold = wakeCostThreshold(sensitivity, references)
            val windows = references.map { confirmingWindows(clip, it, threshold) }
            // The production majority, not unanimity: one enrolled take can be a worse
            // angle than the rest and still lose its vote.
            val votes = references.count {
                confirmingWindows(clip, it, threshold) >=
                    kotlin.math.min(2, kotlin.math.abs(framesOf(clip) - framesOf(it)) + 1)
            }
            assert(votes * 2 >= references.size) {
                "at sensitivity $sensitivity only $votes of ${references.size} references longer than " +
                    "the clip agreed on it, windows " + windows.joinToString(",") +
                    ", costs " + costs(clip, references).map { c3(it) }.joinToString(",") +
                    ", threshold ${c3(threshold)}"
            }
            assert(wakeWordHeard(clip, references, sensitivity)) {
                "the matcher missed a take enrolled longer than the live clip at sensitivity " +
                    "$sensitivity, costs " + costs(clip, references).map { c3(it) }.joinToString(",") +
                    ", threshold ${c3(threshold)}"
            }
        }
    }
}
