package com.orexii.aimarkdownreader.releasetest

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val VIDEO = "/data/local/tmp/fgs-media-playback.mp4"

/**
 * The screen recording for Google Play's foreground service declaration (Media playback), made by
 * .github/workflows/video.yml with the instrumentation argument video=true: Play in the app, reading carries on at
 * the home screen with the media notification, pause and play from the notification, then back to the app from the
 * notification.
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

    /** Whether the app is the resumed activity (asked through the shell: the reading page is slow to inspect). */
    private fun inFront(): Boolean = driver.device.executeShellCommand("dumpsys activity activities").lines()
        .any { "ResumedActivity" in it && "${driver.app}/" in it }

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
        // While reading, the screen is never idle; UiAutomator would wait 10 s for it before each look.
        val configurator = Configurator.getInstance()
        val idleTimeout = configurator.waitForIdleTimeout
        configurator.waitForIdleTimeout = 500
        with(driver) {
            try {
                freshStartWithVoice()
                // Emulators load the voice slowly and then synthesize at about real time: a head start keeps pauses
                // for audio out of the video.
                SystemClock.sleep(120_000)
                record {
                    SystemClock.sleep(2_500)
                    // While reading, looking through the page's accessibility tree takes the emulator many seconds, so
                    // the play button is found now, while paused, and tapped where it is later.
                    val playButton = find("play", 10_000).visibleCenter
                    device.click(playButton.x, playButton.y)
                    waitUntil("reading has started", 30_000) { reading() }
                    SystemClock.sleep(9_000)
                    device.pressHome()
                    SystemClock.sleep(3_500)
                    device.openNotification()
                    val pause = mediaButton("Pause")
                    SystemClock.sleep(4_500)
                    check(reading()) { "Reading stopped at the home screen" }
                    pause.click()
                    waitUntil("the notification has paused reading", 15_000) { sessionState() == 2 }
                    val play = mediaButton("Play")
                    SystemClock.sleep(2_500)
                    play.click()
                    waitUntil("the notification has resumed reading", 15_000) { reading() }
                    SystemClock.sleep(4_000)
                    // Back to the app by tapping the notification (its title is the document's name).
                    device.findObject(By.pkg("com.android.systemui").text("wagyu.md"))?.click()
                    if (runCatching { waitUntil("the notification has opened the app", 5_000, ::inFront) }.isFailure) {
                        device.pressBack()
                        launch()
                    }
                    SystemClock.sleep(6_000)
                    check(reading()) { "Reading stopped when the app came back" }
                    device.click(playButton.x, playButton.y)
                    waitUntil("reading has paused", 15_000) { sessionState() == 2 }
                    SystemClock.sleep(2_000)
                }
            } catch (failure: Throwable) {
                saveFailure()
                throw failure
            } finally {
                configurator.waitForIdleTimeout = idleTimeout
            }
        }
    }
}
