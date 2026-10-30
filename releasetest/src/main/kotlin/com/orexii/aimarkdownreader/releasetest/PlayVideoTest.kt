package com.orexii.aimarkdownreader.releasetest

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val VIDEO = "/data/local/tmp/fgs-media-playback.mp4"

/**
 * The screen recording for Google Play's foreground service declaration (Media playback), made by
 * .github/workflows/video.yml with the instrumentation argument video=true: Play in the app, reading carries on at
 * the home screen with the media notification, pause and play from the notification, then back to the app.
 * Saved to /data/local/tmp/fgs-media-playback.mp4.
 */
@RunWith(AndroidJUnit4::class)
class PlayVideoTest {
    private val driver = AppDriver("PlayVideoTest")

    /** A button in the notification shade's media player. */
    private fun mediaButton(label: String): UiObject2 =
        driver.device.wait(Until.findObject(By.pkg("com.android.systemui").desc(label)), 10_000)
            ?: error("No $label button in the notification's media player")

    private fun recording(): Boolean = driver.device.executeShellCommand("pidof screenrecord").isNotBlank()

    /** Records the screen to [VIDEO] while [steps] run. */
    private fun record(steps: () -> Unit) {
        driver.shell("rm -f $VIDEO")
        // 720 × 1280 at 2 Mbit/s keeps the file small. screenrecord finishes the file when interrupted.
        val recorder = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screenrecord --size 720x1280 --bit-rate 2000000 $VIDEO")
        try {
            driver.waitUntil("the recording has started", 10_000, ::recording)
            steps()
        } finally {
            driver.shell("pkill -INT screenrecord")
            driver.waitUntil("the recording is saved", 30_000) { !recording() }
            recorder.close()
        }
        val size = driver.shell("stat -c %s $VIDEO").trim().toLongOrNull() ?: 0
        check(size > 100_000) { "The recording is missing or empty ($size bytes)" }
    }

    @Test(timeout = 20 * 60 * 1000L)
    fun foregroundServiceVideo() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("video") == "true")
        with(driver) {
            try {
                freshStartWithVoice()
                // Emulators synthesize at about real time: a head start keeps pauses for audio out of the video.
                SystemClock.sleep(30_000)
                record {
                    SystemClock.sleep(2_500)
                    find("play", 10_000).click()
                    waitUntil("reading has started", 30_000) { playLabel() == "Pause" }
                    SystemClock.sleep(9_000)
                    device.pressHome()
                    SystemClock.sleep(3_500)
                    device.openNotification()
                    val pause = mediaButton("Pause")
                    SystemClock.sleep(4_500)
                    pause.click()
                    val play = mediaButton("Play")
                    SystemClock.sleep(2_500)
                    play.click()
                    mediaButton("Pause")
                    SystemClock.sleep(4_000)
                    device.pressBack()
                    SystemClock.sleep(1_000)
                    launch()
                    find("play", 10_000)
                    SystemClock.sleep(5_000)
                    find("play", 5_000).click()
                    waitUntil("reading has paused", 10_000) { playLabel() == "Play" }
                    SystemClock.sleep(2_000)
                }
            } catch (failure: Throwable) {
                saveFailure()
                throw failure
            }
        }
    }
}
