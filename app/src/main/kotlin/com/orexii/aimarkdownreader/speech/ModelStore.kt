package com.orexii.aimarkdownreader.speech

import android.content.Context
import com.orexii.aimarkdownreader.core.ModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** The natural voice files in app storage, downloaded once from a pinned revision and verified. */
class ModelStore(context: Context, private val scope: CoroutineScope) {
    val root = File(context.filesDir, ModelCatalog.FOLDER)
    val voicesDir = File(root, "voices")

    sealed interface Download {
        data object Idle : Download
        data class Running(val received: Long, val total: Long) : Download
        data class Failed(val message: String) : Download
    }

    private val state = MutableStateFlow<Download>(Download.Idle)
    val download: StateFlow<Download> = state.asStateFlow()
    private val installedFlow = MutableStateFlow(0)
    /** Changes whenever files are installed or removed. */
    val changes: StateFlow<Int> = installedFlow.asStateFlow()
    private var job: Job? = null

    fun modelFile(quality: ModelCatalog.Quality) = File(root, quality.fileName)
    fun voiceFile(id: String) = File(voicesDir, "$id.bin")
    fun hasModel(quality: ModelCatalog.Quality) = modelFile(quality).length() == quality.size
    fun hasVoice(id: String) = voiceFile(id).length() == ModelCatalog.VOICE_SIZE
    fun installedQualities() = ModelCatalog.Quality.entries.filter(::hasModel)

    /** Downloads the model and a voice; concurrent callers share one download. */
    fun install(quality: ModelCatalog.Quality, voice: String) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val info = ModelCatalog.voice(voice) ?: ModelCatalog.voice(ModelCatalog.DEFAULT_VOICE)!!
                val needVoice = !hasVoice(info.id)
                val needModel = !hasModel(quality)
                val total = (if (needVoice) ModelCatalog.VOICE_SIZE else 0) + (if (needModel) quality.size else 0)
                var done = 0L
                state.value = Download.Running(0, total)
                if (needVoice) {
                    downloadVerified(ModelCatalog.url("voices/${info.id}.bin"), voiceFile(info.id), ModelCatalog.VOICE_SIZE, info.sha256) {
                        state.value = Download.Running(done + it, total)
                    }
                    done += ModelCatalog.VOICE_SIZE
                }
                if (needModel) {
                    downloadVerified(ModelCatalog.url(quality.file), modelFile(quality), quality.size, quality.sha256) {
                        state.value = Download.Running(done + it, total)
                    }
                }
                state.value = Download.Idle
                installedFlow.value++
            } catch (cancelled: CancellationException) {
                state.value = Download.Idle
                throw cancelled
            } catch (failure: Exception) {
                state.value = Download.Failed(failure.message ?: "The download failed.")
            }
        }
    }

    /** Downloads one more voice (0.5 MB), for example after choosing it in the settings. */
    suspend fun installVoice(id: String) = withContext(Dispatchers.IO) {
        val info = ModelCatalog.voice(id) ?: error("Unknown voice: $id")
        if (!hasVoice(id)) {
            downloadVerified(ModelCatalog.url("voices/$id.bin"), voiceFile(id), ModelCatalog.VOICE_SIZE, info.sha256) {}
            installedFlow.value++
        }
    }

    fun cancel() {
        job?.cancel()
        state.value = Download.Idle
    }

    fun remove() {
        cancel()
        root.deleteRecursively()
        installedFlow.value++
    }

    companion object {
        /**
         * Downloads a pinned file, resuming a partial download, and verifies its size and SHA-256 before moving it
         * into place. Mirrors downloadVerified in the extension's src/model.ts.
         */
        suspend fun downloadVerified(url: String, target: File, size: Long, sha256: String, progress: (Long) -> Unit) {
            target.parentFile?.mkdirs()
            val partial = File(target.path + ".partial")
            if (partial.length() > size) partial.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            if (partial.exists()) {
                partial.inputStream().use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        received += n
                    }
                }
            }
            if (received < size) {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "AI-Markdown-Reader-Android")
                    if (received > 0) setRequestProperty("Range", "bytes=$received-")
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) throw IllegalStateException("Download failed: HTTP $code ${connection.responseMessage ?: ""}".trim())
                    val append = received > 0 && code == HttpURLConnection.HTTP_PARTIAL
                    if (!append) {
                        digest.reset()
                        received = 0
                    }
                    connection.inputStream.use { input ->
                        FileOutputStream(partial, append).use { output ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                received += n
                                if (received > size) throw IllegalStateException("Download is larger than expected.")
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                                progress(received)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (received != size || hash != sha256) {
                partial.delete()
                throw IllegalStateException("The downloaded file failed verification (expected $size bytes, received $received).")
            }
            if (!partial.renameTo(target)) throw IllegalStateException("Could not save ${target.name}.")
        }
    }
}
