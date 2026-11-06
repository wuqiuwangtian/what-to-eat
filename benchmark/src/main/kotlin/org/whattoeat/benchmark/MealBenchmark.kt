package org.whattoeat.benchmark

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 只操作独立性能包；每轮测量前在区间外重建1000条合成历史。 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class MealBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val iterations get() = (InstrumentationRegistry.getArguments().getString("iterations")?.toIntOrNull() ?: 20).also {
        require(it in 3..50)
    }
    private fun ready() {
        check(device.wait(Until.hasObject(By.res("recommendButton")), 10_000)) { "Recommendation screen not ready; foreground=${device.currentPackageName}; pid=${device.executeShellCommand("pidof $PACKAGE")}" }
        device.waitForIdle()
    }
    private fun seed() {
        device.wakeUp()
        device.executeShellCommand("am force-stop $PACKAGE")
        val result = device.executeShellCommand("am start -W -f 0x10008000 -n $PACKAGE/org.whattoeat.app.MainActivity --ez benchmark_seed true")
        check(!result.contains("Error")) { "Fixture setup failed" }
        ready()
    }
    private fun clickAndWait(id: Int) {
        val button = device.wait(Until.findObject(By.res("recommendButton")), 10_000)
        checkNotNull(button) { "Recommendation button unavailable; foreground=${device.currentPackageName}" }.click()
        check(device.wait(Until.hasObject(By.res("recommendationResult-$id")), 10_000)) { "Recommendation did not appear" }
        device.waitForIdle()
    }
    @Test fun startup() = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = iterations,
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        setupBlock = { seed(); pressHome() },
    ) {
        // am -W在部分系统会早于目标进程返回；等待目标UI，由StartupTimingMetric从trace提取耗时。
        val result = device.executeShellCommand(
            "am start -W -f 0x10008000 -n $PACKAGE/org.whattoeat.app.MainActivity --ez benchmark_seed false"
        )
        check(!result.contains("Error")) { "Cold launch failed" }
        ready()
    }

    @Test fun firstRecommendation() = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(TraceSectionMetric("meal.recommend", TraceSectionMetric.Mode.Sum), FrameTimingMetric()),
        iterations = iterations,
        compilationMode = CompilationMode.None(),
        setupBlock = { seed() },
    ) { clickAndWait(1) }

    @Test fun switchRecommendation() = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(TraceSectionMetric("meal.switch", TraceSectionMetric.Mode.Sum), FrameTimingMetric()),
        iterations = iterations,
        compilationMode = CompilationMode.None(),
        setupBlock = { seed(); clickAndWait(1) },
    ) { clickAndWait(2) }

    companion object { private const val PACKAGE = "org.whattoeat.app.benchmark" }
}
