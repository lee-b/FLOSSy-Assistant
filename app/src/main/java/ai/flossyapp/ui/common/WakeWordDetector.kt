package ai.flossyapp.ui.common

import android.content.Context
import android.util.Log
import java.io.File

/**
 * On-device wake-word detector: mel-frequency cepstral features plus dynamic
 * time warping against user-recorded references, the approach popularised by
 * https://github.com/GiviMAD/rustpotter (References mode - no training step).
 *
 * The detector runs entirely on the device over the recorded microphone clip, so
 * deciding "was the wake phrase spoken here?" never touches the STT endpoint.
 */
private const val MFCC_FRAME = 640
private const val MFCC_SHIFT = 160
private const val MFCC_CEPSTRAL = 4
private const val MFCC_FEATURES = MFCC_CEPSTRAL * 2
private const val FFT_SIZE = 1024
private const val MIN_FRAMES = 5
const val MAX_REFERENCE_FRAMES = 500
// The same limit in seconds, so enrollment can ask the user to keep the phrase
// short instead of quoting a frame count.
const val MAX_REFERENCE_SECONDS = MAX_REFERENCE_FRAMES * MFCC_SHIFT / 16000
private const val MAX_REFERENCES = 8
// Floor so identical enrollments (spread 0) still leave a little tolerance.
private const val MIN_MATCH_THRESHOLD = 0.2f
// Fallback spread when fewer than two references are enrolled, in normalised
// units. Measured enrollment of the same phrase spreads over ~0.02, so this
// only has to cover a lone take that was recorded carelessly.
private const val SINGLE_REFERENCE_SPREAD = 0.25f
// Cost a clip may reach at the middle of the sensitivity slider. Measured on
// the user's own enrollment: five takes of the phrase sit 0.30-0.40 apart, the
// phrase inside a 24 s recording costs 0.35-0.37 against them, and unrelated
// speech from another recording 0.48-0.53. The boundary therefore belongs around
// 0.40, with recall needing ~0.36 and precision giving back at ~0.55.
private const val MATCH_COST = 0.40f
// Most of the threshold that enrollment variation is allowed to add. Kept small
// on evidence: widening it for a loose enrollment is what pushes the bar past
// the precision cliff, and the cliff is only 0.15 above the recall floor.
private const val SPREAD_ALLOWANCE = 0.02f
private const val REFERENCE_FILE = "wake_references.txt"
// Samples are rescaled to this peak so enrollment and detection see the same
// loudness whatever the microphone gain, the distance, or the volume was.
private const val TARGET_PEAK = 0.9f
// Moving-average window: a low-pass that keeps roughly everything below
// half the sample rate divided by the window (~2.7 kHz at 16 kHz), where the
// phrase's formants live and room noise does not.
private const val LOWPASS_WINDOW = 3
// Peak frame energy under which a clip is silence outright.
private const val SILENCE_PEAK = 0.01f
// A frame counts as voiced at this fraction of the loudest frame...
private const val VOICED_FRACTION = 0.35f
// ...and as silence below this one. Speech has both; noise is uniform.
private const val QUIET_FRACTION = 0.12f
private const val MIN_SPEECH_FRAMES = 3
private const val MIN_QUIET_FRAMES = 2
// How far the warping path may stray from the diagonal, in frames. A phrase may
// be spoken faster or slower than an enrolled take, but it may not be stretched
// over unrelated speech: this is what keeps a windowed match from sliding a
// short reference across a long clip.
private const val BAND_SIZE = 5
// Where in the sorted per-reference costs the final cost is taken. Below three
// references this lands on the best one; from three on it lands on the median,
// so one lucky reference can no longer fire the wake word on its own.
private const val REFERENCE_PERCENTILE = 0.5f
// How many window positions must agree before the wake word counts as heard.
// Taking the best of every window is biased: an unrelated clip gets several
// chances to land one cheap window, while a genuine phrase confirms over a run
// of consecutive positions because the band absorbs the shift. Counting the
// agreements is what removes that bias, the same confirmation rustpotter calls
// min_scores.
//
// Measured over real speech, the agreement counts are nowhere near this bound:
// unrelated speech confirms on nothing at all up to a cost of ~0.5, while the
// phrase inside a longer clip confirms on 5 positions even at the strictest
// threshold. So the count only has to rule out a single lucky alignment -
// pushing it higher would trade recall for precision the calibrated threshold
// already provides, and a carelessly enrolled take can drop the genuine count.
private const val MIN_CONFIRMING_WINDOWS = 2

/**
 * Extracts the MFCC feature vectors of a WAV clip, returning an empty array when
 * the clip holds no speech. The layout is row-major:
 * [cepstral1..4, delta1..4] per 10 ms frame.
 */
fun extractWakeFeatures(wavBytes: ByteArray): FloatArray {
    val parsed = parseWavBytes(wavBytes) ?: return FloatArray(0)
    val samples = pcmToFloats(parsed.second, parsed.first)
    // Room noise must never reach the matcher: the features are normalised per
    // dimension, so a clip of noise is scaled up to look exactly as "loud" as
    // speech. Requiring voiced frames next to quiet ones is the one test that
    // survives that normalisation, and it is gain-invariant.
    if (!containsSpeech(samples)) return FloatArray(0)
    return mfcc(samples)
}

/**
 * Converts 16-bit little-endian PCM bytes to floats in [-1, 1], resampling to
 * 16 kHz when the WAV header advertises another rate (the energy-saving mode
 * drops the microphone to 8 kHz).
 */
private fun pcmToFloats(pcmBytes: ByteArray, sampleRate: Int): FloatArray {
    val count = pcmBytes.size / 2
    val out = FloatArray(count)
    for (i in 0..<count) {
        val lo = pcmBytes[i * 2].toInt() and 0xFF
        val hi = pcmBytes[i * 2 + 1].toInt()
        out[i] = ((hi shl 8) or lo).toFloat() / 32768.0f
    }
    if (sampleRate == 16000 || sampleRate <= 0) return out

    // A few seconds at 48 kHz is already past 134 k samples, so both products
    // here exceed Int32 and would hand a negative length to FloatArray.
    val target = (out.size.toLong() * 16000 / sampleRate).toInt()
    val resampled = FloatArray(target)
    for (i in 0..<target) {
        resampled[i] = out[(i.toLong() * sampleRate / 16000).toInt()]
    }
    return resampled
}

/**
 * Low-passes the clip and then scales its gain to [TARGET_PEAK].
 *
 * Both steps exist so that a reference recorded in one place matches a clip
 * recorded in another: the moving average drops the broadband part of room
 * noise, and the gain scaling removes the microphone gain, the distance to the
 * mouth and the speaking volume from the comparison. Nothing here is
 * per-clip normalised later, so the two paths cannot drift apart.
 */
private fun prepareSignal(samples: FloatArray): FloatArray {
    val out = FloatArray(samples.size)
    for (i in samples.indices) {
        var sum = 0.0f
        var taps = 0
        for (k in 0..<LOWPASS_WINDOW) {
            val j = i + k - LOWPASS_WINDOW / 2
            if (j >= 0 && j < samples.size) {
                sum += samples[j]
                taps++
            }
        }
        out[i] = sum / taps
    }
    normalizeGain(out)
    return out
}

/**
 * Scales the whole clip so its loudest sample sits at [TARGET_PEAK].
 *
 * A clip that is already silent stays silent: scaling it up would turn room
 * noise into loud noise, which the feature normalisation would then make
 * indistinguishable from speech.
 */
private fun normalizeGain(samples: FloatArray) {
    var peak = 0.0f
    for (value in samples) {
        val abs = if (value < 0.0f) -value else value
        if (abs > peak) peak = abs
    }
    if (peak < 1.0e-5f) return

    val scale = TARGET_PEAK / peak
    for (i in samples.indices) {
        samples[i] = samples[i] * scale
    }
}

/**
 * Decides whether a clip holds speech at all, before any feature is built.
 *
 * The test is relative to the clip's own loudest frame, so it survives gain
 * normalisation: speech is loud frames with quiet ones around it, while room
 * noise is one uniform level and silence has no loud frame. Either missing case
 * returns false, which is what keeps a quiet room from firing the wake word.
 */
private fun containsSpeech(samples: FloatArray): Boolean {
    val frameCount = (samples.size - MFCC_FRAME) / MFCC_SHIFT + 1
    if (frameCount < MIN_FRAMES) return false

    val energies = FloatArray(frameCount)
    var peak = 0.0f
    for (t in 0..<frameCount) {
        val offset = t * MFCC_SHIFT
        var sum = 0.0
        for (i in 0..<MFCC_FRAME) {
            val s = samples[offset + i].toDouble()
            sum += s * s
        }
        val rms = kotlin.math.sqrt(sum / MFCC_FRAME).toFloat()
        energies[t] = rms
        if (rms > peak) peak = rms
    }
    if (peak < SILENCE_PEAK) return false

    var voiced = 0
    var quiet = 0
    for (energy in energies) {
        if (energy >= peak * VOICED_FRACTION) voiced++
        if (energy <= peak * QUIET_FRACTION) quiet++
    }
    return voiced >= MIN_SPEECH_FRAMES && quiet >= MIN_QUIET_FRAMES
}

/**
 * Applies the same signal preparation to every clip - the enrolled references
 * and the live detection both come through here, so a reference is always
 * comparable to the clip that is being matched against it.
 */
fun mfcc(samples: FloatArray): FloatArray {
    val signal = prepareSignal(samples)
    val frameCount = (signal.size - MFCC_FRAME) / MFCC_SHIFT + 1
    if (frameCount < MIN_FRAMES) return FloatArray(0)

    val half = MFCC_FRAME / 2
    // cos(pi * k * (j - 0.5) / N) is frame-independent, so it is built once.
    val cosTable = FloatArray((MFCC_CEPSTRAL + 1) * (half + 1))
    for (k in 1..MFCC_CEPSTRAL) {
        for (j in 1..half) {
            cosTable[k * (half + 1) + j] =
                kotlin.math.cos(kotlin.math.PI * k * (j - 0.5) / MFCC_FRAME).toFloat()
        }
    }

    val cepstral = FloatArray(frameCount * MFCC_CEPSTRAL)
    val re = FloatArray(FFT_SIZE)
    val im = FloatArray(FFT_SIZE)
    val magnitude = FloatArray(half + 1)

    for (t in 0..<frameCount) {
        val offset = t * MFCC_SHIFT
        for (i in 0..<FFT_SIZE) {
            re[i] = if (i < MFCC_FRAME) signal[offset + i] else 0.0f
            im[i] = 0.0f
        }
        fft(re, im)

        for (j in 1..half) {
            val reJ = re[j]
            val imJ = im[j]
            magnitude[j] = kotlin.math.sqrt(reJ * reJ + imJ * imJ).toFloat()
        }

        for (k in 1..MFCC_CEPSTRAL) {
            var sum = 0.0
            for (j in 1..half) {
                val weight = if (j < half) 1.0 else 0.5
                val mag = kotlin.math.max(magnitude[j].toDouble(), 1.0e-7)
                sum += weight * kotlin.math.log(mag, kotlin.math.E) * cosTable[k * (half + 1) + j]
            }
            cepstral[t * MFCC_CEPSTRAL + (k - 1)] = (sum / MFCC_CEPSTRAL).toFloat()
        }
    }

    val delta = FloatArray(frameCount * MFCC_CEPSTRAL)
    for (t in 0..<frameCount) {
        val prev = if (t == 0) frameCount - 1 else t - 1
        for (k in 0..<MFCC_CEPSTRAL) {
            delta[t * MFCC_CEPSTRAL + k] =
                cepstral[t * MFCC_CEPSTRAL + k] - cepstral[prev * MFCC_CEPSTRAL + k]
        }
    }

    val smoothCep = gaussianSmooth(cepstral, frameCount, MFCC_CEPSTRAL)
    val smoothDelta = gaussianSmooth(delta, frameCount, MFCC_CEPSTRAL)

    val features = FloatArray(frameCount * MFCC_FEATURES)
    for (t in 0..<frameCount) {
        for (k in 0..<MFCC_CEPSTRAL) {
            features[t * MFCC_FEATURES + k] = smoothCep[t * MFCC_CEPSTRAL + k]
            features[t * MFCC_FEATURES + MFCC_CEPSTRAL + k] = smoothDelta[t * MFCC_CEPSTRAL + k]
        }
    }
    normalizePerFeature(features)
    return features
}

/**
 * Scales every feature dimension to zero mean and unit variance, in place.
 *
 * Raw cepstral coefficients are sums of log magnitudes, so their absolute size
 * follows the recording's volume and length; comparing them across recordings
 * without this step puts the DTW cost on a scale that no fixed threshold can
 * sit on. After normalising, a cost of 1 means "one standard deviation apart".
 */
private fun normalizePerFeature(features: FloatArray) {
    val frameCount = features.size / MFCC_FEATURES
    if (frameCount < 2) return
    for (k in 0..<MFCC_FEATURES) {
        var sum = 0.0
        for (t in 0..<frameCount) sum += features[t * MFCC_FEATURES + k]
        val mean = sum / frameCount
        var variance = 0.0
        for (t in 0..<frameCount) {
            val d = features[t * MFCC_FEATURES + k] - mean
            variance += d * d
        }
        // A near-constant dimension (silence) has no scale to divide by, so leave it at the mean.
        val std = kotlin.math.sqrt(variance / frameCount)
        if (std < 1.0e-4f) continue
        for (t in 0..<frameCount) {
            features[t * MFCC_FEATURES + k] = ((features[t * MFCC_FEATURES + k] - mean) / std).toFloat()
        }
    }
}

/**
 * In-place iterative radix-2 Cooley-Tukey FFT; results are interleaved in
 * [re]/[im] and only the first half of the spectrum is ever read back.
 */
private fun fft(re: FloatArray, im: FloatArray) {
    val n = re.size
    var j = 0
    for (i in 1..<n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j or bit
        if (i < j) {
            val tr = re[i]
            re[i] = re[j]
            re[j] = tr
            val ti = im[i]
            im[i] = im[j]
            im[j] = ti
        }
    }

    var len = 2
    while (len <= n) {
        val angle = -2.0 * kotlin.math.PI / len
        val wlenRe = kotlin.math.cos(angle).toFloat()
        val wlenIm = kotlin.math.sin(angle).toFloat()
        val half = len shr 1
        var i = 0
        while (i < n) {
            var wRe = 1.0f
            var wIm = 0.0f
            for (k in 0..<half) {
                val ur = re[i + k]
                val ui = im[i + k]
                val vr = re[i + k + half] * wRe - im[i + k + half] * wIm
                val vi = re[i + k + half] * wIm + im[i + k + half] * wRe
                re[i + k] = ur + vr
                im[i + k] = ui + vi
                re[i + k + half] = ur - vr
                im[i + k + half] = ui - vi
                val nextRe = wRe * wlenRe - wIm * wlenIm
                wIm = wRe * wlenIm + wIm * wlenRe
                wRe = nextRe
            }
            i += len
        }
        len = len shl 1
    }
}

/**
 * Applies a 3-tap Gaussian filter along the time axis of a feature sequence.
 */
private fun gaussianSmooth(source: FloatArray, frameCount: Int, stride: Int): FloatArray {
    val out = FloatArray(source.size)
    for (t in 0..<frameCount) {
        val prev = if (t == 0) 0 else t - 1
        val next = if (t == frameCount - 1) t else t + 1
        for (k in 0..<stride) {
            out[t * stride + k] =
                0.25f * source[prev * stride + k] +
                0.5f * source[t * stride + k] +
                0.25f * source[next * stride + k]
        }
    }
    return out
}

/**
 * The cost of a clip against the enrolled references: the best window of the
 * clip that holds the phrase, warped onto each reference, and then aggregated
 * across the references.
 *
 * Warping the whole clip onto the whole reference - what the first
 * implementation did - is what makes the trigger miss when the user says the
 * phrase and the request in one breath: the request has nowhere to go and its
 * cost is folded into the answer. Scoring a window the length of the references
 * and taking the best position lets the window sit exactly on the phrase while
 * the rest of the clip is ignored, which is the difference the idle pass needs
 * because its recorder trims to the whole utterance, not to the phrase.
 *
 * Lower means more similar; an empty clip or missing references yield
 * [Float.MAX_VALUE] so they can never match.
 */
fun bestMatchCost(features: FloatArray, references: List<FloatArray>): Float {
    if (features.isEmpty() || references.isEmpty()) return Float.MAX_VALUE

    val usable = references.filter { it.size >= MFCC_FEATURES && it.size % MFCC_FEATURES == 0 }
    if (usable.isEmpty()) return Float.MAX_VALUE
    val clipFrames = features.size / MFCC_FEATURES
    if (clipFrames == 0) return Float.MAX_VALUE

    val perReference = mutableListOf<Float>()
    for (reference in usable) {
        val refFrames = reference.size / MFCC_FEATURES
        var best = Float.MAX_VALUE
        // The longer sequence is the one that gets sliced, so an enrolled take
        // that outlasts the live clip is compared against the whole clip the
        // same way a longer clip is compared against a whole reference. Slicing
        // only the clip leaves one position for every reference that outlasts
        // it, and the band then has to warp the whole length difference into
        // the cost - which is how a genuine enrollment scores a real phrase.
        if (clipFrames >= refFrames) {
            for (start in 0..(clipFrames - refFrames)) {
                // A window can only improve on what is already known about this
                // reference, so its search stops as soon as it is worse.
                val cost = windowCost(features, start, refFrames, reference, best)
                if (cost < best) best = cost
            }
        } else {
            for (start in 0..(refFrames - clipFrames)) {
                val cost = windowCost(reference, start, clipFrames, features, best)
                if (cost < best) best = cost
            }
        }
        perReference += best
    }
    return aggregateCost(perReference)
}

/**
 * Number of window positions in [features] whose cost against [reference] is at
 * or below [threshold].
 *
 * A single position is not evidence: the search over positions is exactly what
 * makes an unrelated clip look close, so the count of agreeing positions is the
 * quantity that separates a real phrase from one lucky alignment.
 */
fun confirmingWindows(features: FloatArray, reference: FloatArray, threshold: Float): Int {
    if (features.isEmpty() || reference.isEmpty()) return 0
    val clipFrames = features.size / MFCC_FEATURES
    val refFrames = reference.size / MFCC_FEATURES
    if (clipFrames == 0 || refFrames == 0) return 0
    var hits = 0
    if (clipFrames >= refFrames) {
        for (start in 0..(clipFrames - refFrames)) {
            // The threshold doubles as the search limit: a window that cannot beat
            // it is reported as unreachable and never counted.
            if (windowCost(features, start, refFrames, reference, threshold) <= threshold) hits++
        }
    } else {
        // The enrolled take outlasts the clip, so the take is what gets sliced.
        for (start in 0..(refFrames - clipFrames)) {
            if (windowCost(reference, start, clipFrames, features, threshold) <= threshold) hits++
        }
    }
    return hits
}

/**
 * Whether the clip holds the wake phrase.
 *
 * The cost alone cannot answer this. Taking the best of every window position
 * hands an unrelated clip as many chances to land one cheap window as there are
 * positions to try, and on real speech that bias is large: unrelated segments
 * score ~0.49 through the search against ~0.62 for an honest pairwise
 * comparison, which is closer to a genuine 0.18 than it should be. Requiring
 * several positions to agree takes that back off - a genuine phrase confirms
 * over a run of consecutive positions, because the band absorbs the shift
 * between them, while a single lucky window does not confirm on its neighbours.
 *
 * A majority of the enrolled references has to confirm, so enrolling more takes
 * cannot make the detector more permissive.
 *
 * The agreement bound only applies where there is more than one position to
 * agree over. The idle pass trims the clip to the utterance, so a live clip is
 * about as long as the references and the window has a single position to sit
 * in - demanding two of them is what made every tight clip miss, because the
 * count can never reach the bound no matter how good the cost is. With one
 * position there is no best-of-N bias left to rule out, and the calibrated
 * absolute threshold carries the decision alone.
 */
fun wakeWordHeard(features: FloatArray, references: List<FloatArray>, sensitivity: Float): Boolean {
    val usable = references.filter { it.size >= MFCC_FEATURES && it.size % MFCC_FEATURES == 0 }
    if (features.isEmpty() || usable.isEmpty()) return false
    val threshold = wakeCostThreshold(sensitivity, references)
    val votes = agreement(features, usable, threshold).count { it.first >= it.second }
    return votes * 2 >= usable.size
}

/**
 * The per-reference agreement the wake decision is built from: each usable
 * reference's confirming window count paired with the bound it has to clear.
 *
 * Exposed as data rather than as a verdict so a miss can be traced to the
 * enrolled take that cannot confirm - the aggregate cost alone cannot show
 * which of several takes is the one to record over.
 */
private fun agreement(features: FloatArray, usable: List<FloatArray>, threshold: Float): List<Pair<Int, Int>> {
    val clipFrames = features.size / MFCC_FEATURES
    return usable.map { reference ->
        val refFrames = reference.size / MFCC_FEATURES
        val positions = kotlin.math.abs(clipFrames - refFrames) + 1
        confirmingWindows(features, reference, threshold) to
            kotlin.math.min(MIN_CONFIRMING_WINDOWS, positions)
    }
}

/**
 * The same agreement as "confirmed/bound" per reference, in enrollment order.
 */
fun wakeAgreement(features: FloatArray, references: List<FloatArray>, sensitivity: Float): List<String> {
    val usable = references.filter { it.size >= MFCC_FEATURES && it.size % MFCC_FEATURES == 0 }
    if (features.isEmpty() || usable.isEmpty()) return emptyList()
    return agreement(features, usable, wakeCostThreshold(sensitivity, references))
        .map { "${it.first}/${it.second}" }
}

/**
 * Band-limited dynamic time warping between [length] frames of [features]
 * starting at [start] and [reference], normalised by the length of the two
 * sequences.
 *
 * The band bounds the warping path to [BAND_SIZE] frames of the diagonal,
 * widened enough to absorb a length difference between the two sequences.
 * Without it a short reference can be stretched across an unrelated clip and
 * still find a cheap path, which is how a windowed match would otherwise trade
 * the recall it buys for false triggers.
 *
 * [limit] prunes the search: costs only ever grow along a path, so a row whose
 * cheapest cell already exceeds a cost known to be good enough cannot answer
 * better than it.
 */
private fun windowCost(features: FloatArray, start: Int, length: Int, reference: FloatArray, limit: Float): Float {
    val m = reference.size / MFCC_FEATURES
    if (length == 0 || m == 0) return Float.MAX_VALUE

    val inf = Float.MAX_VALUE / 4.0f
    // The band has to reach the bottom-right corner, not just the diagonal: at
    // the last row the reachable columns stop at row + band, so a length
    // difference of exactly BAND_SIZE leaves column m one step out of reach and
    // the warp reports no path at all instead of its cost.
    val band = kotlin.math.max(BAND_SIZE, kotlin.math.abs(length - m)) + 1
    val cutoff = if (limit >= inf) inf else limit * (length + m)

    var previous = FloatArray(m + 1)
    var current = FloatArray(m + 1)
    previous[0] = 0.0f
    // Reachable columns of the row above, so out-of-band cells are never read.
    var prevLo = 0
    var prevHi = 0

    for (row in 1..length) {
        val lo = kotlin.math.max(1, row - band)
        val hi = kotlin.math.min(m + 1, row + band)
        if (lo >= hi) return Float.MAX_VALUE

        var cheapest = inf
        for (col in lo..<hi) {
            var bestPrevious = inf
            if (col >= prevLo && col <= prevHi) bestPrevious = previous[col]
            if (col - 1 >= prevLo && col - 1 <= prevHi) bestPrevious = kotlin.math.min(bestPrevious, previous[col - 1])
            if (col - 1 >= lo) bestPrevious = kotlin.math.min(bestPrevious, current[col - 1])
            val value = frameDistance(
                features,
                (start + row - 1) * MFCC_FEATURES,
                reference,
                (col - 1) * MFCC_FEATURES
            ) + bestPrevious
            current[col] = value
            if (value < cheapest) cheapest = value
        }
        if (cheapest >= cutoff) return Float.MAX_VALUE

        val swap = previous
        previous = current
        current = swap
        prevLo = lo
        prevHi = hi - 1
    }

    if (m < prevLo || m > prevHi) return Float.MAX_VALUE
    return previous[m] / (length + m)
}

/**
 * Distance between two feature frames: the Euclidean distance once each frame
 * is scaled to unit length.
 *
 * Only the direction of a frame's cepstral signature carries its shape, so
 * scaling each frame before comparing it removes the residual loudness that
 * survives the clip-wide gain normalisation - a phrase spoken closer to the
 * microphone is a little louder than the enrolled take of it. Expressing the
 * cosine distance as a Euclidean one keeps the result in [0, 2], the same
 * ballpark the thresholds below are calibrated to.
 */
private fun frameDistance(a: FloatArray, aOffset: Int, b: FloatArray, bOffset: Int): Float {
    var dot = 0.0f
    var magnitudeA = 0.0f
    var magnitudeB = 0.0f
    for (k in 0..<MFCC_FEATURES) {
        val x = a[aOffset + k]
        val y = b[bOffset + k]
        dot += x * y
        magnitudeA += x * x
        magnitudeB += y * y
    }
    if (magnitudeA < 1.0e-9f || magnitudeB < 1.0e-9f) {
        // A frame with no signal has no direction to compare, so it matches
        // nothing - except another frame with no signal, which is the same thing.
        return if (magnitudeA < 1.0e-9f && magnitudeB < 1.0e-9f) 0.0f else 2.0f
    }
    val cosine = dot / kotlin.math.sqrt(magnitudeA * magnitudeB)
    return kotlin.math.sqrt(kotlin.math.max(0.0f, 2.0f * (1.0f - cosine)))
}

/**
 * Reduces the per-reference costs to the one cost of the clip.
 *
 * The best of the references is the most permissive reading, and it becomes
 * more permissive as the user enrolls more takes: with enough of them, one of
 * them flatters any clip. Taking the median from three references on means a
 * genuine take still wins - it matches most of the user's own takes - while a
 * clip that only flatters a single reference no longer counts.
 */
private fun aggregateCost(costs: List<Float>): Float {
    val usable = costs.filter { it.isFinite() }.sorted()
    if (usable.isEmpty()) return Float.MAX_VALUE
    val index = kotlin.math.min(
        usable.size - 1,
        (REFERENCE_PERCENTILE * (usable.size - 1)).toInt()
    )
    return usable[index]
}

/**
 * The cost a clip may have against the enrolled references and still count as
 * the wake word.
 *
 * The frame distance is capped at 2 and the features are normalised per
 * dimension, so this cost is already volume-, phrase-length- and microphone
 * independent: an absolute constant is meaningful here, and it is what the
 * reference implementations tune (rustpotter ships a fixed threshold rather than
 * one derived from the enrolled material). Deriving the whole threshold from the
 * references does not work on real speech - takes of different sentences sit
 * around 0.6 apart while the phrase inside a longer clip reaches 0.18 and
 * unrelated speech 0.49, so a spread-scaled multiple lands far off the boundary
 * that actually separates them.
 *
 * The constant carries the boundary and sensitivity scales it across the slider;
 * the measured enrollment spread is allowed to widen the result only a bounded
 * amount, so takes that genuinely differ from each other buy some tolerance
 * without handing the whole scale back.
 */
fun wakeCostThreshold(sensitivity: Float, references: List<FloatArray>): Float {
    val clamped = kotlin.math.max(0.0f, kotlin.math.min(1.0f, sensitivity))
    // The slider spans a narrow band around the boundary because the boundary
    // itself is already known: recall needs ~0.36 and precision gives back at
    // ~0.55, so the whole range has to live inside that 0.19-wide gap.
    val factor = 0.925f + 0.175f * (1.0f - clamped)
    val allowance = kotlin.math.min(referenceSpread(references), 1.0f) * SPREAD_ALLOWANCE
    return (MATCH_COST * factor + allowance).coerceAtLeast(MIN_MATCH_THRESHOLD)
}

/**
 * Median pairwise cost among the enrolled references - the spread the user's
 * own takes of the same phrase already show.
 *
 * It is measured with the same comparison a live clip goes through, so the
 * threshold stays calibrated to the distance function and the normalisation in
 * use and carries no absolute constant that would depend on volume, phrase
 * length or microphone. Below two references there is nothing to measure, so a
 * single reference falls back to a fixed cost in the same units.
 */
private fun referenceSpread(references: List<FloatArray>): Float {
    val usable = references.filter { it.size >= MFCC_FEATURES && it.size % MFCC_FEATURES == 0 }
    if (usable.size < 2) return SINGLE_REFERENCE_SPREAD

    val costs = mutableListOf<Float>()
    for (i in usable.indices) {
        for (j in (i + 1)..<usable.size) {
            val frames = usable[i].size / MFCC_FEATURES
            val cost = windowCost(usable[i], 0, frames, usable[j], Float.MAX_VALUE)
            if (cost.isFinite()) costs.add(cost)
        }
    }
    if (costs.isEmpty()) return SINGLE_REFERENCE_SPREAD
    costs.sort()
    return costs[costs.size / 2]
}

/**
 * Loads the enrolled wake-word references from the app's private files directory.
 * Returns an empty list when nothing has been recorded yet.
 */
fun loadWakeReferences(context: Context): List<FloatArray> {
    val path = pathForReferences(context)
    val file = java.io.File(path)
    if (!file.exists()) {
        Log.w("WakeEnroll", "No enrollment file at $path")
        return emptyList()
    }
    return try {
        val lines = file.readText().split('\n')
        val references = mutableListOf<FloatArray>()
        for (line in lines) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(' ')
            if (parts.size < 2) continue
            val values = parts.drop(1).mapNotNull { it.toFloatOrNull() }
            // A reference has to be a whole number of frames. Dropping an
            // unparseable token shifts everything after it, and a shifted
            // reference still loads - it just fails every consumer's frame
            // check, which reads back as an enrollment that matches nothing at
            // any sensitivity. Rejecting it here keeps the count the screen
            // shows and the count the matcher can use the same number.
            if (values.size % MFCC_FEATURES != 0) {
                Log.w("FLOSSyAssistant", "Wake reference line is malformed: $parts.size values, expected a multiple of $MFCC_FEATURES")
                continue
            }
            if (values.isNotEmpty()) {
                val reference = values.toFloatArray()
                // Enrollment files written before the features were normalised hold
                // raw log-magnitude coefficients. Normalising again is a no-op for
                // current entries, so old enrollments stay usable without re-recording.
                normalizePerFeature(reference)
                references += reference
            }
        }
        // The read side has to name the path and the usable count the way the
        // write side does: an enrollment file that exists but yields fewer usable
        // lines than the screen claims is the difference between a matcher that
        // was handed nothing and one the user believes is configured.
        Log.w("WakeEnroll", "Loaded ${references.size} usable references from $path")
        references
    } catch (exception: Exception) {
        Log.e("WakeEnroll", "Enrollment file at $path could not be read", exception)
        emptyList()
    }
}

/**
 * Number of 10 ms frames a feature sequence holds. Enrollment reports a rejected
 * clip in these units so the user knows how much to trim, not in raw floats.
 */
fun referenceFrameCount(features: FloatArray): Int = features.size / MFCC_FEATURES

/**
 * Appends one reference to the enrollment file, keeping the newest entries
 * bounded so the file cannot grow without limit.
 */
fun saveWakeReference(context: Context, features: FloatArray): Boolean {
    if (features.isEmpty() || features.size % MFCC_FEATURES != 0) return false
    if (features.size / MFCC_FEATURES > MAX_REFERENCE_FRAMES) return false

    val references = loadWakeReferences(context) + features
    val bounded = if (references.size <= MAX_REFERENCES) {
        references
    } else {
        references.drop(references.size - MAX_REFERENCES)
    }
    return writeReferences(context, bounded)
}

/**
 * Removes every enrolled wake-word reference.
 */
fun clearWakeReferences(context: Context): Boolean {
    return writeReferences(context, emptyList())
}

private fun writeReferences(context: Context, references: List<FloatArray>): Boolean {
    return try {
        val file = java.io.File(pathForReferences(context))
        val text = buildString {
            append("#flossyapp-wake-refs v1\n")
            for (reference in references) {
                append("${reference.size / MFCC_FEATURES}")
                for (value in reference) {
                    append(' ')
                    append(value.toString())
                }
                append('\n')
            }
        }
        // The failure has to be visible: enrollment used to swallow whatever the
        // write threw, which is exactly how a broken enrollment file went
        // invisible - the screen kept showing the old count while every new take
        // failed silently.
        file.writeText(text)
        // Naming the resolved path on the way out is what makes the enrollment
        // file findable from adb: a save that reports success but lands
        // somewhere other than the path the screen later reads back is the
        // difference between "the enrollment is broken" and "the enrollment is
        // in a place nobody looks at".
        Log.w("WakeEnroll", "Wrote ${references.size} references to ${pathForReferences(context)}")
        true
    } catch (exception: Exception) {
        Log.e("FLOSSyAssistant", "Wake enrollment could not be written to ${pathForReferences(context)}", exception)
        false
    }
}

private fun pathForReferences(context: Context): String {
    return "${context.filesDir}/$REFERENCE_FILE"
}
