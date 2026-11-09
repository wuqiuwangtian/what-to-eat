package org.whattoeat.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 非清空检查；不勾选同意、不加入试用，不截图个人偏好输入框。 */
class TrialConsentTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun viewingConsentDoesNotEnrollOrExposePersonalPreferences() {
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        rule.waitUntil(15_000) { !vm.state.value.loading }
        assertFalse(vm.state.value.trial.enabled)
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("本地试用 · 未参加").performScrollTo().performClick()
        rule.onNodeWithText("加入试用").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("我愿意参加本地试用").assertIsOff()
        if (InstrumentationRegistry.getArguments().getString("capture_screenshots") == "true") {
            rule.onNodeWithTag("trialControls").performScrollTo()
            rule.waitForIdle(); android.os.SystemClock.sleep(400); rule.waitForIdle()
            val dir = File(rule.activity.cacheDir, "validation-screenshots").apply { mkdirs() }
            dir.resolve("trial-consent.png").outputStream().use {
                check(rule.onNodeWithTag("trialControls").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        assertFalse(vm.state.value.trial.enabled)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
    }
}
