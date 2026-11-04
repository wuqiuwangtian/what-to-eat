package org.whattoeat.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** 需要模拟器或设备；不能把编译此测试当成实际执行。 */
class SmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun waitForHome() {
        rule.waitUntil(timeoutMillis = 15_000) { rule.onAllNodesWithText("吃什么？").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun offlineRecommendationAndSwitchNeedNoOnboarding() {
        waitForHome()
        rule.onNodeWithText("吃什么？").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("换一个").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("就它了").assertExists()
        rule.onNodeWithText("换一个").performScrollTo().performClick()
        rule.onNodeWithText("为什么推荐").performScrollTo().performClick()
        rule.onNodeWithText("收起依据").assertExists()
    }
    @Test fun plannedMealStaysPlannedInHistory() {
        waitForHome()
        rule.onNodeWithText("看看这顿").performScrollTo().performClick()
        rule.onNodeWithText("餐食描述").performTextInput("打算吃番茄炒蛋")
        rule.onNodeWithText("本地看看搭配").performScrollTo().performClick()
        rule.onNodeWithText("保存为计划").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("记录").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("记录").performScrollTo().performClick()
        rule.onAllNodes(hasText("打算吃", substring = true)).onFirst().assertExists()
    }
}
