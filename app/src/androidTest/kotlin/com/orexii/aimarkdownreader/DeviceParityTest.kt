package com.orexii.aimarkdownreader

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orexii.aimarkdownreader.core.Accent
import com.orexii.aimarkdownreader.core.Kokoro
import com.orexii.aimarkdownreader.core.LexiconContext
import com.orexii.aimarkdownreader.core.Pronounce
import com.orexii.aimarkdownreader.core.Segment
import com.orexii.aimarkdownreader.core.SynthesisRequest
import com.orexii.aimarkdownreader.core.SynthesisUtterance
import com.orexii.aimarkdownreader.core.SynthesisWord
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Kotlin port must behave on Android exactly as on the desktop JVM (Android's regular expressions are ICU,
 * not the JDK's) and as the extension's TypeScript: the same goldens, on the device.
 */
@RunWith(AndroidJUnit4::class)
class DeviceParityTest {
    @Test
    fun pronounceMatchesTheExtension() {
        val golden = DeviceFixtures.json("pronounce.json").jsonObject
        val dictionary = golden.getValue("dictionary").jsonObject.mapValues { it.value.text() }
        val failures = mutableListOf<String>()
        for (case in golden.getValue("cases").jsonArray) {
            val c = case.jsonObject
            val word = c.getValue("word").text()
            val previous = c.getValue("previous").textOrNull()
            val accent = if (c.getValue("accent").text() == "gb") Accent.GB else Accent.US
            val dict = if (c.getValue("dictionary").jsonPrimitive.boolean) dictionary else emptyMap()
            val expected = c.getValue("segments").jsonArray.map { s -> s.jsonObject["phonemes"]?.let { Segment.Phonemes(it.text()) } ?: Segment.Text(s.jsonObject.getValue("text").text()) }
            val actual = Pronounce.pronounce(word, accent, dict, false, previous)
            if (expected != actual) failures += "pronounce($word, previous=$previous): expected $expected, got $actual"
            if (c.getValue("text").text() != Pronounce.pronounceAsText(word, dict, previous)) failures += "pronounceAsText($word)"
        }
        assertEquals(failures.take(20).joinToString("\n"), 0, failures.size)
    }

    @Test
    fun lexiconMatchesTheExtension() {
        val failures = mutableListOf<String>()
        for (case in DeviceFixtures.json("lexicon.json").jsonArray) {
            val (word, accent, nextVowel, nextIsTo, phonemes) = case.jsonArray.toList()
            val lexicon = DeviceFixtures.lexicon(if (accent.text() == "gb") Accent.GB else Accent.US)
            val actual = lexicon.word(word.text(), LexiconContext(nextVowel.jsonPrimitive.booleanOrNull, nextIsTo.jsonPrimitive.boolean))
            if (actual != phonemes.textOrNull()) failures += "${word.text()} (${accent.text()}): expected $phonemes, got $actual"
        }
        assertEquals(failures.take(20).joinToString("\n"), 0, failures.size)
    }

    @Test
    fun espeakMatchesThePhonemizerPackage() {
        val failures = mutableListOf<String>()
        val cases = DeviceFixtures.json("espeak.json").jsonArray
        assertTrue(cases.size > 1000)
        for (case in cases) {
            val (language, text, clauses) = case.jsonArray.toList()
            val actual = DeviceFixtures.espeak.phonemize(text.text(), language.text())
            val expected = clauses.jsonArray.map { it.text() }
            if (actual != expected) failures += "${language.text()} '${text.text()}': expected $expected, got $actual"
        }
        assertEquals(failures.take(20).joinToString("\n"), 0, failures.size)
    }

    @Test
    fun synthesisMatchesTheExtension() {
        val golden = DeviceFixtures.json("kokoro.json").jsonObject
        val style = FakeInference.style()
        val failures = mutableListOf<String>()
        val started = System.nanoTime()
        for ((n, case) in golden.getValue("cases").jsonArray.withIndex()) {
            val c = case.jsonObject
            val r = c.getValue("request").jsonObject
            val request = SynthesisRequest(
                utterances = r.getValue("utterances").jsonArray.map { u ->
                    SynthesisUtterance(u.jsonObject.getValue("words").jsonArray.map { w -> SynthesisWord(w.jsonObject.getValue("text").text(), w.jsonObject.getValue("sep").text()) }, u.jsonObject.getValue("pause").jsonPrimitive.double)
                },
                voice = r.getValue("voice").text(),
                dictionary = r["dictionary"]?.jsonArray?.associate { it.jsonArray[0].text() to it.jsonArray[1].text() } ?: emptyMap(),
            )
            val inference = FakeInference()
            val segments = Kokoro(DeviceFixtures::lexicon, DeviceFixtures.espeak, inference) { style }.synthesize(request)
            val runs = c.getValue("runs").jsonArray
            if (runs.size != inference.runs.size) {
                failures += "case $n: ${runs.size} runs expected, ${inference.runs.size} made"
                continue
            }
            runs.forEachIndexed { k, run ->
                val ids = run.jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.double.toLong() }
                if (ids != inference.runs[k].ids) failures += "case $n run $k: ids differ\n  expected $ids\n  got      ${inference.runs[k].ids}"
                if (run.jsonObject.getValue("styleRow").jsonPrimitive.int != inference.runs[k].styleRow) failures += "case $n run $k: style row"
            }
            c.getValue("segments").jsonArray.forEachIndexed { k, segment ->
                val s = segment.jsonObject
                val actual = segments.getOrNull(k)
                when {
                    actual == null -> failures += "case $n: segment $k missing"
                    s.getValue("checksum").text() != FakeInference.checksum(actual.samples) -> failures += "case $n segment $k: audio differs"
                    s.getValue("wordTimes").jsonArray.map { it.jsonPrimitive.double } != actual.wordTimes.toList() -> failures += "case $n segment $k: word times differ"
                }
            }
        }
        Log.i("DeviceParityTest", "Parity of ${golden.getValue("cases").jsonArray.size} requests in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertEquals(failures.take(10).joinToString("\n"), 0, failures.size)
    }
}
