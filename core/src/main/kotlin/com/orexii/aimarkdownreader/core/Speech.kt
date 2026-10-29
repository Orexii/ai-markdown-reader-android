package com.orexii.aimarkdownreader.core

/** One passage to speak: its words and the silence after it, in seconds at 1×. */
data class SpeechInput(val words: List<SynthesisWord>, val pause: Double)

/** Synthesized audio for one passage: mono 16-bit PCM and the start/end second of each word (-1 when unknown). */
class AudioSegment(val pcm: ShortArray, val sampleRate: Int, val wordTimes: DoubleArray) {
    val duration: Double get() = pcm.size.toDouble() / sampleRate
    val bytes: Long get() = pcm.size * 2L

    /** The word being spoken `offset` seconds into the passage, or -1 (player.js updateWord). */
    fun wordAt(offset: Double): Int {
        var found = -1
        var j = 0
        while (j * 2 < wordTimes.size) {
            val start = wordTimes[2 * j]
            if (start >= 0) {
                if (start <= offset + 0.03) found = j else break
            }
            j++
        }
        return found
    }
}

enum class EngineKind { NATURAL, SYSTEM }

interface SpeechEngine {
    val kind: EngineKind

    /** Spoken characters to combine into one request after the first, latency-sensitive request. */
    val batchChars: Int

    /** Synthesizes passages, blocking the calling thread. Returns one segment per input. */
    fun synthesize(utterances: List<SpeechInput>, voice: String, dictionary: Map<String, String>): List<AudioSegment>

    fun close()
}

/** Converts float samples to 16-bit PCM exactly as the extension's encodeWav does. */
fun toPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { i ->
    val sample = samples[i].toDouble().coerceIn(-1.0, 1.0)
    (if (sample < 0) sample * 0x8000 else sample * 0x7fff).toInt().toShort()
}

/**
 * The natural voice: Kokoro through [Kokoro] (lexicon, eSpeak and ONNX Runtime). Closing waits for a running
 * synthesis to finish, because freeing the model while it runs would crash the process.
 */
class NaturalEngine(private val kokoro: Kokoro, private val onClose: () -> Unit = {}) : SpeechEngine {
    override val kind = EngineKind.NATURAL
    override val batchChars = 170
    private val lock = Any()
    private var closed = false

    override fun synthesize(utterances: List<SpeechInput>, voice: String, dictionary: Map<String, String>): List<AudioSegment> = synchronized(lock) {
        check(!closed) { "The natural voice was closed." }
        val request = SynthesisRequest(utterances.map { SynthesisUtterance(it.words, it.pause) }, voice, 1.0, dictionary)
        kokoro.synthesize(request).map { AudioSegment(toPcm16(it.samples), Kokoro.SAMPLE_RATE, it.wordTimes) }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            onClose()
        }
    }
}
