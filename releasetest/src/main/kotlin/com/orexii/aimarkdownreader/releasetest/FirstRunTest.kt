package com.orexii.aimarkdownreader.releasetest

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

private const val TAG = "FirstRunTest"

/**
 * The release build on a clean first run, used like a user would: opens the Wagyu guide, downloads the natural
 * voice, reads, carries on reading in the background (a foreground service with a media notification) and answers
 * a headset's pause key. Clears the tested app's data first; the tested app is the staging build
 * (com.orexii.aimarkdownreader.staging), never the Play version, unless the instrumentation argument app says so.
 * On a failure, the screen is saved to /data/local/tmp/releasetest-failure.png.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val app = InstrumentationRegistry.getArguments().getString("app") ?: "com.orexii.aimarkdownreader.staging"

    private fun shell(command: String): String {
        val output = device.executeShellCommand(command)
        Log.i(TAG, "$ $command: ${output.take(300).trim()}")
        return output
    }

    private fun launch() {
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $app/com.orexii.aimarkdownreader.MainActivity")
    }

    private fun find(tag: String, timeoutMs: Long): UiObject2 =
        device.wait(Until.findObject(By.res(tag)), timeoutMs) ?: error("\"$tag\" did not appear")

    private fun waitUntil(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { "Timed out waiting until $what" }
            SystemClock.sleep(250)
        }
    }

    /** The player's elapsed time in seconds, or -1 when it is not on screen. */
    private fun elapsed(): Int {
        val text = device.findObject(By.res("elapsed"))?.text ?: return -1
        return text.split(":").fold(0) { total, part -> total * 60 + (part.toIntOrNull() ?: return -1) }
    }

    private fun playLabel(): String? = device.findObject(By.res("play"))?.contentDescription

    /** The app's media session state from dumpsys media_session (2 paused, 3 playing), or null without one. */
    private fun sessionState(): Int? {
        val lines = device.executeShellCommand("dumpsys media_session").lines()
        val at = lines.indexOfFirst { it.trim() == "package=$app" }.takeIf { it >= 0 } ?: return null
        val session = lines.drop(at + 1).takeWhile { !it.trim().startsWith("package=") }
        val state = session.firstOrNull { it.trim().startsWith("state=") } ?: return null
        return Regex("""state=PlaybackState \{state=(?:[A-Z_]+\()?(\d+)""").find(state)?.groupValues?.get(1)?.toInt()
    }

    /** Playing, or briefly waiting for audio (a slow emulator's voice can fall behind for a moment). */
    private fun reading(state: Int?) = state == 3 || state == 6

    @Test
    fun noTelemetry() {
        // ONNX Runtime's Android library starts usage telemetry from a content provider; the app leaves it out.
        val dump = device.executeShellCommand("dumpsys package $app")
        check("Package [$app]" in dump) { "$app is not installed" }
        val telemetry = dump.lines().filter { "onnxruntime" in it }
        check(telemetry.isEmpty()) { "ONNX Runtime's telemetry is in the app: $telemetry" }
    }

    @Test(timeout = 20 * 60 * 1000L)
    fun firstRunReadsAloud() {
        try {
            shell("pm clear $app")
            launch()
            find("sample", 30_000).click()
            find("voice-setup", 60_000)
            find("download-compact", 10_000).click()
            // About 92 MB from Hugging Face, then checked against its pinned SHA-256.
            check(device.wait(Until.gone(By.res("voice-setup")), 10 * 60_000L)) { "The natural voice did not finish downloading" }
            find("play", 10_000).click()
            waitUntil("reading has started", 120_000) { playLabel() == "Pause" && elapsed() >= 2 }
            sessionState().let { check(reading(it)) { "The media session does not say it is playing (state $it)" } }

            val before = elapsed()
            device.pressHome()
            SystemClock.sleep(10_000)
            check(shell("dumpsys activity services $app").contains("isForeground=true")) { "No foreground service while reading in the background" }
            sessionState().let { check(reading(it)) { "Reading stopped in the background (state $it)" } }
            shell("input keyevent KEYCODE_MEDIA_PAUSE")
            waitUntil("the pause key has paused reading", 15_000) { sessionState() == 2 }

            launch()
            find("play", 10_000)
            waitUntil("the player shows it is paused", 10_000) { playLabel() == "Play" }
            val after = elapsed()
            Log.i(TAG, "Elapsed $before s before the background, $after s after")
            check(after >= before + 4) { "Reading did not carry on in the background ($before s, then $after s)" }
        } catch (failure: Throwable) {
            runCatching { shell("screencap -p /data/local/tmp/releasetest-failure.png") }
            runCatching {
                val hierarchy = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString()
                hierarchy.chunked(3_000).forEach { Log.w(TAG, it) }
            }
            throw failure
        }
    }
}
