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
 * Saved to the app's external files folder, screenshots/.
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
        val file = File(folder, "$name.png")
        check(device.takeScreenshot(file) && file.length() > 0) { "Could not save the screenshot $name" }
        Log.i("ScreenshotTest", "Saved $file (${file.length() / 1024} KB)")
    }

    private fun waitFor(timeoutMs: Long, check: () -> Boolean) {
        val started = System.currentTimeMillis()
        while (!check() && System.currentTimeMillis() - started < timeoutMs) Thread.sleep(50)
    }

    @Test
    fun storeScreenshots() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        assumeTrue(DeviceFixtures.installModel())
        runBlocking {
            withContext(Dispatchers.Main) {
                app.settings.update { it.copy(engine = EngineChoice.NATURAL, quality = ModelCatalog.Quality.COMPACT, rate = 1.0, theme = ThemeChoice.LIGHT, expandedSpeeds = false) }
            }
            app.library.savePosition("asset:///samples/wagyu.md", null)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            device.wait(Until.findObject(By.res("sample")), 15_000).click()
            waitFor(60_000) { app.reader.state.value.loaded && app.reader.voice.value == VoiceState.Ready(EngineKind.NATURAL) }
            // Start in the Quick facts table, which shows the row and cell highlighting.
            val doc = DeviceFixtures.documents.getValue("wagyu-rows-announce")
            val table = doc.blocks.indexOfFirst { it.kind == "table" }
            app.reader.player.seek(doc.blocks[table].first + 2, 0.0, play = true)
            waitFor(60_000) { app.reader.state.value.word >= 2 && !app.reader.state.value.waiting }
            Thread.sleep(1800)
            shot("1-reading-table")
            app.reader.command("nextSection")
            waitFor(20_000) { app.reader.state.value.word >= 3 }
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
            runBlocking { withContext(Dispatchers.Main) { app.settings.update { it.copy(theme = ThemeChoice.DARK) } } }
            app.reader.player.play()
            waitFor(20_000) { app.reader.state.value.playing && app.reader.state.value.word >= 1 }
            Thread.sleep(2000)
            shot("5-dark")
            app.reader.player.pause()
            device.pressBack()
            runBlocking { withContext(Dispatchers.Main) { app.settings.update { it.copy(theme = ThemeChoice.LIGHT) } } }
            shot("6-home")
        }
    }
}
