package com.orexii.aimarkdownreader.core

/**
 * Rewrites eSpeak NG IPA in the phoneme set Kokoro was trained on (misaki). Port of toMisaki and
 * postprocess in the extension's src/engine/kokoro.ts; the order of the rewrites matters.
 */
object Misaki {
    private val SYLLABIC_L = Regex("""əl(?=[sz]?(?:\s|\z|[;:,.!?—…"“”()]))""")
    private val X_C = Regex("[xç]")
    private val HUNDRED = Regex("""(?<=[a-zɹː])(?=hˈʌndɹɪd)""")
    private val SPACE_Z = Regex(""" z(?=[;:,.!?¡¿—…"«»“” ]|\z)""")
    private val NINETY = Regex("""(?<=nˈaɪn)ti(?!ː)""")
    private val WHITESPACE = Regex("""\s+""")

    fun toMisaki(phonemes: String, accent: Accent): String {
        var out = phonemes
            .replace("ʔˌn\u0329", "ʔn").replace("ʔn\u0329", "ʔn")
            .replace("aɪ", "I").replace("aʊ", "W").replace("eɪ", "A").replace("ɔɪ", "Y")
            .replace("dʒ", "ʤ").replace("tʃ", "ʧ")
            .replace("ʲo", "jo").replace("ʲə", "jə").replace("ʲ", "")
            .replace("ɚɹ", "əɹ").replace("ɚ", "əɹ").replace("r", "ɹ").replace(X_C, "k").replace("ɐ", "ə").replace("ɬ", "l")
            .replace("\u0303", "")
            // Syllabic l at the end of a word, as in "table" or "tables".
            .replace(SYLLABIC_L, "ᵊl")
        out = if (accent == Accent.GB) {
            out.replace("eə", "ɛː").replace("iə", "ɪə").replace("əʊ", "Q")
        } else {
            out.replace("oʊ", "O").replace("ɜːɹ", "ɜɹ").replace("ɜː", "ɜɹ").replace("ɪə", "iə").replace("ː", "")
        }
        return out.replace("e", "A").replace("o", "ɔ").replace("ʔ", "t")
    }

    /** Fixes used by the official kokoro.js frontend for eSpeak NG output, then misaki's symbols. */
    fun postprocess(phonemes: String, accent: Accent): String {
        var out = phonemes
            .replace("kəkˈoːɹoʊ", "kˈoʊkəɹoʊ")
            .replace("kəkˈɔːɹəʊ", "kˈəʊkəɹəʊ")
            .replace(HUNDRED, " ")
            .replace(SPACE_Z, "z")
        if (accent == Accent.US) out = out.replace(NINETY, "di")
        return jsTrim(toMisaki(out, accent).replace(WHITESPACE, " "))
    }
}
