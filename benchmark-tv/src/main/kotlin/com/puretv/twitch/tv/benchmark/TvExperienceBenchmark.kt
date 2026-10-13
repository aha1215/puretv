package com.puretv.twitch.tv.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
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

private const val ISOLATED_TARGET_PACKAGE = "com.puretv.twitch.tv.perf"
private const val WAIT_MS = 30_000L

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class TvExperienceBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val targetPackage: String
        get() = InstrumentationRegistry.getArguments().getString("targetPackage") ?: ISOLATED_TARGET_PACKAGE

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = targetPackage,
        // The onn firmware's FrameTimeline track currently has no slices for
        // TraceProcessor; keep this smoke measurement usable on that target.
        metrics = listOf(StartupTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.None(),
    ) {
        startActivityAndWait()
    }

    @Test
    fun homeDpadNavigation() = benchmarkRule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.None(),
        setupBlock = {
            device.pressHome()
            startActivityAndWait()
        },
        measureBlock = {
            repeat(12) { device.pressDPadDown() }
            repeat(12) { device.pressDPadUp() }
        },
    )

    /** Exercises SMB bundle playback from the Downloads → Open saved VODs route. */
    @Test
    fun savedVodPlaybackWithChat() = benchmarkSavedVodPlayback(StartupMode.COLD)

    @Test
    fun savedVodRepeatPlaybackWithChat() = benchmarkSavedVodPlayback(StartupMode.WARM)

    private fun benchmarkSavedVodPlayback(startupMode: StartupMode) = benchmarkRule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
            TraceSectionMetric("PureTV.savedVodSelectionToFirstFrame"),
            TraceSectionMetric("PureTV.savedVodSeekToRender"),
        ),
        iterations = 5,
        startupMode = startupMode,
        compilationMode = CompilationMode.None(),
        setupBlock = {
            device.pressHome()
            startActivityAndWait()
            val downloads = device.wait(Until.findObject(By.text("Downloads")), WAIT_MS)
            assertNotNull("Home did not expose the Downloads navigation action", downloads)
            downloads!!.click()
            val openSavedVods = device.wait(Until.findObject(By.text("Open saved VODs")), WAIT_MS)
            assertNotNull("Downloads did not expose Open saved VODs", openSavedVods)
            openSavedVods!!.click()

        },
        measureBlock = {
            val firstVod = device.wait(Until.findObject(By.textContains(" · ")), WAIT_MS)
            assertNotNull("No saved VOD row was found. Configure the device's SMB share and save at least one VOD.", firstVod)
            firstVod!!.click()
            device.pressDPadDown()
            val pauseAction = device.wait(Until.findObject(By.text("Pause")), WAIT_MS)
            assertNotNull("VOD playback controls did not appear", pauseAction)
            // Let the 8-second controls overlay timeout. DirectionRight while
            // controls are visible only changes button focus; with the overlay
            // hidden it performs the real relative seek path.
            Thread.sleep(8_500)
            device.pressDPadRight()
            Thread.sleep(5_000)
        },
    )
}
