package com.orexii.aimarkdownreader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orexii.aimarkdownreader.core.player.PlayerState
import com.orexii.aimarkdownreader.data.ReaderSettings
import com.orexii.aimarkdownreader.reader.OpenDocument
import com.orexii.aimarkdownreader.reader.ReaderController
import com.orexii.aimarkdownreader.reader.VoiceState
import com.orexii.aimarkdownreader.speech.ModelStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The options the page uses to render and narrate a document. */
fun pageOptions(settings: ReaderSettings, document: OpenDocument) = JsonObject(
    mapOf(
        "tableMode" to JsonPrimitive(settings.tableMode.value),
        "codeBlocks" to JsonPrimitive(settings.codeBlocks.value),
        "localImages" to JsonPrimitive(document.folder != null),
    ),
)

/** The status line under the controls (player.js updateControls). */
fun statusOf(state: PlayerState, voice: VoiceState): String? = when {
    state.error != null -> state.error
    !state.loaded -> null
    voice is VoiceState.Failed -> voice.message
    voice == VoiceState.Loading && state.playing -> "Starting the voice…"
    state.playing && state.waiting -> "Preparing audio…"
    state.finished -> "Finished. Press Play to listen again."
    !state.playing -> "Paused"
    else -> null
}

/** A document, rendered with the reading highlighted, and the player under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(reader: ReaderController, document: OpenDocument, webView: ReaderWebView, onBack: () -> Unit) {
    val state by reader.state.collectAsStateWithLifecycle()
    val settings by reader.settings.settings.collectAsStateWithLifecycle()
    val voice by reader.voice.collectAsStateWithLifecycle()
    val download by reader.models.download.collectAsStateWithLifecycle()
    val dark = isDark(settings.theme)
    var showSettings by remember { mutableStateOf(false) }
    var narrated by remember { mutableStateOf(settings.tableMode to settings.codeBlocks) }

    LaunchedEffect(webView, dark) { webView.theme(dark) }
    LaunchedEffect(webView, settings.follow, settings.highlightWords) { webView.configure(settings.follow, settings.highlightWords) }
    LaunchedEffect(webView, document) {
        narrated = settings.tableMode to settings.codeBlocks
        webView.open(document, pageOptions(settings, document))
    }
    LaunchedEffect(settings.tableMode, settings.codeBlocks) {
        val wanted = settings.tableMode to settings.codeBlocks
        if (wanted != narrated) {
            narrated = wanted
            reader.rebuildRequested()
            webView.rebuild(pageOptions(settings, document))
        }
    }
    LaunchedEffect(state.revision, state.index, state.word, state.loaded) {
        if (state.loaded) webView.position(state.index, state.word) else webView.clear()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(document.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(PlayerIcons.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { reader.command("top"); webView.reveal() }, modifier = Modifier.testTag("read-from-start")) {
                        Icon(PlayerIcons.Top, contentDescription = "Read aloud from the start")
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                val needsVoice = voice == VoiceState.NeedsDownload || download is ModelStore.Download.Running
                if (needsVoice && state.loaded) VoiceSetup(reader, settings, download, Modifier.padding(8.dp))
                PlayerBar(
                    state = state,
                    status = statusOf(state, voice),
                    expandedSpeeds = settings.expandedSpeeds,
                    skipBack = settings.skipBack,
                    skipForward = settings.skipForward,
                    onCommand = { action ->
                        if (action == "togglePlay" && !state.playing) webView.reveal()
                        reader.command(action)
                    },
                    onSeek = { reader.player.seekTo(it) },
                    onRate = { reader.setRate(it) },
                    onExpandSpeeds = { on -> reader.update { it.copy(expandedSpeeds = on) } },
                    onSettings = { showSettings = true },
                )
            }
        },
    ) { padding ->
        // The page outlives this screen (reading goes on), so take it from its previous place first.
        AndroidView(
            factory = {
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView
            },
            modifier = Modifier.padding(padding).fillMaxSize().testTag("page"),
        )
    }
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            ReadingSettings(reader, settings, download)
        }
    }
}
