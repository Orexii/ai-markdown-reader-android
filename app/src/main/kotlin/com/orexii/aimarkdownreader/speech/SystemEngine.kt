package com.orexii.aimarkdownreader.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.orexii.aimarkdownreader.core.AudioSegment
import com.orexii.aimarkdownreader.core.EngineKind
import com.orexii.aimarkdownreader.core.Pronounce
import com.orexii.aimarkdownreader.core.SpeechEngine
import com.orexii.aimarkdownreader.core.SpeechInput
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phone's own text-to-speech voice (Google's or the manufacturer's), used without the download or when
 * chosen. Each passage is rendered to a file; word times come from the engine's range callbacks where it
 * reports them, and are estimated otherwise.
 */
class SystemEngine private constructor(private val context: Context, private val tts: TextToSpeech) : SpeechEngine {
    override val kind = EngineKind.SYSTEM
    override val batchChars = 0
    private val directory = File(context.cacheDir, "system-voice").apply { mkdirs() }

    private class Pending(val latch: CountDownLatch = CountDownLatch(1)) {
        val ranges = mutableListOf<Pair<Int, Int>>() // character start, audio frame
        @Volatile var error: String? = null
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) { pending[utteranceId]?.latch?.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)
            override fun onError(utteranceId: String, errorCode: Int) {
                pending[utteranceId]?.let { it.error = "The system voice failed (error $errorCode)."; it.latch.countDown() }
            }
            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                pending[utteranceId]?.ranges?.add(start to frame)
            }
        })
    }

    /** English voices installed on the phone, best quality first. */
    fun voices(): List<Voice> = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
        .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
        .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.locale.toString() }.thenBy { it.name })

    override fun synthesize(utterances: List<SpeechInput>, voice: String, dictionary: Map<String, String>): List<AudioSegment> = synchronized(this) {
        val chosen = voices().firstOrNull { it.name == voice }
        if (chosen != null) tts.voice = chosen else tts.language = Locale.US
        utterances.map { synthesizeOne(it, dictionary) }
    }

    private fun synthesizeOne(utterance: SpeechInput, dictionary: Map<String, String>): AudioSegment {
        val spoken = utterance.words.mapIndexed { k, word -> Pronounce.pronounceAsText(word.text, dictionary, utterance.words.getOrNull(k - 1)?.text) }
        val starts = IntArray(spoken.size)
        val text = StringBuilder()
        spoken.forEachIndexed { k, value ->
            starts[k] = text.length
            text.append(value).append(utterance.words[k].sep)
        }
        val id = UUID.randomUUID().toString()
        val file = File(directory, "$id.wav")
        val job = Pending()
        pending[id] = job
        try {
            val result = tts.synthesizeToFile(text.toString().trim(), Bundle(), file, id)
            check(result == TextToSpeech.SUCCESS) { "The system voice could not start." }
            check(job.latch.await(60, TimeUnit.SECONDS)) { "The system voice took too long." }
            job.error?.let { throw IllegalStateException(it) }
            val (pcm, rate) = readWav(file.readBytes())
            val silence = (utterance.pause * rate).toInt()
            val all = pcm.copyOf(pcm.size + silence)
            val speech = pcm.size.toDouble() / rate
            val times = DoubleArray(spoken.size * 2)
            val ranges = job.ranges.sortedBy { it.first }
            if (ranges.size >= spoken.size / 2 && ranges.isNotEmpty()) {
                // Each word starts at the frame the engine reported for the first range at or after its text.
                for (k in spoken.indices) {
                    val frame = ranges.firstOrNull { it.first >= starts[k] }?.second ?: pcm.size
                    times[2 * k] = frame.toDouble() / rate
                }
                for (k in spoken.indices) times[2 * k + 1] = if (k + 1 < spoken.size) times[2 * k + 2] else speech
            } else {
                // Estimated: time in proportion to the length of each word, punctuation counting as a pause.
                val weights = spoken.mapIndexed { k, value -> maxOf(1, value.length + utterance.words[k].sep.trim().length * 3) }
                val total = weights.sum().toDouble()
                var before = 0
                weights.forEachIndexed { k, weight ->
                    times[2 * k] = speech * before / total
                    before += weight
                    times[2 * k + 1] = speech * before / total
                }
            }
            return AudioSegment(all, rate, times)
        } finally {
            pending.remove(id)
            file.delete()
        }
    }

    override fun close() {
        runCatching { tts.shutdown() }
    }

    companion object {
        /** Connects to the phone's text-to-speech service; blocks up to 10 seconds. Call off the main thread. */
        fun create(context: Context): SystemEngine {
            val ready = CountDownLatch(1)
            var status = TextToSpeech.ERROR
            val tts = TextToSpeech(context.applicationContext) { status = it; ready.countDown() }
            check(ready.await(10, TimeUnit.SECONDS) && status == TextToSpeech.SUCCESS) {
                "This phone has no text-to-speech voice. Install Speech Services by Google, or download the natural voice."
            }
            return SystemEngine(context.applicationContext, tts)
        }

        /** Reads 16-bit PCM WAV (mono or the first channel of stereo). */
        fun readWav(bytes: ByteArray): Pair<ShortArray, Int> {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
            require(bytes.size >= 12 && tag(0) == "RIFF" && tag(8) == "WAVE") { "Not a WAV file." }
            var channels = 1
            var rate = 0
            var bits = 16
            var at = 12
            while (at + 8 <= bytes.size) {
                val id = tag(at)
                val size = buffer.getInt(at + 4)
                val body = at + 8
                if (id == "fmt ") {
                    channels = buffer.getShort(body + 2).toInt()
                    rate = buffer.getInt(body + 4)
                    bits = buffer.getShort(body + 14).toInt()
                } else if (id == "data") {
                    require(bits == 16 && rate > 0) { "Only 16-bit PCM is supported." }
                    val length = minOf(size, bytes.size - body)
                    val frames = length / (2 * channels)
                    val pcm = ShortArray(frames) { buffer.getShort(body + it * 2 * channels) }
                    return pcm to rate
                }
                at = body + size + (size and 1)
            }
            error("The WAV file has no audio.")
        }
    }
}
