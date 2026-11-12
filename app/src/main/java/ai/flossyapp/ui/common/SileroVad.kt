package ai.flossyapp.ui.common

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet

/**
 * Silero VAD, bundled and run on-device.
 *
 * An energy gate cannot tell a word from a tap on the screen or a fan spin:
 * both are just "loud". This runs the bundled silero_vad.onnx network, which
 * was trained to answer one question per 32ms of audio - is a human
 * speaking? - so taps, door slams and coughs score low and speech scores
 * high, whatever the input device, the device model or the room.
 *
 * The model is loaded once from a raw resource (never downloaded at runtime)
 * and the session prefers the NNAPI execution provider, which lands the work
 * on the device's NPU or GPU where one exists (the Pixel 9a has both);
 * unsupported ops fall back to CPU inside the provider, not into failures.
 *
 * Protocol (Silero v5, 16kHz): one 512-sample chunk (32ms) per call, with
 * the previous call's last 64 samples prepended as context; a [2][1][128]
 * state tensor is carried between calls and a sample-rate tensor is fed
 * along. Hysteresis turns per-chunk probabilities into stable speech
 * decisions: speech starts at [START_PROB] and only ends after sustained
 * quiet, so a word's own quiet moments do not chop it into pieces.
 *
 * Inference is cheap and synchronous on the caller's IO thread; nothing
 * here touches the main thread.
 */
class SileroVad(context: Context) {
    companion object {
        const val SAMPLE_RATE = 16000L
        const val CHUNK = 512               // 32ms at 16kHz - the model's input
        const val CONTEXT = 64              // samples prepended from the last call
        const val START_PROB = 0.5f         // above this, speech has started
        const val END_PROB = 0.35f          // below this, quiet counts toward an end
        // Sustained quiet (in chunks) that closes an utterance: ~1.2s.
        const val END_CHUNKS = 38

        private const val IN_INPUT = "input"
        private const val IN_STATE = "state"
        private const val IN_SR = "sr"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val opts = OrtSession.SessionOptions().apply {
            // One thread is plenty: the chunk is 32ms long and NNAPI does
            // the heavy part on the accelerator.
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            // FP16 is exactly what a voice-activity probability needs and
            // halves the accelerator's work.
            addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
        }
        val model = context.resources.openRawResource(
            context.resources.getIdentifier("silero_vad", "raw", context.packageName)
        ).use { it.readBytes() }
        session = env.createSession(model, opts)
    }

    fun close() {
        session.close()
    }

    /**
     * One listening stream: its model state and its speech hysteresis.
     * Create one per capture and feed it every chunk.
     */
    inner class Stream {
        // [2][1][128] per the model's contract, zero-initialised: the
        // documented starting state.
        private val state = FloatArray(256)
        // The last CONTEXT samples fed, prepended to the next chunk.
        private val context = FloatArray(CONTEXT)
        private var silenceChunks = 0

        /** True while the stream is inside a speech run. */
        var speaking = false
            private set

        /**
         * Scores one 512-sample chunk of mono 16kHz audio, [-1,1] floats.
         * Returns the speech probability; watch [speaking] for edges -
         * false->true is an utterance start, and once speaking, [END_CHUNKS]
         * consecutive quiet chunks close it.
         */
        fun score(chunk: FloatArray): Float {
            require(chunk.size == CHUNK) { "Silero chunks are $CHUNK samples" }
            // Context + chunk: the model sees 64 old samples and 512 new.
            val input = ByteBuffer
                .allocateDirect((CONTEXT + CHUNK) * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            input.put(context)
            input.put(chunk)
            input.rewind()

            val stateBuf = ByteBuffer
                .allocateDirect(state.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            stateBuf.put(state)
            stateBuf.rewind()

            val srBuf = ByteBuffer
                .allocateDirect(8)
                .order(ByteOrder.nativeOrder())
                .asLongBuffer()
            srBuf.put(SAMPLE_RATE)
            srBuf.rewind()

            OnnxTensor.createTensor(env, input, longArrayOf(1, (CONTEXT + CHUNK).toLong())).use { inT ->
                OnnxTensor.createTensor(env, stateBuf, longArrayOf(2, 1, 128)).use { stT ->
                    OnnxTensor.createTensor(env, srBuf, longArrayOf(1)).use { srT ->
                        session.run(mapOf(IN_INPUT to inT, IN_STATE to stT, IN_SR to srT)).use { results ->
                            val prob = results.get(0).let { out ->
                                val fb = (out as OnnxTensor).floatBuffer
                                fb.get(fb.position())
                            }
                            val newState = (results.get(1) as OnnxTensor).floatBuffer
                            newState.get(state)
                            // Keep the tail of this chunk as the next call's context.
                            System.arraycopy(chunk, CHUNK - CONTEXT, context, 0, CONTEXT)
                            // Hysteresis: a rising edge needs confidence, a
                            // falling edge needs sustained quiet.
                            if (prob >= START_PROB) {
                                speaking = true
                                silenceChunks = 0
                            } else if (prob < END_PROB) {
                                if (speaking && ++silenceChunks >= END_CHUNKS) {
                                    speaking = false
                                    silenceChunks = 0
                                }
                            } else {
                                silenceChunks = 0
                            }
                            return prob
                        }
                    }
                }
            }
        }
    }
}
