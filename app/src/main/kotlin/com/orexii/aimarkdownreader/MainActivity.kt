package com.orexii.aimarkdownreader

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.orexii.aimarkdownreader.reader.OpenDocument
import com.orexii.aimarkdownreader.reader.PageState
import com.orexii.aimarkdownreader.ui.FolderScreen
import com.orexii.aimarkdownreader.ui.HomeScreen
import com.orexii.aimarkdownreader.ui.PageListener
import com.orexii.aimarkdownreader.ui.ReaderScreen
import com.orexii.aimarkdownreader.ui.ReaderTheme
import com.orexii.aimarkdownreader.ui.ReaderWebView
import kotlinx.coroutines.launch

/** The app's one activity: home, folders and the reader. Opens files shared with it or opened with it. */
class MainActivity : ComponentActivity() {
    private val app get() = application as App
    private lateinit var webView: ReaderWebView
    private var controller: ListenableFuture<MediaController>? = null

    private sealed interface Screen {
        data object Home : Screen
        data class Folder(val tree: Uri, val name: String, val files: List<Pair<String, Uri>>?) : Screen
        data class Reader(val back: Screen) : Screen
    }

    private var screen by mutableStateOf<Screen>(Screen.Home)

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            open(uri, null, Screen.Home)
        }
    }

    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            runCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            showFolder(tree)
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

        override fun onLink(href: String) {
            if (Regex("^(https?|mailto|tel):", RegexOption.IGNORE_CASE).containsMatchIn(href)) {
                ReaderWebView.openExternal(this@MainActivity, href)
                return
            }
            val document = app.reader.document.value ?: return
            val resolved = Documents.resolve(this@MainActivity, document, href)
            if (resolved == null || !Documents.isMarkdown(resolved.name)) {
                Toast.makeText(this@MainActivity, "Open the document's folder to follow links to other files.", Toast.LENGTH_LONG).show()
                return
            }
            when (resolved) {
                is Documents.Resolved.Asset -> show(Documents.sample(this@MainActivity, resolved.name), (screen as? Screen.Reader)?.back ?: Screen.Home)
                is Documents.Resolved.Tree -> open(resolved.uri, Documents.folderOf(resolved.tree, resolved.path), (screen as? Screen.Reader)?.back ?: Screen.Home)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        webView = ReaderWebView(this, listener)
        if (app.reader.document.value != null) screen = Screen.Reader(Screen.Home)
        if (savedInstanceState == null) handle(intent)
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle()
            val document by app.reader.document.collectAsStateWithLifecycle()
            ReaderTheme(settings.theme) {
                when (val current = screen) {
                    is Screen.Home -> HomeScreen(
                        app.reader,
                        onOpenFile = { openFile.launch(arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream")) },
                        onOpenFolder = { openFolder.launch(null) },
                        onSample = { show(Documents.sample(this, "wagyu.md"), Screen.Home) },
                        onRecent = { recent ->
                            when {
                                recent.folder == Documents.SAMPLES -> show(Documents.sample(this, recent.title), Screen.Home)
                                recent.uri.startsWith("shared:") -> app.reader.forget(recent.uri)
                                else -> open(Uri.parse(recent.uri), recent.folder, Screen.Home)
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
                            ReaderScreen(app.reader, open, webView, onBack = { leaveReader(current.back) })
                        }
                    }
                }
            }
        }
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

    private fun open(uri: Uri, folder: String?, back: Screen) {
        lifecycleScope.launch {
            runCatching { Documents.read(this@MainActivity, uri, folder) }
                .onSuccess { show(it, back) }
                .onFailure {
                    Toast.makeText(this@MainActivity, it.message ?: "The file could not be opened.", Toast.LENGTH_LONG).show()
                    app.reader.forget(uri.toString())
                }
        }
    }

    private fun show(document: OpenDocument, back: Screen) {
        app.reader.open(document)
        screen = Screen.Reader(back)
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
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
