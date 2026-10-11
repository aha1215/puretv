package com.puretv.twitch.tv.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "com.puretv.twitch.tv.perf"
private const val WAIT_MS = 30_000L

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class TvExperienceBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Full(),
    ) {
        startActivityAndWait()
    }

    @Test
    fun homeDpadNavigation() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Full(),
        setupBlock = {
            device.pressHome()
            startActivityAndWait()
        },
        measureBlock = {
            repeat(12) { device.pressDPadDown() }
            repeat(12) { device.pressDPadUp() }
        },
    )

    /**
     * Exercises real SMB VOD playback and the saved-chat overlay. It expects the
     * emulator to have an SMB share configured with at least one saved VOD.
     */
    @Test
    fun savedVodPlaybackWithChat() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Full(),
        setupBlock = {
            device.pressHome()
            startActivityAndWait()
            val savedVods = device.wait(Until.findObject(By.text("Saved VODs")), WAIT_MS)
            assertNotNull("Home did not expose the Saved VODs action", savedVods)
            savedVods!!.click()

            val firstVod = device.wait(Until.findObject(By.textContains(" · ")), WAIT_MS)
            assertNotNull(
                "No saved VOD row was found. Configure the emulator's SMB share and save at least one VOD.",
                firstVod,
            )
            firstVod!!.click()
            device.pressDPadDown()
            val pauseAction = device.wait(Until.findObject(By.text("Pause")), WAIT_MS)
            assertNotNull("VOD playback controls did not appear", pauseAction)
        },
        measureBlock = {
            // Let playback and its chat overlay render long enough to capture frames
            // after startup and buffering have settled.
            Thread.sleep(12_000)
        },
    )
}
