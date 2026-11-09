package org.whattoeat.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.whattoeat.core.*
import java.io.File

/** 含清空用例，只在专用测试安装运行。 */
class FoodLibraryInteractionTest {
    @get:Rule(order = 0) val returningUser = ReturningUserRule()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    private fun model() = ViewModelProvider(rule.activity)[MainViewModel::class.java]
    private fun idle() = rule.waitUntil(15_000) { !model().state.value.loading && !model().state.value.busy }
    @Before fun reset() { idle(); rule.runOnIdle { model().clearAll() }; idle() }
    @After fun cleanup() { idle(); rule.runOnIdle { model().clearAll() }; idle() }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("capture_screenshots") != "true") return
        rule.waitForIdle()
        val notice = model().state.value.notice
        if (notice.isNotBlank()) rule.waitUntil(8_000) { rule.onAllNodesWithText(notice).fetchSemanticsNodes().isEmpty() }
        android.os.SystemClock.sleep(350)
        val folder = File(rule.activity.cacheDir, "library-validation").apply { mkdirs() }
        folder.resolve("$name.png").outputStream().use {
            check((if (name == "editor") rule.onNodeWithText("新增菜品").onParent() else rule.onRoot()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private fun library() {
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("菜品库").performScrollTo().performClick()
    }
    @Test fun searchAddFavoriteRecommendAndRecreateKeepThePersonalChoice() {
        library(); capture("starter")
        rule.onNodeWithText("全部").performScrollTo().performClick()
        rule.onNodeWithText("搜索菜名").performTextInput("我的测试焖饭")
        rule.onNodeWithText("搜索菜名").performImeAction()
        rule.onNodeWithTag("foodLibraryList").performScrollToNode(hasText("添加这道菜"))
        rule.onNodeWithText("添加这道菜").performScrollTo().performClick(); capture("editor")
        rule.onAllNodes(isToggleable()).onFirst().performScrollTo().performClick()
        rule.onNodeWithText("主要原料（可选）").performTextInput("牛肉,稻米")
        rule.onNodeWithText("保存菜品").performScrollTo().performClick(); idle()
        assertTrue(model().state.value.library.favoriteIds.size == 1)
        assertTrue(model().state.value.rows.isEmpty())
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("吃什么？").performScrollTo().performClick(); idle()
        rule.waitUntil(10_000) { model().state.value.decision != null }
        assertEquals("我的测试焖饭", model().state.value.decision!!.food.name)
        capture("recommendation")
        val id = model().state.value.decision!!.food.id
        rule.activityRule.scenario.recreate(); idle()
        assertTrue(id in model().state.value.library.favoriteIds)
        assertFalse(model().state.value.rows.any { it.type == "EATEN" })
    }
    @Test fun hiddenFoodsCanBeRestoredAndLibraryBackupDoesNotContainMeals() {
        val food = Catalog.load().first()
        rule.runOnIdle { model().hideFood(food, true) }; idle()
        library()
        rule.onNodeWithText("已移除").performScrollTo().performClick()
        rule.onNodeWithTag("foodLibraryList").performScrollToNode(hasText(food.name))
        rule.onNodeWithText(food.name).assertExists()
        rule.onNodeWithTag("foodLibraryList").performScrollToNode(hasText("新增"))
        rule.onNodeWithText("新增").performScrollTo().performClick()
        rule.onNodeWithText("菜名").performTextInput(food.name)
        rule.onNodeWithText("已有同名菜品，请在菜品库搜索后编辑或恢复。").performScrollTo().assertExists()
        rule.onNodeWithText("保存菜品").assertIsNotEnabled()
        rule.onNodeWithText("取消").performScrollTo().performClick()
        rule.onNodeWithText("恢复").performScrollTo().performClick(); idle()
        assertTrue(model().foods.any { it.id == food.id })
        rule.onNodeWithText("常吃").performScrollTo().performClick(); capture("restored")
        val file = File(rule.activity.cacheDir, "library-test-backup.json")
        rule.runOnIdle { model().exportLibrary(Uri.fromFile(file)) }; idle()
        assertFalse(file.readText().contains("events"))
        rule.runOnIdle { model().importLibrary(Uri.fromFile(file)) }; idle()
        assertTrue(model().foods.any { it.id == food.id })
        file.delete()
    }
    @Test fun choosingSeveralStarterFoodsDoesNotCollapseAfterTheFirstCheck() {
        library()
        rule.onNodeWithTag("foodLibraryList").performScrollToNode(hasTestTag("favorite-food-001"))
        rule.onNodeWithTag("favorite-food-001").performClick(); idle()
        rule.onNodeWithTag("foodLibraryList").performScrollToNode(hasTestTag("favorite-food-002"))
        rule.onNodeWithTag("favorite-food-002").performClick(); idle()
        assertEquals(setOf("food-001", "food-002"), model().state.value.library.favoriteIds)
        rule.onNodeWithTag("foodLibraryList").performScrollToNode(hasText("选好了"))
        rule.onNodeWithText("选好了").performScrollTo().performClick()
        assertTrue(model().state.value.rows.isEmpty())
    }

    @Test fun unavailableThisRoundIsNotADislikeAndNewRoundRestoresIt() {
        val food = Catalog.load().first()
        rule.runOnIdle { model().favoriteFood(food, true) }; idle()
        rule.onNodeWithText("吃什么？").performScrollTo().performClick(); idle()
        rule.runOnIdle { model().unavailableNow(food) }; idle()
        rule.onNodeWithText("吃什么？").performScrollTo().performClick(); idle()
        assertNull(model().state.value.decision)
        assertFalse(model().state.value.rows.any { it.type == "DISLIKED" })
        assertTrue(food.id in model().state.value.library.favoriteIds)
        rule.runOnIdle { model().savePreferences(model().state.value.preferences) }; idle()
        rule.onNodeWithText("吃什么？").performScrollTo().performClick(); idle()
        assertEquals(food.id, model().state.value.decision!!.food.id)
    }
}
