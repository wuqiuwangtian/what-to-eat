package org.whattoeat.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.whattoeat.core.Preferences
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 仅专用测试安装；个人手机不要执行包含清除试用数据的全套测试。 */
class TrialInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun model() = ViewModelProvider(rule.activity)[MainViewModel::class.java]
    private fun waitIdle() = rule.waitUntil(15_000) { !model().state.value.loading && !model().state.value.busy }
    @Before fun resetTrial() {
        waitIdle(); rule.runOnIdle { model().deleteTrialData() }; waitIdle()
    }
    @After fun leaveOrdinaryMode() {
        waitIdle(); rule.runOnIdle { model().deleteTrialData() }; waitIdle()
        rule.runOnIdle { model().savePreferences(Preferences()) }; waitIdle()
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("capture_screenshots") != "true") return
        rule.waitForIdle(); android.os.SystemClock.sleep(400); rule.waitForIdle()
        val out = File(rule.activity.cacheDir, "trial-validation").apply { mkdirs() }
        out.resolve("$name.png").outputStream().use {
            val node = when (name) {
                "reason" -> rule.onNodeWithText("候选符合当前原料排除和用餐场景设置。").onParent()
                "consent" -> rule.onNodeWithTag("trialControls")
                else -> rule.onRoot()
            }
            check(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    @Test fun consentBlindPresentationAndExplicitChoiceWorkTogether() {
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("本地试用 · 未参加").performScrollTo().performClick()
        rule.onNodeWithText("加入试用").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("导出试用数据").assertDoesNotExist()
        capture("consent")
        rule.onAllNodes(isToggleable()).onLast().performScrollTo().performClick()
        rule.onNodeWithText("加入试用").performScrollTo().performClick()
        rule.waitUntil(10_000) { model().state.value.trial.enabled && !model().state.value.busy }
        rule.onNodeWithText("验证工具").assertDoesNotExist()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("吃什么？").performClick()
        rule.waitUntil(10_000) { JSONArray(model().state.value.trial.active?.presentations ?: "[]").length() == 1 }
        capture("recommendation")
        rule.onNodeWithText("为什么推荐").performScrollTo().performClick()
        rule.onNodeWithText("候选符合当前原料排除和用餐场景设置。").assertExists()
        rule.onNodeWithText("决策详情").assertDoesNotExist()
        capture("reason")
        rule.onNodeWithText("收起依据").performScrollTo().performClick()
        rule.onNodeWithText("换一个").performScrollTo().performClick()
        rule.waitUntil(10_000) { JSONArray(model().state.value.trial.active?.presentations ?: "[]").length() == 2 }
        val food = model().state.value.decision!!.food
        rule.onNodeWithText("就它了").performScrollTo().performClick()
        rule.waitUntil(10_000) { model().state.value.trial.active == null && model().state.value.decision == null && !model().state.value.busy }
        rule.waitUntil(10_000) { model().state.value.rows.any { it.foodId == food.id && it.type == "PLANNED" } }
        val db = MealDatabase.open(rule.activity)
        try { runBlocking {
            val s = db.trials().sessions().single()
            assertEquals("ADOPTED", s.endReason); assertEquals(1, s.selectedIndex)
        } } finally { db.close() }
    }
    @Test fun activeAssignmentSurvivesViewModelRecreationAndCanEndWithoutAdoption() {
        rule.runOnIdle { model().enrollTrial() }; waitIdle()
        rule.onNodeWithText("吃什么？").performClick()
        rule.waitUntil(10_000) { JSONArray(model().state.value.trial.active?.presentations ?: "[]").length() == 1 }
        val id = model().state.value.trial.active!!.id
        rule.runOnIdle { rule.activity.viewModelStore.clear() }
        rule.activityRule.scenario.recreate(); waitIdle()
        assertEquals(id, model().state.value.trial.active!!.id)
        rule.onNodeWithText("吃什么？").performClick()
        rule.waitUntil(10_000) { JSONArray(model().state.value.trial.active?.presentations ?: "[]").length() == 2 }
        assertEquals(id, model().state.value.trial.active!!.id)
        rule.onNodeWithText("结束本次").performScrollTo().performClick(); waitIdle()
        assertNull(model().state.value.trial.active)
        val db = MealDatabase.open(rule.activity)
        try { runBlocking { assertEquals("MANUAL", db.trials().session(id)!!.endReason) } } finally { db.close() }
        rule.runOnIdle { model().withdrawTrial() }; waitIdle()
        assertFalse(model().state.value.trial.enabled)
        rule.onNodeWithText("吃什么？").performScrollTo().performClick()
        rule.waitUntil(10_000) { model().state.value.decision != null }
        assertNull(model().state.value.trial.active)
    }
    @Test fun emptyCandidatePoolClosesWithoutChoiceOrRelaxingRestrictions() {
        rule.runOnIdle { model().enrollTrial() }; waitIdle()
        val excluded = model().foods.map { it.id }.toSet()
        rule.runOnIdle { model().savePreferences(Preferences(excludedFoodIds = excluded)) }; waitIdle()
        rule.onNodeWithText("吃什么？").performClick()
        rule.waitUntil(10_000) { !model().state.value.busy && model().state.value.notice.startsWith("没有符合") }
        assertNull(model().state.value.decision)
        assertNull(model().state.value.trial.active)
        assertEquals(excluded, model().state.value.preferences.excludedFoodIds)
        val db = MealDatabase.open(rule.activity)
        try { runBlocking {
            val s = db.trials().sessions().single()
            assertEquals("NO_CANDIDATE", s.endReason)
            assertEquals(0, s.candidateCount)
            assertEquals(0, JSONArray(s.presentations).length())
            assertNull(s.selectedFoodId)
        } } finally { db.close() }
    }
}
