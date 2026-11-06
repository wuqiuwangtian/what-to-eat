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

/** 只操作独立性能包；冷启动只读同一1000条历史，热操作每轮在区间外重建。 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class MealBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val iterations get() = (InstrumentationRegistry.getArguments().getString("iterations")?.toIntOrNull() ?: 20).also {
        require(it in 3..50)
    }
    private fun ready(launchOutput: String = "") {
        check(device.wait(Until.hasObject(By.text("吃什么？")), 10_000)) { "Initial recommendation label not ready; launch=$launchOutput" }
        check(device.wait(Until.hasObject(By.res("recommendButton").enabled(true)), 10_000)) { "Recommendation screen not ready; foreground=${device.currentPackageName}; pid=${device.executeShellCommand("pidof $PACKAGE")}; launch=$launchOutput" }
        device.waitForIdle()
    }
    private fun launch(seedFixture: Boolean) {
        device.wakeUp()
        val result = device.executeShellCommand(
            "am start -W -f 0x10008000 -n $PACKAGE/org.whattoeat.app.MainActivity --ez benchmark_seed $seedFixture"
        )
        check(!result.contains("Error")) { "Launch failed: $result" }
        ready(result)
    }
    private fun seed() = launch(true)
    private fun clickAndWait(id: Int) {
        val button = device.wait(Until.findObject(By.res("recommendButton").enabled(true)), 10_000)
        checkNotNull(button) { "Recommendation button unavailable; foreground=${device.currentPackageName}" }.click()
        check(device.wait(Until.hasObject(By.res("recommendationResult-$id")), 10_000)) { "Recommendation did not appear" }
        device.waitForIdle()
    }
    @Test fun startup() {
        // 冷启动不写历史，只需预置一次；每轮不再额外强停准备进程。
        seed()
        benchmark.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            iterations = iterations,
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.COLD,
            // 保证ProfileInstaller可接收清理shader广播，再由库停止进程进行冷启动。
            setupBlock = { launch(false); pressHome() },
        ) { launch(false) }
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
