package com.orexii.aimarkdownreader.core

import com.orexii.aimarkdownreader.core.espeak.EspeakPhonemizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

class ModelCatalogTest {
    @Test
    fun pinnedFilesMatchTheExtension() {
        val model = Goldens.json("model.json").obj()
        assertEquals(model.getValue("repository").string(), ModelCatalog.REPOSITORY)
        assertEquals(model.getValue("revision").string(), ModelCatalog.REVISION)
        assertEquals(model.getValue("folder").string(), ModelCatalog.FOLDER)
        assertEquals(model.getValue("defaultVoice").string(), ModelCatalog.DEFAULT_VOICE)
        for (quality in ModelCatalog.Quality.entries) {
            val pinned = model.getValue("models").obj().getValue(quality.name.lowercase()).obj()
            assertEquals(pinned.getValue("file").string(), quality.file)
            assertEquals(pinned.getValue("size").number().toLong(), quality.size)
            assertEquals(pinned.getValue("sha256").string(), quality.sha256)
        }
        val voices = model.getValue("voices").array().map { it.obj() }
        assertEquals(voices.map { it.getValue("id").string() }, ModelCatalog.VOICES.map { it.id })
        assertEquals(voices.map { it.getValue("sha256").string() }, ModelCatalog.VOICES.map { it.sha256 })
        assertEquals(voices.map { it.getValue("grade").string() }, ModelCatalog.VOICES.map { it.grade })
        assertEquals(voices.map { it.getValue("name").string() }, ModelCatalog.VOICES.map { it.name })
        assertEquals("Heart · US female · A", ModelCatalog.voice("af_heart")!!.label)
    }
}

/** The real model through the whole pipeline, as the extension's real-model test does (CMR_MODEL_DIR). */
class RealModelTest {
    private val directory = File(System.getProperty("amr.modelDir") ?: ".model")
    private val modelFile = listOf("model_quantized.onnx", "model.onnx").map { File(directory, "onnx/$it") }.firstOrNull { it.isFile }

    private fun engine(): NaturalEngine {
        assumeTrue("Run node tools/fetch-model.mjs .model compact to test the real model.", modelFile != null && Goldens.espeakLibrary != null)
        val style = parseVoice(File(directory, "voices/af_heart.bin").readBytes())
        val inference = OrtInference(modelFile!!.absolutePath, 4)
        val kokoro = Kokoro(Goldens::lexicon, EspeakPhonemizer(Goldens.espeakData.absolutePath, Goldens.espeakLibrary!!.absolutePath), inference) { style }
        return NaturalEngine(kokoro) { inference.close() }
    }

    private fun words(text: String): List<SynthesisWord> {
        val parts = text.split(" ")
        return parts.mapIndexed { k, part ->
            val match = Regex("""^(.*?)([.,:;!?]*)$""").find(part)!!
            SynthesisWord(match.groupValues[1], match.groupValues[2] + if (k < parts.size - 1) " " else "")
        }
    }

    @Test
    fun speaksWithWordTimes() {
        val engine = engine()
        val segments = engine.synthesize(
            listOf(SpeechInput(words("The ANF team reads tables, row by row."), 0.3), SpeechInput(words("Mail. State: ready."), 0.5)),
            "af_heart", emptyMap(),
        )
        assertEquals(2, segments.size)
        for (segment in segments) {
            assertTrue(segment.duration > 0.8)
            var energy = 0.0
            for (sample in segment.pcm) energy += (sample / 32768.0) * (sample / 32768.0)
            assertTrue("audible speech", sqrt(energy / segment.pcm.size) > 0.01)
            val times = segment.wordTimes
            for (i in times.indices step 2) {
                assertTrue(times[i] >= 0 && times[i + 1] >= times[i] && times[i + 1] <= segment.duration + 1e-6)
                if (i > 0) assertTrue(times[i] >= times[i - 2])
            }
        }
        engine.close()
    }

    @Test
    fun speaksTheWagyuGuideFasterThanRealTime() {
        val engine = engine()
        val utterances = Goldens.documents.getValue("wagyu-rows-announce").utterances.take(12)
        val started = System.nanoTime()
        var audio = 0.0
        for (batch in utterances.chunked(3)) {
            val segments = engine.synthesize(batch.map { u -> SpeechInput(u.words.map { SynthesisWord(it.text, it.sep) }, u.pauseAfter) }, "af_heart", emptyMap())
            assertEquals(batch.size, segments.size)
            audio += segments.sumOf { it.duration }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        println("Real model: ${"%.1f".format(audio)} s of speech in ${"%.1f".format(seconds)} s (${"%.1f".format(audio / seconds)}× real time)")
        assertTrue(audio > 20)
        engine.close()
    }
}
