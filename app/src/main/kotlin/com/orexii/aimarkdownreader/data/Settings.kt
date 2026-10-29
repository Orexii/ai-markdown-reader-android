package com.orexii.aimarkdownreader.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.orexii.aimarkdownreader.core.ModelCatalog
import com.orexii.aimarkdownreader.core.Speeds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class EngineChoice { NATURAL, SYSTEM }
enum class TableMode(val value: String) { ROWS("rows"), COLUMNS("columns") }
enum class CodeBlocks(val value: String) { ANNOUNCE("announce"), READ("read"), SKIP("skip") }
enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** The reader's settings; the same choices as the extension's aiMarkdownReader.* settings. */
data class ReaderSettings(
    val engine: EngineChoice = EngineChoice.NATURAL,
    val voice: String = ModelCatalog.DEFAULT_VOICE,
    val systemVoice: String = "",
    val quality: ModelCatalog.Quality = ModelCatalog.Quality.COMPACT,
    val rate: Double = 1.0,
    val expandedSpeeds: Boolean = false,
    val skipBack: Int = 10,
    val skipForward: Int = 30,
    val tableMode: TableMode = TableMode.ROWS,
    val codeBlocks: CodeBlocks = CodeBlocks.ANNOUNCE,
    val highlightWords: Boolean = true,
    val follow: Boolean = true,
    val pronunciations: Map<String, String> = emptyMap(),
    val threads: Int = 0,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
)

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private object Keys {
        val engine = stringPreferencesKey("engine")
        val voice = stringPreferencesKey("naturalVoice")
        val systemVoice = stringPreferencesKey("systemVoice")
        val quality = stringPreferencesKey("naturalQuality")
        val rate = doublePreferencesKey("rate")
        val expandedSpeeds = booleanPreferencesKey("expandedSpeeds")
        val skipBack = intPreferencesKey("skipBackSeconds")
        val skipForward = intPreferencesKey("skipForwardSeconds")
        val tableMode = stringPreferencesKey("tableMode")
        val codeBlocks = stringPreferencesKey("codeBlocks")
        val highlightWords = booleanPreferencesKey("highlightWords")
        val follow = booleanPreferencesKey("followInEditor")
        val pronunciations = stringPreferencesKey("pronunciations")
        val threads = intPreferencesKey("threads")
        val theme = stringPreferencesKey("theme")
    }

    private fun read(p: Preferences) = ReaderSettings(
        engine = if (p[Keys.engine] == "system") EngineChoice.SYSTEM else EngineChoice.NATURAL,
        voice = p[Keys.voice]?.takeIf { ModelCatalog.voice(it) != null } ?: ModelCatalog.DEFAULT_VOICE,
        systemVoice = p[Keys.systemVoice] ?: "",
        quality = if (p[Keys.quality] == "best") ModelCatalog.Quality.BEST else ModelCatalog.Quality.COMPACT,
        rate = Speeds.nearest(p[Keys.rate] ?: 1.0),
        expandedSpeeds = p[Keys.expandedSpeeds] ?: false,
        skipBack = (p[Keys.skipBack] ?: 10).coerceIn(1, 300),
        skipForward = (p[Keys.skipForward] ?: 30).coerceIn(1, 300),
        tableMode = if (p[Keys.tableMode] == "columns") TableMode.COLUMNS else TableMode.ROWS,
        codeBlocks = CodeBlocks.entries.firstOrNull { it.value == p[Keys.codeBlocks] } ?: CodeBlocks.ANNOUNCE,
        highlightWords = p[Keys.highlightWords] ?: true,
        follow = p[Keys.follow] ?: true,
        pronunciations = parsePronunciations(p[Keys.pronunciations]),
        threads = (p[Keys.threads] ?: 0).coerceIn(0, 16),
        theme = ThemeChoice.entries.firstOrNull { it.name.lowercase() == p[Keys.theme] } ?: ThemeChoice.SYSTEM,
    )

    val settings: StateFlow<ReaderSettings> = context.settingsStore.data.map(::read)
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { read(context.settingsStore.data.first()) })

    suspend fun update(change: (ReaderSettings) -> ReaderSettings) {
        context.settingsStore.edit { p ->
            val next = change(read(p))
            p[Keys.engine] = next.engine.name.lowercase()
            p[Keys.voice] = next.voice
            p[Keys.systemVoice] = next.systemVoice
            p[Keys.quality] = next.quality.name.lowercase()
            p[Keys.rate] = next.rate
            p[Keys.expandedSpeeds] = next.expandedSpeeds
            p[Keys.skipBack] = next.skipBack
            p[Keys.skipForward] = next.skipForward
            p[Keys.tableMode] = next.tableMode.value
            p[Keys.codeBlocks] = next.codeBlocks.value
            p[Keys.highlightWords] = next.highlightWords
            p[Keys.follow] = next.follow
            p[Keys.pronunciations] = JsonObject(next.pronunciations.mapValues { JsonPrimitive(it.value) }).toString()
            p[Keys.threads] = next.threads
            p[Keys.theme] = next.theme.name.lowercase()
        }
    }

    companion object {
        fun parsePronunciations(text: String?): Map<String, String> = runCatching {
            (Json.parseToJsonElement(text ?: "{}") as JsonObject).mapNotNull { (key, value) ->
                value.jsonPrimitive.contentOrNull?.let { key to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
