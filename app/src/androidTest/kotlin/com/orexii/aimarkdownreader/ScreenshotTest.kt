package com.orexii.aimarkdownreader

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.orexii.aimarkdownreader.core.EngineKind
import com.orexii.aimarkdownreader.core.ModelCatalog
import com.orexii.aimarkdownreader.data.EngineChoice
import com.orexii.aimarkdownreader.data.ThemeChoice
import com.orexii.aimarkdownreader.reader.Documents
import com.orexii.aimarkdownreader.reader.VoiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshots for the Google Play listing (run by .github/workflows/screenshots.yml with screenshots=true).
 * Saved to the app's external files folder, screenshots/. On a failure, the screen and its view hierarchy are
 * saved there too.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val device = UiDevice.getInstance(DeviceFixtures.instrumentation)
    private val app get() = DeviceFixtures.app.applicationContext as App
    private val folder: File by lazy {
        File(DeviceFixtures.app.getExternalFilesDir(null), "screenshots").apply { deleteRecursively(); mkdirs() }
    }

    private fun shot(name: String) {
        Thread.sleep(700)
        device.waitForIdle(3_000)
        val file = File(folder, "$name.png")
        check(device.takeScreenshot(file) && file.length() > 0) { "Could not save the screenshot $name" }
        Log.i("ScreenshotTest", "Saved $file (${file.length() / 1024} KB)")
    }

    private fun waitFor(what: String, timeoutMs: Long, check: () -> Boolean) {
        val started = System.currentTimeMillis()
        while (!check()) {
            if (System.currentTimeMillis() - started > timeoutMs) {
                Log.w("ScreenshotTest", "Gave up waiting for $what; state ${app.reader.state.value}")
                return
            }
            Thread.sleep(50)
        }
    }

    private fun settings(change: (com.orexii.aimarkdownreader.data.ReaderSettings) -> com.orexii.aimarkdownreader.data.ReaderSettings) =
        runBlocking { withContext(Dispatchers.Main) { app.settings.update(change) } }

    @Test(timeout = 8 * 60 * 1000L)
    fun storeScreenshots() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        assumeTrue(DeviceFixtures.installModel())
        settings { it.copy(engine = EngineChoice.NATURAL, quality = ModelCatalog.Quality.COMPACT, rate = 1.0, theme = ThemeChoice.LIGHT, expandedSpeeds = false) }
        runBlocking { app.library.savePosition("asset:///samples/wagyu.md", null) }
        // Open the guide before the activity starts, so it shows the reader straight away (no Home tap to wait for).
        runBlocking { withContext(Dispatchers.Main) { app.reader.open(Documents.sample(DeviceFixtures.app, "wagyu.md")) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                device.wait(Until.hasObject(By.res("play")), 30_000)
                waitFor("the narration and the voice", 90_000) { app.reader.state.value.loaded && app.reader.voice.value == VoiceState.Ready(EngineKind.NATURAL) }
                // Start in the Quick facts table, which shows the row and cell highlighting.
                val doc = DeviceFixtures.documents.getValue("wagyu-rows-announce")
                val table = doc.blocks.indexOfFirst { it.kind == "table" }
                val row = doc.blocks[table].first + 2
                app.reader.player.seek(row, 0.0, play = true)
                waitFor("a highlighted word in the table", 90_000) { app.reader.state.value.let { it.index == row && it.playing && !it.waiting && it.word >= 2 } }
                Thread.sleep(1500)
                shot("1-reading-table")
                val section = (com.orexii.aimarkdownreader.core.Navigation.nextSection(doc.blocks, app.reader.state.value.index) as com.orexii.aimarkdownreader.core.Navigation.Move.To).index
                app.reader.command("nextSection")
                // The heading, then into its first paragraph.
                waitFor("reading the next section", 90_000) { app.reader.state.value.let { it.index > section && it.playing && !it.waiting && it.word >= 3 } }
                Thread.sleep(1500)
                shot("2-reading-text")
                app.reader.player.pause()
                device.findObject(By.res("speed"))?.click()
                shot("3-speeds")
                device.pressBack()
                device.findObject(By.res("settings"))?.click()
                Thread.sleep(800)
                shot("4-settings")
                device.pressBack()
                settings { it.copy(theme = ThemeChoice.DARK) }
                app.reader.player.play()
                waitFor("reading in the dark theme", 60_000) { app.reader.state.value.let { it.playing && !it.waiting && it.word >= 1 } }
                Thread.sleep(2000)
                shot("5-dark")
                app.reader.player.pause()
                device.pressBack()
                settings { it.copy(theme = ThemeChoice.LIGHT) }
                device.wait(Until.hasObject(By.res("sample")), 10_000)
                shot("6-home")
            }
        } catch (failure: Throwable) {
            runCatching { device.takeScreenshot(File(folder, "failure.png")) }
            runCatching { File(folder, "failure-hierarchy.xml").outputStream().use { device.dumpWindowHierarchy(it) } }
            throw failure
        }
    }
}
