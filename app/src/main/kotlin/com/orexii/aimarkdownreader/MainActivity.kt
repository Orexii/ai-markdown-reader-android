package com.orexii.aimarkdownreader

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.orexii.aimarkdownreader.playback.PlaybackService
import com.orexii.aimarkdownreader.reader.Documents
import com.orexii.aimarkdownreader.reader.EditorModel
import com.orexii.aimarkdownreader.reader.OpenDocument
import com.orexii.aimarkdownreader.reader.PageState
import com.orexii.aimarkdownreader.ui.EditorScreen
import com.orexii.aimarkdownreader.ui.FolderScreen
import com.orexii.aimarkdownreader.ui.HomeScreen
import com.orexii.aimarkdownreader.ui.PageListener
import com.orexii.aimarkdownreader.ui.ReaderScreen
import com.orexii.aimarkdownreader.ui.ReaderTheme
import com.orexii.aimarkdownreader.ui.ReaderWebView
import com.orexii.aimarkdownreader.ui.isDark
import kotlinx.coroutines.launch

/** AndroidX's default scrims for three-button navigation bars. */
private const val NAVIGATION_SCRIM_LIGHT = 0xE6FFFFFF.toInt()
private const val NAVIGATION_SCRIM_DARK = 0x801B1B1B.toInt()

/** The app's one activity: home, folders and the reader. Opens files shared with it or opened with it. */
class MainActivity : ComponentActivity() {
    private val app get() = application as App
    private val editor: EditorModel by viewModels()
    private var webView by mutableStateOf<ReaderWebView?>(null)
    private var controller: ListenableFuture<MediaController>? = null

    private sealed interface Screen {
        data object Home : Screen
        data class Folder(val tree: Uri, val name: String, val files: List<Pair<String, Uri>>?) : Screen
        data class Reader(val back: Screen) : Screen
        data class Editor(val back: Screen) : Screen
    }

    private var screen by mutableStateOf<Screen>(Screen.Home)
    private var afterDiscard by mutableStateOf<(() -> Unit)?>(null)

    private val fileTypes = arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream")
    private fun filePicker(preview: Boolean) = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Documents.keepAccess(this, uri)
            open(uri, null, Screen.Home, preview)
        }
    }
    private val openFile = filePicker(preview = false)
    private val previewFile = filePicker(preview = true)

    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            Documents.keepAccess(this, tree)
            showFolder(tree)
        }
    }

    private val createFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val draft = editor.draft
        if (draft != null && draft.choosingDestination) {
            draft.choosingDestination = false
            if (uri != null) {
                Documents.keepAccess(this, uri)
                editor.save(uri)
            }
        }
    }

    private val listener = object : PageListener {
        override fun onReady() {}
        override fun onNarration(json: String, selection: Boolean) = app.reader.onNarration(json, selection)
        override fun onReadFrom(line: Int, character: Int) = app.reader.readFrom(line, character)
        override fun onCommand(action: String) = app.reader.command(action)
        override fun onState(sentence: String, word: String, following: Boolean) {
            app.reader.pageState.value = PageState(sentence, word, following)
        }

        override fun onRenderGone() {
            // Replace the page; the reader screen opens the document in the new one and reading continues.
            webView?.let { old ->
                (old.parent as? ViewGroup)?.removeView(old)
                old.destroy()
            }
            webView = ReaderWebView(this@MainActivity, this)
        }

        override fun onLink(href: String) {
            if (Regex("^(https?|mailto|tel):", RegexOption.IGNORE_CASE).containsMatchIn(href)) {
                ReaderWebView.openExternal(this@MainActivity, href)
                return
            }
            val current = screen
            val (document, back) = when (current) {
                is Screen.Editor -> (editor.draft?.document ?: return) to current.back
                is Screen.Reader -> (app.reader.document.value ?: return) to current.back
                else -> return
            }
            val preview = current is Screen.Editor
            val resolved = Documents.resolve(this@MainActivity, document, href)
            if (resolved == null || !Documents.isMarkdown(resolved.name)) {
                Toast.makeText(this@MainActivity, "Open the document's folder to follow links to other files.", Toast.LENGTH_LONG).show()
                return
            }
            when (resolved) {
                is Documents.Resolved.Asset -> show(Documents.sample(this@MainActivity, resolved.name), back, preview)
                is Documents.Resolved.Tree -> open(resolved.uri, Documents.folderOf(resolved.tree, resolved.path), back, preview)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        webView = ReaderWebView(this, listener)
        if (app.reader.document.value != null) screen = Screen.Reader(Screen.Home)
        if (savedInstanceState?.getBoolean("editor") == true && editor.restore()) {
            val tree = savedInstanceState.getString("editorTree")
            screen = Screen.Editor(if (tree == null) Screen.Home else Screen.Folder(Uri.parse(tree), "", null))
        }
        if (savedInstanceState == null) handle(intent)
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle()
            val document by app.reader.document.collectAsStateWithLifecycle()
            val dark = isDark(settings.theme)
            // The status bar icons follow the app's theme, which can differ from the phone's.
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(NAVIGATION_SCRIM_DARK) else SystemBarStyle.light(NAVIGATION_SCRIM_LIGHT, NAVIGATION_SCRIM_DARK),
                )
            }
            ReaderTheme(settings.theme) {
                when (val current = screen) {
                    is Screen.Home -> HomeScreen(
                        app.reader,
                        onOpenFile = { openFile.launch(fileTypes) },
                        onPreviewFile = { previewFile.launch(fileTypes) },
                        onNewFile = { show(Documents.untitled, Screen.Home, preview = true) },
                        onOpenFolder = { openFolder.launch(null) },
                        onSample = { show(Documents.sample(this, "wagyu.md"), Screen.Home) },
                        onRecent = { recent ->
                            when {
                                recent.folder == Documents.SAMPLES -> show(Documents.sample(this, recent.title), Screen.Home, recent.preview)
                                recent.uri.startsWith("shared:") -> app.reader.forget(recent.uri)
                                else -> open(Uri.parse(recent.uri), recent.folder, Screen.Home, recent.preview)
                            }
                        },
                    )
                    is Screen.Folder -> {
                        BackHandler { screen = Screen.Home }
                        FolderScreen(current.name, current.files, onBack = { screen = Screen.Home }) { path, uri ->
                            open(uri, Documents.folderOf(current.tree, path), current)
                        }
                    }
                    is Screen.Reader -> {
                        val open = document
                        if (open == null) {
                            LaunchedEffect(Unit) { screen = current.back }
                        } else {
                            BackHandler { leaveReader(current.back) }
                            webView?.let { page -> ReaderScreen(app.reader, open, page, onBack = { leaveReader(current.back) }, onPreview = {
                                show(open, current.back, preview = true)
                            }) }
                        }
                    }
                    is Screen.Editor -> {
                        val back = {
                            leaveDraft {
                                if (current.back is Screen.Folder) showFolder(current.back.tree) else screen = current.back
                            }
                        }
                        BackHandler(onBack = back)
                        val draft = editor.draft
                        val page = webView
                        if (draft != null && page != null) {
                            EditorScreen(draft, page, settings, onBack = back, onSave = ::save, onListen = {
                                show(draft.document, current.back)
                            })
                        }
                    }
                }
                afterDiscard?.let { proceed ->
                    AlertDialog(
                        onDismissRequest = { afterDiscard = null },
                        title = { Text("Discard unsaved changes?") },
                        text = { Text("Your edits have not been saved to a file.") },
                        confirmButton = { TextButton(onClick = { afterDiscard = null; proceed() }) { Text("Discard") } },
                        dismissButton = { TextButton(onClick = { afterDiscard = null }) { Text("Keep editing") } },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val active = screen as? Screen.Editor ?: return
        outState.putBoolean("editor", editor.checkpoint())
        (active.back as? Screen.Folder)?.let { outState.putString("editorTree", it.tree.toString()) }
    }

    private fun save(saveAs: Boolean) {
        val draft = editor.draft ?: return
        if (draft.busy) return
        if (!saveAs && draft.document.writable) editor.save(Uri.parse(draft.document.uri))
        else {
            draft.choosingDestination = true
            try {
                createFile.launch(draft.suggestedName())
            } catch (e: android.content.ActivityNotFoundException) {
                draft.choosingDestination = false
                Toast.makeText(this, "No document picker is available to save this file.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun leaveDraft(next: () -> Unit) {
        val draft = editor.draft
        if (draft == null) { next(); return }
        if (draft.busy) return
        val leave = { editor.clear(); next() }
        if (draft.dirty) afterDiscard = leave else leave()
    }

    private fun leaveReader(back: Screen) {
        // Reading continues in the background while you look at other files; pause to stop it.
        screen = back
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Files opened with the app, shared with it, or a text share. */
    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { open(it, null, Screen.Home) }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> open(stream, null, Screen.Home)
                    !text.isNullOrBlank() -> show(Documents.shared(text, intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE)), Screen.Home)
                }
            }
        }
    }

    private fun open(uri: Uri, folder: String?, back: Screen, preview: Boolean = false) {
        val from = screen
        lifecycleScope.launch {
            runCatching { Documents.read(this@MainActivity, uri, folder) }
                .onSuccess { if (screen === from) show(it, back, preview) }
                .onFailure {
                    Toast.makeText(this@MainActivity, it.message ?: "The file could not be opened.", Toast.LENGTH_LONG).show()
                    app.reader.forget(uri.toString())
                }
        }
    }

    private fun show(document: OpenDocument, back: Screen, preview: Boolean = false) = leaveDraft {
        if (preview) {
            if (app.reader.document.value?.uri == document.uri) app.reader.command("pause")
            editor.open(document)
            screen = Screen.Editor(back)
        } else {
            app.reader.open(document)
            screen = Screen.Reader(back)
        }
    }

    private fun showFolder(tree: Uri) {
        val name = Documents.displayName(this, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
        screen = Screen.Folder(tree, name, null)
        lifecycleScope.launch {
            val files = runCatching { Documents.list(this@MainActivity, tree) }.getOrDefault(emptyList())
            if ((screen as? Screen.Folder)?.tree == tree) screen = Screen.Folder(tree, name, files)
        }
    }

    override fun onStart() {
        super.onStart()
        // Connecting to the session keeps the playback service (and its notification) ready.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        webView?.let { page ->
            (page.parent as? ViewGroup)?.removeView(page)
            page.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
