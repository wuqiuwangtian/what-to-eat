package org.whattoeat.app

import android.graphics.Bitmap
import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** 专用测试安装：只复位引导标记，预置并核对合成事件/常吃不受引导影响。 */
class GettingStartedTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var db: MealDatabase
    private lateinit var events: List<EventRow>
    private lateinit var library: List<LibraryRow>
    private var initialLibrary = emptyList<LibraryRow>()
    private lateinit var eventId: String
    private var explicitlyCleared = false
    private var originalEvents = emptyList<EventRow>()

    @Before fun prepareFirstRun() = runBlocking {
        context.getSharedPreferences(GuideStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        db = MealDatabase.open(context)
        initialLibrary = db.library().rows()
        originalEvents = db.events().all()
        eventId = "guide-${UUID.randomUUID()}"
        db.events().put(listOf(EventRow(eventId, "PLANNED", "food-001", "引导前的合成记录", 1234, "test", 1.0, null)))
        db.library().put(LibraryRow("food-001", favorite = true))
        events = db.events().all(); library = db.library().rows()
    }
    @After fun preserveUserData() = runBlocking {
        scenario?.close()
        try {
            if (explicitlyCleared) {
                assertTrue(db.events().all().isEmpty()); assertTrue(db.library().rows().isEmpty())
            } else {
                assertEquals(events, db.events().all())
                assertEquals(library, db.library().rows())
            }
            assertNull(db.trials().config())
        } finally {
            db.events().delete(eventId)
            if (explicitlyCleared) db.events().put(originalEvents)
            db.library().clear(); initialLibrary.forEach { db.library().put(it) }
            db.close(); GuideStore(context).markSeen()
        }
    }
    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("gettingStarted").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithTag("recommendButton").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("capture_screenshots") != "true") return
        rule.waitForIdle()
        val directory = File(context.cacheDir, "guide-validation").apply { mkdirs() }
        directory.resolve("$name.png").outputStream().use {
            check(rule.onNodeWithTag("gettingStarted").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private fun home() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("recommendButton").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("gettingStarted").assertDoesNotExist()
    }
    @Test fun firstLaunchCanSkipAndNextLaunchDoesNotRepeat() {
        launch(); rule.onNodeWithText("一键推荐").assertExists(); capture("recommend")
        rule.onNodeWithText("跳过").performScrollTo().performClick(); home()
        scenario!!.close(); launch(); home()
    }
    @Test fun rotationRetainsProgressAndFullGuideCanOpenLibraryWithoutEnrollment() {
        launch()
        rule.onNodeWithText("下一步").performScrollTo().performClick()
        scenario!!.recreate()
        rule.onNodeWithText("选几样常吃").assertIsDisplayed(); capture("familiar")
        rule.onNodeWithText("下一步").performScrollTo().performClick()
        rule.onNodeWithText("计划与吃过").assertIsDisplayed(); capture("records")
        rule.onNodeWithText("下一步").performScrollTo().performClick()
        rule.onNodeWithText("自愿试用").assertIsDisplayed(); capture("trial")
        rule.onNodeWithText("去选常吃").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("foodLibraryList").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(context.getSharedPreferences(GuideStore.FILE, Context.MODE_PRIVATE).getBoolean("seen", false))
    }
    @Test fun settingsCanReplayAndBackReturnsToSettingsWithoutAutoRepeating() {
        launch(); rule.onNodeWithText("跳过").performScrollTo().performClick(); home()
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("不吃的原料").performScrollTo().performTextInput("尚未保存的原料草稿")
        rule.onNodeWithText("新手指引").performScrollTo().performClick()
        rule.onNodeWithText("一键推荐").assertExists()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("不吃的原料").performScrollTo().assertTextContains("尚未保存的原料草稿")
        scenario!!.close(); launch(); home()
    }
    @Test fun completedGuideStartsAtHomeAndClearingDietDoesNotRepeatGuide() {
        launch()
        rule.onNodeWithText("下一步").performSemanticsAction(SemanticsActions.OnClick) { click -> repeat(8) { click() } }
        rule.onNodeWithText("自愿试用").assertIsDisplayed()
        rule.onNodeWithText("上一步").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { click -> repeat(8) { click() } }
        rule.onNodeWithText("一键推荐").assertIsDisplayed()
        repeat(3) { rule.onNodeWithText("下一步").performScrollTo().performClick() }
        rule.onNodeWithText("开始使用").performScrollTo().performClick(); home()
        explicitlyCleared = true
        lateinit var model: MainViewModel
        scenario!!.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java]; model.clearAll() }
        rule.waitUntil(10_000) { !model.state.value.busy && model.state.value.rows.isEmpty() }
        home()
        scenario!!.close(); launch(); home()
    }
}
