package com.orexii.aimarkdownreader

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.orexii.aimarkdownreader.reader.Documents
import com.orexii.aimarkdownreader.reader.EditorModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownEditorTest {
    private val device = UiDevice.getInstance(DeviceFixtures.instrumentation)
    private val app = DeviceFixtures.app.applicationContext as App
    private fun tap(text: String) = requireNotNull(device.wait(Until.findObject(By.text(text)), 10_000)) { "No '$text' button" }.click()
    private fun source() = requireNotNull(device.wait(Until.findObject(By.res("markdown-source")), 10_000))

    @Test
    fun previewCreateAndOverwriteWithoutStartingTheReader() {
        DeviceFixtures.instrumentation.runOnMainSync { app.reader.close() }
        var created: Uri? = null
        val name = "amr-test-${java.util.UUID.randomUUID()}.md"
        val first = "# Notes\n\nFirst **draft** with a longer ending."
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                tap("New Markdown file")
                source().text = first
                tap("Preview")
                assertTrue(device.wait(Until.hasObject(By.text("Notes")), 10_000))
                assertFalse(app.reader.state.value.loaded)
                device.pressBack()
                tap("Keep editing")
                tap("Edit")
                assertEquals(first, source().text)
                tap("Save")
                val filename = By.clazz("android.widget.EditText")
                assertTrue(device.wait(Until.hasObject(filename), 10_000))
                device.pressBack()
                if (!device.wait(Until.hasObject(By.res("markdown-source")), 1_000)) device.pressBack()
                scenario.recreate()
                assertEquals(first, source().text)
                tap("Save")
                requireNotNull(device.wait(Until.findObject(filename), 10_000)).text = name
                requireNotNull(device.wait(Until.findObject(By.res("android:id/button1")), 10_000)).click()
                assertTrue(device.wait(Until.hasObject(By.res("markdown-source")), 10_000))
                assertTrue(device.wait(Until.hasObject(By.text(name)), 10_000))
                scenario.onActivity { created = Uri.parse(ViewModelProvider(it)[EditorModel::class.java].draft!!.document.uri) }
                val uri = requireNotNull(created)
                assertEquals(first, runBlocking { Documents.read(app, uri).text })
                // A shorter Unicode edit must replace the file, not leave a suffix or a second copy.
                source().text = "# Café"
                tap("Save")
                assertTrue(device.wait(Until.gone(By.text("Save")), 10_000))
                val saved = runBlocking { Documents.read(app, uri) }
                assertEquals("# Café", saved.text)
                scenario.onActivity { assertFalse(ViewModelProvider(it)[EditorModel::class.java].draft!!.dirty) }
                // An edit by another app must not be overwritten using the stale baseline.
                app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write("external".toByteArray()) }
                assertTrue(runCatching { runBlocking { Documents.write(app, uri, saved, "stale") } }.exceptionOrNull() is IllegalStateException)
                assertEquals("external", runBlocking { Documents.read(app, uri).text })
            } finally {
                created?.let { uri ->
                    android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri)
                    runBlocking { app.library.forget(uri.toString()) }
                }
            }
        }
    }
}
