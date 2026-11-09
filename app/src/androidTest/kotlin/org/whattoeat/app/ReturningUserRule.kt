package org.whattoeat.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** 原有业务用例模拟已看过引导的用户；全新安装由GettingStartedTest独立验收。 */
class ReturningUserRule : ExternalResource() {
    override fun before() { check(GuideStore(InstrumentationRegistry.getInstrumentation().targetContext).markSeen()) }
}
