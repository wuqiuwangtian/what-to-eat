package org.whattoeat.core

import java.time.Duration
import kotlin.math.exp
import kotlin.random.Random

class Recommender(private val random: Random = Random.Default) {
    fun eligible(foods: List<Food>, preferences: Preferences): List<Food> = foods.filter { food ->
        food.id !in preferences.excludedFoodIds &&
            (food.ingredientsKnown || preferences.excludedIngredients.isEmpty()) &&
            food.ingredients.none { ingredient -> preferences.excludedIngredients.any { it in ingredient } } &&
            (preferences.context.isBlank() || preferences.context in food.contexts)
    }

    fun rank(foods: List<Food>, events: List<MealEvent>, context: RequestContext,
             strategy: Strategy = Strategy.PERSONALIZED, historyFoods: List<Food> = foods): List<Decision> {
        val available = eligible(foods, context.preferences)
        val unseen = available.filter { it.id !in context.shownIds }
        val newRound = unseen.isEmpty() && available.isNotEmpty()
        val pool = if (newRound) available else unseen
        val history = if (context.preferences.learningEnabled) events.filter { !it.at.isAfter(context.at) } else emptyList()
        val foodById = historyFoods.associateBy(Food::id)
        val recentEaten = history.filter { it.type == EventType.EATEN &&
            Duration.between(it.at, context.at).toHours() in 0..(7 * 24) }
        val observedGroups = recentEaten.flatMap { (it.groupsSnapshot ?: foodById[it.foodId]?.groups).orEmpty() }.toSet()
        return pool.map { food ->
            val preference = history.sumOf { event ->
                val known = foodById[event.foodId]
                val related = if (event.foodId == food.id) 1.0 else if (known != null || event.groupsSnapshot != null) {
                    food.groups.intersect(event.groupsSnapshot ?: known!!.groups).size * 0.12
                } else 0.0
                val ageDays = Duration.between(event.at, context.at).seconds / 86400.0
                val weight = when (event.type) {
                    EventType.PREFERRED, EventType.COMPARED -> 1.5
                    EventType.DISLIKED -> -2.0
                    EventType.PLANNED -> 0.25
                    EventType.EATEN, EventType.ORDERED -> 0.15
                    EventType.SHOWN, EventType.SKIPPED -> 0.0
                }
                related * weight * exp(-ageDays / 45) * event.confidence
            }.coerceIn(-4.0, 4.0)
            val explicit = food.groups.intersect(context.preferences.preferredGroups).size * 0.6
            val recent = recentEaten.filter { it.foodId == food.id }.sumOf {
                2.0 * exp(-Duration.between(it.at, context.at).seconds / 86400.0 / 2.0) * it.confidence
            }.coerceAtMost(3.0)
            val sessionSkip = history.count { it.type == EventType.SKIPPED && it.foodId == food.id &&
                it.sessionId == context.sessionId && context.sessionId.isNotBlank() } * 0.3
            val diversity = if (recentEaten.size >= 3) food.groups.count {
                it in setOf("蔬菜", "豆类", "鱼类") && it !in observedGroups
            } * 0.3 else 0.0
            val hour = context.at.atZone(context.zone).hour
            val mealMatch = if (hour in 5..10 && "早餐" in food.groups) 0.5
                else if (hour !in 5..10 && "正餐" in food.groups) 0.5 else 0.0
            val factors = when (strategy) {
                Strategy.RANDOM -> emptyMap()
                Strategy.SIMPLE -> linkedMapOf("历史与偏好" to preference + explicit, "餐次" to mealMatch, "近期重复" to -recent)
                Strategy.PERSONALIZED -> linkedMapOf("历史与偏好" to preference + explicit, "餐次" to mealMatch,
                    "近期重复" to -recent, "本轮换选" to -sessionSkip, "记录中的类别变化" to diversity)
            }
            val reasons = buildList {
                if (preference + explicit > 0.1 && strategy != Strategy.RANDOM) add("与你的明确偏好或历史选择较接近")
                if (recent > 0 && strategy != Strategy.RANDOM) add("近期确认吃过，已降低重复优先级")
                if (mealMatch > 0 && strategy != Strategy.RANDOM) add("符合当前餐次的常见搭配")
                if (diversity > 0 && strategy == Strategy.PERSONALIZED) add("可增加近七天已记录餐食中的类别变化")
                if (isEmpty()) add(if (strategy == Strategy.RANDOM) "在符合限制的候选中随机选择" else "从符合限制的常见餐食中选择")
            }
            Decision(food, factors.values.sum(), factors, reasons, strategy, startsNewRound = newRound)
        }.sortedWith(compareByDescending<Decision> { it.score }.thenBy { it.food.id })
    }

    fun recommend(foods: List<Food>, events: List<MealEvent>, context: RequestContext,
                  strategy: Strategy = Strategy.PERSONALIZED, historyFoods: List<Food> = foods): Decision? {
        val ranked = rank(foods, events, context, strategy, historyFoods)
        if (ranked.isEmpty()) return null
        if (strategy == Strategy.RANDOM) return ranked.random(random)
        // 在接近最高分的候选中探索，不以确定性的同一答案困住新用户。
        return ranked.filter { it.score >= ranked.first().score - 0.35 }.random(random)
    }
}

data class MealAnalysis(val foods: List<Food>, val unknownText: String?, val suggestions: List<String>)
class MealAnalyzer(private val catalog: List<Food>) {
    fun analyze(text: String): MealAnalysis {
        val normalized = text.trim()
        // 最长别名优先并占用文本区间，避免把红烧牛肉面重复识别成牛肉面。
        data class Match(val food: Food, val start: Int, val end: Int)
        val occupied = BooleanArray(normalized.length)
        val matches = catalog.flatMap { food ->
            (setOf(food.name) + food.aliases).filter(String::isNotBlank).mapNotNull { alias ->
                val start = normalized.indexOf(alias, ignoreCase = true)
                if (start >= 0) Match(food, start, start + alias.length) else null
            }
        }.sortedByDescending { it.end - it.start }
        val found = matches.filter { match ->
            if ((match.start until match.end).any { occupied[it] }) false
            else { (match.start until match.end).forEach { occupied[it] = true }; true }
        }.map { it.food }.distinctBy(Food::id)
        return assess(found, if (found.isEmpty()) normalized else null)
    }

    fun assess(foods: List<Food>, unknownText: String? = null): MealAnalysis {
        val groups = foods.flatMap(Food::groups).toSet()
        val suggestions = buildList {
            if (foods.isEmpty()) add("暂时无法确认这顿的组成；可选择已知菜品或补充菜名，不估算热量。")
            else if (foods.any { it.groups.isEmpty() || !it.ingredientsKnown }) {
                add("部分菜品的原料或类别尚未确认，暂不判断这顿的搭配。")
            } else {
                if ("蔬菜" !in groups) add("按常见做法，这份搭配可能蔬菜较少；方便时可加一份青菜。")
                if (groups.none { it in setOf("肉类", "蛋类", "鱼类", "水产", "豆类", "奶类") }) add("可以考虑搭配鸡蛋、豆腐等蛋白质来源。")
                if (isEmpty()) add("按常见做法包含多类食物；实际搭配仍取决于配料和份量。")
                add("这里只分析这次输入，不据此判断全天或整周营养是否充足。")
            }
        }
        return MealAnalysis(foods, unknownText, suggestions)
    }
}
