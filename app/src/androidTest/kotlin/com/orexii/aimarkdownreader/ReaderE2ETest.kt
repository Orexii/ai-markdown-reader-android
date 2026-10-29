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

private fun until(what: String, timeoutMs: Long, describe: () -> String = { "" }, check: () -> Boolean) {
    val started = System.currentTimeMillis()
    while (!check()) {
        if (System.currentTimeMillis() - started > timeoutMs) throw AssertionError("Timed out waiting for $what. ${describe()}")
        Thread.sleep(50)
    }
}

/** Seconds of speech the natural voice makes per second on this device, from three passages of the guide. */
private fun measureSpeed(app: App, threads: Int): Double {
    val engine = NaturalVoice(DeviceFixtures.app, app.models).create(ModelCatalog.Quality.COMPACT, threads)
    try {
        val utterances = DeviceFixtures.documents.getValue("wagyu-rows-announce").utterances.take(6)
        engine.synthesize(utterances.take(1).map { u -> SpeechInput(u.words.map { SynthesisWord(it.text, it.sep) }, u.pauseAfter) }, "af_heart", emptyMap())
        val started = System.nanoTime()
        val audio = engine.synthesize(utterances.drop(1).map { u -> SpeechInput(u.words.map { SynthesisWord(it.text, it.sep) }, u.pauseAfter) }, "af_heart", emptyMap())
            .sumOf { it.duration }
        return audio / ((System.nanoTime() - started) / 1e9)
    } finally {
        engine.close()
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
 * stall. The rate argument sets the speed; "auto" (CI's emulator, which has no fast vector instructions) picks the
 * fastest speed the device can synthesize with room to spare.
 */
@RunWith(AndroidJUnit4::class)
class ReaderE2ETest {
    private val device = UiDevice.getInstance(DeviceFixtures.instrumentation)
    private val app get() = DeviceFixtures.app.applicationContext as App

    @Test
    fun readsTheWagyuGuideWithTheSpokenWordHighlighted() {
        assumeTrue("Push the model to /data/local/tmp/amr-model (CI does this).", DeviceFixtures.installModel())
        val argument = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("rate") ?: "3"
        // On a slow device (CI's emulator) use every core and the fastest speed it keeps up with, with room to spare.
        val threads = if (argument == "auto") Runtime.getRuntime().availableProcessors() else 0
        val rate = if (argument == "auto") {
            val speed = measureSpeed(app, threads)
            (Math.round(speed / 1.5 * 4) / 4.0).coerceIn(0.25, 3.0).also { Log.i(TAG, "Synthesis runs at %.1f× real time here; testing at $it×".format(speed)) }
        } else argument.toDouble()
        runBlocking {
            withContext(Dispatchers.Main) {
                app.settings.update {
                    it.copy(engine = EngineChoice.NATURAL, quality = ModelCatalog.Quality.COMPACT, voice = "af_heart", rate = rate, threads = threads, highlightWords = true, follow = true)
                }
            }
            app.library.savePosition("asset:///samples/wagyu.md", null)
        }
        val describe = {
            val s = app.reader.state.value
            "Player: playing=${s.playing} waiting=${s.waiting} index=${s.index} word=${s.word} elapsed=%.1f stalls=${s.stalls} error=${s.error}; voice=${app.reader.voice.value}; page=${app.reader.pageState.value}; blocked=${app.reader.blocked.value}".format(s.elapsed)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            device.wait(Until.findObject(By.res("sample")), 15_000)?.click() ?: throw AssertionError("The home screen has no sample button")
            until("the narration", 30_000, describe) { app.reader.state.value.loaded }
            until("the natural voice", 60_000, describe) { app.reader.voice.value == VoiceState.Ready(EngineKind.NATURAL) }
            device.wait(Until.findObject(By.res("play")), 10_000).click()
            val audio = DeviceFixtures.app.getSystemService(android.media.AudioManager::class.java)
            if (audio.mode == android.media.AudioManager.MODE_IN_COMMUNICATION || audio.mode == android.media.AudioManager.MODE_IN_CALL) {
                // Media can't play over a call: the player must say it waits for the call to end, not fail silently.
                until("the player to wait for the call", 10_000, describe) { app.reader.waitingForCall.value }
                assertEquals("Reading starts when the call ends.", app.reader.blocked.value)
                device.findObject(By.res("play")).click()
                until("the wait to be cancelled", 5_000, describe) { !app.reader.waitingForCall.value && !app.reader.state.value.playing }
                throw AssertionError("The phone is in a call, so reading waits for it to end (that part works). End the call and run the test again.")
            }
            until("speech with a highlighted word", 60_000, describe) {
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
            assertTrue("words were highlighted one after another: ${words.size} in %.0f s of text".format(read), words.size >= read * 0.5)
            assertTrue("the page highlights the word being spoken: $matches of $checks", checks > 50 && matches >= checks * 0.7)
            assertTrue("the word is inside the highlighted sentence: $inside of $checks", inside >= checks * 0.9)

            device.findObject(By.res("play")).click()
            until("pause", 10_000, describe) { !app.reader.state.value.playing }
            val paused = app.reader.state.value.elapsed
            Thread.sleep(1500)
            assertEquals("paused means paused", paused, app.reader.state.value.elapsed, 0.3)
        }
    }
}
