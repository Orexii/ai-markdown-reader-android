package com.orexii.aimarkdownreader.core

/**
 * Passage durations (exact once synthesized, estimated from spoken characters before) with prefix sums
 * for elapsed time, seeking and chapter marks. Mirrors duration/rebuildTimeline/locate in player.js.
 */
class Timeline(private val utterances: List<Utterance>) {
    private val known = DoubleArray(utterances.size) { Double.NaN }
    private val chars = IntArray(utterances.size) { utterances[it].spokenLength() }
    private var prefix = DoubleArray(utterances.size + 1)
    private var cps = 14.5
    private var spokenChars = 0.0
    private var spokenSeconds = 0.0

    init {
        rebuild()
    }

    val size: Int get() = utterances.size

    fun duration(i: Int): Double {
        if (i !in utterances.indices) return 0.0
        val exact = known[i]
        return if (exact.isNaN()) chars[i] / cps + utterances[i].pauseAfter else exact
    }

    fun isKnown(i: Int): Boolean = i in utterances.indices && !known[i].isNaN()

    /** Records a passage's real duration and refines the speaking-rate estimate used for the rest. */
    fun learn(i: Int, duration: Double) {
        if (i !in utterances.indices) return
        val first = known[i].isNaN()
        known[i] = duration
        val speech = duration - utterances[i].pauseAfter
        if (first && speech > 0.4 && chars[i] > 8) {
            spokenChars += chars[i]
            spokenSeconds += speech
            cps = (spokenChars / spokenSeconds).coerceIn(8.0, 25.0)
        }
        rebuild()
    }

    /** Forgets every real duration, for example after the voice changes. */
    fun reset() {
        known.fill(Double.NaN)
        cps = 14.5
        spokenChars = 0.0
        spokenSeconds = 0.0
        rebuild()
    }

    private fun rebuild() {
        val next = DoubleArray(utterances.size + 1)
        for (i in utterances.indices) next[i + 1] = next[i] + duration(i)
        prefix = next
    }

    fun start(i: Int): Double = prefix[i.coerceIn(0, utterances.size)]

    val total: Double get() = prefix[utterances.size]

    /** Whether any passage from [from] on still has an estimated duration. */
    fun estimatedFrom(from: Int): Boolean = (from.coerceAtLeast(0) until utterances.size).any { known[it].isNaN() }

    /** The passage playing at [time] seconds into the document. */
    fun locate(time: Double): Int {
        var low = 0
        var high = maxOf(0, utterances.size - 1)
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (prefix[middle] <= time) low = middle else high = middle - 1
        }
        return low
    }
}

/** Moving through the text, as the player's buttons and keys do (player.js sentence/paragraph/moveSection). */
object Navigation {
    /** Seconds into a passage after which "previous" restarts it instead of going back. */
    const val RESTART_SECONDS = 1.5

    sealed interface Move {
        data class To(val index: Int) : Move
        /** Nothing further in that direction; the player says so. */
        data class End(val message: String) : Move
    }

    fun nextSentence(utterances: List<Utterance>, index: Int): Move =
        if (index >= utterances.size - 1) Move.End("This is the last sentence") else Move.To(index + 1)

    fun previousSentence(index: Int, offset: Double): Move =
        Move.To(if (offset > RESTART_SECONDS) index else maxOf(0, index - 1))

    /** Whether two passages are in the same paragraph stop: the same block and, in a table, the same row. */
    private fun sameStop(a: Utterance, b: Utterance) = a.block == b.block && a.part == b.part

    fun nextParagraph(utterances: List<Utterance>, index: Int): Move {
        val here = utterances.getOrNull(index) ?: return Move.End("This is the last paragraph")
        val next = (index + 1 until utterances.size).firstOrNull { !sameStop(utterances[it], here) }
        return if (next != null) Move.To(next) else Move.End("This is the last paragraph")
    }

    /** Back to the start of this paragraph (or table row), or to the one before when already at its start. */
    fun previousParagraph(utterances: List<Utterance>, index: Int, offset: Double): Move {
        val here = utterances.getOrNull(index) ?: return Move.To(0)
        var start = index
        while (start > 0 && sameStop(utterances[start - 1], here)) start--
        val insideStop = start < index || offset > RESTART_SECONDS
        if (!insideStop && start > 0) {
            val previous = utterances[--start]
            while (start > 0 && sameStop(utterances[start - 1], previous)) start--
        }
        return Move.To(start)
    }

    private fun sectionStarts(blocks: List<ReaderBlock>): List<Int> = blocks.filter { it.kind == "heading" && it.first >= 0 }.map { it.first }

    fun nextSection(blocks: List<ReaderBlock>, index: Int): Move {
        val next = sectionStarts(blocks).firstOrNull { it > index }
        return if (next != null) Move.To(next) else Move.End("This is the last section")
    }

    fun previousSection(blocks: List<ReaderBlock>, index: Int, offset: Double): Move {
        val starts = sectionStarts(blocks)
        val current = starts.lastOrNull { it <= index }
        if (current != null && (current < index || offset > RESTART_SECONDS)) return Move.To(current)
        val previous = starts.lastOrNull { it < (current ?: index) }
        return Move.To(previous ?: 0)
    }

    /** The heading of the section being read. */
    fun section(blocks: List<ReaderBlock>, index: Int): String {
        var heading: ReaderBlock? = null
        for (block in blocks) {
            if (block.kind != "heading" || block.first < 0) continue
            if (block.first > index) break
            heading = block
        }
        return heading?.heading?.text ?: ""
    }

    /** Headings shown as marks on the progress bar: levels 1 to 3. */
    fun chapters(blocks: List<ReaderBlock>): List<ReaderBlock> =
        blocks.filter { it.kind == "heading" && it.first >= 0 && (it.heading?.level ?: 9) <= 3 }

    /** The first passage of the first block at or after [line], for resuming and "read from here". */
    fun atLine(utterances: List<Utterance>, line: Int): Int = maxOf(0, utterances.indexOfFirst { it.endLine > line })

    /** The passage and word to read from for a source position (word -1 for the start of the passage); ranges.ts locate. */
    fun locate(utterances: List<Utterance>, line: Int, character: Int): Pair<Int, Int> {
        fun endsBefore(range: SourceRange) = range.endLine < line || (range.endLine == line && range.endCharacter <= character)
        fun startsAfter(range: SourceRange) = range.line > line || (range.line == line && range.character >= character)
        var index = utterances.indexOfFirst { u -> u.range?.let { !endsBefore(it) } ?: (u.endLine > line) }
        if (index < 0) index = maxOf(0, utterances.size - 1)
        val utterance = utterances.getOrNull(index)
        if (utterance == null || (utterance.range != null && startsAfter(utterance.range))) return index to -1
        val word = utterance.words.indexOfFirst { w -> w.range != null && !endsBefore(w.range) }
        return index to (if (word > 0) word else -1)
    }
}
