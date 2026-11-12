package ai.flossyapp.test

import ai.flossyapp.ui.common.buildSilentWav
import ai.flossyapp.ui.common.isNonSpeechTranscript
import ai.flossyapp.ui.common.matchesWakeWord
import ai.flossyapp.ui.common.parseWavBytes
import ai.flossyapp.ui.common.splitIntoSentences
import org.junit.Test

/**
 * Unit tests for the audio/text helpers that run without a device.
 *
 * The WAV cases matter because the STT and TTS endpoints answer with RIFF files
 * whose chunk list is not always the canonical 44-byte header, and the
 * transcript cases decide what reaches the LLM.
 */
class CommonUtilitiesTest {

    // Chunks may appear before "fmt " (tagged files) or between "fmt " and "data"
    // (server padding); both shift where the interesting bytes live.
    private fun wavBytes(
        sampleRate: Int,
        pcm: ByteArray,
        leadingChunkName: String = "",
        leadingChunkBytes: ByteArray = ByteArray(0),
        extraChunkName: String = "",
        extraChunkBytes: ByteArray = ByteArray(0)
    ): ByteArray {
        val out = mutableListOf<Int>()
        fun put(text: String) = text.toByteArray().forEach { out.add(it.toInt() and 0xFF) }
        fun le32(value: Int) = (0..3).forEach { out.add((value shr (8 * it)) and 0xFF) }
        fun le16(value: Int) = (0..1).forEach { out.add((value shr (8 * it)) and 0xFF) }
        fun chunk(name: String, body: ByteArray) {
            if (name.isEmpty()) return
            put(name)
            le32(body.size)
            body.forEach { out.add(it.toInt() and 0xFF) }
            if (body.size and 1 == 1) out.add(0)
        }

        fun padded(name: String, body: ByteArray) =
            if (name.isEmpty()) 0 else 8 + body.size + (body.size and 1)

        put("RIFF")
        le32(36 + pcm.size + padded(leadingChunkName, leadingChunkBytes) + padded(extraChunkName, extraChunkBytes))
        put("WAVE")
        chunk(leadingChunkName, leadingChunkBytes)
        put("fmt ")
        le32(16)
        le16(1)
        le16(1)
        le32(sampleRate)
        le32(sampleRate * 2)
        le16(2)
        le16(16)
        chunk(extraChunkName, extraChunkBytes)
        put("data")
        le32(pcm.size)
        pcm.forEach { out.add(it.toInt() and 0xFF) }
        return out.map { it.toByte() }.toByteArray()
    }

    @Test
    fun parseWavBytes_readsDataChunkAfterAnExtraChunk() {
        val pcm = ByteArray(16) { (it * 7).toByte() }
        val parsed = parseWavBytes(
            wavBytes(22050, pcm, extraChunkName = "junk", extraChunkBytes = ByteArray(5))
        ) ?: throw AssertionError("a WAV with an extra chunk was rejected")
        assert(parsed.first == 22050) { "the sample rate was read from the wrong offset" }
        assert(parsed.second.toList() == pcm.toList()) { "the PCM block did not round-trip" }
    }

    @Test
    fun parseWavBytes_readsTheRateFromTheFmtChunk() {
        val pcm = ByteArray(8) { (it * 3).toByte() }
        val parsed = parseWavBytes(
            wavBytes(44100, pcm, leadingChunkName = "LIST", leadingChunkBytes = ByteArray(7))
        ) ?: throw AssertionError("a WAV with a chunk before fmt was rejected")
        assert(parsed.first == 44100) { "a leading chunk shifts the rate away from the fixed offset" }
        assert(parsed.second.toList() == pcm.toList()) { "the PCM block did not round-trip" }
    }

    @Test
    fun parseWavBytes_readsAPlainWav() {
        val parsed = parseWavBytes(buildSilentWav(100, 16000))
            ?: throw AssertionError("the generated silent WAV was rejected")
        assert(parsed.first == 16000) { "the sample rate must be the one buildSilentWav wrote" }
        assert(parsed.second.size == 16000 * 2 / 10) { "100 ms of 16 kHz mono 16-bit PCM is 3200 bytes" }
    }

    @Test
    fun isNonSpeechTranscript_ignoresBracketedMarkers() {
        assert(isNonSpeechTranscript("")) { "an empty transcript must not reach the LLM" }
        assert(isNonSpeechTranscript("[BLANK_AUDIO]")) { "whisper.cpp answers silence with a bracketed token" }
        assert(isNonSpeechTranscript("[MUSIC] [APPLAUSE]")) { "several markers are still not speech" }
        assert(!isNonSpeechTranscript("open the door")) { "speech must not be dropped" }
        assert(!isNonSpeechTranscript("[BLANK_AUDIO] open the door")) { "speech after a marker is still speech" }
    }

    @Test
    fun splitIntoSentences_keepsNumbersIntact() {
        val sentences = splitIntoSentences("Turn left. The id is 3.14 exactly")
        assert(sentences.size == 2) { "a decimal point must not end a sentence" }
        assert(sentences[0] == "Turn left.") { "the first sentence is cut at the real stop" }
        assert(sentences[1] == "The id is 3.14 exactly") { "the number stays in one sentence" }
    }

    @Test
    fun matchesWakeWord_needsTheSensitivityShareOfTokens() {
        assert(matchesWakeWord("hey assistant open the door", "hey assistant", 0.7f)) { "the exact phrase must trigger" }
        assert(matchesWakeWord("hey assistants open the door", "hey assistant", 0.7f)) { "a pluralised prefix must still trigger" }
        assert(!matchesWakeWord("open the door", "hey assistant", 0.3f)) { "an unrelated request must not trigger" }
    }
}
