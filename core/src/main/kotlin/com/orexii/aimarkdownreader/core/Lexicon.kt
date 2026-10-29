package com.orexii.aimarkdownreader.core

/**
 * Kotlin port of the extension's src/engine/lexicon.ts, itself a port of the word lookup in misaki
 * (https://github.com/hexgrad/misaki, Apache-2.0), the grapheme-to-phoneme frontend Kokoro was trained
 * with. Without a part-of-speech tagger, entries that depend on the tag use their default reading.
 */
sealed interface Entry {
    data class Plain(val phonemes: String) : Entry
    /** Readings by part-of-speech tag; a tag can map to null ("no reading"). */
    data class Tagged(val tags: Map<String, String?>) : Entry
}

class LexiconData(val gold: Map<String, Entry>, val silver: Map<String, Entry>) {
    companion object {
        /** Parses misaki's us.json / gb.json ({"gold": {...}, "silver": {...}}). */
        fun parse(json: String): LexiconData {
            val root = JsonReader(json).readValue() as? Map<*, *> ?: error("Lexicon is not a JSON object.")
            return LexiconData(entries(root["gold"]), entries(root["silver"]))
        }

        private fun entries(value: Any?): Map<String, Entry> {
            val map = value as? Map<*, *> ?: return emptyMap()
            val result = LinkedHashMap<String, Entry>(map.size * 2)
            for ((key, entry) in map) {
                when (entry) {
                    is String -> result[key as String] = Entry.Plain(entry)
                    is Map<*, *> -> result[key as String] = Entry.Tagged(entry.entries.associate { (tag, phonemes) -> tag as String to phonemes as String? })
                }
            }
            return result
        }
    }
}

/** Whether phonemes start with a vowel sound, a consonant, a phrase boundary, or neither (startsWithVowel). */
enum class Onset { VOWEL, CONSONANT, BOUNDARY, UNKNOWN }

/** Lookup context: whether the next word starts with a vowel sound (null at a phrase boundary or the end). */
data class LexiconContext(val nextVowel: Boolean?, val nextIsTo: Boolean)

class Lexicon(data: LexiconData, private val british: Boolean) {
    private val golds: Map<String, Entry> = grow(data.gold)
    private val silvers: Map<String, Entry> = grow(data.silver)

    /** The JavaScript choose(): undefined (no entry), null (an explicit "no reading") or phonemes. */
    private sealed interface Pick {
        data object Missing : Pick
        data object Null : Pick
        data class Value(val phonemes: String) : Pick
    }

    private fun Pick.orNull(): String? = (this as? Pick.Value)?.phonemes

    private fun choose(entry: Entry?, context: LexiconContext): Pick = when (entry) {
        null -> Pick.Missing
        is Entry.Plain -> Pick.Value(entry.phonemes)
        is Entry.Tagged -> {
            val tag = if (context.nextVowel == null && entry.tags.containsKey("None")) "None" else "DEFAULT"
            if (!entry.tags.containsKey(tag)) Pick.Missing
            else entry.tags[tag]?.let { Pick.Value(it) } ?: Pick.Null
        }
    }

    /** Spells a word letter by letter with the lexicon's letter names (misaki get_NNP). */
    fun spell(word: String): String? {
        val letters = word.filter { it in 'A'..'Z' || it in 'a'..'z' }.map { golds[it.uppercaseChar().toString()] }
        if (letters.isEmpty() || letters.any { it !is Entry.Plain }) return null
        val joined = applyStress(letters.joinToString("") { (it as Entry.Plain).phonemes }, 0.0)
        val last = joined.lastIndexOf(SECONDARY)
        return if (last < 0) joined else "${joined.substring(0, last)}$PRIMARY${joined.substring(last + 1)}"
    }

    private fun special(word: String, context: LexiconContext): String? {
        if (word == "a" || word == "A") return if (context.nextVowel == null && word == "A") "${PRIMARY}A" else "ɐ"
        if (word == "an" || word == "An") return "ɐn"
        if (word == "I") return "${SECONDARY}I"
        if (word == "to" || word == "To") {
            return when (context.nextVowel) {
                null -> choose(golds["to"], context).orNull()
                true -> "tʊ"
                false -> "tə"
            }
        }
        if (word == "in" || word == "In") return if (context.nextVowel == null) "${PRIMARY}ɪn" else "ɪn"
        if (word == "the" || word == "The") return if (context.nextVowel == true) "ði" else "ðə"
        if (word == "used" || word == "Used") {
            val entry = golds["used"]
            if (entry is Entry.Tagged) return if (context.nextIsTo) entry.tags["VBD"] else entry.tags["DEFAULT"]
        }
        return null
    }

    private fun known(word: String): Boolean {
        if (golds.containsKey(word) || silvers.containsKey(word)) return true
        if (!isAlpha(word)) return false
        if (word.length == 1) return true
        if (word == word.uppercase() && golds.containsKey(word.lowercase())) return true
        return word.substring(1) == word.substring(1).uppercase()
    }

    private fun lookup(word: String, context: LexiconContext, stress: Double? = null): String? {
        var key = word
        if (key == key.uppercase() && !golds.containsKey(key)) key = key.lowercase()
        var phonemes = choose(golds[key], context)
        if (phonemes == Pick.Missing) phonemes = choose(silvers[key], context)
        if (phonemes !is Pick.Value) return spell(key)
        return applyStress(phonemes.phonemes, stress)
    }

    private fun plural(stem: String?): String? {
        if (stem.isNullOrEmpty()) return null
        val last = stem.last()
        if (last in "ptkfθ") return "${stem}s"
        if (last in "szʃʒʧʤ") return "$stem${if (british) "ɪ" else "ᵻ"}z"
        return "${stem}z"
    }

    private fun past(stem: String?): String? {
        if (stem.isNullOrEmpty()) return null
        val last = stem.last()
        if (last in "pkfθʃsʧ") return "${stem}t"
        if (last == 'd') return "$stem${if (british) "ɪ" else "ᵻ"}d"
        if (last != 't') return "${stem}d"
        if (british || stem.length < 2) return "${stem}ɪd"
        if (stem[stem.length - 2] in US_TAUS) return "${stem.dropLast(1)}ɾᵻd"
        return "${stem}ᵻd"
    }

    private fun progressive(stem: String?): String? {
        if (stem.isNullOrEmpty()) return null
        if (british) return if (stem.last() in "əː") null else "${stem}ɪŋ"
        if (stem.length > 1 && stem.last() == 't' && stem[stem.length - 2] in US_TAUS) return "${stem.dropLast(1)}ɾɪŋ"
        return "${stem}ɪŋ"
    }

    private fun stemS(word: String, context: LexiconContext): String? {
        if (word.length < 3 || !word.endsWith("s")) return null
        val stem = when {
            !word.endsWith("ss") && known(word.dropLast(1)) -> word.dropLast(1)
            (word.endsWith("'s") || (word.length > 4 && word.endsWith("es") && !word.endsWith("ies"))) && known(word.dropLast(2)) -> word.dropLast(2)
            word.length > 4 && word.endsWith("ies") && known("${word.dropLast(3)}y") -> "${word.dropLast(3)}y"
            else -> return null
        }
        return plural(lookup(stem, context))
    }

    private fun stemEd(word: String, context: LexiconContext): String? {
        if (word.length < 4 || !word.endsWith("d")) return null
        val stem = when {
            !word.endsWith("dd") && known(word.dropLast(1)) -> word.dropLast(1)
            word.length > 4 && word.endsWith("ed") && !word.endsWith("eed") && known(word.dropLast(2)) -> word.dropLast(2)
            else -> return null
        }
        return past(lookup(stem, context))
    }

    private fun stemIng(word: String, context: LexiconContext): String? {
        if (word.length < 5 || !word.endsWith("ing")) return null
        val stem = when {
            word.length > 5 && known(word.dropLast(3)) -> word.dropLast(3)
            known("${word.dropLast(3)}e") -> "${word.dropLast(3)}e"
            word.length > 5 && DOUBLED_ING.containsMatchIn(word) && known(word.dropLast(4)) -> word.dropLast(4)
            else -> return null
        }
        return progressive(lookup(stem, context, 0.5))
    }

    /** Phonemes for one word, or null when the lexicon does not know it. */
    fun word(text: String, context: LexiconContext): String? {
        var word = text.replace('‘', '\'').replace('’', '\'')
        special(word, context)?.let { return it }
        val lower = word.lowercase()
        if (word.length > 1 && isAlpha(word.replace("'", "")) && word != lower && !golds.containsKey(word) && !silvers.containsKey(word) &&
            (word == word.uppercase() || word.substring(1) == word.substring(1).lowercase()) &&
            (golds.containsKey(lower) || silvers.containsKey(lower) ||
                stemS(lower, context) != null || stemEd(lower, context) != null || stemIng(lower, context) != null)
        ) {
            word = lower
        }
        if (known(word)) return lookup(word, context)
        if (word.endsWith("s'") && known("${word.dropLast(2)}'s")) return lookup("${word.dropLast(2)}'s", context)
        if (word.endsWith("'") && known(word.dropLast(1))) return lookup(word.dropLast(1), context)
        return stemS(word, context) ?: stemEd(word, context) ?: stemIng(word, context)
    }

    companion object {
        const val PRIMARY = 'ˈ'
        const val SECONDARY = 'ˌ'
        private const val VOWELS = "AIOQWYaiuæɑɒɔəɛɜɪʊʌᵻ"
        private const val CONSONANTS = "bdfhjklmnpstvwzðŋɡɹɾʃʒʤʧθ"
        private const val US_TAUS = "AIOWYiuæɑəɛɪɹʊʌ"
        private const val PUNCTUATION = ";:,.!?—…"
        private val ALPHA = Regex("""^[A-Za-z]+\z""")
        private val DOUBLED_ING = Regex("""([bcdgklmnprstvxz])\1ing\z|cking\z""")

        private fun isAlpha(word: String) = ALPHA.matches(word)

        private fun capitalize(word: String): String =
            if (word.isEmpty()) word else word.substring(0, 1).uppercase() + word.substring(1).lowercase()

        private fun grow(entries: Map<String, Entry>): Map<String, Entry> {
            val map = HashMap<String, Entry>(entries.size * 2)
            map.putAll(entries)
            for ((key, value) in entries) {
                if (key.length < 2) continue
                if (key == key.lowercase()) {
                    val capital = capitalize(key)
                    if (key != capital && !map.containsKey(capital)) map[capital] = value
                } else if (key == capitalize(key.lowercase()) && !map.containsKey(key.lowercase())) {
                    map[key.lowercase()] = value
                }
            }
            return map
        }

        /** Moves each stress mark to just before the vowel it stresses. */
        private fun restress(phonemes: String): String {
            class Item(val ch: Char, var at: Double)
            val chars = phonemes.mapIndexed { i, ch -> Item(ch, i.toDouble()) }
            for (item in chars) {
                if (item.ch != PRIMARY && item.ch != SECONDARY) continue
                val vowel = chars.firstOrNull { it.at >= item.at && it.ch in VOWELS }
                if (vowel != null) item.at = vowel.at - 0.5
            }
            return chars.sortedBy { it.at }.joinToString("") { it.ch.toString() }
        }

        fun applyStress(phonemes: String, stress: Double?): String {
            if (stress == null) return phonemes
            val hasVowel = phonemes.any { it in VOWELS }
            val marked = PRIMARY in phonemes || SECONDARY in phonemes
            if (stress < -1) return phonemes.replace(PRIMARY.toString(), "").replace(SECONDARY.toString(), "")
            if (stress == -1.0 || ((stress == 0.0 || stress == -0.5) && PRIMARY in phonemes)) {
                return phonemes.replace(SECONDARY.toString(), "").replace(PRIMARY, SECONDARY)
            }
            if ((stress == 0.0 || stress == 0.5 || stress == 1.0) && !marked) return if (hasVowel) restress(SECONDARY + phonemes) else phonemes
            if (stress >= 1 && PRIMARY !in phonemes && SECONDARY in phonemes) return phonemes.replace(SECONDARY, PRIMARY)
            if (stress > 1 && !marked) return if (hasVowel) restress(PRIMARY + phonemes) else phonemes
            return phonemes
        }

        /** Whether phonemes start with a vowel sound (startsWithVowel in lexicon.ts). */
        fun onset(phonemes: String?): Onset {
            for (ch in phonemes ?: "") {
                if (ch in VOWELS) return Onset.VOWEL
                if (ch in CONSONANTS) return Onset.CONSONANT
                if (ch in PUNCTUATION) return Onset.BOUNDARY
            }
            return Onset.UNKNOWN
        }
    }
}

/** A small, allocation-light JSON reader for the lexicons: objects, arrays, strings, numbers, booleans, null. */
internal class JsonReader(private val text: String) {
    private var at = 0

    fun readValue(): Any? {
        skip()
        return when (val ch = text[at]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            'n' -> { expect("null"); null }
            't' -> { expect("true"); true }
            'f' -> { expect("false"); false }
            else -> if (ch == '-' || ch in '0'..'9') readNumber() else error("Unexpected '$ch' at $at")
        }
    }

    private fun skip() {
        while (at < text.length && text[at].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) at++
    }

    private fun expect(word: String) {
        check(text.startsWith(word, at)) { "Expected $word at $at" }
        at += word.length
    }

    private fun readObject(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        at++
        skip()
        if (text[at] == '}') { at++; return result }
        while (true) {
            skip()
            val key = readString()
            skip()
            check(text[at] == ':') { "Expected ':' at $at" }
            at++
            result[key] = readValue()
            skip()
            when (text[at++]) {
                ',' -> continue
                '}' -> return result
                else -> error("Expected ',' or '}' at ${at - 1}")
            }
        }
    }

    private fun readArray(): List<Any?> {
        val result = ArrayList<Any?>()
        at++
        skip()
        if (text[at] == ']') { at++; return result }
        while (true) {
            result.add(readValue())
            skip()
            when (text[at++]) {
                ',' -> continue
                ']' -> return result
                else -> error("Expected ',' or ']' at ${at - 1}")
            }
        }
    }

    private fun readNumber(): Double {
        val start = at
        while (at < text.length && text[at] in "+-0123456789.eE") at++
        return text.substring(start, at).toDouble()
    }

    private fun readString(): String {
        check(text[at] == '"') { "Expected a string at $at" }
        at++
        val start = at
        while (text[at] != '"' && text[at] != '\\') at++
        if (text[at] == '"') return text.substring(start, at++)
        val out = StringBuilder(at - start + 16).append(text, start, at)
        while (true) {
            when (val ch = text[at++]) {
                '"' -> return out.toString()
                '\\' -> when (val escaped = text[at++]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> { out.append(text.substring(at, at + 4).toInt(16).toChar()); at += 4 }
                    else -> out.append(escaped)
                }
                else -> out.append(ch)
            }
        }
    }
}
