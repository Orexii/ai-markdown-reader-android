package com.orexii.aimarkdownreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin pronunciation rules and lexicon give exactly what the extension's TypeScript gives. */
class PronounceParityTest {
    private fun segment(json: kotlinx.serialization.json.JsonElement): Segment {
        val obj = json.obj()
        return obj["phonemes"]?.let { Segment.Phonemes(it.string()) } ?: Segment.Text(obj.getValue("text").string())
    }

    @Test
    fun pronounceMatchesTheExtension() {
        val golden = Goldens.json("pronounce.json").obj()
        val dictionary = golden.getValue("dictionary").obj().mapValues { it.value.string() }
        val cases = golden.getValue("cases").array()
        assertTrue("goldens have cases", cases.size > 1000)
        val failures = mutableListOf<String>()
        for (case in cases) {
            val c = case.obj()
            val word = c.getValue("word").string()
            val previous = c.getValue("previous").stringOrNull()
            val accent = if (c.getValue("accent").string() == "gb") Accent.GB else Accent.US
            val dict = if (c.getValue("dictionary").bool()) dictionary else emptyMap()
            val expected = c.getValue("segments").array().map(::segment)
            val actual = Pronounce.pronounce(word, accent, dict, false, previous)
            if (expected != actual) failures += "pronounce($word, $accent, previous=$previous): expected $expected, got $actual"
            val text = Pronounce.pronounceAsText(word, dict, previous)
            if (c.getValue("text").string() != text) failures += "pronounceAsText($word, previous=$previous): expected ${c["text"]}, got $text"
        }
        assertEquals(failures.take(20).joinToString("\n"), 0, failures.size)
    }

    @Test
    fun lexiconMatchesTheExtension() {
        val cases = Goldens.json("lexicon.json").array()
        assertTrue(cases.size > 10000)
        val failures = mutableListOf<String>()
        for (case in cases) {
            val (word, accent, nextVowel, nextIsTo, phonemes) = case.array().toList()
            val lexicon = Goldens.lexicon(if (accent.string() == "gb") Accent.GB else Accent.US)
            val actual = lexicon.word(word.string(), LexiconContext(nextVowel.boolOrNull(), nextIsTo.bool()))
            if (actual != phonemes.stringOrNull()) failures += "${word.string()} (${accent.string()}, next vowel ${nextVowel}, to $nextIsTo): expected $phonemes, got $actual"
        }
        assertEquals(failures.take(20).joinToString("\n"), 0, failures.size)
    }

    @Test
    fun builtInsDoNotLeakObjectPrototypeNames() {
        // The TypeScript tables are plain objects, so these words reached Object.prototype there.
        val expected = mapOf("constructor" to "constructor", "toString" to "to String", "valueOf" to "value Of",
            "hasOwnProperty" to "has Own Property", "__proto__" to "proto", "isPrototypeOf" to "is Prototype Of")
        for ((word, spoken) in expected) {
            assertEquals(word, listOf(Segment.Text(spoken)), Pronounce.pronounce(word))
            assertEquals(word, spoken, Pronounce.pronounceAsText(word))
            assertEquals(word, listOf(Segment.Text(spoken)), Pronounce.pronounce(word, previous = "8"))
        }
    }
}
