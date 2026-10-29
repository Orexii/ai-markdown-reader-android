package com.orexii.aimarkdownreader

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.orexii.aimarkdownreader.core.EngineKind
import com.orexii.aimarkdownreader.core.ModelCatalog
import com.orexii.aimarkdownreader.data.EngineChoice
import com.orexii.aimarkdownreader.reader.VoiceState
import com.orexii.aimarkdownreader.speech.NaturalVoice
import com.orexii.aimarkdownreader.core.SpeechInput
import com.orexii.aimarkdownreader.core.SynthesisWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

private const val TAG = "AmrE2E"

private fun letters(text: String) = text.filter { it.isLetterOrDigit() }.lowercase()

private fun until(what: String, timeoutMs: Long, check: () -> Boolean) {
    val started = System.currentTimeMillis()
    while (!check()) {
        if (System.currentTimeMillis() - started > timeoutMs) throw AssertionError("Timed out waiting for $what")
        Thread.sleep(50)
    }
}

/** The real model on this device: audio, word times and speed (the extension's CMR_MODEL_DIR test). */
@RunWith(AndroidJUnit4::class)
class RealModelDeviceTest {
    @Test
    fun theNaturalVoiceSpeaksFasterThanRealTime() {
        assumeTrue("Push the model to /data/local/tmp/amr-model (CI does this).", DeviceFixtures.installModel())
        val app = DeviceFixtures.app.applicationContext as App
        val engine = NaturalVoice(DeviceFixtures.app, app.models).create(ModelCatalog.Quality.COMPACT, 0)
        val utterances = DeviceFixtures.documents.getValue("wagyu-rows-announce").utterances.take(9)
        val started = System.nanoTime()
        var audio = 0.0
        for (batch in utterances.chunked(3)) {
            val segments = engine.synthesize(batch.map { u -> SpeechInput(u.words.map { SynthesisWord(it.text, it.sep) }, u.pauseAfter) }, "af_heart", emptyMap())
            assertEquals(batch.size, segments.size)
            for (segment in segments) {
                var energy = 0.0
                for (sample in segment.pcm) energy += (sample / 32768.0) * (sample / 32768.0)
                assertTrue("audible speech", sqrt(energy / segment.pcm.size) > 0.01)
                val times = segment.wordTimes
                for (i in times.indices step 2) if (times[i] >= 0) assertTrue(times[i + 1] >= times[i] && times[i + 1] <= segment.duration + 1e-6)
            }
            audio += segments.sumOf { it.duration }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        Log.i(TAG, "Natural voice on this device: %.1f s of speech in %.1f s (%.1f× real time)".format(audio, seconds, audio / seconds))
        engine.close()
    }
}

/**
 * The whole app, as the extension's tools/e2e.mjs tests VS Code: real taps open the Wagyu field guide and press
 * Play; at 3× the page must highlight the word being spoken, inside the highlighted sentence, with at most one
 * stall.
 */
@RunWith(AndroidJUnit4::class)
class ReaderE2ETest {
    private val device = UiDevice.getInstance(DeviceFixtures.instrumentation)
    private val app get() = DeviceFixtures.app.applicationContext as App

    @Test
    fun readsTheWagyuGuideWithTheSpokenWordHighlighted() {
        assumeTrue("Push the model to /data/local/tmp/amr-model (CI does this).", DeviceFixtures.installModel())
        val rate = DeviceFixtures.instrumentation.let { androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("rate") }?.toDouble() ?: 3.0
        runBlocking {
            withContext(Dispatchers.Main) {
                app.settings.update { it.copy(engine = EngineChoice.NATURAL, quality = ModelCatalog.Quality.COMPACT, voice = "af_heart", rate = rate, highlightWords = true, follow = true) }
            }
            app.library.savePosition("asset:///samples/wagyu.md", null)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            device.wait(Until.findObject(By.res("sample")), 15_000)?.click() ?: throw AssertionError("The home screen has no sample button")
            until("the narration", 30_000) { app.reader.state.value.loaded }
            until("the natural voice", 60_000) { app.reader.voice.value == VoiceState.Ready(EngineKind.NATURAL) }
            device.wait(Until.findObject(By.res("play")), 10_000).click()
            until("speech with a highlighted word", 60_000) {
                val s = app.reader.state.value
                s.playing && !s.waiting && s.word >= 0 && app.reader.pageState.value?.word?.isNotEmpty() == true
            }
            val start = app.reader.state.value
            var checks = 0
            var matches = 0
            var inside = 0
            val words = HashSet<Pair<Int, Int>>()
            val began = System.currentTimeMillis()
            while (System.currentTimeMillis() - began < 25_000) {
                val s = app.reader.state.value
                val page = app.reader.pageState.value
                val expected = app.reader.expectedWord(s.index, s.word)
                if (s.playing && !s.waiting && page != null && expected != null && page.word.isNotEmpty()) {
                    checks++
                    if (letters(page.word) == letters(expected)) matches++
                    if (page.sentence.contains(page.word.trimEnd('.', ',', ';', ':', '!', '?'))) inside++
                }
                if (s.word >= 0) words += s.index to s.word
                Thread.sleep(40)
            }
            val end = app.reader.state.value
            val read = end.elapsed - start.elapsed
            Log.i(TAG, "At ${rate}×: read %.0f s of text in 25 s; passage ${end.index} of ${end.count}; ${words.size} words highlighted; page matched the spoken word in $matches of $checks checks; stalls ${end.stalls}".format(read))
            assertTrue("at most one stall at ${rate}× (the extension's bar): ${end.stalls}", end.stalls <= 1)
            assertTrue("reading moved on: $read s", read > 25 * rate * 0.6)
            assertTrue("words were highlighted one after another: ${words.size}", words.size > 30)
            assertTrue("the page highlights the word being spoken: $matches of $checks", checks > 50 && matches >= checks * 0.7)
            assertTrue("the word is inside the highlighted sentence: $inside of $checks", inside >= checks * 0.9)

            device.findObject(By.res("play")).click()
            until("pause", 10_000) { !app.reader.state.value.playing }
            val paused = app.reader.state.value.elapsed
            Thread.sleep(1500)
            assertEquals("paused means paused", paused, app.reader.state.value.elapsed, 0.3)
        }
    }
}
