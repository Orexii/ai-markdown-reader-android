package com.orexii.aimarkdownreader

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.orexii.aimarkdownreader.core.Accent
import com.orexii.aimarkdownreader.core.Kokoro
import com.orexii.aimarkdownreader.core.KokoroInference
import com.orexii.aimarkdownreader.core.Lexicon
import com.orexii.aimarkdownreader.core.LexiconData
import com.orexii.aimarkdownreader.core.ModelCatalog
import com.orexii.aimarkdownreader.core.ReaderDocument
import com.orexii.aimarkdownreader.core.espeak.EspeakPhonemizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.floor

/** Fixtures for the device tests: the same goldens and model files as the JVM tests. */
object DeviceFixtures {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val app: Context get() = instrumentation.targetContext
    private val testAssets get() = instrumentation.context.assets

    fun json(name: String): JsonElement = Json.parseToJsonElement(testAssets.open("goldens/$name").use { it.readBytes().toString(Charsets.UTF_8) })

    val documents: Map<String, ReaderDocument> by lazy {
        json("documents.json").jsonObject.mapValues { ReaderDocument.parse(it.value.toString()) }
    }

    private val lexicons = HashMap<Accent, Lexicon>()

    @Synchronized
    fun lexicon(accent: Accent): Lexicon = lexicons.getOrPut(accent) {
        val text = app.assets.open("lexicon/${accent.name.lowercase()}.json").use { it.readBytes().toString(Charsets.UTF_8) }
        Lexicon(LexiconData.parse(text), accent == Accent.GB)
    }

    /** The app's eSpeak data, copied out of the APK as the app does. */
    val espeak: EspeakPhonemizer by lazy {
        val root = File(app.cacheDir, "test-espeak")
        root.deleteRecursively()
        fun copy(path: String, target: File) {
            val children = app.assets.list(path).orEmpty()
            if (children.isEmpty()) {
                target.parentFile?.mkdirs()
                app.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            } else children.forEach { copy("$path/$it", File(target, it)) }
        }
        copy("espeak-ng-data", File(root, "espeak-ng-data"))
        EspeakPhonemizer(root.absolutePath)
    }

    /**
     * The pinned model for the real-model tests: CI pushes it to /data/local/tmp/amr-model (same layout as
     * tools/fetch-model.mjs). It is copied where the app keeps the downloaded voice. False when absent.
     */
    fun installModel(): Boolean {
        val source = File("/data/local/tmp/amr-model")
        val model = File(source, "onnx/${ModelCatalog.Quality.COMPACT.fileName}")
        val voice = File(source, "voices/af_heart.bin")
        if (!model.canRead() || !voice.canRead()) return false
        val root = File(app.filesDir, ModelCatalog.FOLDER)
        val targetModel = File(root, ModelCatalog.Quality.COMPACT.fileName)
        val targetVoice = File(root, "voices/af_heart.bin")
        if (targetModel.length() != model.length()) {
            root.mkdirs()
            model.copyTo(targetModel, overwrite = true)
        }
        if (targetVoice.length() != voice.length()) voice.copyTo(targetVoice, overwrite = true)
        return true
    }
}

fun JsonElement.text(): String = jsonPrimitive.content
fun JsonElement.textOrNull(): String? = if (this is JsonNull) null else jsonPrimitive.content

/** The deterministic stand-in for the model used by the goldens (tools/goldens-entry.ts fakeRun). */
class FakeInference : KokoroInference {
    class Run(val ids: List<Long>, val styleRow: Int)
    val runs = mutableListOf<Run>()

    override fun run(ids: LongArray, style: FloatArray, speed: Float): Pair<FloatArray, FloatArray> {
        runs += Run(ids.toList(), Math.round(style[0]))
        val durations = FloatArray(ids.size) { i -> (((ids[i] * 7 + i) % 5) + 1).toFloat() }
        var frames = 0.0
        for (value in durations) frames += value
        val length = floor(frames * 600 * 0.98).toInt()
        val waveform = FloatArray(length) { j -> ((((j.toLong() * 7919) % 2001) - 1000) / 1666.0).toFloat() }
        return waveform to durations
    }

    companion object {
        fun style() = FloatArray(Kokoro.VOICE_ROWS * Kokoro.STYLE_SIZE) { i -> ((i / Kokoro.STYLE_SIZE) + (i % Kokoro.STYLE_SIZE) / 1000.0).toFloat() }

        fun checksum(samples: FloatArray): String {
            var hash = 0x811c9dc5.toInt()
            for (sample in samples) {
                val bits = java.lang.Float.floatToRawIntBits(sample)
                for (shift in intArrayOf(0, 8, 16, 24)) hash = (hash xor ((bits ushr shift) and 0xff)) * 0x01000193
            }
            return "%08x".format(hash)
        }
    }
}
