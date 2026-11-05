package org.whattoeat.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import org.whattoeat.core.*
import java.time.Instant
import java.util.UUID

data class UiState(
    val dataGeneration: Int = 0,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val rows: List<EventRow> = emptyList(),
    val preferences: Preferences = Preferences(),
    val decision: Decision? = null,
    val analysis: MealAnalysis? = null,
    val compared: Decision? = null,
    val comparisonFoods: List<Food> = emptyList(),
    val notice: String = "",
    val modelEnabled: Boolean = false,
    val modelBaseUrl: String = "",
    val modelName: String = "",
    val benchmark: String = "",
    val strategy: Strategy = Strategy.PERSONALIZED,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val foods = Catalog.load()
    private val engine = Recommender()
    private val analyzer = MealAnalyzer(foods)
    private val database = MealDatabase.open(application)
    private val settings = SettingsStore(application)
    private val vault = SecureVault(application)
    private var modelConfig: ModelConfig? = null
    private var modelJob: Job? = null
    private var shown = mutableSetOf<String>()
    private var sessionId = UUID.randomUUID().toString()
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = withContext(Dispatchers.IO) { settings.read() }
            val config = withContext(Dispatchers.IO) { runCatching { vault.read() }.getOrNull() }
            modelConfig = config
            mutable.update { it.copy(preferences = prefs, modelEnabled = config != null,
                modelBaseUrl = config?.baseUrl.orEmpty(), modelName = config?.model.orEmpty()) }
            database.events().observe().collect { rows ->
                mutable.update { it.copy(rows = rows, loading = false) }
            }
        }
    }

    private fun history() = mutable.value.rows.map(EventRow::domain)
    private fun context() = RequestContext(preferences = mutable.value.preferences, shownIds = shown.toSet(), sessionId = sessionId)
    private fun row(type: EventType, food: Food, at: Instant = Instant.now(), detail: String = "") =
        EventRow(UUID.randomUUID().toString(), type.name, food.id, food.name, at.toEpochMilli(), "user", 1.0, sessionId, detail)

    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy || mutable.value.loading) return
        mutable.update { it.copy(busy = true, notice = "") }
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(notice = "操作未完成，请重试；不会自动放宽限制或清空数据库。") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun recommend() = action {
        val before = mutable.value
        val decision = engine.recommend(foods, history(), context(), before.strategy)
        if (decision == null) {
            mutable.update { it.copy(decision = null, notice = "没有符合当前限制的候选；请到设置检查限制，不会自动放宽。") }
        } else {
            if (decision.startsNewRound) shown.clear()
            shown += decision.food.id
            val detail = JSONObject().put("algorithmVersion", decision.algorithmVersion).put("strategy", decision.strategy.name)
                .put("score", decision.score).put("factors", JSONObject(decision.factors))
                .put("reasons", JSONArray(decision.reasons)).put("candidateCount", engine.eligible(foods, before.preferences).size).toString()
            val rows = buildList {
                before.decision?.let { add(row(EventType.SKIPPED, it.food)) }
                add(row(EventType.SHOWN, decision.food, detail = detail).copy(source = "recommendation"))
            }
            database.events().put(rows)
            mutable.update { it.copy(decision = decision, notice = if (decision.startsNewRound) "这一轮都看过了，已开始新一轮。" else "") }
        }
    }

    fun feedback(type: EventType, food: Food) = action {
        require(type in setOf(EventType.PLANNED, EventType.EATEN, EventType.PREFERRED, EventType.DISLIKED, EventType.COMPARED))
        database.events().put(listOf(row(type, food)))
        mutable.update { it.copy(notice = when (type) {
            EventType.PLANNED -> "已记录打算吃；尚未计入实际饮食。"
            EventType.EATEN -> "已记录为你确认吃过的餐食。"
            EventType.COMPARED -> "已记录你的比较选择；不代表已吃过。"
            else -> "已记录这次明确偏好，可在历史中删除。"
        }) }
    }

    fun exclude(food: Food) = action {
        val prefs = mutable.value.preferences.let { it.copy(excludedFoodIds = it.excludedFoodIds + food.id) }
        withContext(Dispatchers.IO) { settings.write(prefs) }
        shown.clear()
        mutable.update { it.copy(preferences = prefs, decision = null, compared = null, notice = "已加入长期排除；可在设置恢复。") }
    }

    fun analyze(text: String, cloud: Boolean = false) {
        if (text.isBlank() || text.length > 2000) { mutable.update { it.copy(notice = "请输入 1–2000 字餐食描述。") }; return }
        if (!cloud) { mutable.update { it.copy(analysis = analyzer.analyze(text), notice = "") }; return }
        val config = modelConfig ?: return
        modelJob?.cancel()
        modelJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, notice = "正在解析；不会自动保存记录。") }
            try {
                val ids = CompatibleModelProvider(config).normalize(text, foods)
                mutable.update { it.copy(analysis = analyzer.assess(foods.filter { f -> f.id in ids }, if (ids.isEmpty()) text else null), notice = "") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(analysis = analyzer.analyze(text), notice = "模型不可用，已回退本地解析；未保存。") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun resetAnalysis() { mutable.update { it.copy(analysis = null) } }

    fun saveMeal(foodIds: Set<String>, raw: String, type: EventType, at: Instant, positive: Boolean) = action {
        require(type == EventType.EATEN || type == EventType.PLANNED)
        require(raw.isNotBlank() && raw.length <= 2000)
        require(!at.isAfter(Instant.now().plusSeconds(300)) || type == EventType.PLANNED)
        val known = foods.filter { it.id in foodIds }
        val chosen = known.ifEmpty { listOf(Food("custom:${UUID.randomUUID()}", raw.trim(), emptySet(), emptySet(), emptySet())) }
        val rows = chosen.flatMap { food -> buildList {
            add(row(type, food, at).copy(source = if (known.isEmpty()) "user-unmatched" else "user-confirmed"))
            if (positive) add(row(EventType.PREFERRED, food, at))
        } }
        database.events().put(rows)
        mutable.update { it.copy(analysis = null, notice = "已保存${if (type == EventType.EATEN) "吃过" else "计划"}记录；未匹配的菜名不参与类别分析。") }
    }

    fun compare(firstId: String, secondId: String) {
        val pair = foods.filter { it.id == firstId || it.id == secondId }
        if (pair.size != 2) { mutable.update { it.copy(notice = "请选择两道不同的已知菜品。") }; return }
        val d = engine.recommend(pair, history(), context().copy(shownIds = emptySet()), mutable.value.strategy)
        mutable.update { it.copy(compared = d, comparisonFoods = pair, notice = if (d == null) "两道菜都不符合当前限制。" else "") }
    }
    fun resetComparison() { mutable.update { it.copy(compared = null, comparisonFoods = emptyList()) } }

    fun delete(row: EventRow) = action {
        database.events().delete(row.id)
        invalidate("已删除记录，后续推荐按剩余记录重新计算。")
    }
    fun edit(row: EventRow, foodId: String?, name: String, type: EventType, at: Instant) = action {
        require(type in setOf(EventType.EATEN, EventType.PLANNED, EventType.ORDERED))
        require(name.isNotBlank())
        require(type != EventType.EATEN || !at.isAfter(Instant.now().plusSeconds(300)))
        val known = foods.find { it.id == foodId }
        database.events().put(listOf(row.copy(type = type.name, foodId = known?.id ?: "custom:${row.id}",
            foodName = known?.name ?: name.trim().take(2000), time = at.toEpochMilli(), source = "user-corrected", detail = "")))
        invalidate("已修改记录，画像将在后续推荐中重新计算。")
    }
    private fun invalidate(message: String) {
        shown.clear()
        sessionId = UUID.randomUUID().toString()
        mutable.update { it.copy(decision = null, compared = null, benchmark = "", notice = message) }
    }

    fun savePreferences(prefs: Preferences) = action {
        withContext(Dispatchers.IO) { settings.write(prefs) }
        mutable.update { it.copy(preferences = prefs) }
        invalidate("设置已保存。限制依据常见配方标签，实际商家配料需自行确认。")
    }
    fun selectStrategy(strategy: Strategy) { mutable.update { it.copy(strategy = strategy) }; invalidate("本地策略已切换。") }

    fun saveModel(base: String, model: String, key: String) = action {
        val config = ModelConfig(base.trim(), model.trim(), key.ifBlank { modelConfig?.apiKey.orEmpty() }.trim())
        try {
            ModelConfig.validate(config)
            withContext(Dispatchers.IO) { vault.save(config) }
            modelConfig = config
            mutable.update { it.copy(modelEnabled = true, modelBaseUrl = config.baseUrl, modelName = config.model, notice = "已保存。仅主动点击云端功能时发送任务数据，可能产生费用。") }
        } catch (_: Exception) { mutable.update { it.copy(notice = "配置未保存：请检查 HTTPS 地址、模型名和 API Key。") } }
    }
    fun disableModel() {
        modelJob?.cancel()
        action {
            withContext(Dispatchers.IO) { vault.clear() }
            modelConfig = null
            mutable.update { it.copy(modelEnabled = false, modelBaseUrl = "", modelName = "", notice = "已关闭云端模型并删除本地密钥。") }
        }
    }
    fun export(uri: Uri) = action {
        val content = DataExport.encode(mutable.value.rows, mutable.value.preferences)
        withContext(Dispatchers.IO) {
            requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(content) }
        }
        mutable.update { it.copy(notice = "已导出记录与设置，不含 API Key。导出文件可能包含个人饮食信息，请妥善保管。") }
    }
    fun clearAll() = action {
        withContext(Dispatchers.IO) { settings.clear(); vault.clear() }
        database.events().clear()
        modelConfig = null
        mutable.update { previous -> UiState(dataGeneration = previous.dataGeneration + 1, loading = false, busy = true, notice = "已清除本地记录、偏好与 API 配置；外部导出文件和服务商留存需另行处理。") }
        shown.clear()
        sessionId = UUID.randomUUID().toString()
    }

    /** 探索入口：展示四组输出，不计算或宣称用户效果。 */
    fun benchmarkCloud() {
        val config = modelConfig ?: return
        if (mutable.value.busy) return
        val ctx = context().copy(shownIds = emptySet())
        val allHistory = history()
        val candidates = engine.eligible(foods, ctx.preferences)
        if (candidates.isEmpty()) { mutable.update { it.copy(notice = "没有合格候选。") }; return }
        modelJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, benchmark = "", notice = "正在运行对照；本次上传候选、限制和结构化历史，不上传密钥以外的账号信息。") }
            try {
                val results = Strategy.entries.map { strategy ->
                    "$strategy：${engine.recommend(foods, allHistory, ctx, strategy)?.food?.name}"
                }
                val id = CompatibleModelProvider(config).choose(ctx, allHistory, candidates)
                val name = candidates.first { it.id == id }.name
                mutable.update { it.copy(benchmark = (results + "DIRECT_LLM：$name" + "同一候选池和可见历史；这是输出检查，不是效果结论。").joinToString("\n"), notice = "本次对照未写入饮食或偏好记录。") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(notice = "大模型对照未完成，不以本地结果冒充。") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    override fun onCleared() { database.close(); super.onCleared() }
}
