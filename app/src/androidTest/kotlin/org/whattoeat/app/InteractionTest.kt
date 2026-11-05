package org.whattoeat.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 专用测试安装内验证用户路径，不使用真实餐食或模型凭据。 */
class InteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun model() = ViewModelProvider(rule.activity)[MainViewModel::class.java]
    private fun home() {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("吃什么？").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun systemBackReturnsHomeAndKeepsTheMealDraft() {
        home()
        rule.onNodeWithText("看看这顿").performScrollTo().performClick()
        rule.onNodeWithText("餐食描述").performTextInput("准备吃番茄炒蛋")
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("吃什么？").assertExists()
        rule.onNodeWithText("看看这顿").performScrollTo().performClick()
        rule.onNodeWithText("餐食描述").assertTextContains("准备吃番茄炒蛋")
    }
    @Test fun recreatingActivityKeepsTheUsersCorrectionToDetectedFoods() {
        home()
        rule.onNodeWithText("看看这顿").performScrollTo().performClick()
        rule.onNodeWithText("餐食描述").performTextInput("番茄炒蛋")
        rule.onNodeWithText("看看搭配").performScrollTo().performClick()
        rule.onAllNodes(isToggleable()).onFirst().performScrollTo().performClick()
        rule.onAllNodes(isToggleable()).onFirst().assertIsOff()
        rule.activityRule.scenario.recreate()
        rule.onAllNodes(isToggleable()).onFirst().assertIsOff()
        rule.onNodeWithText("餐食描述").performScrollTo().assertTextContains("番茄炒蛋")
    }
    @Test fun clearingAllDataAlsoDiscardsUnsavedDrafts() {
        home()
        rule.onNodeWithText("看看这顿").performScrollTo().performClick()
        rule.onNodeWithText("餐食描述").performTextInput("private draft")
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("清除全部本地数据").performScrollTo().performClick()
        rule.onNodeWithText("确认清除").performClick()
        home()
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("已清除", substring = true)).fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("看看这顿").performScrollTo().performClick()
        rule.onNodeWithText("餐食描述").assert(!hasText("private draft", substring = true))
        rule.runOnIdle { assertTrue(model().state.value.rows.isEmpty()) }
    }
    @Test fun comparisonDoesNotCreatePreferenceUntilTheUserChooses() {
        home()
        val before = model().state.value.rows.map { it.id }.toSet()
        rule.onNodeWithText("帮我二选一").performScrollTo().performClick()
        rule.onNodeWithText("第一道菜").performTextInput("红烧牛肉面")
        rule.onAllNodesWithText("红烧牛肉面").onLast().performScrollTo().performClick()
        rule.onNodeWithText("第二道菜").performScrollTo().performTextInput("番茄牛腩饭")
        rule.onAllNodesWithText("番茄牛腩饭").onLast().performScrollTo().performClick()
        rule.onNodeWithText("帮我选一个").performScrollTo().performClick()
        rule.runOnIdle {
            assertNotNull(model().state.value.compared)
            assertTrue(model().state.value.rows.none { it.id !in before })
        }
        rule.onNodeWithText("我选 红烧牛肉面").performScrollTo().performClick()
        rule.waitUntil(10_000) { model().state.value.rows.any { it.id !in before } }
        val added = model().state.value.rows.filter { it.id !in before }
        assertEquals(1, added.size)
        assertEquals("COMPARED", added.single().type)
        assertEquals("红烧牛肉面", added.single().foodName)
    }
}
