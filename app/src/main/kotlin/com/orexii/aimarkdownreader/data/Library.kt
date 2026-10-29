package com.orexii.aimarkdownreader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A document opened before, for the home screen. */
@Serializable
data class RecentDocument(val uri: String, val title: String, val folder: String? = null, val at: Long)

/** Where reading stopped in a document: the line and passage, as the extension saves it. */
@Serializable
data class SavedPosition(val line: Int, val index: Int, val total: Int, val at: Long)

private val Context.libraryStore by preferencesDataStore(name = "library")

/** Recent documents and the place reading stopped in each (the 200 most recent, like the extension). */
class Library(private val context: Context, scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true }
    private val recentKey = stringPreferencesKey("recent")
    private val positionsKey = stringPreferencesKey("positions")
    private val recentSerializer = ListSerializer(RecentDocument.serializer())
    private val positionsSerializer = MapSerializer(String.serializer(), SavedPosition.serializer())

    private fun recentOf(text: String?): List<RecentDocument> = runCatching { json.decodeFromString(recentSerializer, text ?: "[]") }.getOrDefault(emptyList())
    private fun positionsOf(text: String?): Map<String, SavedPosition> = runCatching { json.decodeFromString(positionsSerializer, text ?: "{}") }.getOrDefault(emptyMap())

    val recent: StateFlow<List<RecentDocument>> = context.libraryStore.data.map { recentOf(it[recentKey]) }
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { recentOf(context.libraryStore.data.first()[recentKey]) })

    suspend fun opened(document: RecentDocument) {
        context.libraryStore.edit { p ->
            val list = listOf(document) + recentOf(p[recentKey]).filter { it.uri != document.uri }
            p[recentKey] = json.encodeToString(recentSerializer, list.take(30))
        }
    }

    suspend fun forget(uri: String) {
        context.libraryStore.edit { p -> p[recentKey] = json.encodeToString(recentSerializer, recentOf(p[recentKey]).filter { it.uri != uri }) }
    }

    suspend fun position(uri: String): SavedPosition? = positionsOf(context.libraryStore.data.first()[positionsKey])[uri]

    suspend fun savePosition(uri: String, position: SavedPosition?) {
        context.libraryStore.edit { p ->
            val positions = positionsOf(p[positionsKey]).toMutableMap()
            if (position != null) positions[uri] = position else positions.remove(uri)
            val kept = positions.entries.sortedByDescending { it.value.at }.take(200).associate { it.key to it.value }
            p[positionsKey] = json.encodeToString(positionsSerializer, kept)
        }
    }
}
