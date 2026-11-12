package ai.flossyapp.test

import ai.flossyapp.ui.common.bestMatchCost
import ai.flossyapp.ui.common.confirmingWindows
import ai.flossyapp.ui.common.convertPcmToWav
import ai.flossyapp.ui.common.extractWakeFeatures
import ai.flossyapp.ui.common.parseWavBytes
import ai.flossyapp.ui.common.wakeCostThreshold
import ai.flossyapp.ui.common.wakeWordHeard
import org.junit.Test
import java.io.File

/**
 * The wake matcher against the user's own voice rather than synthetic fixtures.
 *
 * The sine fixtures in [WakeWordDetectorTest] share too much structure for a
 * threshold to separate, which is why the calibration constants were measured
 * over real recordings instead: five hand-recorded takes of "hey qwen" stand in
 * for enrollment and a 24 s recording of the same phrase five times, with
 * rambling in between, stands in for what the idle pass hands the matcher.
 *
 * Those recordings live under [test_audio], which is git-ignored, so this oracle
 * only runs on the machine that holds them. A missing fixture fails loudly
 * rather than passing vacuously - silently skipping is what would turn this file
 * back into a test that measures nothing.
 */
class WakeWordRealEnrollmentTest {

    private val referencePaths = listOf(1, 2, 3, 4, 5).map { "test_audio/hey_qwen-references/$it.wav" }

    // Walks up from the test's working directory the same way the bundled-speech
    // fixtures are found, so the test does not depend on where Gradle runs it.
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

    // The recordings are 44.1 kHz stereo. The detector wants mono 16 kHz, and the
    // app gets mono 16 kHz straight off the microphone, so keep the left channel
    // and leave the rate alone: the production resampling then does the rate
    // change exactly as it does on the device.
    private fun monoPcm(asset: String): Pair<Int, ByteArray> {
        val parsed = parseWavBytes(fixture(asset))
        if (parsed == null || parsed.first <= 0) return Pair(16000, ByteArray(0))
        val payload = parsed.second
        // Two 16-bit channels, so four bytes per frame, not two.
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

    // Enrollment goes through recordAudioWithVAD, which cuts the clip to the
    // utterance itself: the first and last frame above a fraction of the peak,
    // padded by the same margins. A hand-recorded WAV carries the silence around
    // the phrase, so an enrolled reference and a hand recording are only
    // comparable once this cut is applied.
    private fun enrolledFeatures(asset: String): FloatArray {
        val (rate, pcm) = monoPcm(asset)
        val samples = pcm.size / 2
        val win = kotlin.math.max(1, rate / 100)
        val rms = mutableListOf<Double>()
        var peak = 0.0
        for (i in 0..<(samples - win) step win) {
            var total = 0.0
            for (j in i..<(i + win)) {
                val lo = pcm[j * 2].toInt() and 0xFF
                val v = ((pcm[j * 2 + 1].toInt() shl 8) or lo).toDouble()
                total += v * v
            }
            val level = kotlin.math.sqrt(total / win)
            rms.add(level)
            if (level > peak) peak = level
        }
        val limit = 0.35 * peak
        var firstFrame = -1
        var lastFrame = -1
        for (i in rms.indices) {
            if (rms[i] > limit) {
                if (firstFrame < 0) firstFrame = i
                lastFrame = i
            }
        }
        if (firstFrame < 0) return extractWakeFeatures(convertPcmToWav(pcm, rate))
        val from = kotlin.math.max(0, firstFrame - 30) * win
        val to = kotlin.math.min(samples, (lastFrame + 1 + 50) * win)
        val out = ByteArray((to - from) * 2)
        for (i in 0..<(to - from)) {
            out[i * 2] = pcm[(from + i) * 2]
            out[i * 2 + 1] = pcm[(from + i) * 2 + 1]
        }
        return extractWakeFeatures(convertPcmToWav(out, rate))
    }

    private fun framesOf(features: FloatArray): Int = features.size / 8

    private fun c3(value: Float): String = "${kotlin.math.round(value * 1000.0) / 1000.0}"

    // How many of the enrolled takes agree that a clip is the phrase - the same
    // majority the live decision applies.
    private fun votesFor(clip: FloatArray, references: List<FloatArray>, threshold: Float): Int =
        references.count { confirmingWindows(clip, it, threshold) >= 2 }

    @Test
    fun enrollmentOfFiveRealTakes_agreesWithItself() {
        // Enrollment is what sets the tolerance, so its own spread is the first
        // thing to pin: five takes of one phrase are not near-identical, they sit
        // a third of the way across the cost scale. A matcher calibrated against
        // a ~0.02 synthetic spread would put its bar nowhere near the boundary
        // that separates real speech.
        val references = referencePaths.map { enrolledFeatures(it) }
        assert(references.all { framesOf(it) > 0 }) {
            "an enrolled take produced no features: " + references.map { framesOf(it) }.joinToString(",")
        }
        // Each take is a couple of seconds of the same two words.
        assert(references.all { framesOf(it) in 100..160 }) {
            "an enrolled take was not about two seconds long: " + references.map { framesOf(it) }.joinToString(",")
        }
        val costs = mutableListOf<Float>()
        for (i in references.indices) {
            for (j in (i + 1)..<references.size) costs.add(bestMatchCost(references[i], listOf(references[j])))
        }
        val cheapest = costs.min()
        val dearest = costs.max()
        assert(cheapest >= 0.25f && dearest <= 0.45f) {
            "the five takes spread from ${c3(cheapest)} to ${c3(dearest)}, outside the measured 0.30-0.40"
        }
    }

    @Test
    fun wakeWordHeard_firesOnTheRealPhraseAtEverySensitivity() {
        // The recall half. The whole 24 s recording is handed over untrimmed, the
        // harder case than the idle pass's VAD cut: more window positions means
        // more chances for one flattering alignment to fake a match, so a
        // trigger that survives it survives the trimmed one too.
        val references = referencePaths.map { enrolledFeatures(it) }
        val clip = features("test_audio/hey_qwen_wakeword_test_5x.wav")
        assert(references.all { framesOf(it) > 0 } && framesOf(clip) > 0) {
            "the real recordings produced no features"
        }
        for (sensitivity in listOf(0.0f, 0.5f, 1.0f)) {
            val threshold = wakeCostThreshold(sensitivity, references)
            val agreeing = votesFor(clip, references, threshold)
            assert(agreeing >= 3) {
                "at sensitivity $sensitivity only $agreeing of ${references.size} enrolled takes agreed, threshold ${c3(threshold)}"
            }
            assert(wakeWordHeard(clip, references, sensitivity)) {
                "the real phrase missed at sensitivity $sensitivity, threshold ${c3(threshold)}"
            }
        }
    }

    @Test
    fun wakeWordHeard_doesNotFireOnUnrelatedSpeechAgainstTheRealEnrollment() {
        // The precision half, and the margin matters: unrelated speech has to
        // stay short of the majority bound at the loosest setting, not merely
        // fail by one vote. Measured, it confirms on nothing until the threshold
        // reaches ~0.55 while the slider tops out at ~0.45.
        val references = referencePaths.map { enrolledFeatures(it) }
        val unrelated = features("test_audio/voice-clone.wav")
        assert(references.all { framesOf(it) > 0 } && framesOf(unrelated) > 0) {
            "the real recordings produced no features"
        }
        for (sensitivity in listOf(0.0f, 0.5f, 1.0f)) {
            val threshold = wakeCostThreshold(sensitivity, references)
            val agreeing = votesFor(unrelated, references, threshold)
            assert(agreeing <= 1) {
                "at sensitivity $sensitivity $agreeing of ${references.size} enrolled takes agreed on speech that is not the phrase, threshold ${c3(threshold)}"
            }
            assert(!wakeWordHeard(unrelated, references, sensitivity)) {
                "unrelated speech fired the wake word at sensitivity $sensitivity, threshold ${c3(threshold)}"
            }
        }
    }

    @Test
    fun realCosts_leaveTheBoundaryRoomOnBothSides() {
        // The calibration itself, pinned to the recordings: the phrase inside the
        // long clip reaches ~0.36 against each enrolled take and unrelated
        // speech ~0.50, so the boundary has a gap to live in. If a future change
        // moves the constants out of that gap, this is the test that says so.
        val references = referencePaths.map { enrolledFeatures(it) }
        val clip = features("test_audio/hey_qwen_wakeword_test_5x.wav")
        val unrelated = features("test_audio/voice-clone.wav")
        val genuine = references.map { bestMatchCost(clip, listOf(it)) }
        val other = references.map { bestMatchCost(unrelated, listOf(it)) }
        assert(genuine.all { it >= 0.30f && it <= 0.40f }) {
            "the real phrase cost " + genuine.map { c3(it) }.joinToString(",") + " against the enrolled takes"
        }
        assert(other.all { it >= 0.45f && it <= 0.60f }) {
            "unrelated speech cost " + other.map { c3(it) }.joinToString(",") + " against the enrolled takes"
        }
        for (sensitivity in listOf(0.0f, 0.5f, 1.0f)) {
            val threshold = wakeCostThreshold(sensitivity, references)
            assert(threshold >= 0.36f && threshold <= 0.45f) {
                "at sensitivity $sensitivity the threshold ${c3(threshold)} left the gap between the real phrase at ~0.36 and unrelated speech at ~0.48"
            }
        }
    }

    @Test
    fun confirmingWindows_countTheRealPhraseAtEveryWindowPosition() {
        // The agreement itself, not just its sign: the genuine clip confirms on
        // tens of window positions across a 24 s recording while unrelated speech
        // confirms on none, so no single lucky alignment is what fires the wake
        // word.
        val references = referencePaths.map { enrolledFeatures(it) }
        val clip = features("test_audio/hey_qwen_wakeword_test_5x.wav")
        val unrelated = features("test_audio/voice-clone.wav")
        for (sensitivity in listOf(0.0f, 0.5f, 1.0f)) {
            val threshold = wakeCostThreshold(sensitivity, references)
            val genuine = references.map { confirmingWindows(clip, it, threshold) }
            val other = references.map { confirmingWindows(unrelated, it, threshold) }
            assert(genuine.count { it >= 2 } > other.count { it >= 2 }) {
                "at sensitivity $sensitivity unrelated speech agreed on " +
                    "${other.count { it >= 2 }} references, the real phrase on ${genuine.count { it >= 2 }}"
            }
            // The measured margin: unrelated speech has no cheap window at all
            // below the precision cliff, while the genuine clip clears the
            // agreement bound on most of the enrolled takes.
            assert(other.all { it == 0 }) {
                "at sensitivity $sensitivity unrelated speech confirmed on " + other.joinToString(",")
            }
            assert(genuine.count { it >= 2 } >= 3) {
                "at sensitivity $sensitivity only " +
                    "${genuine.count { it >= 2 }} of ${genuine.size} enrolled takes confirmed the real phrase on " +
                    "${genuine.joinToString(",")} window positions"
            }
        }
    }

    // The shape the device actually hands the matcher. recordAudioWithVAD trims
    // the live clip to the utterance, so a wake clip is about as long as the
    // enrolled references and the sliding window has a single position to sit
    // in - there is nothing to agree over. The 24 s recording above is the
    // easier case, which is why it never exposed this.
    @Test
    fun wakeWordHeard_firesOnATightLiveClipWithOnlyOneWindowPosition() {
        val references = referencePaths.map { enrolledFeatures(it) }
        assert(references.all { framesOf(it) > 0 }) { "an enrolled take produced no features" }
        for (take in referencePaths) {
            val clip = enrolledFeatures(take)
            val positions = references.map { kotlin.math.max(0, framesOf(clip) - framesOf(it)) + 1 }
            assert(positions.min() == 1) {
                "take $take against the enrollment left " + positions.joinToString(",") +
                    " window positions, expected a tight clip to leave one"
            }
            for (sensitivity in listOf(0.0f, 0.5f, 1.0f)) {
                assert(wakeWordHeard(clip, references, sensitivity)) {
                    "a live clip trimmed to one utterance missed at sensitivity $sensitivity, " +
                        "costs " + references.map { bestMatchCost(clip, listOf(it)) }.map { c3(it) }.joinToString(",") +
                        " against the enrollment, threshold ${c3(wakeCostThreshold(sensitivity, references))}"
                }
            }
        }
    }

    @Test
    fun wakeWordHeard_doesNotFireOnUnrelatedSpeechTrimmedToOneUtterance() {
        // The precision half of the same tight geometry: unrelated speech cut to
        // one utterance has a single position too, so the agreement bound cannot
        // be what holds the boundary back there - only the calibrated cost can.
        val references = referencePaths.map { enrolledFeatures(it) }
        val unrelated = enrolledFeatures("test_audio/voice-clone.wav")
        assert(references.all { framesOf(it) > 0 } && framesOf(unrelated) > 0) {
            "the real recordings produced no features"
        }
        val costs = references.map { bestMatchCost(unrelated, listOf(it)) }
        assert(costs.all { it >= 0.45f && it <= 0.60f }) {
            "unrelated speech trimmed to one utterance cost " + costs.map { c3(it) }.joinToString(",")
        }
        for (sensitivity in listOf(0.0f, 0.5f, 1.0f)) {
            assert(!wakeWordHeard(unrelated, references, sensitivity)) {
                "unrelated speech trimmed to one utterance fired the wake word at sensitivity $sensitivity"
            }
        }
    }

    // A reference agrees when it confirms over as many window positions as the
    // clip leaves it: the production bound, which collapses to one for a clip
    // trimmed to the utterance. votesFor above keeps the fixed bound of two and
    // answers a different question - the long-clip oracle.
    private fun agrees(clip: FloatArray, reference: FloatArray, threshold: Float): Boolean {
        val positions = kotlin.math.max(
            0,
            framesOf(clip) - kotlin.math.min(framesOf(reference), framesOf(clip)) + 1,
        )
        return confirmingWindows(clip, reference, threshold) >= kotlin.math.min(2, positions)
    }

    // The enrollment the user actually builds by tapping "Record wake reference"
    // three times: an odd count, so the majority is two of three and no single
    // lucky reference can fire the wake word on its own. The probe over the tab's
    // own enrollment showed a three-reference file firing on every take at every
    // sensitivity - this pins that shape permanently, because the five-reference
    // tests above never exercise a majority of two.
    @Test
    fun wakeWordHeard_firesOnAThreeReferenceEnrollmentByMajority() {
        val all = referencePaths.map { enrolledFeatures(it) }
        assert(all.all { framesOf(it) > 0 }) { "an enrolled take produced no features" }
        val unrelated = enrolledFeatures("test_audio/voice-clone.wav")
        assert(framesOf(unrelated) > 0) { "the unrelated recording produced no features" }
        for (pick in listOf(listOf(0, 1, 2), listOf(0, 2, 4), listOf(1, 3, 4))) {
            val three = pick.map { all[it] }
            for (sensitivity in listOf(0.0f, 0.5f, 1.0f)) {
                val threshold = wakeCostThreshold(sensitivity, three)
                for ((index, take) in all.withIndex()) {
                    val votes = three.count { agrees(take, it, threshold) }
                    assert(votes >= 2) {
                        "take ${index + 1} of the real phrase cleared only $votes of ${three.size} " +
                            "references at sensitivity $sensitivity, threshold ${c3(threshold)}"
                    }
                    assert(wakeWordHeard(take, three, sensitivity)) {
                        "take ${index + 1} of the real phrase missed a three-reference enrollment " +
                            "at sensitivity $sensitivity, costs " +
                            three.map { c3(bestMatchCost(take, listOf(it))) }.joinToString(",") +
                            ", threshold ${c3(threshold)}"
                    }
                }
                val stray = three.count { agrees(unrelated, it, threshold) }
                assert(stray <= 1) {
                    "at sensitivity $sensitivity $stray of ${three.size} references agreed on speech " +
                        "that is not the phrase, threshold ${c3(threshold)}"
                }
                assert(!wakeWordHeard(unrelated, three, sensitivity)) {
                    "unrelated speech fired a three-reference enrollment at sensitivity $sensitivity"
                }
            }
        }
    }

    // The enrollment cap. MAX_REFERENCES is 8, so "over seven references" is what
    // tapping "Record wake reference" repeatedly reaches and the majority bound
    // becomes four of eight. Measured here, every take of the phrase still clears
    // that bound at every sensitivity and unrelated speech still confirms on
    // nothing - so enrollment size is not what breaks the detector, and a cost
    // reported as "no match" cannot be the bar sitting too tight. That sentinel
    // only comes back when the enrollment itself is unreadable, which is what
    // pinning it here keeps honest.
    @Test
    fun wakeWordHeard_firesOnAFullEightReferenceEnrollmentAndNeverReportsTheSentinel() {
        val all = referencePaths.map { enrolledFeatures(it) }
        assert(all.all { framesOf(it) > 0 }) { "an enrolled take produced no features" }
        val unrelated = enrolledFeatures("test_audio/voice-clone.wav")
        assert(framesOf(unrelated) > 0) { "the unrelated recording produced no features" }
        for (count in listOf(7, 8)) {
            val refs = mutableListOf<FloatArray>()
            var taken = 0
            while (refs.size < count) {
                refs += all[taken % all.size]
                taken++
            }
            for (sensitivity in listOf(0.0f, 0.5f, 1.0f)) {
                val threshold = wakeCostThreshold(sensitivity, refs)
                for ((index, take) in all.withIndex()) {
                    val votes = refs.count { agrees(take, it, threshold) }
                    assert(votes * 2 >= refs.size) {
                        "take ${index + 1} of the real phrase cleared only $votes of ${refs.size} " +
                            "references at sensitivity $sensitivity, threshold ${c3(threshold)}"
                    }
                    assert(wakeWordHeard(take, refs, sensitivity)) {
                        "take ${index + 1} of the real phrase missed a $count-reference enrollment " +
                            "at sensitivity $sensitivity, costs " +
                            refs.map { c3(bestMatchCost(take, listOf(it))) }.joinToString(",") +
                            ", threshold ${c3(threshold)}"
                    }
                }
                val aggregate = bestMatchCost(all[0], refs)
                assert(aggregate < Float.MAX_VALUE / 2.0f) {
                    "a $count-reference enrollment answered the sentinel for a clip that is the phrase"
                }
                assert(aggregate >= 0.25f && aggregate <= 0.45f) {
                    "a $count-reference enrollment put the phrase at ${c3(aggregate)}, off the measured band"
                }
                val stray = refs.count { agrees(unrelated, it, threshold) }
                assert(stray * 2 < refs.size) {
                    "at sensitivity $sensitivity $stray of ${refs.size} references agreed on speech " +
                        "that is not the phrase, threshold ${c3(threshold)}"
                }
                assert(!wakeWordHeard(unrelated, refs, sensitivity)) {
                    "unrelated speech fired a $count-reference enrollment at sensitivity $sensitivity"
                }
            }
        }
    }
}
