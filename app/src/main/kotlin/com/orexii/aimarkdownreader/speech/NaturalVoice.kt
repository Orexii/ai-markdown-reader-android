package com.orexii.aimarkdownreader.speech

import android.content.Context
import com.orexii.aimarkdownreader.core.Accent
import com.orexii.aimarkdownreader.core.Kokoro
import com.orexii.aimarkdownreader.core.Lexicon
import com.orexii.aimarkdownreader.core.LexiconData
import com.orexii.aimarkdownreader.core.ModelCatalog
import com.orexii.aimarkdownreader.core.NaturalEngine
import com.orexii.aimarkdownreader.core.OrtInference
import com.orexii.aimarkdownreader.core.espeak.EspeakPhonemizer
import com.orexii.aimarkdownreader.core.parseVoice
import java.io.File

/** Builds the natural voice: eSpeak data from the app's assets, misaki's lexicons, ONNX Runtime and the voices. */
class NaturalVoice(private val context: Context, private val models: ModelStore) {
    private val lexicons = HashMap<Accent, Lexicon>()
    private val styles = HashMap<String, FloatArray>()
    private var phonemizer: EspeakPhonemizer? = null

    @Synchronized
    private fun lexicon(accent: Accent): Lexicon = lexicons.getOrPut(accent) {
        val text = context.assets.open("lexicon/${accent.name.lowercase()}.json").use { it.readBytes().toString(Charsets.UTF_8) }
        Lexicon(LexiconData.parse(text), accent == Accent.GB)
    }

    @Synchronized
    private fun style(voice: String): FloatArray = styles.getOrPut(voice) {
        val file = models.voiceFile(voice)
        check(file.length() == ModelCatalog.VOICE_SIZE) { "The voice $voice is not downloaded." }
        parseVoice(file.readBytes())
    }

    /** Copies eSpeak NG's English data out of the APK once per app version (eSpeak reads files by path). */
    @Synchronized
    private fun espeak(): EspeakPhonemizer = phonemizer ?: run {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val root = File(context.noBackupFilesDir, "espeak")
        val marker = File(root, "version")
        if (marker.takeIf { it.exists() }?.readText() != version.toString()) {
            root.deleteRecursively()
            copyAssets("espeak-ng-data", File(root, "espeak-ng-data"))
            marker.writeText(version.toString())
        }
        EspeakPhonemizer(root.absolutePath).also { phonemizer = it }
    }

    private fun copyAssets(path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            for (child in children) copyAssets("$path/$child", File(target, child))
        }
    }

    /** Loads the model (a second or two); call off the main thread. */
    fun create(quality: ModelCatalog.Quality, threads: Int): NaturalEngine {
        val model = models.modelFile(quality)
        check(models.hasModel(quality)) { "The natural voice is not downloaded." }
        val phonemes = espeak()
        val inference = OrtInference(model.absolutePath, if (threads > 0) threads else OrtInference.automaticThreads()) { options ->
            // Idle threads sleep between passages instead of spinning, which saves battery.
            options.addConfigEntry("session.intra_op.allow_spinning", "0")
        }
        // Parse the lexicon of the default accent now, so the first sentence is not delayed.
        lexicon(Accent.US)
        val kokoro = Kokoro(::lexicon, phonemes, inference, ::style)
        return NaturalEngine(kokoro) { inference.close() }
    }

    /** Forgets cached voice data, for example after the files were removed. */
    @Synchronized
    fun clear() = styles.clear()
}
