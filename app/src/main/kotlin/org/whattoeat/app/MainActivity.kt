package org.whattoeat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.whattoeat.core.*
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1D1D1F), onPrimary = Color.White,
                    secondary = Color(0xFF6E6E73), onSecondary = Color.White,
                    background = Color.White, surface = Color.White,
                    surfaceVariant = Color(0xFFF5F5F7), onSurfaceVariant = Color(0xFF6E6E73),
                    surfaceContainer = Color(0xFFF5F5F7), surfaceContainerLow = Color(0xFFF5F5F7),
                    surfaceContainerHighest = Color(0xFFF5F5F7), secondaryContainer = Color(0xFFF5F5F7),
                    onSecondaryContainer = Color(0xFF1D1D1F), outline = Color(0xFFD2D2D7),
                ),
                shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(14.dp),
                    large = RoundedCornerShape(20.dp)),
            ) { App() }
        }
    }
}
private val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)
private fun currentTimeText() = LocalDateTime.now().format(dateFormat)
private fun parseTime(value: String): Instant? = runCatching {
    LocalDateTime.parse(value, dateFormat).atZone(ZoneId.systemDefault()).toInstant()
}.getOrNull()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    key(state.dataGeneration) { AppContent(state, vm) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppContent(state: UiState, vm: MainViewModel) {
    var page by rememberSaveable { mutableStateOf("home") }
    val pages = rememberSaveableStateHolder()
    BackHandler(enabled = page != "home") { page = "home" }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::export) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        if (state.notice.isNotBlank()) snackbar.showSnackbar(state.notice)
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 580.dp).fillMaxSize().padding(horizontal = 24.dp)) {
                FlowRow(Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    horizontalArrangement = if (page == "home") Arrangement.End else Arrangement.SpaceBetween) {
                    if (page != "home") TextButton(onClick = { page = "home" }) {
                        Text("‹ 吃什么", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row {
                        TextButton(onClick = { page = "history" }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) { Text("记录") }
                        TextButton(onClick = { page = "settings" }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) { Text("设置") }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    pages.SaveableStateProvider(page) {
                    if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else if (page == "home") Home(state, vm,
                        { vm.resetAnalysis(); page = "meal" }, { vm.resetComparison(); page = "compare" })
                    else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        when (page) {
                            "meal" -> MealPage(state, vm)
                            "compare" -> ComparePage(state, vm)
                            "history" -> HistoryPage(state, vm, { exporter.launch("what-to-eat-records.json") })
                            "settings" -> SettingsPage(state, vm)
                        }
                    }
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Home(state: UiState, vm: MainViewModel, openMeal: () -> Unit, openCompare: () -> Unit) {
    var detailOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val mainHeight = (maxHeight - 80.dp).coerceAtLeast(240.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.fillMaxWidth().heightIn(min = mainHeight).padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                state.decision?.let { decision ->
                    Text(decision.food.name, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { detailOpen = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) { Text("为什么推荐") }
                    Spacer(Modifier.height(32.dp))
                }
                Button(onClick = vm::recommend, enabled = !state.busy,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(min = 220.dp).heightIn(min = 56.dp)) {
                    Text(if (state.decision == null) "吃什么？" else "换一个", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
                state.decision?.let { decision ->
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.feedback(EventType.PLANNED, decision.food) }, enabled = !state.busy) { Text("就它了") }
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)) {
                TextButton(onClick = openMeal, enabled = !state.busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) { Text("看看这顿") }
                TextButton(onClick = openCompare, enabled = !state.busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) { Text("帮我二选一") }
            }
        }
    }
    if (detailOpen) state.decision?.let { decision ->
        ModalBottomSheet(onDismissRequest = { detailOpen = false }, containerColor = Color.White) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(decision.food.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                decision.reasons.forEach { Text(it) }
                Text("实际配料请向商家确认。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                OutlinedButton(onClick = { vm.feedback(EventType.EATEN, decision.food) }, enabled = !state.busy) { Text("确认吃过") }
                FlowRow {
                    TextButton(onClick = { vm.feedback(EventType.PREFERRED, decision.food) }, enabled = !state.busy) { Text("我喜欢") }
                    TextButton(onClick = { vm.feedback(EventType.DISLIKED, decision.food) }, enabled = !state.busy) { Text("不太喜欢") }
                    TextButton(onClick = { vm.exclude(decision.food); detailOpen = false }, enabled = !state.busy) { Text("长期排除") }
                }
                var technical by remember { mutableStateOf(false) }
                TextButton(onClick = { technical = !technical }) { Text("决策详情") }
                if (technical) {
                    decision.factors.forEach { (name, score) -> Text("$name：${"%.2f".format(score)}", style = MaterialTheme.typography.bodySmall) }
                    Text("${decision.algorithmVersion} · ${decision.strategy}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { detailOpen = false }) { Text("收起依据") }
                Spacer(Modifier.height(16.dp))
            }
        }
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
    var text by rememberSaveable { mutableStateOf("") }
    var eaten by rememberSaveable { mutableStateOf(false) }
    var positive by rememberSaveable { mutableStateOf(false) }
    var time by rememberSaveable { mutableStateOf(currentTimeText()) }
    var selected by rememberSaveable(state.analysis) {
        mutableStateOf(state.analysis?.foods?.map(Food::id).orEmpty())
    }
    OutlinedTextField(text, { text = it; vm.resetAnalysis() }, label = { Text("餐食描述") }, placeholder = { Text("例如：准备吃牛肉面") }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
    Row {
        FilterChip(selected = !eaten, onClick = { eaten = false }, label = { Text("准备吃") })
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = eaten, onClick = { eaten = true }, label = { Text("已经吃过") })
    }
    OutlinedTextField(time, { time = it }, label = { Text("时间") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Button(onClick = { vm.analyze(text) }, enabled = text.isNotBlank() && !state.busy) { Text("看看搭配") }
    if (state.modelEnabled) TextButton(onClick = { vm.analyze(text, cloud = true) }, enabled = text.isNotBlank() && !state.busy) { Text("云端解析（上传文字）") }
    state.analysis?.let { analysis ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("核对识别结果", fontWeight = FontWeight.Bold)
                if (analysis.foods.isEmpty()) Text("未识别到已知餐食；可保留原文，不推测组成。")
                analysis.foods.forEach { food ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(food.id in selected, { checked -> selected = if (checked) (selected + food.id).distinct() else selected - food.id })
                        Text(food.name, Modifier.weight(1f))
                    }
                }
                analysis.suggestions.forEach { Text(it) }
                Text("仅供一般搭配参考，不提供疾病膳食方案。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
                FoodSearch("补充／纠正菜名", vm.foods) { food -> selected = (selected + food.id).distinct() }
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
                Button(onClick = { at?.let { vm.saveMeal(selected.toSet(), text, if (eaten) EventType.EATEN else EventType.PLANNED, it, positive) } }, enabled = validTime && !state.busy) { Text(if (eaten) "保存为吃过" else "保存为计划") }
            }
        }
    }
}

@Composable
private fun ComparePage(state: UiState, vm: MainViewModel) {
    Text("帮我二选一", style = MaterialTheme.typography.headlineMedium)
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
    OutlinedButton(onClick = export, enabled = !state.busy) { Text("导出记录与设置") }
    var onlyMeals by rememberSaveable { mutableStateOf(true) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(onlyMeals, { onlyMeals = it }); Text("只看餐食记录")
    }
    val visible = state.rows.filter { !onlyMeals || it.type in setOf("EATEN", "PLANNED", "ORDERED") }
    if (visible.isEmpty()) Text("暂无记录")
    var editing by remember { mutableStateOf<EventRow?>(null) }
    var deleting by remember { mutableStateOf<EventRow?>(null) }
    visible.take(200).forEach { row ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.foodName, style = MaterialTheme.typography.titleMedium)
                Text("${typeLabel(row.type)} · ${Instant.ofEpochMilli(row.time).atZone(ZoneId.systemDefault()).format(dateFormat)}")
                run {
                    var expanded by remember(row.id) { mutableStateOf(false) }
                    TextButton(onClick = { expanded = !expanded }) { Text("详情") }
                    if (expanded) {
                        Text("来源：${row.source} · 置信度 ${row.confidence}", style = MaterialTheme.typography.bodySmall)
                        if (row.detail.isNotBlank()) Text(row.detail, style = MaterialTheme.typography.bodySmall)
                    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsPage(state: UiState, vm: MainViewModel) {
    Text("偏好与数据", style = MaterialTheme.typography.headlineMedium)
    var excluded by remember(state.preferences) { mutableStateOf(state.preferences.excludedIngredients.joinToString(",")) }
    var preferred by remember(state.preferences) { mutableStateOf(state.preferences.preferredGroups.joinToString(",")) }
    var context by remember(state.preferences) { mutableStateOf(state.preferences.context) }
    var learning by remember(state.preferences) { mutableStateOf(state.preferences.learningEnabled) }
    OutlinedTextField(excluded, { excluded = it }, label = { Text("不吃的原料") }, placeholder = { Text("例如：花生,虾") }, modifier = Modifier.fillMaxWidth())
    Text("按常见配方过滤；过敏者仍需确认实际配料及交叉接触。", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(preferred, { preferred = it }, label = { Text("口味偏好") }, placeholder = { Text("例如：家常,面食,清淡") }, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("外卖", "食堂", "自炊").forEach { item -> FilterChip(context == item, { context = item }, label = { Text(item) }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) { Switch(learning, { learning = it }); Spacer(Modifier.width(8.dp)); Text("从本地记录学习偏好") }
    Button(onClick = { vm.savePreferences(state.preferences.copy(excludedIngredients = excluded.toValues(), preferredGroups = preferred.toValues(), context = context, learningEnabled = learning)) }, enabled = !state.busy) { Text("保存偏好") }
    if (state.preferences.excludedFoodIds.isNotEmpty()) {
        Text("长期排除 ${state.preferences.excludedFoodIds.size} 道餐食")
        TextButton(onClick = { vm.savePreferences(state.preferences.copy(excludedFoodIds = emptySet())) }, enabled = !state.busy) { Text("恢复所有长期排除的餐食") }
    }
    HorizontalDivider()
    var modelExpanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { modelExpanded = !modelExpanded }) { Text("云端模型 · ${if (state.modelEnabled) "已配置" else "未启用"}") }
    if (modelExpanded) {
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
    }
    HorizontalDivider()
    var toolsExpanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { toolsExpanded = !toolsExpanded }) { Text("验证工具") }
    if (toolsExpanded) {
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
    }
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
}
