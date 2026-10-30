package com.orexii.aimarkdownreader.releasetest

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream

/**
 * Drives the app under test from outside, through its screen and the shell, as a user would. The app is the staging
 * build (com.orexii.aimarkdownreader.staging), never the Play version, unless the instrumentation argument app
 * names another.
 */
class AppDriver(private val logTag: String) {
    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val app: String = InstrumentationRegistry.getArguments().getString("app") ?: "com.orexii.aimarkdownreader.staging"

    fun shell(command: String): String {
        val output = device.executeShellCommand(command)
        Log.i(logTag, "$ $command: ${output.take(300).trim()}")
        return output
    }

    fun launch() {
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $app/com.orexii.aimarkdownreader.MainActivity")
    }

    fun find(tag: String, timeoutMs: Long): UiObject2 =
        device.wait(Until.findObject(By.res(tag)), timeoutMs) ?: error("\"$tag\" did not appear")

    fun waitUntil(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { "Timed out waiting until $what" }
            SystemClock.sleep(250)
        }
    }

    /** The player's elapsed time in seconds, or -1 when it is not on screen. */
    fun elapsed(): Int {
        val text = device.findObject(By.res("elapsed"))?.text ?: return -1
        return text.split(":").fold(0) { total, part -> total * 60 + (part.toIntOrNull() ?: return -1) }
    }

    fun playLabel(): String? = device.findObject(By.res("play"))?.contentDescription

    /** The app's media session state from dumpsys media_session (2 paused, 3 playing), or null without one. */
    fun sessionState(): Int? {
        val lines = device.executeShellCommand("dumpsys media_session").lines()
        val at = lines.indexOfFirst { it.trim() == "package=$app" }.takeIf { it >= 0 } ?: return null
        val session = lines.drop(at + 1).takeWhile { !it.trim().startsWith("package=") }
        val state = session.firstOrNull { it.trim().startsWith("state=") } ?: return null
        return Regex("""state=PlaybackState \{state=(?:[A-Z_]+\()?(\d+)""").find(state)?.groupValues?.get(1)?.toInt()
    }

    /**
     * Clears the app's data, opens the Wagyu guide and downloads the compact natural voice (about 92 MB from Hugging
     * Face, checked against its pinned SHA-256). Leaves the guide open and paused.
     */
    fun freshStartWithVoice() {
        shell("pm clear $app")
        launch()
        find("sample", 30_000).click()
        find("voice-setup", 60_000)
        find("download-compact", 10_000).click()
        check(device.wait(Until.gone(By.res("voice-setup")), 10 * 60_000L)) { "The natural voice did not finish downloading" }
    }

    /** Saves the screen to /data/local/tmp/releasetest-failure.png and logs the window hierarchy. */
    fun saveFailure() {
        runCatching { shell("screencap -p /data/local/tmp/releasetest-failure.png") }
        runCatching {
            val hierarchy = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString()
            hierarchy.chunked(3_000).forEach { Log.w(logTag, it) }
        }
    }
}
