package com.orexii.aimarkdownreader.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The narration of a Markdown document, as built by the extension's document.ts in the reader's web
 * view. Ranges are zero-based source positions, end exclusive.
 */
@Serializable
data class SourceRange(val line: Int, val character: Int, val endLine: Int, val endCharacter: Int)

@Serializable
data class SpokenWord(
    /** Text to speak for this unit, before pronunciation rules. */
    val text: String,
    /** Whitespace and punctuation spoken after the unit; shapes pauses and intonation. */
    val sep: String,
    /** Source text highlighted while the unit is spoken; null for spoken-only cues. */
    val range: SourceRange? = null,
)

@Serializable
data class Utterance(
    val block: Int,
    /**
     * Which stop within its block this belongs to when moving by paragraph: a table's introduction and each of
     * its rows (or columns, when reading by column) are separate stops; other blocks are one stop.
     */
    val part: Int = 0,
    val words: List<SpokenWord>,
    val range: SourceRange? = null,
    /** Total silence after this utterance, in seconds at 1×. */
    val pauseAfter: Double,
    val line: Int,
    val endLine: Int,
)

@Serializable
data class Heading(val level: Int, val text: String)

@Serializable
data class ReaderBlock(
    val kind: String,
    val line: Int,
    val endLine: Int,
    /** First utterance index, or -1 for blocks that are not spoken. */
    val first: Int,
    val heading: Heading? = null,
)

@Serializable
data class ReaderDocument(val utterances: List<Utterance>, val blocks: List<ReaderBlock>) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun parse(text: String): ReaderDocument = json.decodeFromString(serializer(), text)
    }

    fun encode(): String = json.encodeToString(serializer(), this)
}

fun Utterance.text(): String = words.joinToString("") { it.text + it.sep }.trim()

/** Spoken characters, used to estimate durations and size synthesis batches (scheduler.ts spokenLength). */
fun Utterance.spokenLength(): Int = words.sumOf { it.text.length + it.sep.length }
