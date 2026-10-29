package com.orexii.aimarkdownreader.core

import com.orexii.aimarkdownreader.core.espeak.EspeakPhonemizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.floor

/** The same deterministic stand-in for the model as tools/goldens-entry.ts fakeRun. */
class FakeInference : KokoroInference {
    class Run(val ids: List<Long>, val styleRow: Int, val speed: Float)
    val runs = mutableListOf<Run>()

    override fun run(ids: LongArray, style: FloatArray, speed: Float): Pair<FloatArray, FloatArray> {
        runs += Run(ids.toList(), Math.round(style[0]), speed)
        val durations = FloatArray(ids.size) { i -> (((ids[i] * 7 + i) % 5) + 1).toFloat() }
        var frames = 0.0
        for (value in durations) frames += value
        val length = floor(frames * 600 * 0.98).toInt()
        val waveform = FloatArray(length) { j -> ((((j.toLong() * 7919) % 2001) - 1000) / 1666.0).toFloat() }
        return waveform to durations
    }
}

/** The fake voice files of the goldens: row r, column c holds r + c / 1000. */
fun fakeStyle(): FloatArray = FloatArray(Kokoro.VOICE_ROWS * Kokoro.STYLE_SIZE) { i -> ((i / Kokoro.STYLE_SIZE) + (i % Kokoro.STYLE_SIZE) / 1000.0).toFloat() }

/** FNV-1a over float32 little-endian bytes, as the goldens record it. */
fun checksum(samples: FloatArray): String {
    var hash = 0x811c9dc5.toInt()
    for (sample in samples) {
        val bits = java.lang.Float.floatToRawIntBits(sample)
        for (shift in intArrayOf(0, 8, 16, 24)) hash = (hash xor ((bits ushr shift) and 0xff)) * 0x01000193
    }
    return "%08x".format(hash)
}

class KokoroParityTest {
    private val golden = Goldens.json("kokoro.json").obj()

    /** eSpeak as recorded while generating the goldens, keyed by language and text. */
    private val recorded: Map<Pair<String, String>, List<String>> = golden.getValue("espeak").array().associate { entry ->
        val obj = entry.obj()
        (obj.getValue("language").string() to obj.getValue("text").string()) to obj.getValue("clauses").array().map { it.string() }
    }

    private fun request(json: kotlinx.serialization.json.JsonObject) = SynthesisRequest(
        utterances = json.getValue("utterances").array().map { u ->
            SynthesisUtterance(u.obj().getValue("words").array().map { w -> SynthesisWord(w.obj().getValue("text").string(), w.obj().getValue("sep").string()) }, u.obj().getValue("pause").number())
        },
        voice = json.getValue("voice").string(),
        dictionary = json["dictionary"]?.array()?.associate { pair -> pair.array()[0].string() to pair.array()[1].string() } ?: emptyMap(),
    )

    private fun check(phonemizer: Phonemizer) {
        val cases = golden.getValue("cases").array()
        assertTrue(cases.size > 300)
        val style = fakeStyle()
        val failures = mutableListOf<String>()
        for ((n, case) in cases.withIndex()) {
            val c = case.obj()
            val inference = FakeInference()
            val kokoro = Kokoro(Goldens::lexicon, phonemizer, inference) { style }
            val request = request(c.getValue("request").obj())
            val segments = kokoro.synthesize(request)
            val runs = c.getValue("runs").array()
            val label = "case $n (${request.utterances.joinToString(" ") { u -> u.words.joinToString(" ") { it.text } }.take(80)})"
            if (runs.size != inference.runs.size) {
                failures += "$label: ${runs.size} model runs expected, ${inference.runs.size} made"
                continue
            }
            runs.forEachIndexed { k, run ->
                val ids = run.obj().getValue("ids").array().map { it.number().toLong() }
                if (ids != inference.runs[k].ids) failures += "$label run $k: ids\n  expected $ids\n  got      ${inference.runs[k].ids}"
                if (run.obj().getValue("styleRow").integer() != inference.runs[k].styleRow) failures += "$label run $k: style row"
            }
            val expected = c.getValue("segments").array()
            if (expected.size != segments.size) {
                failures += "$label: ${expected.size} segments expected, ${segments.size} made"
                continue
            }
            expected.forEachIndexed { k, segment ->
                val s = segment.obj()
                val actual = segments[k]
                if (s.getValue("samples").integer() != actual.samples.size) failures += "$label segment $k: ${s["samples"]} samples expected, ${actual.samples.size}"
                else if (s.getValue("checksum").string() != checksum(actual.samples)) failures += "$label segment $k: audio differs"
                val times = s.getValue("wordTimes").array().map { it.number() }
                if (times != actual.wordTimes.toList()) failures += "$label segment $k: word times\n  expected $times\n  got      ${actual.wordTimes.toList()}"
                if (s.getValue("duration").number() != actual.duration) failures += "$label segment $k: duration"
            }
        }
        assertEquals(failures.take(10).joinToString("\n"), 0, failures.size)
    }

    @Test
    fun synthesisMatchesTheExtensionWithRecordedEspeak() {
        check { text, language -> recorded[language to text] ?: error("eSpeak was not called for '$text' ($language) in the extension") }
    }

    @Test
    fun synthesisMatchesTheExtensionWithNativeEspeak() {
        val library = Goldens.espeakLibrary
        assumeTrue("host eSpeak library not built (tools/espeak-data.sh)", library != null)
        check(EspeakPhonemizer(Goldens.espeakData.absolutePath, library!!.absolutePath))
    }
}

class EspeakParityTest {
    @Test
    fun nativeEspeakMatchesThePhonemizerPackage() {
        val library = Goldens.espeakLibrary
        assumeTrue("host eSpeak library not built (tools/espeak-data.sh)", library != null)
        val espeak = EspeakPhonemizer(Goldens.espeakData.absolutePath, library!!.absolutePath)
        val cases = Goldens.json("espeak.json").array()
        assertTrue(cases.size > 1000)
        val failures = mutableListOf<String>()
        for (case in cases) {
            val (language, text, clauses) = case.array().toList()
            val actual = espeak.phonemize(text.string(), language.string())
            val expected = clauses.array().map { it.string() }
            if (actual != expected) failures += "${language.string()} '${text.string()}': expected $expected, got $actual"
        }
        assertEquals(failures.take(20).joinToString("\n"), 0, failures.size)
    }
}
