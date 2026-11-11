package com.localassistant.app.ui.common

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes a 32-bit integer to the output stream in little-endian byte order.
 */
fun writeIntLE(output: ByteArrayOutputStream, value: Int) {
    output.write(value and 0xFF)
    output.write((value shr 8) and 0xFF)
    output.write((value shr 16) and 0xFF)
    output.write((value shr 24) and 0xFF)
}

/**
 * Writes a 16-bit short to the output stream in little-endian byte order.
 */
fun writeShortLE(output: ByteArrayOutputStream, value: Int) {
    output.write(value and 0xFF)
    output.write((value shr 8) and 0xFF)
}

/**
 * Records raw PCM audio for up to maxDurationMs using AudioRecord,
 * then wraps it in a WAV container. Returns null if recording fails.
 */
fun recordAudioForSTT(
    context: Context,
    maxDurationMs: Long = 10000L
): ByteArray? {
    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    val minBufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    if (minBufferSize <= 0) return null

    val audioRecord = android.media.AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        sampleRate, channelConfig, audioFormat, minBufferSize * 4
    )

    if (audioRecord.state != android.media.AudioRecord.STATE_INITIALIZED) {
        audioRecord.release()
        return null
    }

    val buffer = ShortArray(minBufferSize / 2)
    val output = ByteArrayOutputStream()
    var recordingTimeMs = 0L
    val startTime = System.currentTimeMillis()

    // Capture may have to ride the Bluetooth headset; the route is closed
    // whichever way the recording ends.
    val restoreRoute = beginCommunicationCapture(context)
    try {
        try {
            audioRecord.startRecording()
            while (recordingTimeMs < maxDurationMs) {
                val readCount = audioRecord.read(buffer, 0, buffer.size)
                if (readCount > 0) {
                    for (i in 0 until readCount) {
                        output.write(buffer[i].toInt() and 0xFF)
                        output.write((buffer[i].toInt() shr 8) and 0xFF)
                    }
                }
                recordingTimeMs = System.currentTimeMillis() - startTime
            }
        } catch (e: Exception) {
            try { audioRecord.stop() } catch (_: Exception) {}
            audioRecord.release()
            return null
        }
    } finally {
        restoreRoute?.invoke()
    }

    try { audioRecord.stop() } catch (_: Exception) {}
    audioRecord.release()

    // Convert raw PCM to WAV with proper headers
    val pcmData = output.toByteArray()
    val wavHeader = ByteArrayOutputStream()
    // RIFF header
    wavHeader.writeBytes("RIFF".toByteArray())
    writeIntLE(wavHeader, 36 + pcmData.size)
    wavHeader.writeBytes("WAVE".toByteArray())
    // fmt chunk
    wavHeader.writeBytes("fmt ".toByteArray())
    writeIntLE(wavHeader, 16)  // chunk size
    writeShortLE(wavHeader, 1) // PCM format
    writeShortLE(wavHeader, 1) // mono
    writeIntLE(wavHeader, sampleRate)
    writeIntLE(wavHeader, sampleRate * 2) // byte rate
    writeShortLE(wavHeader, 2) // block align
    writeShortLE(wavHeader, 16) // bits per sample
    // data chunk
    wavHeader.writeBytes("data".toByteArray())
    writeIntLE(wavHeader, pcmData.size)
    wavHeader.write(pcmData, 0, pcmData.size)

    return wavHeader.toByteArray()
}

/**
 * Records audio with Voice Activity Detection (VAD).
 * Uses energy-based VAD to detect speech breaks and automatically stop recording.
 *
 * Returns null when no speech was heard at all, and trims the clip to the
 * utterance plus a short margin. Callers must treat "no speech" as "nothing
 * happened": a clip of a quiet room is not an utterance, and enrolling it as a
 * wake-word reference would describe the room instead of the phrase.
 *
 * The three windows are separate on purpose. `maxSeconds` is the hard cap that
 * bounds memory; `silenceThresholdMs` ends a clip once speech has been heard,
 * which is what trims an utterance; and `noSpeechTimeoutMs` gives up when *nothing*
 * has been heard, which is the window the user answers into after the wake
 * phrase - a request that starts late must not be cut off, so it is only the
 * silence-only case that ends early.
 */
fun recordAudioWithVAD(
    context: Context,
    stopSignal: java.util.concurrent.atomic.AtomicBoolean,
    maxSeconds: Int = 60,
    silenceThresholdMs: Long = 1000L,
    noSpeechTimeoutMs: Long = 0L
): ByteArray? {
    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    val minBufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    if (minBufferSize <= 0) {
        android.util.Log.e("VAD", "Invalid buffer size: $minBufferSize")
        return null
    }

    val audioRecord = android.media.AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        sampleRate, channelConfig, audioFormat, minBufferSize * 4
    )

    if (audioRecord.state != android.media.AudioRecord.STATE_INITIALIZED) {
        android.util.Log.e("VAD", "AudioRecord initialization failed")
        audioRecord.release()
        return null
    }

    val audioBuffers = mutableListOf<Short>()
    val buffer = ShortArray(minBufferSize / 2)

    var speechDetected = false
    var lastSpeechTime: Long = 0
    // This absolute energy only gates "did anything happen at all" and the
    // silence break. It must not decide where the utterance starts and ends:
    // see voicedSpan, which cuts on the clip's own loudness instead. The old
    // 800 sat above normal speech through a headset mic - whole sentences
    // peaked under 500 RMS in the logs - so detection needed shouting, then
    // stopping, before anything was heard.
    val minEnergy = 300
    val recordingStart = System.currentTimeMillis()

    android.util.Log.d("VAD", "Starting recording with energy threshold $minEnergy...")

    // Capture may have to ride a Bluetooth headset; the route is closed when
    // the recorder is released. No cues here: they used to mark every capture,
    // which put a plonk between listening and responding on every turn - the
    // callers that open and close a call play the matched pair themselves.
    val restoreRoute = beginCommunicationCapture(context)
    try {
        audioRecord.startRecording()

        var chunkCount = 0
        // Each iteration consumes buffer.size samples, so the cap has to be
        // counted in iterations - counting samples would allow hours of silence.
        val maxIterations = sampleRate * maxSeconds / buffer.size

        while (chunkCount < maxIterations && !stopSignal.get()) {
            if (noSpeechTimeoutMs > 0 && !speechDetected &&
                System.currentTimeMillis() - recordingStart > noSpeechTimeoutMs) {
                // Nothing was heard within the window the caller allows, so this
                // is silence, not a late utterance - the caller decides what to
                // say about it.
                android.util.Log.d("VAD", "No speech within ${noSpeechTimeoutMs}ms, giving up")
                break
            }
            val bytesRead = audioRecord.read(buffer, 0, buffer.size)

            if (bytesRead < 0) {
                android.util.Log.e("VAD", "Error reading audio: $bytesRead")
                break
            }

            if (bytesRead == 0) {
                Thread.sleep(10)
                chunkCount++
                continue
            }

            // Calculate RMS energy for the ENTIRE chunk before processing samples
            var totalEnergy = 0L
            for (i in 0 until bytesRead) {
                val sample = buffer[i].toInt()
                totalEnergy += sample * sample
            }
            val rms = Math.sqrt(totalEnergy.toDouble() / bytesRead).toInt()

            // Reset hasSpeechInChunk based on RMS energy, not individual samples
            var hasSpeechInChunk = rms > minEnergy

            android.util.Log.d("VAD", "Chunk $chunkCount: bytesRead=$bytesRead, RMS=$rms, speechDetected=$speechDetected")

            if (hasSpeechInChunk) {
                lastSpeechTime = System.currentTimeMillis()
                speechDetected = true

                if (chunkCount % 160 == 0 && !stopSignal.get()) {
                    android.util.Log.d("VAD", "Recording... ${audioBuffers.size / 16000}s, RMS=$rms")
                }
            } else if (speechDetected && audioBuffers.isNotEmpty() && !stopSignal.get()) {
                // Check if we've been silent for too long
                val silenceDuration = System.currentTimeMillis() - lastSpeechTime
                if (silenceDuration > silenceThresholdMs) {
                    android.util.Log.d("VAD", "Silence detected (${silenceDuration}ms), stopping")
                    break
                }
            }

            // Store samples regardless of speech detection (to capture full utterance)
            for (i in 0 until bytesRead) {
                audioBuffers.add(buffer[i])
            }

            chunkCount++
        }

        android.util.Log.d("VAD", "Loop ended: chunkCount=$chunkCount, stopSignal=${stopSignal.get()}, speechDetected=$speechDetected")
        audioRecord.stop()
    } catch (e: Exception) {
        android.util.Log.e("VAD", "Recording error: ${e.message}", e)
        try { audioRecord.stop() } catch (_: Exception) {}
    } finally {
        try { audioRecord.release() } catch (_: Exception) {}
        restoreRoute?.invoke()
    }
    android.util.Log.d("VAD", "Recording completed, ${audioBuffers.size} samples (${audioBuffers.size / sampleRate.toFloat()}s)")

    if (!speechDetected) {
        android.util.Log.d("VAD", "No speech heard in this clip, returning nothing")
        return null
    }

    // Cut to the utterance. The cut is keyed to the clip's OWN loudest window,
    // not to the absolute RMS that gated the loop: the matcher normalises gain
    // before it compares anything, so an absolute cut is not on the same scale
    // as the features it feeds. Measured on the tab, the same phrase spoken at
    // arm's length fell under that absolute cut and kept only its loudest
    // fraction of a second - live clips of 71-139 frames against enrolled takes
    // of 135-223 - and that length gap alone scored a genuine phrase at 0.41-0.59
    // against a 0.38 bar. The margins stay because they carry the plosives at the
    // edges of the phrase.
    val leadSamples = sampleRate * 3 / 10
    val trailSamples = sampleRate / 2
    val (voicedFrom, voicedTo) = voicedSpan(audioBuffers, buffer.size, VOICED_TRIM_FRACTION)
    val from = kotlin.math.max(0, voicedFrom - leadSamples)
    val to = kotlin.math.min(audioBuffers.size, voicedTo + trailSamples)
    // A quarter-second is the shortest span that can carry a word: anything
    // less is a door click, a breath or the speaker's own tail, and sending it
    // to STT only earns a blank transcript and a needless "misheard" answer.
    if (voicedTo - voicedFrom < sampleRate / 4) {
        android.util.Log.d("VAD", "Voiced span ${(voicedTo - voicedFrom) / (sampleRate / 1000)}ms is under 250ms, treating as noise")
        return null
    }
    if (to <= from) return null

    // Convert to WAV format (little-endian PCM)
    val pcmBytes = ByteArray((to - from) * 2)
    for (i in from..<to) {
        val sample = audioBuffers[i].toInt()
        pcmBytes[(i - from) * 2] = (sample and 0xFF).toByte()
        pcmBytes[(i - from) * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
    }

    return convertPcmToWav(pcmBytes, sampleRate)
}

/**
 * Fraction of a clip's loudest window above which a window counts as voiced
 * when cutting a clip to its utterance. Deliberately well below the peak: the
 * cut has to keep the whole phrase, and the quiet syllables of a phrase sit a
 * long way under its loudest window.
 */
const val VOICED_TRIM_FRACTION = 0.25

/**
 * Sample offsets of the utterance inside [samples], keyed to the clip's own
 * loudest window instead of to an absolute energy.
 *
 * Scaling every sample by any positive factor leaves this span untouched, which
 * is the property the wake path needs: enrollment and the live pass are one and
 * the same phrase at different loudness, and a cut that moves with loudness
 * hands the matcher two clips of different lengths for the same utterance.
 *
 * Returns the whole buffer when there is no signal to key the cut to, so the
 * caller's own "was anything heard" gate stays the thing that answers silence.
 */
fun voicedSpan(
    samples: MutableList<Short>,
    window: Int,
    voicedFraction: Double
): Pair<Int, Int> {
    val total = samples.size
    if (total == 0 || window <= 0) return Pair(0, 0)

    val levels = mutableListOf<Double>()
    var peak = 0.0
    var start = 0
    while (start + window <= total) {
        var energy = 0.0
        for (i in start..<(start + window)) {
            val v = samples[i].toDouble()
            energy += v * v
        }
        val level = kotlin.math.sqrt(energy / window)
        levels.add(level)
        if (level > peak) peak = level
        start += window
    }
    if (peak <= 0.0) return Pair(0, total)

    val limit = peak * voicedFraction
    var firstWindow = -1
    var lastWindow = -1
    for (i in levels.indices) {
        if (levels[i] > limit) {
            if (firstWindow < 0) firstWindow = i
            lastWindow = i
        }
    }
    if (firstWindow < 0) return Pair(0, total)

    return Pair(
        firstWindow * window,
        kotlin.math.min(total, (lastWindow + 1) * window)
    )
}

/**
 * Simple PCM to WAV converter. Converts raw PCM byte array into a valid WAV file format.
 */
fun convertPcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
    val buffer = ByteArrayOutputStream()
    buffer.write("RIFF".toByteArray())
    writeIntLE(buffer, 36 + pcm.size) // File size - 44
    buffer.write("WAVE".toByteArray())

    buffer.write("fmt ".toByteArray())
    writeIntLE(buffer, 16) // Subchunk1Size (PCM)
    writeShortLE(buffer, 1) // AudioFormat (1 = PCM)
    writeShortLE(buffer, 1) // NumChannels (mono)
    writeIntLE(buffer, sampleRate)
    writeIntLE(buffer, sampleRate * 2) // ByteRate
    writeShortLE(buffer, 2) // BlockAlign
    writeShortLE(buffer, 16) // BitsPerSample

    buffer.write("data".toByteArray())
    writeIntLE(buffer, pcm.size)
    buffer.write(pcm)

    return buffer.toByteArray()
}

/**
 * Parses a WAV file and returns the sample rate and the raw PCM payload.
 *
 * TTS endpoints (Piper, OpenAI-compatible proxies) return full WAV files with a
 * RIFF header. The header must be stripped before playback and the actual sample
 * rate extracted, otherwise the audio plays at the wrong speed/pitch and the
 * header bytes produce an audible click.
 *
 * @return Pair(sampleRate, pcmBytes) or null if the data is not a valid WAV file.
 */
fun parseWavBytes(data: ByteArray): Pair<Int, ByteArray>? {
    if (data.size < 44) return null
    if ((data[0].toInt() and 0xFF) != 'R'.code ||
        (data[1].toInt() and 0xFF) != 'I'.code ||
        (data[2].toInt() and 0xFF) != 'F'.code ||
        (data[3].toInt() and 0xFF) != 'F'.code
    ) return null

    fun le16(i: Int): Int = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
    fun le32(i: Int): Int = (data[i].toInt() and 0xFF) or
        ((data[i + 1].toInt() and 0xFF) shl 8) or
        ((data[i + 2].toInt() and 0xFF) shl 16) or
        ((data[i + 3].toInt() and 0xFF) shl 24)

    // The canonical layout puts the rate at a fixed offset, but a leading LIST chunk
    // moves it - the walk below overwrites this when it sees the real fmt chunk.
    var sampleRate = le32(24)

    // Walk chunk list to find the "data" chunk (there may be extra chunks)
    var offset = 12
    while (offset + 8 <= data.size) {
        val chunkSize = le32(offset + 4)
        if ((data[offset].toInt() and 0xFF) == 'f'.code &&
            (data[offset + 1].toInt() and 0xFF) == 'm'.code &&
            (data[offset + 2].toInt() and 0xFF) == 't'.code &&
            (data[offset + 3].toInt() and 0xFF) == ' '.code
        ) {
            // fmt payload is format(2), channels(2), then sample rate(4)
            sampleRate = le32(offset + 8 + 4)
        } else if ((data[offset].toInt() and 0xFF) == 'd'.code &&
            (data[offset + 1].toInt() and 0xFF) == 'a'.code &&
            (data[offset + 2].toInt() and 0xFF) == 't'.code &&
            (data[offset + 3].toInt() and 0xFF) == 'a'.code
        ) {
            val dataStart = offset + 8
            if (dataStart < data.size) {
                return sampleRate to data.copyOfRange(dataStart, data.size)
            }
            return null
        }
        // Skip the whole chunk, padded to an even byte count - stepping by only the
        // 8-byte header lands inside the chunk data and misreads it as a chunk name.
        offset += 8 + chunkSize + (chunkSize and 1)
    }

    // Fallback: standard 44-byte header layout
    if (44 < data.size) {
        return sampleRate to data.copyOfRange(44, data.size)
    }
    return null
}

/**
 * One AudioTrack carrying a whole reply.
 *
 * Per-chunk playback used to build and tear down a track for every sentence
 * and drain each with a timed poll - which truncated long sentences, left a
 * gap between sentences, and computed frames against a guessed rate. Here the
 * reply's sentences are written into a single track: the blocking write is
 * what throttles the pipeline, so the buffer stays primed and no sentence
 * boundary is audible, and the track is drained once, against the frame count
 * it actually received, at the end of the reply.
 *
 * @param abortSignal raised to cut the reply short mid-stream.
 */
class ReplyAudioPlayer(
    private val abortSignal: java.util.concurrent.atomic.AtomicBoolean?,
    private val onStarving: (() -> Unit)? = null
) {
    private var track: android.media.AudioTrack? = null
    private var rate: Int = 0
    private var totalFrames: Long = 0
    private var aborted: Boolean = false
    private var lastStarveWarnAt: Long = 0

    /** Writes [byteCount] bytes of mono 16-bit PCM; false means stop (abort). */
    fun write(pcmBytes: ByteArray, byteCount: Int, sampleRate: Int): Boolean {
        if (aborted) return false
        if (rate != sampleRate) {
            drain()
            open(sampleRate)
            rate = sampleRate
            if (track == null) return false
        }
        val t = track ?: return false
        var written = 0
        while (written < byteCount) {
            if (abortSignal?.get() == true) {
                cutNow()
                aborted = true
                return false
            }
            val toWrite = minOf(byteCount - written, 32768)
            val n = try { t.write(pcmBytes, written, toWrite) } catch (e: Exception) { break }
            if (n <= 0) break
            written += n
        }
        totalFrames += written / 2
        // The lead is how many frames are buffered ahead of the playback
        // head. When it drops under a quarter second, the server cannot keep
        // up and the user is about to hear silence: ask the caller to fill
        // the gap (a waiting tick, a note in the chat), but only every few
        // seconds - one tick per written chunk would be a siren.
        val lead = totalFrames - (track?.playbackHeadPosition ?: 0)
        if (onStarving != null && rate > 0 && lead < rate / 4L) {
            val now = System.currentTimeMillis()
            if (now - lastStarveWarnAt > 3000) {
                lastStarveWarnAt = now
                try { onStarving() } catch (_: Exception) {}
            }
        }
        return true
    }

    /** End of the reply: play out what was written, then let the track go. */
    fun finish() {
        drain()
    }

    private fun open(sampleRate: Int) {
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = android.media.AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return
        track = android.media.AudioTrack(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            // A small ring starves between sentence fetches: each sentence is
            // a full HTTP round trip, and when the next one lands slower than
            // this one plays, the ring runs empty and the user hears a gap.
            // Sixteen minimum buffers (~1-2s of speech) ride out that jitter;
            // the blocking write still throttles the producer, so the extra
            // memory costs nothing but wait time.
            minBuffer * 16,
            android.media.AudioTrack.MODE_STREAM,
            0
        )
        if (track?.state != android.media.AudioTrack.STATE_INITIALIZED) {
            try { track?.release() } catch (_: Exception) {}
            track = null
            return
        }
        track?.play()
    }

    private fun drain() {
        val t = track ?: return
        try {
            t.stop()
            // The poll is only a release guard - its deadline is the audio's
            // own length plus slack, and the frame count it waits for is the
            // count the track actually received, so nothing is cut.
            val drainMs = if (rate > 0) totalFrames * 1000 / rate + 500 else 500L
            val deadline = System.currentTimeMillis() + drainMs
            while (System.currentTimeMillis() < deadline) {
                if (t.playbackHeadPosition >= totalFrames) break
                Thread.sleep(20)
            }
        } catch (e: Exception) {
            android.util.Log.w("TTS", "Drain failed: ${e.message}")
        }
        try { t.release() } catch (_: Exception) {}
        track = null
        totalFrames = 0
    }

    private fun cutNow() {
        val t = track ?: return
        try {
            t.pause()
            t.flush()
        } catch (_: Exception) {}
        try { t.release() } catch (_: Exception) {}
        track = null
        totalFrames = 0
    }
}

/**
 * Plays raw 16-bit mono PCM audio through AudioTrack, blocking until playback
 * completes. Tries the preferred sample rate first, then falls back to common
 * rates for device compatibility.
 *
 * @param pcmBytes Raw little-endian 16-bit mono PCM samples.
 * @param sampleRate Preferred sample rate of the PCM data.
 * @param abortSignal Raised while playing - the samples stop being written, so the
 *   wake word can take over mid-reply.
 * @return true if any audio was played.
 */
fun playPcmAudio(pcmBytes: ByteArray, sampleRate: Int, abortSignal: AtomicBoolean? = null, player: ReplyAudioPlayer? = null): Boolean {
    if (pcmBytes.isEmpty()) return false
    if (player != null) return player.write(pcmBytes, pcmBytes.size, sampleRate)

    val preferredRate = if (sampleRate in 8000..96000) sampleRate else 22050
    val rates = (listOf(preferredRate) + listOf(22050, 16000, 24000, 44100, 8000, 11025))
        .distinct()

    for (rate in rates) {
        var audioTrack: android.media.AudioTrack? = null
        try {
            val minBufferSize = android.media.AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) continue

            audioTrack = android.media.AudioTrack(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                minBufferSize * 4,
                android.media.AudioTrack.MODE_STREAM,
                0
            )

            if (audioTrack.state != android.media.AudioTrack.STATE_INITIALIZED) {
                audioTrack.release()
                audioTrack = null
                continue
            }

            audioTrack.play()
            var totalWritten = 0
            while (totalWritten < pcmBytes.size) {
                if (abortSignal?.get() == true) {
                    android.util.Log.d("TTS", "Samples stopped by the wake word (written=$totalWritten)")
                    break
                }
                val remaining = pcmBytes.size - totalWritten
                val toWrite = minOf(remaining, 32768)
                val written = audioTrack.write(pcmBytes, totalWritten, toWrite)
                if (written <= 0) break
                totalWritten += written
            }

            if (totalWritten == 0) {
                audioTrack.release()
                audioTrack = null
                continue
            }

            // The caller hung up mid-sentence: cut the sound out of the speaker
            // right now instead of playing the already-written buffer out. The
            // drain discipline below exists to protect a reply's tail; an abort
            // exists to silence it.
            if (abortSignal?.get() == true) {
                audioTrack.pause()
                audioTrack.flush()
                audioTrack.release()
                android.util.Log.d("TTS", "Playback cut short by abort (written=$totalWritten)")
                return false
            }

            // The write is blocking but only until the buffer accepts the data,
            // so returning here would leave the speaker still sounding the tail
            // - which the freshly re-armed microphone then hears as speech and
            // turns into phantom utterances. stop() plays out what was written
            // (flush() would discard it), and the head position is polled until
            // every frame has actually left the speaker.
            audioTrack.stop()
            val totalFrames = totalWritten / 2L
            // The drain waits as long as the audio itself lasts plus a beat of
            // slack - a fixed cap truncates every sentence longer than the cap,
            // which is exactly the "reply cut off at the end of each portion"
            // a fixed two-second deadline used to produce on long sentences.
            val drainMs = totalFrames * 1000L / rate + 500
            val deadline = System.currentTimeMillis() + drainMs
            while (System.currentTimeMillis() < deadline) {
                if (audioTrack.playbackHeadPosition >= totalFrames) break
                Thread.sleep(20)
            }
            audioTrack.release()
            android.util.Log.d("TTS", "Played ${totalWritten} bytes at ${rate}Hz")
            return true
        } catch (e: Exception) {
            android.util.Log.w("TTS", "Playback failed at ${rate}Hz: ${e.message}")
            try { audioTrack?.release() } catch (_: Exception) {}
        }
    }

    android.util.Log.e("TTS", "Failed to play TTS - no compatible sample rate")
    return false
}

/**
 * Split text into sentences based on common punctuation marks.
 */
fun splitIntoSentences(text: String): List<String> {
    val sentences = mutableListOf<String>()
    val currentSentence = StringBuilder()

    val chars = text.toCharArray()
    var i = 0

    while (i < chars.size) {
        currentSentence.append(chars[i])

        // Check for sentence-ending punctuation followed by space or end of string
        if ((chars[i] == '.' || chars[i] == '!' || chars[i] == '?' || chars[i] == ';' || chars[i] == ':') && i + 1 < chars.size) {
            // Skip if it's part of a number (e.g., "3.14")
            val isNumber = chars[i] == '.' && i > 0 && chars[i - 1].isDigit() && i + 1 < chars.size - 1 && chars[i + 1].isDigit()

            if (!isNumber) {
                // Check for space after punctuation or end of string
                val nextIsSpace = i + 1 >= chars.size || chars[i + 1] == ' ' || chars[i + 1] == '\n'

                if (nextIsSpace && currentSentence.length > 1) {
                    val sentence = currentSentence.toString().trim()
                    if (sentence.isNotEmpty()) {
                        sentences.add(sentence)
                    }
                    currentSentence.clear()
                }
            }
        }

        i++
    }

    // Add any remaining text as the last sentence
    val remaining = currentSentence.toString().trim()
    if (remaining.isNotEmpty()) {
        sentences.add(remaining)
    }

    return sentences
}

/**
 * Play TTS audio for the given text using the configured endpoint.
 * Supports both full response and streaming modes.
 *
 * @param text Text to synthesize.
 * @param ttsSettings Pair of (baseUrl, apiKey) for the TTS endpoint.
 * @param model TTS model name from settings.
 * @param voice Voice name from settings.
 * @param enableStreaming Whether to use streaming TTS playback.
 * @param timeoutSeconds HTTP timeout for each synthesis request, from the app settings.
 * @param abortSignal Raised while the reply is being spoken - the wake word cut the
 *   exchange short, so the rest of the audio is dropped rather than played.
 */
 suspend fun playTTSAudio(
     text: String?,
     ttsSettings: Pair<String?, String?>?,
     model: String = "tts-1-hd",
     voice: String? = null,
     responseFormat: String = "mp3",
     enableStreaming: Boolean = false,
     timeoutSeconds: Long,
     abortSignal: AtomicBoolean? = null,
     onTtsTooSlow: (() -> Unit)? = null
 ) {
     if (text == null || ttsSettings?.first == null) {
         return
     }

     val cleanText = text.trim()
     if (cleanText.isEmpty()) {
         return
     }

     // One track carries the whole reply - every sentence, however many -
     // so sentence boundaries are inaudible and only one drain happens at
     // the end.
     val player = ReplyAudioPlayer(abortSignal, onTtsTooSlow)

     val ttsBaseUrl = ttsSettings.first!!
     val ttsApiKey = ttsSettings.second?.takeIf { it.isNotBlank() }

     // Streaming playback: synthesize and speak one sentence at a time, so audio
     // starts before the whole reply is ready. The local TTS proxies have no SSE
     // route, so the chunking happens here against the plain /audio/speech call.
     //
     // The synthesis is the expensive half - the local server costs a beat per
     // sentence - so the next sentence is requested while the current one is still
     // playing. A request that waited for the previous sentence to finish playing
     // leaves that beat as silence the user can hear between sentences.
     if (enableStreaming) {
         val sentences = splitIntoSentences(cleanText)
         if (sentences.size > 1) {
             var played = false
             val fetchScope = CoroutineScope(Dispatchers.Default + Job())
             fun fetch(text: String) = fetchScope.async {
                 fetchTtsAudio(ttsBaseUrl, ttsApiKey, text, model, voice, responseFormat, timeoutSeconds)
                     ?: fetchTtsAudio(ttsBaseUrl, ttsApiKey, text, model, null, responseFormat, timeoutSeconds)
             }
             // Two fetches in flight: one playing, one landing. A single
             // prefetch made the pipeline exactly as fast as the network -
             // any fetch slower than playback of its sentence starved the
             // track and was heard as a drop-out, so the second slot keeps a
             // sentence of slack between the server and the speaker.
             val inFlight = ArrayDeque< kotlinx.coroutines.Deferred<ByteArray?> >()
             inFlight.add(fetch(sentences[0]))
             if (sentences.size > 1) inFlight.add(fetch(sentences[1]))
             for (index in sentences.indices) {
                 val chunk = inFlight.removeFirst().await()
                 val nextIndex = index + 2
                 if (nextIndex < sentences.size) inFlight.add(fetch(sentences[nextIndex]))
                 if (abortSignal?.get() == true) {
                     android.util.Log.d("TTS", "Playback cut short by the wake word")
                     fetchScope.cancel()
                     return
                 }
                 if (chunk != null && chunk.isNotEmpty() && playAudioBytes(chunk, abortSignal, player)) {
                     played = true
                 }
             }
             player.finish()
             fetchScope.cancel()
             if (played) return
             android.util.Log.w("TTS", "Streaming playback failed, falling back to a single request")
         }
     }

     try {
         // Download the full audio. Many local TTS servers (e.g. Piper proxies)
         // reject the `voice` field with HTTP 400, so if the first request fails
         // we retry without a voice to fall back to the server's default voice.
         val audioBytes = fetchTtsAudio(ttsBaseUrl, ttsApiKey, cleanText, model, voice, responseFormat, timeoutSeconds)
             ?: fetchTtsAudio(ttsBaseUrl, ttsApiKey, cleanText, model, null, responseFormat, timeoutSeconds)

         if (audioBytes == null || audioBytes.isEmpty()) {
             android.util.Log.e("TTS", "TTS produced no audio")
             return
         }

         if (playAudioBytes(audioBytes, abortSignal, player)) {
             player.finish()
             return
         }

         // Some endpoints advertise mp3 but only produce a container this device can
         // decode as wav, so retry once in the format the player is known to handle.
         if (responseFormat != "wav") {
             android.util.Log.w("TTS", "Could not play $responseFormat, retrying as wav")
             val wav = fetchTtsAudio(ttsBaseUrl, ttsApiKey, cleanText, model, null, "wav", timeoutSeconds)
             if (wav != null && wav.isNotEmpty()) {
                 playAudioBytes(wav, abortSignal, player)
             }
             player.finish()
         }
     } catch (e: Exception) {
         android.util.Log.e("TTS", "TTS playback failed: ${e.message}", e)
     }
 }

/**
 * Download TTS audio from the endpoint, returning null on any failure.
 */
private suspend fun fetchTtsAudio(
    baseUrl: String,
    apiKey: String?,
    text: String,
    model: String,
    voice: String?,
    responseFormat: String = "mp3",
    timeoutSeconds: Long
): ByteArray? {
    return try {
        com.localassistant.app.data.remote.ApiClient.synthesizeSpeech(
            baseUrl = baseUrl,
            apiKey = apiKey,
            text = text,
            model = model,
            voice = voice,
            responseFormat = responseFormat,
            timeoutSeconds = timeoutSeconds
        )
    } catch (e: Exception) {
        android.util.Log.w("TTS", "TTS request failed (voice=${voice ?: "none"}, format=$responseFormat): ${e.message}")
        null
    }
}

/**
 * Loads the sample recording bundled at 'assets/voice_clone.wav' from the APK,
 * used by the STT test. Paths are relative to the 'assets/' directory.
 *
 * @return the WAV bytes, or an empty array when the asset is missing.
 */
fun loadBundledTestAudio(context: android.content.Context): ByteArray {
    for (path in listOf("voice_clone.wav", "res/raw/voice_clone.wav")) {
        try {
            val data = context.assets.open(path).readBytes()
            if (data.isNotEmpty()) return data
            android.util.Log.w("STT", "Bundled test audio empty at $path")
        } catch (e: Exception) {
            android.util.Log.w("STT", "Bundled test audio not readable at $path: ${e.message}")
        }
    }
    return ByteArray(0)
}

fun buildSilentWav(durationMs: Int = 500, sampleRate: Int = 16000): ByteArray =
    convertPcmToWav(ByteArray(sampleRate * 2 * durationMs / 1000), sampleRate)

// Sample rate the synthesised wake cue is played at.
private const val WAKE_CUE_SAMPLE_RATE = 16000

// Sample rate the synthesised waiting cue is played at.
private const val WAITING_CUE_SAMPLE_RATE = 16000

/**
 * Plays the rising plop that tells the user the microphone is now listening -
 * the same up-chirp ChatGPT plays when its voice session opens. Synthesised so
 * it needs no asset and still plays while the app is minimized.
 */
fun playListenStartCue(): Boolean = playPlopCue(240.0, 900.0)

/**
 * The falling downbeat that closes a listening window - a drop chirp paired
 * with [playListenStartCue], so opening and closing the mic read as a matched
 * pair like a ChatGPT-style voice session.
 */
fun playListenEndCue(): Boolean = playPlopCue(900.0, 240.0)

/**
 * Renders one bubble-like chirp sweeping between two frequencies.
 *
 * The phase is integrated from the frequency curve rather than sampled per
 * sample, because an instantaneous-frequency sine would click wherever the
 * sweep passes through; a cumulative phase makes the plop continuous.
 */
private fun playPlopCue(fromHz: Double, toHz: Double): Boolean {
    val durationMs = 160
    val seconds = durationMs / 1000.0
    val samples = FloatArray(WAITING_CUE_SAMPLE_RATE * durationMs / 1000)
    for (index in samples.indices) {
        val t = index.toDouble() / WAITING_CUE_SAMPLE_RATE
        val progress = (t / seconds).coerceIn(0.0, 1.0)
        // Quadratic ease makes the plop spend more time at its start pitch,
        // which is what gives a bubble its "blip" rather than a plain sweep.
        val eased = progress * progress
        val phase = 2.0 * Math.PI *
            (fromHz * t + (toHz - fromHz) * t * t / (2.0 * seconds))
        // Fast attack so the chirp has no click, then decay like a bubble
        // popping - a constant amplitude would sound like a test tone.
        val attack = minOf(1.0, t / 0.003)
        val fade = kotlin.math.exp(-t / (durationMs / 1400.0))
        samples[index] = (0.16 * attack * fade * kotlin.math.sin(phase)).toFloat()
    }

    val pcm = ByteArray(samples.size * 2)
    samples.forEachIndexed { index, value ->
        val sample = (value * Short.MAX_VALUE).toInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
        pcm[index * 2] = (sample and 0xFF).toByte()
        pcm[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
    }
    return playPcmAudio(pcm, WAITING_CUE_SAMPLE_RATE)
}

/**
 * Routes microphone capture through the audio manager's communication routing,
 * which is the meta input that follows whatever the user is actually using -
 * Bluetooth headphones, a wired headset, or the phone's own mic.
 *
 * AudioSource.VOICE_COMMUNICATION keeps recording the phone's own mic unless
 * the audio manager is told where communication audio belongs, so with modern
 * Bluetooth headphones (Sony XM5 and friends) the recorder either reads the
 * phone mic or silence - the STT then hears nothing or hears the room.
 *
 * A headset mic is always claimed explicitly when one is available. Trusting
 * the framework's [AudioManager.getCommunicationDevice] is not enough: on a
 * modern Bluetooth headset the framework reports the A2DP output as the
 * "active communication device" (audio_flinger then shows Input device:
 * AUDIO_DEVICE_IN_BUILTIN_MIC), so the output plays through the headphones
 * while the input rides the phone mic - the mismatch this function exists to
 * fix. Only when no headset candidate exists at all does the built-in mic
 * carry the capture.
 *
 * Returns a restore runnable callers run in a finally block; it undoes
 * exactly what was set here.
 */
fun beginCommunicationCapture(context: Context): (() -> Unit)? {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null

    val candidates = audioManager.getAvailableCommunicationDevices()
    // Prefer the headset the user is actually wearing; fall back to the
    // built-in mic only when there is nothing else.
    val preferred = candidates.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    } ?: candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?: return null

    // Re-routing to the same device object the framework already reports is a
    // no-op and costs nothing; re-routing away from the framework's phantom
    // A2DP choice onto a real headset input is the whole point.
    try {
        audioManager.setCommunicationDevice(preferred)
        // Routing is asynchronous and on Bluetooth it is not instant: the SCO
        // link itself has to be established, which can take a second or two
        // from a cold start. A fixed sleep races that link - the recorder would
        // open on the phone mic, miss the first words, and only later turn on
        // the headset. So poll until the route is actually reported active,
        // with a generous cap; falling through just means the recorder starts
        // a moment before the route lands rather than never.
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + 3000
        var routed = false
        while (System.currentTimeMillis() < deadline) {
            val active = try { audioManager.getCommunicationDevice() } catch (e: Exception) { null }
            if (active != null && active.type == preferred.type) {
                routed = true
                break
            }
            Thread.sleep(50)
        }
        android.util.Log.d(
            "VAD",
            "Communication route set: type=${preferred.type} routed=$routed " +
                "waited=${System.currentTimeMillis() - startedAt}ms"
        )
    } catch (e: Exception) {
        android.util.Log.w("VAD", "Could not set communication route: ${e.message}")
        return null
    }
    return {
        try {
            audioManager.clearCommunicationDevice()
        } catch (e: Exception) {
            android.util.Log.w("VAD", "Could not clear communication route: ${e.message}")
        }
    }
}



/**
 * Plays the busy cue: three light, quick "tippy-tap" ticks, like a small
 * creature typing away at its work. Bright wood-block pitches rather than low
 * thuds - the low footstep version read as an ominous heartbeat while the
 * connection retried - and the rising tick-tick-tick reads as activity moving
 * along, never as an alarm. At lowish volume it never interrupts speech or the
 * TTS reply.
 */
fun playWaitingCue(): Boolean {
    val durationMs = 450
    val samples = FloatArray(WAITING_CUE_SAMPLE_RATE * durationMs / 1000)
    // Three quick ticks, rising in pitch and dropping in level: tip, tip,
    // tip. Kept subtle - about a third of the first version's loudness and
    // a warmer pitch, so the cue sits in the background instead of beeping.
    addSoftTick(samples, 0, 60, 1200.0f, 0.02f)
    addSoftTick(samples, 140, 60, 1450.0f, 0.016f)
    addSoftTick(samples, 290, 60, 1750.0f, 0.013f)

    val pcm = ByteArray(samples.size * 2)
    samples.forEachIndexed { index, value ->
        val sample = (value * Short.MAX_VALUE).toInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
        pcm[index * 2] = (sample and 0xFF).toByte()
        pcm[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
    }
    return playPcmAudio(pcm, WAITING_CUE_SAMPLE_RATE)
}

/**
 * Adds one light tick to the buffer: a short sine blip at a bright frequency
 * with a quick decay, so it sounds like a finger tapping wood rather than a
 * tone or a thud.
 */
private fun addSoftTick(
    samples: FloatArray,
    startMs: Int,
    durationMs: Int,
    frequencyHz: Float,
    peak: Float
) {
    val start = WAITING_CUE_SAMPLE_RATE * startMs / 1000
    val end = minOf(samples.size, WAITING_CUE_SAMPLE_RATE * (startMs + durationMs) / 1000)
    for (index in start until end) {
        val t = (index - start).toDouble() / WAITING_CUE_SAMPLE_RATE
        // The attack rises over the first few milliseconds so the step has no
        // click, then decays like a struck drum - a constant amplitude would
        // sound like a beep instead of a footstep.
        val attack = minOf(1f, (t / 0.004).toFloat())
        val fade = kotlin.math.exp(-t / (durationMs / 900.0)).toFloat()
        samples[index] += peak * attack * fade *
            kotlin.math.sin(2.0 * kotlin.math.PI * frequencyHz.toDouble() * t).toFloat()
    }
}

/**
 * Plays the short chime that tells the user the wake phrase was heard and that
 * the microphone is now recording their request.
 *
 * The cue is synthesised rather than bundled as an asset so it needs no file, no
 * decoder and no endpoint, and so it still plays when the app is minimized and
 * only the service process is left. It blocks until the chime has finished, which
 * is what the wake pipeline needs: the request recording must not start before the
 * user has heard that they can speak.
 */
fun playWakeCue(): Boolean {
    val durationMs = 320
    val samples = FloatArray(WAKE_CUE_SAMPLE_RATE * durationMs / 1000)
    addFadingNote(samples, 0, 180, 1046.5f, 0.30f)
    addFadingNote(samples, 100, 240, 1396.9f, 0.22f)

    val pcm = ByteArray(samples.size * 2)
    samples.forEachIndexed { index, value ->
        val sample = (value * Short.MAX_VALUE).toInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
        pcm[index * 2] = (sample and 0xFF).toByte()
        pcm[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
    }
    return playPcmAudio(pcm, WAKE_CUE_SAMPLE_RATE)
}

/**
 * Adds one struck note to the buffer: full amplitude at the strike, then an
 * exponential fade. A constant amplitude would sound like a beep, not a ding.
 */
private fun addFadingNote(
    samples: FloatArray,
    startMs: Int,
    durationMs: Int,
    frequencyHz: Float,
    peak: Float
) {
    val start = WAKE_CUE_SAMPLE_RATE * startMs / 1000
    val end = minOf(samples.size, WAKE_CUE_SAMPLE_RATE * (startMs + durationMs) / 1000)
    for (index in start..end - 1) {
        val t = (index - start).toDouble() / WAKE_CUE_SAMPLE_RATE
        val fade = kotlin.math.exp(-t / (durationMs / 3000.0)).toFloat()
        samples[index] += peak * fade * kotlin.math.sin(2.0 * kotlin.math.PI * frequencyHz.toDouble() * t).toFloat()
    }
}

/**
 * Decides whether a transcript is the configured wake word.
 *
 * Matching is token based with prefix tolerance, so a slightly misheard phrase
 * ("hey assistent") still triggers. @param sensitivity is the fraction of the
 * wake word tokens that must be heard, between 0 and 1.
 */
fun matchesWakeWord(transcript: String, wakeWord: String, sensitivity: Float): Boolean {
    val target = wakeWord.trim().lowercase().split(" ").filter { it.isNotBlank() }
    if (target.isEmpty()) return false

    val heard = transcript.trim().lowercase().split(" ").filter { it.isNotBlank() }
    if (heard.isEmpty()) return false

    val threshold = sensitivity.coerceIn(0f, 1f)
    val hits = target.count { word ->
        heard.any { heardWord ->
            heardWord == word ||
                (word.length > 3 && heardWord.length > 3 &&
                    (heardWord.startsWith(word) || word.startsWith(heardWord)))
        }
    }
    return hits.toFloat() / target.size >= threshold
}

/**
 * Decides whether a transcript carries speech at all.
 *
 * whisper.cpp answers silence or music with bracketed tokens ("[BLANK_AUDIO]",
 * "[MUSIC]") instead of an empty string, so both listening paths need to treat
 * such a transcript as "nothing was said" before it reaches the LLM.
 */
fun isNonSpeechTranscript(text: String): Boolean {
    val tokens = text.trim().split(" ").filter { it.isNotBlank() }
    if (tokens.isEmpty()) return true
    return tokens.all {
        (it.startsWith("[") && it.endsWith("]")) || (it.startsWith("(") && it.endsWith(")"))
    }
}

/**
 * Play audio bytes, detecting the container format. WAV files are parsed to raw
 * PCM and played via AudioTrack; other containers (MP3, OGG, FLAC) are decoded
 * via MediaCodec and played via AudioTrack.
 *
 * @param abortSignal Raised while playing - the audio is cut short rather than
 *   played to the end, so the wake word can take over mid-reply.
 * @return true if audio was played.
 */
suspend fun playAudioBytes(data: ByteArray, abortSignal: AtomicBoolean? = null, player: ReplyAudioPlayer? = null): Boolean {
    android.util.Log.e("TTS", "playAudioBytes: size=${data.size}")
    if (data.isEmpty()) return false

    // WAV files start with the "RIFF" magic; play as raw PCM after stripping the header.
    if (data.size >= 44 &&
        (data[0].toInt() and 0xFF) == 'R'.code &&
        (data[1].toInt() and 0xFF) == 'I'.code &&
        (data[2].toInt() and 0xFF) == 'F'.code &&
        (data[3].toInt() and 0xFF) == 'F'.code
    ) {
        val parsed = parseWavBytes(data)
        if (parsed == null) {
            // MediaCodec understands the RIFF container too, so decode it instead of
            // playing the header bytes as PCM at a guessed rate.
            return playMp3Audio(data, abortSignal)
        }
        return playPcmAudio(parsed.second, parsed.first, abortSignal, player)
    }

    // MP3 / OGG / FLAC / other - decode via MediaCodec and play via AudioTrack.
    return playMp3Audio(data, abortSignal, player)
}

/**
 * Decode MP3 audio bytes to PCM via MediaCodec and play them through
 * AudioTrack, blocking until playback completes. Runs on the IO dispatcher so
 * the blocking decode/playback does not stall the main thread. This avoids
 * ExoPlayer, whose high-level event dispatch is unreliable on this device.
 *
 * @return true if audio was played.
 */
 private suspend fun playMp3Audio(data: ByteArray, abortSignal: AtomicBoolean? = null, player: ReplyAudioPlayer? = null): Boolean =
     withContext(Dispatchers.IO) {
         android.util.Log.e("TTS", "playMp3Audio: size=${data.size}")
         val firstBytes = data.take(12).joinToString(" ") { String.format("%02x", it) }
         android.util.Log.e("TTS", "first bytes: $firstBytes")
         var codec: android.media.MediaCodec? = null
        var track: android.media.AudioTrack? = null
        var decodedRate = 24000
        try {
            val sampleRate = 24000
            val channelMask = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            // A shared ReplyAudioPlayer carries the sound across the whole
            // reply, so this function only decodes into it and creates no
            // track of its own. The guess rate stays only as a fallback when
            // the decoder's output format declines to name one.
            if (player == null) {
            val minBufferSize = android.media.AudioTrack.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) return@withContext false

            track = android.media.AudioTrack(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                audioFormat,
                minBufferSize * 4,
                android.media.AudioTrack.MODE_STREAM,
                0
            )
            if (track.state != android.media.AudioTrack.STATE_INITIALIZED) {
                track.release()
                track = null
                return@withContext false
            }
            }

            // List available decoders and find an MP3-compatible MIME type
            var chosenMime: String? = null
            try {
                val count = android.media.MediaCodecList.getCodecCount()
                for (i in 0 until count) {
                    val info = android.media.MediaCodecList.getCodecInfoAt(i)
                    if (!info.isEncoder) {
                        val types = info.getSupportedTypes()
                        android.util.Log.d("TTS", "Decoder: ${info.name} [${types.joinToString(", ")}]")
                        for (t in types) {
                            if (t == "audio/mp3" || t == "audio/mpeg" || t == "audio/mpeg3") {
                                chosenMime = t
                                break
                            }
                        }
                        if (chosenMime != null) break
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TTS", "Failed to list codecs: ${e.message}")
            }

            if (chosenMime == null) {
                android.util.Log.e("TTS", "No MP3 decoder found on this device")
                return@withContext false
            }

            codec = android.media.MediaCodec.createDecoderByType(chosenMime)
            val format = android.media.MediaFormat.createAudioFormat(chosenMime, sampleRate, 1)
            android.util.Log.e("TTS", "About to configure (mime=$chosenMime)")
            codec.configure(format, null, null, 0)
            android.util.Log.e("TTS", "Configured, about to start")
            codec.start()
            android.util.Log.e("TTS", "Codec started, data.size=${data.size}")

            // Feed and drain in the same loop. The decoder's input queue is only a
            // handful of buffers deep, so pushing the whole reply first stalls it:
            // dequeueInputBuffer answers "queue full" partway through and the rest of
            // the payload never reaches the decoder, which is what made a long mp3 reply
            // play as silence.
            track?.play()
            android.util.Log.e("TTS", "Track playing, starting feed/decode loop")
            val bufInfo = android.media.MediaCodec.BufferInfo()
            val outBuf = ByteArray(8192)
            var offset = 0
            var feedCount = 0
            var done = false
            var idleSpins = 0
            var totalWritten = 0
            while (!done) {
                if (abortSignal?.get() == true) {
                    android.util.Log.d("TTS", "Decoding cut short by the wake word (written=$totalWritten)")
                    break
                }
                if (offset < data.size) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val maxLen = minOf(inputBuffer.capacity(), data.size - offset)
                            inputBuffer.put(data, offset, maxLen)
                            val flags = if (offset + maxLen >= data.size) android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            codec.queueInputBuffer(inputIndex, maxLen, 0, 0L, flags)
                            offset += maxLen
                            feedCount++
                        }
                    }
                }
                val idx = codec.dequeueOutputBuffer(bufInfo, 1000)
                if (idx >= 0) {
                    if (bufInfo.size > 0 &&
                        (bufInfo.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        val ob = codec.getOutputBuffer(idx)
                        if (ob != null) {
                            ob.position(bufInfo.offset)
                            ob.limit(bufInfo.offset + bufInfo.size)
                            // The decoder, not the container guess, names the
                            // rate its PCM actually plays at - the hardcoded
                            // guess here is what made the drain math cut long
                            // sentences short.
                            decodedRate = codec.outputFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE, decodedRate)
                            var pos = 0
                            while (pos < bufInfo.size) {
                                val chunk = minOf(outBuf.size, bufInfo.size - pos)
                                ob.get(outBuf, 0, chunk)
                                if (player != null) {
                                    if (!player.write(outBuf, chunk, decodedRate)) {
                                        codec.releaseOutputBuffer(idx, false)
                                        return@withContext false
                                    }
                                } else {
                                    track!!.write(outBuf, 0, chunk)
                                }
                                pos += chunk
                            }
                            totalWritten += bufInfo.size
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((bufInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        done = true
                    }
                    idleSpins = 0
                } else {
                    idleSpins++
                    if (idleSpins > 200) break
                }
            }
            android.util.Log.e(
                "TTS",
                "Feed/decode finished: fed=$offset/${data.size} bytes, chunks=$feedCount, pcm=$totalWritten"
            )

            // The reply-level player drains once for the whole reply, so a
            // per-chunk drain here would truncate every sentence again.
            // A reply-level player drains once for the whole reply, so a
            // per-chunk drain here would truncate every sentence again; its
            // caller decides what a decode that produced nothing means.
            if (player != null) {
                codec.stop()
                codec.release()
                return@withContext true
            }
            // An abort cuts the sound out of the speaker immediately - the
            // drain discipline below exists to protect a reply's tail, and an
            // abort exists to silence it, so it must not play out what was
            // already written.
            if (abortSignal?.get() == true) {
                track?.pause()
                track?.flush()
                track?.release()
                codec.stop()
                codec.release()
                android.util.Log.d("TTS", "MediaCodec playback cut short by abort (written=$totalWritten)")
                return@withContext false
            }

            // Same drain discipline as playPcmAudio: stop() plays out what is
            // already written (flush() would cut the reply's tail), and the head
            // position is polled until the speaker has actually finished.
            track?.stop()
            val totalFrames = totalWritten / 2L
            // Duration-scaled, like playPcmAudio: a fixed cap amputates the
            // tail of every sentence that outlives it. The decoder names the
            // rate its PCM really plays at - the old hardcoded guess is what
            // cut long sentences short.
            val drainMs = totalFrames * 1000L / decodedRate + 500
            val drainDeadline = System.currentTimeMillis() + drainMs
            while (System.currentTimeMillis() < drainDeadline) {
                if (track?.playbackHeadPosition ?: 0 >= totalFrames) break
                Thread.sleep(20)
            }
            track?.release()
            codec.stop()
            codec.release()
            if (totalWritten == 0) {
                // Nothing came out, so the caller must not read this as "played":
                // reporting success here is what hides the failure from the wav
                // fallback and leaves the user with a silent reply.
                android.util.Log.e("TTS", "MediaCodec decoded nothing from ${data.size} bytes")
                false
            } else {
                android.util.Log.d("TTS", "Played $totalWritten PCM bytes via MediaCodec")
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("TTS", "MediaCodec playback failed: ${e.message}", e)
            false
        } finally {
            try { track?.release() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
        }
    }

private fun isMp3(data: ByteArray): Boolean =
    data.size >= 3 &&
        (data[0].toInt() and 0xFF) == 'I'.code &&
        (data[1].toInt() and 0xFF) == 'D'.code &&
        (data[2].toInt() and 0xFF) == '3'.code

private fun isOgg(data: ByteArray): Boolean =
    data.size >= 4 &&
        (data[0].toInt() and 0xFF) == 'O'.code &&
        (data[1].toInt() and 0xFF) == 'g'.code &&
        (data[2].toInt() and 0xFF) == 'g'.code &&
        (data[3].toInt() and 0xFF) == 'S'.code

private fun isFlac(data: ByteArray): Boolean =
    data.size >= 4 &&
        (data[0].toInt() and 0xFF) == 'f'.code &&
        (data[1].toInt() and 0xFF) == 'L'.code &&
        (data[2].toInt() and 0xFF) == 'a'.code &&
        (data[3].toInt() and 0xFF) == 'C'.code

/**
 * Share a message via the system share sheet.
 */
fun shareMessage(context: Context, text: String) {
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, text)
        type = "text/plain"
    }

    val shareIntent = Intent.createChooser(sendIntent, null)
    context.startActivity(shareIntent)
}

/**
 * Export chat messages to a text file in the external files directory.
 */
fun exportChatToTextFile(context: Context, messages: List<com.localassistant.app.domain.model.ChatMessage>) {
    try {
        val exportDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())
        val timestamp = dateFormat.format(System.currentTimeMillis())
        val file = File(exportDir, "chat_export_$timestamp.txt")

        val content = buildString {
            append("Local Assistant - Chat Export\n")
            append("=" .repeat(50))
            append("\nExported: $timestamp\n\n")

            for ((index, message) in messages.withIndex()) {
                val role = if (message.role == com.localassistant.app.domain.model.MessageRole.USER) "User" else "Assistant"
                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(message.timestamp)

                append("[${index + 1}] $role ($timeStr)\n")
                append("-".repeat(40))
                append("\n")
                append("${message.content}\n\n")
            }

            append("=" .repeat(50))
            append("\nTotal messages: ${messages.size}")
        }

        file.writeText(content)

        Toast.makeText(
            context,
            "Chat exported to: ${file.absolutePath}",
            Toast.LENGTH_LONG
        ).show()
    } catch (e: Exception) {
        Toast.makeText(
            context,
            "Failed to export chat: ${e.message}",
            Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Export chat messages as a structured JSON file with metadata.
 */
fun exportChatToJsonFile(context: Context, messages: List<com.localassistant.app.domain.model.ChatMessage>) {
    try {
        val exportDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())
        val timestamp = dateFormat.format(System.currentTimeMillis())
        val file = File(exportDir, "chat_export_$timestamp.json")

        // Get app version
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        // Build JSON manually to avoid adding external dependencies
        val json = buildString {
            append("{\n")
            append("  \"metadata\": {\n")
            append("    \"export_timestamp\": \"$timestamp\",\n")
            append("    \"app_version\": \"$versionName\",\n")
            append("    \"total_messages\": ${messages.size},\n")
            append("    \"format_version\": 1\n")
            append("  },\n")
            append("  \"messages\": [\n")

            for ((index, message) in messages.withIndex()) {
                val role = if (message.role == com.localassistant.app.domain.model.MessageRole.USER) "user" else "assistant"
                val timeStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault()).format(message.timestamp)
                // Escape JSON special characters in content
                val escapedContent = message.content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

                append("    {\n")
                append("      \"id\": ${message.id},\n")
                append("      \"role\": \"$role\",\n")
                append("      \"content\": \"$escapedContent\",\n")
                append("      \"timestamp\": \"$timeStr\"\n")
                append("    }${if (index < messages.size - 1) "," else ""}\n")
            }

            append("  ]\n")
            append("}")
        }

        file.writeText(json)

        Toast.makeText(
            context,
            "Chat exported as JSON: ${file.absolutePath}",
            Toast.LENGTH_LONG
        ).show()
    } catch (e: Exception) {
        Toast.makeText(
            context,
            "Failed to export chat: ${e.message}",
            Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Load pinned messages from SharedPreferences.
 */
fun loadPinnedMessages(context: Context): List<com.localassistant.app.domain.model.ChatMessage> {
    val prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
    val count = prefs.getInt("pinned_count", 0)
    if (count == 0) return emptyList()

    val messages = mutableListOf<com.localassistant.app.domain.model.ChatMessage>()
    for (i in 0 until count) {
        val id = prefs.getLong("pinned_id_$i", -1)
        val role = prefs.getString("pinned_role_$i", "") ?: ""
        val content = prefs.getString("pinned_content_$i", "") ?: ""
        val timestamp = prefs.getLong("pinned_timestamp_$i", 0L)

        if (id > 0 && content.isNotBlank()) {
            messages.add(
                com.localassistant.app.domain.model.ChatMessage(
                    id = id,
                    role = when (role.uppercase()) {
                        "USER" -> com.localassistant.app.domain.model.MessageRole.USER
                        "ASSISTANT" -> com.localassistant.app.domain.model.MessageRole.ASSISTANT
                        else -> com.localassistant.app.domain.model.MessageRole.ASSISTANT
                    },
                    content = content,
                    timestamp = if (timestamp > 0) timestamp else System.currentTimeMillis()
                )
            )
        }
    }
    return messages
}

/**
 * Format a timestamp to a human-readable relative time string.
 */
fun formatRelativeTime(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diffSeconds = (now - timestampMs) / 1000

    return when {
        diffSeconds < 30 -> "Just now"
        diffSeconds < 60 -> "$diffSeconds sec ago"
        diffSeconds < 3600 -> "${diffSeconds / 60} min ago"
        else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(timestampMs)
    }
}
