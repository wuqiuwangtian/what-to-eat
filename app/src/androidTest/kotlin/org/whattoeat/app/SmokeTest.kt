package org.whattoeat.app

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import java.io.File
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** 需要模拟器或设备；不能把编译此测试当成实际执行。 */
class SmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun waitForHome() {
        rule.waitUntil(timeoutMillis = 15_000) { rule.onAllNodesWithText("吃什么？").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("capture_screenshots") != "true") return
        rule.waitForIdle()
        // 端侧 RippleDrawable 使用真实时间；等按压反馈结束再保存演示画面。
        android.os.SystemClock.sleep(400)
        rule.waitForIdle()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "validation-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private fun captureRotation() {
        if (InstrumentationRegistry.getArguments().getString("capture_screenshots") != "true") return
        val requested = rule.activity.requestedOrientation
        val previous = rule.activity.resources.configuration.orientation
        try {
            rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            capture("landscape")
        } finally {
            rule.activityRule.scenario.onActivity { it.requestedOrientation = requested }
        }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == previous }
    }
    @Test fun offlineRecommendationAndSwitchNeedNoOnboarding() {
        waitForHome()
        capture("home")
        rule.onNodeWithText("吃什么？").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("换一个").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("就它了").assertExists()
        capture("recommendation")
        captureRotation()
        rule.onNodeWithText("换一个").performScrollTo().performClick()
        rule.onNodeWithText("为什么推荐").performScrollTo().performClick()
        rule.onNodeWithText("收起依据").assertExists()
    }
    @Test fun plannedMealStaysPlannedInHistory() {
        waitForHome()
        rule.onNodeWithText("看看这顿").performScrollTo().performClick()
        rule.onNodeWithText("餐食描述").performTextInput("打算吃番茄炒蛋")
        rule.onNodeWithText("看看搭配").performScrollTo().performClick()
        rule.onNodeWithText("保存为计划").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("记录").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("记录").assertIsDisplayed().performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("打算吃", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodes(hasText("打算吃", substring = true)).onFirst().assertExists()
    }
}
