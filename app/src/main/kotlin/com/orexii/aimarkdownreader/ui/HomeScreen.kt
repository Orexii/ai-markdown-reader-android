package com.orexii.aimarkdownreader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orexii.aimarkdownreader.R
import com.orexii.aimarkdownreader.data.RecentDocument
import com.orexii.aimarkdownreader.reader.ReaderController

/** The start screen: open a file or a folder, try the Wagyu field guide, or reopen a recent document. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    reader: ReaderController,
    onOpenFile: () -> Unit,
    onOpenFolder: () -> Unit,
    onSample: () -> Unit,
    onRecent: (RecentDocument) -> Unit,
) {
    val recent by reader.library.recent.collectAsStateWithLifecycle()
    val settings by reader.settings.settings.collectAsStateWithLifecycle()
    val download by reader.models.download.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Markdown Reader") },
                actions = { IconButton(onClick = { showSettings = true }) { Icon(PlayerIcons.Settings, contentDescription = "Settings") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.widthIn(max = 640.dp).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.wagyu), contentDescription = "Wagyu, a red Shiba Inu, reading a Markdown page aloud", modifier = Modifier.size(112.dp).clip(RoundedCornerShape(24.dp)))
                    Text("Listen to Markdown with a natural voice that runs on your phone.", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onOpenFile, modifier = Modifier.fillMaxWidth().testTag("open-file")) { Text("Open a Markdown file") }
                    OutlinedButton(onClick = onOpenFolder, modifier = Modifier.fillMaxWidth()) { Text("Open a folder") }
                    OutlinedButton(onClick = onSample, modifier = Modifier.fillMaxWidth().testTag("sample")) { Text("Try it: the Wagyu field guide") }
                }
            }
            if (recent.isNotEmpty()) {
                item {
                    Text("Recent", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 20.dp))
                }
                items(recent, key = { it.uri }) { document ->
                    ListItem(
                        headlineContent = { Text(document.title) },
                        supportingContent = { Text(describe(document), maxLines = 1) },
                        leadingContent = { Icon(PlayerIcons.File, contentDescription = null) },
                        trailingContent = {
                            IconButton(onClick = { reader.forget(document.uri) }) { Icon(PlayerIcons.Close, contentDescription = "Remove from recent") }
                        },
                        modifier = Modifier.widthIn(max = 640.dp).clickable { onRecent(document) },
                    )
                }
            }
            item { Spacer(Modifier.padding(24.dp)) }
        }
    }
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            ReadingSettings(reader, settings, download)
        }
    }
}

private fun describe(document: RecentDocument): String = when {
    document.folder == "samples" -> "Sample"
    document.uri.startsWith("shared:") -> "Shared text"
    else -> android.text.format.DateUtils.getRelativeTimeSpanString(document.at).toString()
}

/** The Markdown files in a folder the user opened. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(name: String, files: List<Pair<String, android.net.Uri>>?, onBack: () -> Unit, onOpen: (String, android.net.Uri) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(PlayerIcons.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        when {
            files == null -> Text("Looking for Markdown files…", Modifier.padding(padding).padding(20.dp))
            files.isEmpty() -> Text("There are no Markdown files in this folder.", Modifier.padding(padding).padding(20.dp))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(files, key = { it.first }) { (path, uri) ->
                    ListItem(
                        headlineContent = { Text(path.substringAfterLast('/')) },
                        supportingContent = if (path.contains('/')) ({ Text(path.substringBeforeLast('/')) }) else null,
                        leadingContent = { Icon(PlayerIcons.File, contentDescription = null) },
                        modifier = Modifier.clickable { onOpen(path, uri) },
                    )
                }
            }
        }
    }
}
