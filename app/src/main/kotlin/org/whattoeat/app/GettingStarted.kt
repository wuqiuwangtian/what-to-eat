package org.whattoeat.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** UI偏好与饮食数据分开；首次展示时记录，退出/中断后不在下一次全新启动重复弹出。 */
internal class GuideStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun markSeen(): Boolean = preferences.edit().putBoolean("seen", true).commit()
    fun consumeFirstLaunch(): Boolean = !preferences.getBoolean("seen", false) && markSeen()
    companion object { const val FILE = "getting-started" }
}

internal data class GuideStep(val title: String, val paragraphs: List<String>)
internal val guideSteps = listOf(
    GuideStep("一键推荐", listOf(
        "点“吃什么？”看一个建议，不合适就“换一个”。",
        "想尝鲜时，点“想换换口味”。")),
    GuideStep("选几样常吃", listOf(
        "在“设置 → 菜品库”勾选常吃，推荐会优先从中选择。不选也能直接用。",
        "点菜名可编辑或移出；找不到的可以新增，移出的也能恢复。")),
    GuideStep("计划与吃过", listOf(
        "“就它了”只记计划。真的吃了，再到“记录”中改为吃过。",
        "“看看这顿”帮你核对搭配；“帮我二选一”在两个选项之间给建议。")),
    GuideStep("自愿试用", listOf(
        "愿意参加测试时，到“设置 → 本地试用”阅读说明，再勾选加入；随时可退出。",
        "记录留在本机。愿意分享时，在同一面板选“导出试用数据”。")),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GettingStarted(step: Int, next: () -> Unit, previous: () -> Unit, skip: () -> Unit,
    finish: () -> Unit, openLibrary: () -> Unit) {
    val content = guideSteps[step]
    Box(Modifier.fillMaxSize().testTag("gettingStarted"), contentAlignment = Alignment.TopCenter) {
        key(step) {
        Column(Modifier.widthIn(max = 580.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${step + 1} / ${guideSteps.size}", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.secondary)
                TextButton(onClick = skip) { Text("跳过") }
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(content.title, style = MaterialTheme.typography.headlineMedium)
                content.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step > 0) OutlinedButton(onClick = previous) { Text("上一步") }
                if (step < guideSteps.lastIndex) Button(onClick = next) { Text("下一步") }
                else Button(onClick = finish) { Text("开始使用") }
            }
            if (step == guideSteps.lastIndex) TextButton(onClick = openLibrary) { Text("去选常吃") }
        }
        }
    }
}
