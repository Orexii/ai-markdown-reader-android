package com.orexii.aimarkdownreader.core

import kotlin.math.max
import kotlin.math.min

/** eSpeak NG text-to-phonemes: one string per clause, as the phonemizer package returns. */
fun interface Phonemizer {
    fun phonemize(text: String, language: String): List<String>
}

data class SynthesisWord(val text: String, val sep: String)
data class SynthesisUtterance(val words: List<SynthesisWord>, val pause: Double)

data class SynthesisRequest(
    val utterances: List<SynthesisUtterance>,
    val voice: String,
    val speed: Double = 1.0,
    val dictionary: Map<String, String> = emptyMap(),
)

/** Token ids for one request, and for each utterance the [start, end) token span of each word (or null). */
class Tokens(val ids: LongArray, val spans: List<List<IntArray?>>)

class SynthesizedSegment(
    val samples: FloatArray,
    /** Start and end seconds for each word, relative to the segment; -1 when a word produced no sound. */
    val wordTimes: DoubleArray,
    val duration: Double,
)

class TooLongException(message: String) : Exception(message)

/** Runs the Kokoro model: token ids, a 256-value style vector and a speed in; waveform and token durations out. */
interface KokoroInference {
    fun run(ids: LongArray, style: FloatArray, speed: Float): Pair<FloatArray, FloatArray>
}

/**
 * Port of KokoroModel in the extension's src/engine/kokoro.ts: pronunciation, misaki lexicon lookups with
 * eSpeak NG for unknown words, tokens with word spans, and splitting the waveform back into passages.
 */
class Kokoro(
    private val lexicon: (Accent) -> Lexicon?,
    private val phonemizer: Phonemizer,
    private val inference: KokoroInference,
    private val style: (String) -> FloatArray,
) {
    private val cache = HashMap<String, String>()

    private class Piece(val word: Int, val text: String? = null, var phonemes: String? = null, val punct: String? = null)

    private fun espeak(text: String, accent: Accent): String {
        val key = "${accent.name.lowercase()}:$text"
        cache[key]?.let { return it }
        val value = Misaki.postprocess(phonemizer.phonemize(text, if (accent == Accent.GB) "en" else "en-us").joinToString(" "), accent)
        if (cache.size > 20000) cache.clear()
        cache[key] = value
        return value
    }

    /** Token ids and word spans for a request; throws TooLongException beyond the model's 512 tokens. */
    fun tokenize(request: SynthesisRequest): Tokens {
        val accent = if (request.voice.startsWith("b")) Accent.GB else Accent.US
        val lex = lexicon(accent)
        val ids = ArrayList<Long>(MAX_TOKENS + 8)
        ids.add(0)
        val spans = ArrayList<List<IntArray?>>()
        fun push(text: String) {
            for (ch in text) Vocab.IDS[ch]?.let { ids.add(it.toLong()) }
        }
        fun space() {
            if (ids.size > 1 && ids.last() != SPACE) ids.add(SPACE)
        }

        for ((u, utterance) in request.utterances.withIndex()) {
            val pieces = ArrayList<Piece>()
            for ((k, word) in utterance.words.withIndex()) {
                for (segment in Pronounce.pronounce(word.text, accent, request.dictionary, false, utterance.words.getOrNull(k - 1)?.text)) {
                    when (segment) {
                        is Segment.Phonemes -> pieces.add(Piece(k, phonemes = Misaki.toMisaki(segment.phonemes, accent)))
                        is Segment.Text -> for (token in segment.text.split(TOKEN_SPLIT)) {
                            val text = token.replace(TOKEN_TRIM, "")
                            if (text.isNotEmpty()) pieces.add(Piece(k, text = text))
                        }
                    }
                }
                val marks = PUNCTUATION.findAll(word.sep).joinToString("") { it.value }
                val last = k == utterance.words.size - 1
                // Keep a phrase boundary between utterances even when a long sentence was cut without punctuation.
                if (marks.isNotEmpty() || last) {
                    pieces.add(Piece(-1, punct = marks.ifEmpty { if (u < request.utterances.size - 1) "," else "." }))
                }
            }
            // Resolve right to left, as misaki does, so "the", "a", "to" and "in" can depend on the next sound.
            var nextVowel: Boolean? = null
            var nextIsTo = false
            for (i in pieces.indices.reversed()) {
                val piece = pieces[i]
                if (piece.punct != null) {
                    if (BOUNDARY.containsMatchIn(piece.punct)) nextVowel = null
                    nextIsTo = false
                    continue
                }
                if (piece.text != null) {
                    piece.phonemes = lex?.word(piece.text, LexiconContext(nextVowel, nextIsTo)) ?: espeak(piece.text, accent)
                }
                when (Lexicon.onset(piece.phonemes)) {
                    Onset.VOWEL -> nextVowel = true
                    Onset.CONSONANT -> nextVowel = false
                    Onset.BOUNDARY -> nextVowel = null
                    Onset.UNKNOWN -> {}
                }
                nextIsTo = piece.text != null && TO.matches(piece.text)
            }
            val wordSpans = arrayOfNulls<IntArray>(utterance.words.size)
            for (piece in pieces) {
                if (piece.punct != null) {
                    push(piece.punct)
                    continue
                }
                val phonemes = piece.phonemes
                if (phonemes.isNullOrEmpty()) continue
                space()
                val start = ids.size
                push(phonemes)
                if (ids.size == start) continue
                val existing = wordSpans[piece.word]
                wordSpans[piece.word] = if (existing != null) intArrayOf(existing[0], ids.size) else intArrayOf(start, ids.size)
            }
            spans.add(wordSpans.toList())
        }
        while (ids.last() == SPACE) ids.removeAt(ids.size - 1)
        ids.add(0)
        if (ids.size > MAX_TOKENS) throw TooLongException("Request has ${ids.size} phoneme tokens; the limit is $MAX_TOKENS.")
        return Tokens(ids.toLongArray(), spans)
    }

    /** Synthesizes consecutive utterances in one pass for natural prosody, then splits them at word boundaries. */
    fun synthesize(request: SynthesisRequest): List<SynthesizedSegment> {
        try {
            return synthesizeOnce(request)
        } catch (error: TooLongException) {
            val utterances = request.utterances
            if (utterances.size > 1) {
                val middle = (utterances.size + 1) / 2
                return synthesize(request.copy(utterances = utterances.subList(0, middle))) +
                    synthesize(request.copy(utterances = utterances.subList(middle, utterances.size)))
            }
            val words = utterances[0].words
            if (words.size < 2) throw error
            val middle = (words.size + 1) / 2
            val first = synthesize(request.copy(utterances = listOf(SynthesisUtterance(words.subList(0, middle), 0.12)))).first()
            val second = synthesize(request.copy(utterances = listOf(SynthesisUtterance(words.subList(middle, words.size), utterances[0].pause)))).first()
            val samples = FloatArray(first.samples.size + second.samples.size)
            first.samples.copyInto(samples)
            second.samples.copyInto(samples, first.samples.size)
            val offset = first.duration
            val times = first.wordTimes + second.wordTimes.map { if (it < 0) it else it + offset }.toDoubleArray()
            return listOf(SynthesizedSegment(samples, times, samples.size.toDouble() / SAMPLE_RATE))
        }
    }

    private fun synthesizeOnce(request: SynthesisRequest): List<SynthesizedSegment> {
        val tokens = tokenize(request)
        val voice = style(request.voice)
        val count = min(max(tokens.ids.size - 2, 0), 509)
        val (waveform, durations) = inference.run(tokens.ids, voice.copyOfRange(count * STYLE_SIZE, (count + 1) * STYLE_SIZE), request.speed.toFloat())
        return split(waveform, durations, tokens.spans, request.utterances.map { it.pause })
    }

    companion object {
        const val SAMPLE_RATE = 24000
        const val STYLE_SIZE = 256
        const val VOICE_ROWS = 510
        private const val SAMPLES_PER_FRAME = 600
        private const val MAX_TOKENS = 512
        private const val LEAD = 0.03
        private const val TAIL = 0.08
        private val SPACE = Vocab.SPACE.toLong()
        private const val JS_SPACES = "\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
        private val TOKEN_SPLIT = Regex("[$JS_SPACES/]+|(?<=\\p{L})-(?=\\p{L})")
        private val TOKEN_TRIM = Regex("""^[^\p{L}\p{N}]+|[^\p{L}\p{N}']+\z""")
        private val PUNCTUATION = Regex("""[;:,.!?—…"“”()]""")
        private val BOUNDARY = Regex("[;:,.!?—…]")
        private val TO = Regex("""^to\z""", RegexOption.IGNORE_CASE)

        /** Splits one waveform into per-utterance segments using predicted token durations. */
        fun split(waveform: FloatArray, durations: FloatArray, spans: List<List<IntArray?>>, pauses: List<Double>): List<SynthesizedSegment> {
            val cumulative = DoubleArray(durations.size + 1)
            for (i in durations.indices) cumulative[i + 1] = cumulative[i] + max(0.0, durations[i].toDouble())
            val total = cumulative[durations.size]
            val scale = if (total > 0) waveform.size / (total * SAMPLES_PER_FRAME) else 1.0
            fun time(token: Int): Double = (cumulative[min(token, durations.size)] * SAMPLES_PER_FRAME * scale) / SAMPLE_RATE
            val length = waveform.size.toDouble() / SAMPLE_RATE
            val bounds: List<DoubleArray?> = spans.map { words ->
                val used = words.filterNotNull()
                if (used.isEmpty()) null else doubleArrayOf(time(used.minOf { it[0] }), time(used.maxOf { it[1] }))
            }
            val segments = ArrayList<SynthesizedSegment>()
            var previousEnd = 0.0
            for ((u, bound) in bounds.withIndex()) {
                val pause = max(0.0, pauses.getOrNull(u) ?: 0.0)
                if (bound == null) {
                    segments.add(SynthesizedSegment(FloatArray(jsRound(pause * SAMPLE_RATE).toInt()), DoubleArray(spans[u].size * 2) { -1.0 }, pause))
                    continue
                }
                val next = bounds.subList(u + 1, bounds.size).firstOrNull { it != null }
                val middle = if (next != null) (bound[1] + next[0]) / 2 else length
                val start = max(max(previousEnd, bound[0] - LEAD), 0.0)
                val end = min(min(bound[1] + TAIL, middle), length)
                previousEnd = end
                val first = jsRound(start * SAMPLE_RATE).toInt()
                val last = max(first, jsRound(end * SAMPLE_RATE).toInt())
                val speechStart = min(first, waveform.size)
                val speechLength = min(last, waveform.size) - speechStart
                val silence = max(0.0, pause - (end - bound[1]) - LEAD)
                val samples = FloatArray(speechLength + jsRound(silence * SAMPLE_RATE).toInt())
                waveform.copyInto(samples, 0, speechStart, speechStart + speechLength)
                // Short fades avoid clicks where the waveform is cut.
                val fadeIn = min(speechLength, jsRound(0.004 * SAMPLE_RATE).toInt())
                val fadeOut = min(speechLength, jsRound(0.012 * SAMPLE_RATE).toInt())
                if (first > 0) for (i in 0 until fadeIn) samples[i] = (samples[i] * (i.toDouble() / fadeIn)).toFloat()
                for (i in 0 until fadeOut) samples[speechLength - 1 - i] = (samples[speechLength - 1 - i] * (i.toDouble() / fadeOut)).toFloat()
                val times = DoubleArray(spans[u].size * 2)
                spans[u].forEachIndexed { k, span ->
                    times[2 * k] = if (span == null) -1.0 else max(0.0, time(span[0]) - start)
                    times[2 * k + 1] = if (span == null) -1.0 else max(0.0, time(span[1]) - start)
                }
                segments.add(SynthesizedSegment(samples, times, samples.size.toDouble() / SAMPLE_RATE))
            }
            return segments
        }

        /** JavaScript's Math.round for the non-negative values used here. */
        private fun jsRound(value: Double): Long = kotlin.math.floor(value + 0.5).toLong()
    }
}

/** A voice file: 510 rows of 256 little-endian float32 style values. */
fun parseVoice(bytes: ByteArray): FloatArray {
    require(bytes.size == Kokoro.VOICE_ROWS * Kokoro.STYLE_SIZE * 4) { "The voice file is damaged. Download the natural voice again." }
    val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(buffer.remaining()).also { buffer.get(it) }
}
