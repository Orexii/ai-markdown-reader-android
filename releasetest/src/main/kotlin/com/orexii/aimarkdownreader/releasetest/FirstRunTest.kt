package com.orexii.aimarkdownreader.releasetest

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "FirstRunTest"

/**
 * The release build on a clean first run, used like a user would: opens the Wagyu guide, downloads the natural
 * voice, reads, carries on reading in the background (a foreground service with a media notification) and answers
 * a headset's pause key. Clears the tested app's data first ([AppDriver] says which app).
 * On a failure, the screen is saved to /data/local/tmp/releasetest-failure.png.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunTest {
    private val driver = AppDriver(TAG)

    @Test
    fun noTelemetry() {
        // ONNX Runtime's Android library starts usage telemetry from a content provider; the app leaves it out.
        val app = driver.app
        val dump = driver.device.executeShellCommand("dumpsys package $app")
        check("Package [$app]" in dump) { "$app is not installed" }
        val telemetry = dump.lines().filter { "onnxruntime" in it }
        check(telemetry.isEmpty()) { "ONNX Runtime's telemetry is in the app: $telemetry" }
    }

    @Test(timeout = 20 * 60 * 1000L)
    fun firstRunReadsAloud() {
        with(driver) {
            try {
                freshStartWithVoice()
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
                // Had reading stopped at Home, the time would not move; an emulator's slow voice can stall for part of it.
                check(after >= before + 2) { "Reading did not carry on in the background ($before s, then $after s)" }
            } catch (failure: Throwable) {
                saveFailure()
                throw failure
            }
        }
    }
}
