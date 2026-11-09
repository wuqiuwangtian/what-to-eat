package org.whattoeat.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.whattoeat.core.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoodLibraryPage(state: UiState, vm: MainViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("常吃") }
    var choosingStarter by rememberSaveable { mutableStateOf(state.library.favoriteIds.isEmpty()) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportLibrary) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importLibrary) }
    val keyboard = LocalSoftwareKeyboardController.current
    val snapshot = state.library
    val regular = if (choosingStarter) PersonalPool.starterIds + snapshot.favoriteIds else snapshot.favoriteIds.ifEmpty { PersonalPool.starterIds }
    val visible = snapshot.all.filter { food ->
        (when (tab) { "已移除" -> snapshot.isHidden(food.id); "常吃" -> !snapshot.isHidden(food.id) && food.id in regular; else -> !snapshot.isHidden(food.id) }) &&
            (query.isBlank() || food.name.contains(query.trim(), true) || food.aliases.any { it.contains(query.trim(), true) })
    }
    LazyColumn(Modifier.fillMaxSize().testTag("foodLibraryList"), contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it; if (it.isNotBlank() && tab == "常吃") tab = "全部"; vm.userInteracted() }, label = { Text("搜索菜名") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }), modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("常吃", "全部", "已移除").forEach { title -> FilterChip(tab == title, { tab = title }, label = { Text(title) }) }
                    TextButton(onClick = { editingId = null; adding = true }) { Text("新增") }
                }
                if (choosingStarter && tab == "常吃") {
                    Text("先选几样常吃", style = MaterialTheme.typography.bodySmall)
                    if (snapshot.favoriteIds.isNotEmpty()) TextButton(onClick = { choosingStarter = false }) { Text("选好了") }
                }
            }
        }
        if (visible.isEmpty()) item {
            Text("没有匹配的菜品", Modifier.padding(vertical = 16.dp))
            if (query.isNotBlank()) TextButton(onClick = { editingId = null; adding = true }) { Text("添加这道菜") }
        }
        items(visible, key = { it.id }) { food ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (tab != "已移除") Checkbox(food.id in snapshot.favoriteIds,
                    { vm.favoriteFood(food, it) }, enabled = !state.busy,
                    modifier = Modifier.testTag("favorite-${food.id}").semantics { contentDescription = "${food.name} · 常吃" })
                TextButton(onClick = { editingId = food.id }, modifier = Modifier.weight(1f)) {
                    Text(food.name, modifier = Modifier.fillMaxWidth())
                }
                if (tab == "已移除") TextButton(onClick = { vm.hideFood(food, false) }, enabled = !state.busy) { Text("恢复") }
            }
        }
        item {
            FlowRow {
                TextButton(onClick = { exporter.launch("what-to-eat-food-library.json") }, enabled = !state.busy) { Text("备份菜品库") }
                TextButton(onClick = { importer.launch(arrayOf("application/json")) }, enabled = !state.busy) { Text("恢复备份") }
            }
        }
    }
    val food = snapshot.all.find { it.id == editingId }
    if (adding || food != null) FoodEditor(food, if (adding) query else "", food?.id in snapshot.favoriteIds, vm,
        { adding = false; editingId = null }, hidden = food?.let { snapshot.isHidden(it.id) } == true)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoodEditor(food: Food?, initialName: String, initialFavorite: Boolean, vm: MainViewModel, dismiss: () -> Unit, hidden: Boolean = false) {
    val id = rememberSaveable(food?.id) { food?.id ?: FoodLibrary.newId() }
    var name by rememberSaveable(id) { mutableStateOf(food?.name ?: initialName) }
    var ingredients by rememberSaveable(id) { mutableStateOf(food?.ingredients?.joinToString(",").orEmpty()) }
    var known by rememberSaveable(id) { mutableStateOf(food?.ingredientsKnown ?: false) }
    var favorite by rememberSaveable(id) { mutableStateOf(initialFavorite) }
    var aliases by rememberSaveable(id) { mutableStateOf(food?.aliases?.joinToString(",").orEmpty()) }
    var groups by rememberSaveable(id) { mutableStateOf(food?.groups?.toList().orEmpty()) }
    var contexts by rememberSaveable(id) { mutableStateOf(food?.contexts?.toList() ?: listOf("外卖", "食堂", "自炊")) }
    var more by rememberSaveable(id) { mutableStateOf(false) }
    val state by vm.state.collectAsState()
    val busy = state.busy
    var savingRequested by rememberSaveable(id) { mutableStateOf(false) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (food == null) "新增菜品" else "编辑菜品", style = MaterialTheme.typography.headlineMedium)
                OutlinedTextField(name, { name = it.take(80); vm.userInteracted() }, label = { Text("菜名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(favorite, { favorite = it }); Text("加入常吃")
                }
                OutlinedTextField(ingredients, { ingredients = it.take(2000); known = false; vm.userInteracted() }, label = { Text("主要原料（可选）") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(known, { known = it }, enabled = ingredients.isNotBlank()); Text("已核对原料", Modifier.weight(1f))
                }
                if (!known) Text("有原料排除时，原料未确认的菜品不自动推荐。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { more = !more }) { Text(if (more) "收起其他设置" else "其他设置") }
                if (more) {
                    OutlinedTextField(aliases, { aliases = it.take(2000); vm.userInteracted() }, label = { Text("别名（逗号分隔）") }, modifier = Modifier.fillMaxWidth())
                    Text("类别", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("早餐", "正餐", "家常", "面食", "肉类", "蛋类", "鱼类", "水产", "豆类", "奶类", "蔬菜", "水果", "清淡", "辛辣").forEach { group ->
                            FilterChip(group in groups, { groups = if (group in groups) groups - group else groups + group }, label = { Text(group) })
                        }
                    }
                    Text("适用场景", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("外卖", "食堂", "自炊").forEach { context -> FilterChip(context in contexts,
                            { contexts = if (context in contexts) contexts - context else contexts + context }, label = { Text(context) }) }
                    }
                }
                val proposed = Food(id, name.trim(), ingredients.toValues(), groups.toSet(), contexts.toSet(), aliases.toValues(), "user-library", known)
                val duplicate = state.library.all.any { it.id != id && it.name.equals(name.trim(), true) }
                val valid = !duplicate && runCatching { FoodCodec.validate(proposed) }.isSuccess
                if (duplicate) Text("已有同名菜品，请在菜品库搜索后编辑或恢复。", color = MaterialTheme.colorScheme.error)
                if (savingRequested && !busy && state.notice.isNotBlank()) Text(state.notice, color = MaterialTheme.colorScheme.error)
                Button(onClick = { savingRequested = true; vm.saveFood(proposed, favorite, dismiss) }, enabled = valid && !busy) { Text("保存菜品") }
                if (food != null) {
                    TextButton(onClick = { vm.hideFood(food, !hidden); dismiss() }, enabled = !busy) { Text(if (hidden) "恢复菜品" else "移出菜品库") }
                    if (!food.id.startsWith("user:")) TextButton(onClick = { vm.restoreFood(food); dismiss() }, enabled = !busy) { Text("恢复默认内容") }
                    Text("移除不删除已有饮食记录，可在“已移除”恢复。", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = dismiss) { Text("取消") }
            }
        }
    }
}
