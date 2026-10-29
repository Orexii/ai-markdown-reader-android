package com.orexii.aimarkdownreader.core

/**
 * Kotlin port of the extension's src/engine/pronounce.ts: turns one display word into text for
 * eSpeak or exact phonemes. Kept line-for-line equivalent; ParityTest checks it against the
 * TypeScript. One intended difference: lookups only see real table entries (the TypeScript tables are
 * plain objects, so words such as "constructor" reach Object.prototype).
 */
sealed interface Segment {
    data class Text(val text: String) : Segment
    data class Phonemes(val phonemes: String) : Segment
}

enum class Accent { US, GB }

object Pronounce {
    // Letter names as [onset, nucleus...] so the stress mark lands before the vowel, as eSpeak writes it.
    private val LETTERS: Map<Char, Pair<String, String>> = mapOf(
        'A' to ("" to "eɪ"), 'B' to ("b" to "iː"), 'C' to ("s" to "iː"), 'D' to ("d" to "iː"), 'E' to ("" to "iː"),
        'F' to ("" to "ɛf"), 'G' to ("dʒ" to "iː"), 'H' to ("" to "eɪtʃ"), 'I' to ("" to "aɪ"), 'J' to ("dʒ" to "eɪ"),
        'K' to ("k" to "eɪ"), 'L' to ("" to "ɛl"), 'M' to ("" to "ɛm"), 'N' to ("" to "ɛn"), 'O' to ("" to "oʊ"),
        'P' to ("p" to "iː"), 'Q' to ("kj" to "uː"), 'R' to ("" to "ɑːɹ"), 'S' to ("" to "ɛs"), 'T' to ("t" to "iː"),
        'U' to ("j" to "uː"), 'V' to ("v" to "iː"), 'W' to ("d" to "ʌbəljuː"), 'X' to ("" to "ɛks"), 'Y' to ("w" to "aɪ"),
        'Z' to ("z" to "iː"),
    )
    private val BRITISH: Map<Char, Pair<String, String>> = mapOf('O' to ("" to "əʊ"), 'R' to ("" to "ɑː"), 'Z' to ("z" to "ɛd"))

    /** Upper-case words that are normally pronounced as words rather than spelled out. */
    private val WORD_ACRONYMS = setOf(
        "NASA", "NATO", "JSON", "JSONL", "YAML", "TOML", "REST", "CRUD", "RAM", "ROM", "LAN", "WAN", "GIF", "JPEG",
        "SCSI", "POSIX", "CORS", "ASCII", "AJAX", "WASM", "CAPTCHA", "LINQ", "MAUI", "CUDA", "OLAP", "SIM", "PIN",
        "LASER", "RADAR", "SONAR", "UNESCO", "UNICEF", "FIFO", "LIFO", "WYSIWYG", "NAND", "SKU", "SWOT", "OKR",
        "SaaS", "PaaS", "IaaS", "DAX", "DAG", "SOAP", "SAML", "SCIM", "MIME", "TOTP", "FIDO", "GUID", "OK", "TODO",
    )

    private val BUILTIN: Map<String, String> = mapOf(
        "OAuth" to "oh auth", "iOS" to "eye oh ess", "macOS" to "mac oh ess", "iPadOS" to "eye pad oh ess",
        "PostgreSQL" to "post gress Q L", "MySQL" to "my S Q L", "SQLite" to "S Q lite", "NuGet" to "new get",
        "kubectl" to "kube control", "nginx" to "engine x", "GUID" to "goo id", "C#" to "C sharp", "F#" to "F sharp",
        "C++" to "C plus plus", ".NET" to "dot net", "README" to "read me", "stdin" to "standard in",
        "stdout" to "standard out", "stderr" to "standard error", "regex" to "rej ex", "TODO" to "to do",
        "JSONL" to "jason L", "npm" to "N P M", "pnpm" to "P N P M", "WinGet" to "win get", "winget" to "win get",
        "vs" to "versus", "Ctrl" to "control", "Cmd" to "command", "Esc" to "escape", "PgUp" to "page up",
        "PgDn" to "page down",
    )

    private val ABBREVIATIONS: Map<String, String> = mapOf(
        "e.g." to "for example", "i.e." to "that is", "etc." to "et cetera", "vs." to "versus", "approx." to "approximately",
        "cf." to "compare", "mr." to "mister", "mrs." to "missus", "ms." to "miss", "dr." to "doctor", "no." to "number",
        "w/" to "with", "w/o" to "without",
    )

    private val SYMBOLS: Map<String, String> = mapOf(
        "&" to "and", "+" to "plus", "=" to "equals", "%" to "percent", "$" to "dollars", "#" to "number", "@" to "at",
        "<" to "less than", ">" to "greater than", "→" to "to", "->" to "to", "=>" to "to", "<-" to "from", "×" to "times",
        "~" to "about", "≈" to "about", "±" to "plus or minus", "/" to "slash", "↑" to "up", "↓" to "down", "←" to "back",
        // Only reaches here between numbers: elsewhere an asterisk is Markdown and is not spoken.
        "*" to "times",
    )

    /** Arrow and key symbols as key names, for keyboard shortcuts such as Shift+← or <kbd>→</kbd>. */
    val KEY_NAMES: Map<String, String> = mapOf(
        "←" to "left arrow", "→" to "right arrow", "↑" to "up arrow", "↓" to "down arrow", "⌘" to "command",
        "⌥" to "option", "⇧" to "shift", "⏎" to "enter", "↵" to "enter",
    )

    private val SEPARATORS: Map<String, String> = mapOf(
        "." to "dot", "/" to "slash", "\\" to "backslash", "@" to "at", "+" to "plus", "=" to "equals", "#" to "hash",
        "_" to "", "-" to "", ":" to "",
    )

    private val PLURAL_LETTER = Regex("""[sxz]\z|tʃ\z""")

    fun spell(letters: String, accent: Accent = Accent.US, plural: Boolean = false): String {
        val chars = letters.uppercase().filter { it in LETTERS }
        val parts = chars.mapIndexed { i, ch ->
            val (onset, rest) = (if (accent == Accent.GB) BRITISH[ch] else null) ?: LETTERS.getValue(ch)
            "$onset${if (i == chars.length - 1) "ˈ" else "ˌ"}$rest"
        }
        var result = parts.joinToString("")
        if (plural) result += if (PLURAL_LETTER.containsMatchIn(LETTERS.getValue(chars.lastOrNull() ?: 'A').second)) "ɪz" else "z"
        return result
    }

    private val ALL_CAPS = Regex("""^[A-Z]{2,6}\z""")
    private val MIXED_ACRONYM = Regex("""^[A-Z][A-Za-z]{1,3}\z""")
    private val CAPITALIZED = Regex("""^[A-Z][a-z]+\z""")

    private fun isAcronym(word: String): Boolean {
        if (word in WORD_ACRONYMS) return false
        if (ALL_CAPS.matches(word)) return true
        // Short mixed-case acronyms such as IcM, PhD or IoT.
        return MIXED_ACRONYM.matches(word) && word.count { it in 'A'..'Z' } >= 2 && !CAPITALIZED.matches(word)
    }

    private fun money(symbol: String, amount: String): String {
        val unit = when (symbol) { "$" -> "dollar"; "£" -> "pound"; else -> "euro" }
        val parts = amount.replace(",", "").split(".")
        val whole = parts[0]
        val fraction = parts.getOrNull(1)
        val units = "$whole $unit${if (whole == "1") "" else "s"}"
        if (fraction.isNullOrEmpty()) return units
        val cents = jsParseInt(fraction.padEnd(2, '0').substring(0, 2))
        val coin = if (symbol == "£") (if (cents == 1) "penny" else "pence") else if (cents == 1) "cent" else "cents"
        return if (cents != 0) "$units and $cents $coin" else units
    }

    private fun year(value: String): String {
        val number = jsParseInt(value.substring(0, 4))
        val suffix = if (value.endsWith("s")) "s" else ""
        if (number < 1100 || number > 2099 || number % 1000 < 10) return value
        val left = value.substring(0, 2)
        val right = jsParseInt(value.substring(2, 4))
        if (number % 1000 >= 100 && right == 0) return "$left hundred$suffix"
        if (number % 1000 >= 100 && right < 10) return "$left oh $right$suffix"
        return "$left $right$suffix"
    }

    /** Units said in full after a number, as [plural, singular]. Single letters are only units after a number. */
    private val UNITS: Map<String, Pair<String, String>> = mapOf(
        "kg" to ("kilograms" to "kilogram"), "g" to ("grams" to "gram"), "mg" to ("milligrams" to "milligram"),
        "lb" to ("pounds" to "pound"), "lbs" to ("pounds" to "pound"), "oz" to ("ounces" to "ounce"),
        "km" to ("kilometers" to "kilometer"), "m" to ("meters" to "meter"), "cm" to ("centimeters" to "centimeter"),
        "mm" to ("millimeters" to "millimeter"), "mi" to ("miles" to "mile"), "ft" to ("feet" to "foot"),
        "mph" to ("miles per hour" to "mile per hour"), "ml" to ("milliliters" to "milliliter"),
        "mL" to ("milliliters" to "milliliter"), "h" to ("hours" to "hour"), "hr" to ("hours" to "hour"),
        "hrs" to ("hours" to "hour"), "min" to ("minutes" to "minute"), "s" to ("seconds" to "second"),
        "sec" to ("seconds" to "second"), "ms" to ("milliseconds" to "millisecond"), "µs" to ("microseconds" to "microsecond"),
        "ns" to ("nanoseconds" to "nanosecond"), "KB" to ("kilobytes" to "kilobyte"), "MB" to ("megabytes" to "megabyte"),
        "GB" to ("gigabytes" to "gigabyte"), "TB" to ("terabytes" to "terabyte"), "PB" to ("petabytes" to "petabyte"),
        "KiB" to ("kibibytes" to "kibibyte"), "MiB" to ("mebibytes" to "mebibyte"), "GiB" to ("gibibytes" to "gibibyte"),
        "TiB" to ("tebibytes" to "tebibyte"), "PiB" to ("pebibytes" to "pebibyte"),
        "Kbps" to ("kilobits per second" to "kilobit per second"), "Mbps" to ("megabits per second" to "megabit per second"),
        "Gbps" to ("gigabits per second" to "gigabit per second"), "Hz" to ("hertz" to "hertz"),
        "kHz" to ("kilohertz" to "kilohertz"), "MHz" to ("megahertz" to "megahertz"), "GHz" to ("gigahertz" to "gigahertz"),
        "°C" to ("degrees Celsius" to "degree Celsius"), "°F" to ("degrees Fahrenheit" to "degree Fahrenheit"),
    )

    /** Units that are unambiguous when written straight after a number, as in 8kg or 100GiB ("80s" and "5G" are not). */
    private val ATTACHED_UNITS: Set<String> = UNITS.keys.filter { it.length > 1 && it !in setOf("mi", "ml", "mL", "min", "sec") }.toSet()

    private fun unitName(unit: String, amount: String): String? {
        val names = UNITS[unit] ?: return null
        return if (amount.replace(",", "") == "1") names.second else names.first
    }

    private val ATTACHED = Regex("""^([0-9][0-9,]*(?:\.[0-9]+)?)([A-Za-zµ°]+)\z""")
    private val MONEY = Regex("""^([$£€])([0-9][0-9,]*(?:\.[0-9]+)?)\z""")
    private val TIME = Regex("""^([0-9]{1,2}):([0-9]{2})\z""")
    private val YEAR = Regex("""^[0-9]{4}s?\z""")
    private val RANGE = Regex("""^([0-9][0-9,]*)[-–]([0-9][0-9,]*)\z""")
    private val MULTIPLIER = Regex("""^([0-9]+(?:\.[0-9]+)?)[xX]\z""")
    private val VERSION = Regex("""^[vV]([0-9]+(?:\.[0-9]+){0,3})\z""")
    private val ABOUT = Regex("""^~([0-9].*)\z""")
    private val PERCENT = Regex("""^([0-9][0-9,]*(?:\.[0-9]+)?)%\z""")
    private val THOUSANDS = Regex("""^[0-9]{1,3}(,[0-9]{3})+(\.[0-9]+)?\z""")

    /** Normalizes numbers, money, times and ranges to words eSpeak pronounces naturally. */
    fun normalizeNumber(word: String): String? {
        ATTACHED.find(word)?.let { match ->
            val (amount, unit) = match.destructured
            if (unit in ATTACHED_UNITS) return "${amount.replace(",", "").split(".").joinToString(" point ")} ${unitName(unit, amount)}"
        }
        MONEY.find(word)?.let { return money(it.groupValues[1], it.groupValues[2]) }
        TIME.find(word)?.let {
            val hours = it.groupValues[1].toInt()
            val minutes = it.groupValues[2].toInt()
            return when {
                minutes == 0 -> "$hours o'clock"
                minutes < 10 -> "$hours oh $minutes"
                else -> "$hours $minutes"
            }
        }
        if (YEAR.matches(word)) return year(word)
        RANGE.find(word)?.let { return "${it.groupValues[1].replace(",", "")} to ${it.groupValues[2].replace(",", "")}" }
        MULTIPLIER.find(word)?.let { return "${it.groupValues[1]} times" }
        VERSION.find(word)?.let { return "version ${it.groupValues[1].split(".").joinToString(" point ")}" }
        ABOUT.find(word)?.let { return "about ${it.groupValues[1]}" }
        PERCENT.find(word)?.let { return "${it.groupValues[1].replace(",", "").split(".").joinToString(" point ")} percent" }
        if (THOUSANDS.matches(word)) return word.replace(",", "")
        return null
    }

    private val IDENTIFIER_PART = Regex("""[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|[0-9]+(?:\.[0-9]+)*|[^A-Za-z0-9]""")
    private val IDENTIFIER = Regex("""[a-z][A-Z]|[A-Z]{2,}[a-z]|[A-Za-z0-9][._/\\@#+=][A-Za-z0-9]|^[._/]""")
    private val URL_START = Regex("""^https?:""", RegexOption.IGNORE_CASE)

    private fun identifierParts(word: String): List<String> =
        IDENTIFIER_PART.findAll(word).map { it.value }.toList().ifEmpty { listOf(word) }

    private fun looksLikeIdentifier(word: String): Boolean = IDENTIFIER.containsMatchIn(word) && !URL_START.containsMatchIn(word)

    private fun mergeText(segments: List<Segment>): List<Segment> {
        val merged = mutableListOf<Segment>()
        for (segment in segments) {
            if (segment is Segment.Text && jsTrim(segment.text).isEmpty()) continue
            val previous = merged.lastOrNull()
            if (segment is Segment.Text && previous is Segment.Text) merged[merged.size - 1] = Segment.Text("${previous.text} ${segment.text}")
            else if (segment !is Segment.Text || segment.text.isNotEmpty()) merged.add(segment)
        }
        return merged
    }

    private val AMOUNT_BEFORE = Regex("""([0-9][0-9,]*(?:\.[0-9]+)?)\)?\z""")
    private val DASH = Regex("[-–]")
    private val IPA_ENTRY = Regex("""^/(.+)/\z""")
    private val POSSESSIVE = Regex("""^([A-Z][A-Za-z]{1,5})(?:'s|’s|s)\z""")
    private val CODED = Regex("""^([A-Z]{1,4})([0-9]+)\z""")
    private val SHORTCUT = Regex("""^[A-Za-z]+(?:\+[A-Za-z]+)*\+[A-Za-z0-9←→↑↓⌘⌥⇧⏎↵]\z""")
    private val ARROW_SHORTCUT = Regex("""^(?:[A-Za-z]+\+)+[←→↑↓]\z""")
    private val LETTERS_ONLY = Regex("""^[A-Za-z]+\z""")
    private val DIGIT_START = Regex("""^[0-9]""")

    /**
     * Converts one display word into pronunciation segments: plain text for eSpeak, or exact phonemes for
     * spelled acronyms and IPA dictionary entries. With textOnly, spelled acronyms become spaced letters so
     * system voices can say them.
     */
    fun pronounce(
        word: String,
        accent: Accent = Accent.US,
        dictionary: Map<String, String> = emptyMap(),
        textOnly: Boolean = false,
        previous: String? = null,
    ): List<Segment> {
        val value = jsTrim(word)
        if (value.isEmpty()) return emptyList()
        // A unit after a number: "8–11 kg" is "8 to 11 kilograms", "1 GB" is "1 gigabyte".
        val amount = previous?.let { AMOUNT_BEFORE.find(jsTrim(it))?.groupValues?.get(1) }
        if (amount != null && value in UNITS && value !in dictionary) {
            return listOf(Segment.Text(unitName(value, if (DASH.containsMatchIn(previous)) "2" else amount)!!))
        }
        fun letters(text: String, plural: Boolean = false): Segment =
            if (textOnly) Segment.Text("${text.uppercase().toCharArray().joinToString(" ")}${if (plural) "'s" else ""}")
            else Segment.Phonemes(spell(text, accent, plural))

        val custom = dictionary[value] ?: dictionary[value.lowercase()]
        if (custom != null) {
            val ipa = IPA_ENTRY.find(jsTrim(custom)) ?: return listOf(Segment.Text(custom))
            return if (textOnly) listOf(Segment.Text(value)) else listOf(Segment.Phonemes(ipa.groupValues[1]))
        }
        BUILTIN[value]?.let { if (it.isNotEmpty()) return listOf(Segment.Text(it)) }
        ABBREVIATIONS[value.lowercase()]?.let { if (it.isNotEmpty()) return listOf(Segment.Text(it)) }
        SYMBOLS[value]?.let { if (it.isNotEmpty()) return listOf(Segment.Text(it)) }
        normalizeNumber(value)?.let { return listOf(Segment.Text(it)) }

        val possessive = POSSESSIVE.find(value)
        if (possessive != null && isAcronym(possessive.groupValues[1])) return listOf(letters(possessive.groupValues[1], true))
        if (isAcronym(value)) return listOf(letters(value))
        // Letter and digit names such as EC2, S3 or M365.
        CODED.find(value)?.let { return listOf(letters(it.groupValues[1]), Segment.Text(it.groupValues[2])) }

        // Keyboard shortcuts such as Ctrl+Shift+→: arrows and symbols are key names here.
        if (SHORTCUT.matches(value) || ARROW_SHORTCUT.matches(value)) {
            return mergeText(value.split("+").flatMapIndexed { k, key ->
                val name: Segment = when {
                    KEY_NAMES[key] != null -> Segment.Text(KEY_NAMES.getValue(key))
                    !BUILTIN[key].isNullOrEmpty() -> Segment.Text(BUILTIN.getValue(key))
                    isAcronym(key) && key.length > 1 -> letters(key)
                    else -> Segment.Text(key)
                }
                (if (k > 0) listOf(Segment.Text("plus")) else emptyList()) + name
            })
        }
        if (!looksLikeIdentifier(value)) return listOf(Segment.Text(value))
        val segments = mutableListOf<Segment>()
        for (part in identifierParts(value)) {
            if (LETTERS_ONLY.matches(part)) {
                val builtin = BUILTIN[part]
                when {
                    !builtin.isNullOrEmpty() -> segments.add(Segment.Text(builtin))
                    isAcronym(part) -> segments.add(letters(part))
                    else -> segments.add(Segment.Text(part))
                }
            } else if (DIGIT_START.containsMatchIn(part)) {
                segments.add(Segment.Text(part.split(".").joinToString(" point ")))
            } else {
                SEPARATORS[part]?.let { segments.add(Segment.Text(it)) }
            }
        }
        return mergeText(segments)
    }

    /** Plain-text rendering for system voices, which cannot take phoneme input. */
    fun pronounceAsText(word: String, dictionary: Map<String, String> = emptyMap(), previous: String? = null): String =
        jsTrim(pronounce(word, Accent.US, dictionary, true, previous).joinToString(" ") { if (it is Segment.Text) it.text else "" })

    /** Builds a pronunciation dictionary from user entries, ignoring invalid ones. */
    fun dictionaryFrom(entries: Map<String, String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for ((key, value) in entries) {
            val k = jsTrim(key)
            val v = jsTrim(value)
            if (k.isEmpty() || v.isEmpty()) continue
            result[k] = v
            if (k.lowercase() !in result) result[k.lowercase()] = v
        }
        return result
    }
}

/** JavaScript's String.prototype.trim: strips WhiteSpace and LineTerminator characters. */
internal fun jsTrim(text: String): String = text.trim { it.isJsWhitespace() }

internal fun Char.isJsWhitespace(): Boolean = when (this) {
    '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
    else -> this in '\u2000'..'\u200A'
}

/** parseInt(text, 10) for strings of ASCII digits; returns 0 for an empty prefix (callers never pass one). */
internal fun jsParseInt(text: String): Int {
    val digits = text.takeWhile { it in '0'..'9' }
    return if (digits.isEmpty()) 0 else digits.toInt()
}
