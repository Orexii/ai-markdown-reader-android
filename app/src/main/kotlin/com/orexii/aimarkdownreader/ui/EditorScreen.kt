package com.orexii.aimarkdownreader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.orexii.aimarkdownreader.data.ReaderSettings
import com.orexii.aimarkdownreader.reader.MarkdownDraft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    draft: MarkdownDraft, page: ReaderWebView, settings: ReaderSettings, onBack: () -> Unit,
    onSave: (saveAs: Boolean) -> Unit, onListen: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val dark = isDark(settings.theme)
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(draft.document.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val status = when {
                            draft.saving -> "Saving…"
                            draft.dirty -> "Unsaved changes"
                            draft.isNew -> "New file"
                            !draft.document.writable -> "Read-only original"
                            else -> null
                        }
                        if (status != null) Text(status, style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack, enabled = !draft.busy) { Icon(PlayerIcons.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { draft.editing = !draft.editing }, enabled = !draft.busy) {
                        Text(if (draft.editing) "Preview" else "Edit")
                    }
                    if (draft.dirty || draft.isNew) {
                        TextButton(onClick = { onSave(false) }, enabled = !draft.busy) { Text("Save") }
                    }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !draft.busy) { Icon(PlayerIcons.More, "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Save as…") }, onClick = { menu = false; onSave(true) })
                            DropdownMenuItem(
                                text = { Text(if (draft.dirty || draft.isNew) "Save first to read aloud" else "Read aloud") },
                                enabled = !draft.dirty && !draft.isNew,
                                onClick = { menu = false; onListen() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (draft.editing) {
            TextField(
                state = draft.source,
                readOnly = draft.busy,
                placeholder = { Text("Write Markdown here…") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(padding).fillMaxSize().testTag("markdown-source"),
            )
        } else {
            LaunchedEffect(page, dark) { page.theme(dark) }
            LaunchedEffect(page, draft, draft.document) {
                val document = draft.document.copy(text = draft.source.text.toString())
                page.open(document, pageOptions(settings, document), previewOnly = true)
            }
            DocumentPage(page, Modifier.padding(padding))
        }
    }
}
