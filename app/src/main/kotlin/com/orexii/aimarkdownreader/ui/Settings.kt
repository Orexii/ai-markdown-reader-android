package com.orexii.aimarkdownreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.orexii.aimarkdownreader.BuildConfig
import com.orexii.aimarkdownreader.core.ModelCatalog
import com.orexii.aimarkdownreader.data.CodeBlocks
import com.orexii.aimarkdownreader.data.EngineChoice
import com.orexii.aimarkdownreader.data.ReaderSettings
import com.orexii.aimarkdownreader.data.TableMode
import com.orexii.aimarkdownreader.data.ThemeChoice
import com.orexii.aimarkdownreader.reader.ReaderController
import com.orexii.aimarkdownreader.speech.ModelStore

const val SOURCE_URL = "https://github.com/Orexii/ai-markdown-reader-android"
const val PRIVACY_URL = "https://github.com/Orexii/ai-markdown-reader-android/blob/main/PRIVACY.md"

/** The natural voice download, offered when it is needed (the extension's setup panel). */
@Composable
fun VoiceSetup(reader: ReaderController, settings: ReaderSettings, download: ModelStore.Download, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().testTag("voice-setup"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (download) {
                is ModelStore.Download.Running -> {
                    val percent = if (download.total > 0) (download.received * 100 / download.total).toInt() else 0
                    Text("Downloading the natural voice… $percent%")
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { reader.models.cancel() }) { Text("Cancel") }
                }
                else -> {
                    Text("The natural voice is a one-time download. It then runs entirely on this phone, even offline.")
                    if (download is ModelStore.Download.Failed) Text(download.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { reader.downloadNaturalVoice(ModelCatalog.Quality.COMPACT) }, modifier = Modifier.testTag("download-compact")) {
                            Text("Download (${ModelCatalog.Quality.COMPACT.megabytes} MB)")
                        }
                        OutlinedButton(onClick = { reader.downloadNaturalVoice(ModelCatalog.Quality.BEST) }) {
                            Text("Best (${ModelCatalog.Quality.BEST.megabytes} MB)")
                        }
                    }
                    TextButton(onClick = { reader.useSystemVoice() }) { Text("Use the phone's voice instead") }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, text) ->
                SegmentedButton(selected = value == selected, onClick = { onSelect(value) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
                    Text(text, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Picker(label: String, current: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Column {
            TextButton(onClick = { open = true }) { Text(options.firstOrNull { it.first == current }?.second ?: current.ifEmpty { "Default" }) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((value, text) in options) DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); open = false })
            }
        }
    }
}

/** Every reading setting: voice, tables, code, highlighting, speeds, skips, pronunciations and appearance. */
@Composable
fun ReadingSettings(reader: ReaderController, settings: ReaderSettings, download: ModelStore.Download, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        SectionTitle("Voice")
        Choice("Voice engine", listOf(EngineChoice.NATURAL to "Natural (on device)", EngineChoice.SYSTEM to "Phone's voice"), settings.engine) {
            reader.update { s -> s.copy(engine = it) }
        }
        if (settings.engine == EngineChoice.NATURAL) {
            Picker("Voice", settings.voice, ModelCatalog.VOICES.map { it.id to it.label }) { id -> reader.update { it.copy(voice = id) } }
            Choice("Quality", ModelCatalog.Quality.entries.map { it to "${if (it == ModelCatalog.Quality.BEST) "Best" else "Compact"} (${it.megabytes} MB)" }, settings.quality) {
                reader.update { s -> s.copy(quality = it) }
            }
            val installed = reader.models.hasModel(settings.quality)
            when {
                download is ModelStore.Download.Running -> {
                    LinearProgressIndicator(progress = { if (download.total > 0) download.received.toFloat() / download.total else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                !installed -> Button(onClick = { reader.downloadNaturalVoice(settings.quality) }) { Text("Download the natural voice (${settings.quality.megabytes} MB)") }
                else -> TextButton(onClick = { reader.removeNaturalVoice() }) { Text("Remove the downloaded natural voice") }
            }
        } else {
            val voices = remember(settings.engine) { reader.systemVoices() }
            Picker("Voice", settings.systemVoice, listOf("" to "Phone default") + voices) { name -> reader.update { it.copy(systemVoice = name) } }
            Text("Phone voices vary in quality, and words are highlighted approximately.", style = MaterialTheme.typography.bodySmall)
        }

        SectionTitle("Reading")
        Choice("Tables", listOf(TableMode.ROWS to "Row by row", TableMode.COLUMNS to "Column by column"), settings.tableMode) {
            reader.update { s -> s.copy(tableMode = it) }
        }
        Choice("Code blocks", listOf(CodeBlocks.ANNOUNCE to "Announce", CodeBlocks.READ to "Read aloud", CodeBlocks.SKIP to "Skip"), settings.codeBlocks) {
            reader.update { s -> s.copy(codeBlocks = it) }
        }
        Toggle("Highlight each word", settings.highlightWords) { on -> reader.update { it.copy(highlightWords = on) } }
        Toggle("Keep the reading in view", settings.follow) { on -> reader.update { it.copy(follow = on) } }
        Toggle("More speeds (0.25× to 5×)", settings.expandedSpeeds) { on -> reader.update { it.copy(expandedSpeeds = on) } }
        val skips = listOf(5, 10, 15, 30, 60)
        Picker("Back button", settings.skipBack.toString(), skips.map { it.toString() to "$it seconds" }) { v -> reader.update { it.copy(skipBack = v.toInt()) } }
        Picker("Forward button", settings.skipForward.toString(), skips.map { it.toString() to "$it seconds" }) { v -> reader.update { it.copy(skipForward = v.toInt()) } }

        SectionTitle("Pronunciations")
        Pronunciations(settings.pronunciations) { entries -> reader.update { it.copy(pronunciations = entries) } }

        SectionTitle("Appearance")
        Choice("Theme", listOf(ThemeChoice.SYSTEM to "System", ThemeChoice.LIGHT to "Light", ThemeChoice.DARK to "Dark"), settings.theme) {
            reader.update { s -> s.copy(theme = it) }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("AI Markdown Reader ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        Text(
            "Your documents never leave this phone. The natural voice is Kokoro-82M (Apache-2.0), downloaded once from Hugging Face and checked before use.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row {
            TextButton(onClick = { ReaderWebView.openExternal(context, SOURCE_URL) }) { Text("Source code and licenses") }
            TextButton(onClick = { ReaderWebView.openExternal(context, PRIVACY_URL) }) { Text("Privacy") }
        }
    }
}

/** Your own words: "Kusto" → "kuss toe", or IPA between slashes, as aiMarkdownReader.pronunciations. */
@Composable
private fun Pronunciations(entries: Map<String, String>, onChange: (Map<String, String>) -> Unit) {
    var word by remember { mutableStateOf("") }
    var spoken by remember { mutableStateOf("") }
    for ((key, value) in entries) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$key → $value", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { onChange(entries - key) }) { Icon(PlayerIcons.Close, contentDescription = "Remove $key") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(word, { word = it }, label = { Text("Word") }, singleLine = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(spoken, { spoken = it }, label = { Text("Say") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    TextButton(
        onClick = {
            onChange(entries + (word.trim() to spoken.trim()))
            word = ""
            spoken = ""
        },
        enabled = word.isNotBlank() && spoken.isNotBlank(),
    ) { Text("Add") }
    Text("Write how to say it, or IPA between slashes, for example /ɹɑːkˈiːm/.", style = MaterialTheme.typography.bodySmall)
}
