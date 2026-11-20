package com.orexii.aimarkdownreader.reader

import android.app.Application
import android.net.Uri
import android.util.AtomicFile
import android.widget.Toast
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orexii.aimarkdownreader.App
import com.orexii.aimarkdownreader.data.RecentDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

class MarkdownDraft(document: OpenDocument, text: String = document.text, editing: Boolean = false) {
    var document by mutableStateOf(document)
        private set
    val source = TextFieldState(text)
    var editing by mutableStateOf(editing)
    var choosingDestination by mutableStateOf(false)
    var saving by mutableStateOf(false)
    val busy get() = saving || choosingDestination
    val dirty get() = source.text.toString() != document.text
    val isNew get() = document.uri == Documents.untitled.uri

    fun saved(result: OpenDocument) { document = result }

    fun suggestedName(): String {
        val name = document.title.substringAfterLast('/').trim().ifBlank { "Untitled" }
        return if (Documents.isMarkdown(name)) name else "$name.md"
    }
}

class EditorModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val recovery = AtomicFile(File(app.cacheDir, "markdown-editor.json"))
    var draft by mutableStateOf<MarkdownDraft?>(null)
        private set

    fun open(document: OpenDocument) {
        clear()
        draft = MarkdownDraft(document, editing = document == Documents.untitled)
        if (document.uri != Documents.untitled.uri && !document.uri.startsWith("shared:")) {
            viewModelScope.launch { remember(document) }
        }
    }

    private suspend fun remember(doc: OpenDocument) {
        app.library.opened(RecentDocument(doc.uri, doc.title, doc.folder, System.currentTimeMillis(), preview = true))
    }

    fun save(uri: Uri) {
        val active = draft ?: return
        if (active.saving) return
        active.saving = true
        viewModelScope.launch {
            try {
                val result = Documents.write(app, uri, active.document, active.source.text.toString())
                active.saved(result)
                // A notification or headset must not resume an older version after an in-place edit.
                app.reader.document.value?.let {
                    if (it.uri == result.uri && it.text != result.text) app.reader.close()
                }
                remember(result)
                toast("Saved ${result.title}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Could not save: ${e.localizedMessage ?: "try Save as to choose another location."}")
            } finally {
                active.saving = false
            }
        }
    }

    // The picker can outlive our process. Keep document-sized strings out of the activity's Binder bundle.
    @Serializable
    private data class Snapshot(val document: OpenDocument, val changes: String?, val editing: Boolean, val choosing: Boolean)

    fun checkpoint(): Boolean {
        val active = draft ?: return false
        return try {
            val snapshot = Snapshot(active.document, if (active.dirty) active.source.text.toString() else null, active.editing, active.choosingDestination)
            val stream = recovery.startWrite()
            try {
                stream.write(Json.encodeToString(snapshot).toByteArray())
                recovery.finishWrite(stream)
            } catch (e: Exception) {
                recovery.failWrite(stream)
                throw e
            }
            true
        } catch (e: Exception) {
            toast("Could not keep a recovery copy of the draft: ${e.localizedMessage}")
            false
        }
    }

    fun restore(): Boolean {
        if (draft != null) return true
        return try {
            val s = Json.decodeFromString<Snapshot>(recovery.readFully().decodeToString())
            draft = MarkdownDraft(s.document, s.changes ?: s.document.text, s.editing).also { it.choosingDestination = s.choosing }
            true
        } catch (e: Exception) {
            toast("Could not restore the editor: ${e.localizedMessage}")
            false
        }
    }

    fun clear() {
        draft = null
        recovery.delete()
    }

    private fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_LONG).show()
}
