package org.whattoeat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.whattoeat.core.*
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF96512F), secondary = Color(0xFF466753),
                background = Color(0xFFFAF8F3), surface = Color(0xFFFFFCF7))) { App() }
        }
    }
}
private val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)
private fun currentTimeText() = LocalDateTime.now().format(dateFormat)
private fun parseTime(value: String): Instant? = runCatching {
    LocalDateTime.parse(value, dateFormat).atZone(ZoneId.systemDefault()).toInstant()
}.getOrNull()

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("home") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::export) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().fillMaxWidth().widthIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { page = "home" }) { Text("吃什么", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                Row {
                    TextButton(onClick = { page = "history" }) { Text("记录") }
                    TextButton(onClick = { page = "settings" }) { Text("设置") }
                }
            }
            if (state.notice.isNotBlank()) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Text(state.notice, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.loading) {
                CircularProgressIndicator()
                Text("正在读取本地餐食库…")
            } else when (page) {
                "home" -> Home(state, vm, { vm.resetAnalysis(); page = "meal" }, { vm.resetComparison(); page = "compare" })
                "meal" -> MealPage(state, vm)
                "compare" -> ComparePage(state, vm)
                "history" -> HistoryPage(state, vm, { exporter.launch("what-to-eat-records.json") })
                "settings" -> SettingsPage(state, vm)
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text("记录留在本机 · 云端功能由你主动开启", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun Home(state: UiState, vm: MainViewModel, openMeal: () -> Unit, openCompare: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Text("少一点纠结，\n好好吃这一顿。", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
    Text("不用填问卷，也不用配置 AI。\n先从一个简单的答案开始。", style = MaterialTheme.typography.bodyLarge)
    state.decision?.let { decision ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("这顿可以吃", style = MaterialTheme.typography.labelLarge)
                Text(decision.food.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(decision.reasons.first(), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.feedback(EventType.PLANNED, decision.food) }, enabled = !state.busy) { Text("就它了") }
                    TextButton(onClick = { vm.feedback(EventType.EATEN, decision.food) }, enabled = !state.busy) { Text("确认吃过") }
                }
                var expanded by remember(decision) { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起依据" else "为什么推荐") }
                if (expanded) {
                    decision.reasons.forEach { Text("· $it") }
                    decision.factors.forEach { (name, score) -> Text("$name：${"%.2f".format(score)}") }
                    Text("${decision.algorithmVersion} · ${decision.strategy}\n依据：本地菜品标签、明确设置、允许学习的历史记录。\n不是实时商家信息；实际原料需向商家确认。", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { vm.feedback(EventType.PREFERRED, decision.food) }, enabled = !state.busy) { Text("我喜欢") }
                        TextButton(onClick = { vm.feedback(EventType.DISLIKED, decision.food) }, enabled = !state.busy) { Text("不太喜欢") }
                        TextButton(onClick = { vm.exclude(decision.food) }, enabled = !state.busy) { Text("长期排除") }
                    }
                }
            }
        }
    }
    Button(onClick = vm::recommend, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(60.dp)) {
        Text(if (state.decision == null) "吃什么？" else "换一个", style = MaterialTheme.typography.titleLarge)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = openMeal, modifier = Modifier.weight(1f), enabled = !state.busy) { Text("看看这顿") }
        OutlinedButton(onClick = openCompare, modifier = Modifier.weight(1f), enabled = !state.busy) { Text("帮我二选一") }
    }
}

@Composable
private fun FoodSearch(label: String, foods: List<Food>, initial: String = "", onChange: () -> Unit = {}, onSelect: (Food) -> Unit) {
    var query by rememberSaveable { mutableStateOf(initial) }
    var selected by rememberSaveable { mutableStateOf(initial.isNotBlank()) }
    OutlinedTextField(value = query, onValueChange = { query = it; selected = false; onChange() }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    if (query.isNotBlank() && !selected) {
        val matches = foods.filter { it.name.contains(query) || it.aliases.any { alias -> alias.contains(query) } }.take(6)
        if (matches.isEmpty()) Text("暂无匹配，可换一个常见菜名。", style = MaterialTheme.typography.bodySmall)
        matches.forEach { food ->
            TextButton(onClick = { query = food.name; selected = true; onSelect(food) }) { Text(food.name) }
        }
    }
}

@Composable
private fun MealPage(state: UiState, vm: MainViewModel) {
    Text("看看这顿", style = MaterialTheme.typography.headlineMedium)
    Text("说说准备吃什么，或刚吃了什么。先给建议，再由你决定是否记录。")
    var text by rememberSaveable { mutableStateOf("") }
    var eaten by rememberSaveable { mutableStateOf(false) }
    var positive by rememberSaveable { mutableStateOf(false) }
    var time by rememberSaveable { mutableStateOf(currentTimeText()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(state.analysis) { selected = state.analysis?.foods?.map(Food::id)?.toSet().orEmpty() }
    OutlinedTextField(text, { text = it; vm.resetAnalysis() }, label = { Text("餐食描述") }, placeholder = { Text("例如：准备吃牛肉面") }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
    Row {
        FilterChip(selected = !eaten, onClick = { eaten = false }, label = { Text("准备吃") })
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = eaten, onClick = { eaten = true }, label = { Text("已经吃过") })
    }
    OutlinedTextField(time, { time = it }, label = { Text("时间（yyyy-MM-dd HH:mm）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Button(onClick = { vm.analyze(text) }, enabled = text.isNotBlank() && !state.busy) { Text("本地看看搭配") }
    if (state.modelEnabled) TextButton(onClick = { vm.analyze(text, cloud = true) }, enabled = text.isNotBlank() && !state.busy) { Text("用已配置模型解析（上传这段文字）") }
    state.analysis?.let { analysis ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("核对识别结果", fontWeight = FontWeight.Bold)
                if (analysis.foods.isEmpty()) Text("未识别到已知餐食；可保留原文，不推测组成。")
                analysis.foods.forEach { food ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(food.id in selected, { checked -> selected = if (checked) selected + food.id else selected - food.id })
                        Text(food.name, Modifier.weight(1f))
                    }
                }
                analysis.suggestions.forEach { Text(it) }
                FoodSearch("补充／纠正菜名", vm.foods) { food -> selected = selected + food.id }
                selected.filter { id -> analysis.foods.none { it.id == id } }.forEach { id ->
                    TextButton(onClick = { selected = selected - id }) { Text("已添加：${vm.foods.first { it.id == id }.name}（点此移除）") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(positive, { positive = it })
                    Text("我喜欢这顿（独立的可选反馈）", Modifier.weight(1f))
                }
                val at = parseTime(time)
                val validTime = at != null && (!eaten || !at.isAfter(Instant.now().plusSeconds(300)))
                if (!validTime) Text("请检查时间；吃过的餐食不能记录为未来时间。", color = MaterialTheme.colorScheme.error)
                Button(onClick = { at?.let { vm.saveMeal(selected, text, if (eaten) EventType.EATEN else EventType.PLANNED, it, positive) } }, enabled = validTime && !state.busy) { Text(if (eaten) "保存为吃过" else "保存为计划") }
            }
        }
    }
    Text("一般性搭配参考，不计算精确热量，不提供疾病膳食方案。", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ComparePage(state: UiState, vm: MainViewModel) {
    Text("帮我二选一", style = MaterialTheme.typography.headlineMedium)
    Text("把备选交给我，最后怎么选仍由你决定。")
    var first by rememberSaveable { mutableStateOf<String?>(null) }
    var second by rememberSaveable { mutableStateOf<String?>(null) }
    FoodSearch("第一道菜", vm.foods, onChange = { first = null; vm.resetComparison() }) { first = it.id; vm.resetComparison() }
    FoodSearch("第二道菜", vm.foods, onChange = { second = null; vm.resetComparison() }) { second = it.id; vm.resetComparison() }
    Button(onClick = { vm.compare(first.orEmpty(), second.orEmpty()) }, enabled = first != null && second != null && first != second && !state.busy) { Text("帮我选一个") }
    state.compared?.let { d ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("今天建议选", style = MaterialTheme.typography.labelLarge)
                Text(d.food.name, style = MaterialTheme.typography.titleLarge)
                d.reasons.forEach { Text(it) }
                Text("你的实际选择是？")
                state.comparisonFoods.forEach { food ->
                    OutlinedButton(onClick = { vm.feedback(EventType.COMPARED, food) }, enabled = !state.busy) { Text("我选 ${food.name}") }
                }
                Text("选择仅用于偏好学习，不会自动记成吃过。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun typeLabel(type: String) = when (type) {
    "SHOWN" -> "推荐展示"; "SKIPPED" -> "本轮换选"; "PLANNED" -> "打算吃"; "EATEN" -> "确认吃过"
    "ORDERED" -> "曾下单"; "PREFERRED" -> "明确喜欢"; "DISLIKED" -> "不太喜欢"; "COMPARED" -> "比较选择"
    else -> type
}

@Composable
private fun HistoryPage(state: UiState, vm: MainViewModel, export: () -> Unit) {
    Text("我的记录", style = MaterialTheme.typography.headlineMedium)
    Text("推荐过、打算吃和确认吃过分别记录。删除或修改后，后续推荐按剩余记录重新计算。")
    OutlinedButton(onClick = export, enabled = !state.busy) { Text("导出记录与设置") }
    var onlyMeals by rememberSaveable { mutableStateOf(true) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(onlyMeals, { onlyMeals = it }); Text("只看餐食记录")
    }
    val visible = state.rows.filter { !onlyMeals || it.type in setOf("EATEN", "PLANNED", "ORDERED") }
    if (visible.isEmpty()) Text("还没有这类记录。你可以完全不记录，照常使用推荐。")
    var editing by remember { mutableStateOf<EventRow?>(null) }
    var deleting by remember { mutableStateOf<EventRow?>(null) }
    visible.take(200).forEach { row ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.foodName, style = MaterialTheme.typography.titleMedium)
                Text("${typeLabel(row.type)} · ${Instant.ofEpochMilli(row.time).atZone(ZoneId.systemDefault()).format(dateFormat)}")
                Text("来源：${row.source} · 置信度 ${row.confidence}", style = MaterialTheme.typography.bodySmall)
                if (row.detail.isNotBlank()) {
                    var expanded by remember(row.id) { mutableStateOf(false) }
                    TextButton(onClick = { expanded = !expanded }) { Text("查看决策记录") }
                    if (expanded) Text(row.detail, style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    if (row.type in setOf("EATEN", "PLANNED", "ORDERED")) TextButton(onClick = { editing = row }, enabled = !state.busy) { Text("修改") }
                    TextButton(onClick = { deleting = row }, enabled = !state.busy) { Text("删除") }
                }
            }
        }
    }
    if (visible.size > 200) Text("仅展示最近 200 条；导出包含全部记录。")
    editing?.let { row -> EditDialog(row, vm.foods, onDismiss = { editing = null }) { id, name, type, at ->
        vm.edit(row, id, name, type, at); editing = null
    } }
    deleting?.let { row ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条记录？") },
            text = { Text("${typeLabel(row.type)}：${row.foodName}。删除后将不再用于画像。") },
            confirmButton = { TextButton(onClick = { vm.delete(row); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } })
    }
}

@Composable
private fun EditDialog(row: EventRow, foods: List<Food>, onDismiss: () -> Unit, onSave: (String?, String, EventType, Instant) -> Unit) {
    var name by remember { mutableStateOf(row.foodName) }
    var id by remember { mutableStateOf(foods.find { it.id == row.foodId }?.id) }
    var type by remember { mutableStateOf(EventType.valueOf(row.type)) }
    var time by remember { mutableStateOf(Instant.ofEpochMilli(row.time).atZone(ZoneId.systemDefault()).format(dateFormat)) }
    val at = parseTime(time)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("纠正餐食记录") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it; id = foods.firstOrNull { f -> f.name == it }?.id }, label = { Text("菜名；未知配方可保留原文") })
            FoodSearch("匹配已知菜品（可选）", foods) { id = it.id; name = it.name }
            OutlinedTextField(time, { time = it }, label = { Text("yyyy-MM-dd HH:mm") })
            listOf(EventType.PLANNED, EventType.EATEN, EventType.ORDERED).forEach { t ->
                FilterChip(type == t, onClick = { type = t }, label = { Text(typeLabel(t.name)) })
            }
        }
    }, confirmButton = {
        TextButton(onClick = { at?.let { onSave(id, name, type, it) } }, enabled = name.isNotBlank() && at != null && (type != EventType.EATEN || !at.isAfter(Instant.now().plusSeconds(300)))) { Text("保存修改") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun SettingsPage(state: UiState, vm: MainViewModel) {
    Text("偏好与数据", style = MaterialTheme.typography.headlineMedium)
    var excluded by remember(state.preferences) { mutableStateOf(state.preferences.excludedIngredients.joinToString(",")) }
    var preferred by remember(state.preferences) { mutableStateOf(state.preferences.preferredGroups.joinToString(",")) }
    var context by remember(state.preferences) { mutableStateOf(state.preferences.context) }
    var learning by remember(state.preferences) { mutableStateOf(state.preferences.learningEnabled) }
    OutlinedTextField(excluded, { excluded = it }, label = { Text("明确排除的原料，用逗号分隔") }, placeholder = { Text("例如：花生,虾") }, modifier = Modifier.fillMaxWidth())
    Text("仅依据典型配方过滤，未填写不代表没有限制；过敏者仍需确认实际配料及交叉接触风险。", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(preferred, { preferred = it }, label = { Text("偏好标签（可留空）") }, placeholder = { Text("例如：家常,面食,清淡") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("外卖", "食堂", "自炊").forEach { item -> FilterChip(context == item, { context = item }, label = { Text(item) }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) { Switch(learning, { learning = it }); Spacer(Modifier.width(8.dp)); Text("从本地记录学习偏好") }
    Button(onClick = { vm.savePreferences(state.preferences.copy(excludedIngredients = excluded.toValues(), preferredGroups = preferred.toValues(), context = context, learningEnabled = learning)) }, enabled = !state.busy) { Text("保存偏好") }
    if (state.preferences.excludedFoodIds.isNotEmpty()) {
        Text("长期排除 ${state.preferences.excludedFoodIds.size} 道餐食")
        TextButton(onClick = { vm.savePreferences(state.preferences.copy(excludedFoodIds = emptySet())) }, enabled = !state.busy) { Text("恢复所有长期排除的餐食") }
    }
    HorizontalDivider()
    Text("可选云端模型", style = MaterialTheme.typography.titleLarge)
    Text("应用不提供模型服务。启用后，主动点击解析会把该段文字与菜品库发送给你指定的提供商，可能产生费用；普通推荐与换选不联网。")
    Text("当前：${if (state.modelEnabled) "已配置 ${state.modelName}" else "关闭"}")
    var base by remember(state.modelBaseUrl) { mutableStateOf(state.modelBaseUrl) }
    var model by remember(state.modelName) { mutableStateOf(state.modelName) }
    var key by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    OutlinedTextField(base, { base = it }, label = { Text("HTTPS 基础地址（含 /v1）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(model, { model = it }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(key, { key = it }, label = { Text(if (state.modelEnabled) "API Key（留空保留已存密钥）" else "API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent, { consent = it }); Text("我了解数据将发往该提供商，并自行承担调用费用。", Modifier.weight(1f)) }
    Button(onClick = { vm.saveModel(base, model, key); key = "" }, enabled = consent && !state.busy) { Text("保存并启用云端配置") }
    if (state.modelEnabled) TextButton(onClick = vm::disableModel, enabled = !state.busy) { Text("关闭云端并删除密钥") }
    HorizontalDivider()
    Text("验证工具", style = MaterialTheme.typography.titleLarge)
    Text("可切换本地策略检查行为。下面的单次输出对照不代表哪种方案效果更好。")
    Strategy.entries.forEach { strategy ->
        FilterChip(state.strategy == strategy, { vm.selectStrategy(strategy) }, label = { Text(when (strategy) {
            Strategy.RANDOM -> "受约束随机"; Strategy.SIMPLE -> "简单历史规则"; Strategy.PERSONALIZED -> "完整轻量策略"
        }) }, enabled = !state.busy)
    }
    if (state.modelEnabled) {
        Text("大模型对照将发送同一候选池、明确设置和允许学习的结构化历史。不会自动保存推荐为饮食。")
        OutlinedButton(onClick = vm::benchmarkCloud, enabled = !state.busy) { Text("运行四组输出对照（上传历史）") }
    }
    if (state.benchmark.isNotBlank()) Text(state.benchmark)
    HorizontalDivider()
    var clearing by remember { mutableStateOf(false) }
    TextButton(onClick = { clearing = true }, enabled = !state.busy) { Text("清除全部本地数据") }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("清除全部本地数据？") },
        text = { Text("将删除记录、偏好与 API 配置，无法撤销。已导出的文件和第三方服务商留存不会随之删除。") },
        confirmButton = { TextButton(onClick = { vm.clearAll(); clearing = false }) { Text("确认清除") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("取消") } })
    val appContext = LocalContext.current
    var licenses by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = {
        licenses = listOf("PROJECT-LICENSE.txt", "THIRD_PARTY_NOTICES.md", "Apache-2.0.txt").joinToString("\n\n") { file ->
            appContext.assets.open(file).bufferedReader().use { it.readText() }
        }
    }) { Text("开源许可") }
    licenses?.let { content -> AlertDialog(onDismissRequest = { licenses = null }, title = { Text("开源许可与归属") },
        text = { Text(content, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { licenses = null }) { Text("关闭") } }) }
    Text("开发预览 · MIT\n无账号、无应用后端、无自动遥测。仅提供一般性搭配参考。", style = MaterialTheme.typography.bodySmall)
}
