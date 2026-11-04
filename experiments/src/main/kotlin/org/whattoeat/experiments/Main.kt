package org.whattoeat.experiments

import org.whattoeat.core.*
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

/** 合成情境只验证可重复运行及规则行为，不构成满意度或健康效果证据。 */
fun main() {
    val foods = Catalog.load()
    val now = Instant.parse("2026-11-04T12:00:00Z")
    val histories = listOf(
        "cold-start" to emptyList(),
        "explicit-preference" to listOf(MealEvent("1", EventType.PREFERRED, "food-001", now.minusSeconds(86400))),
        "recent-confirmed" to listOf(MealEvent("2", EventType.EATEN, "food-001", now.minusSeconds(3600))),
        "planned-only" to listOf(MealEvent("3", EventType.PLANNED, "food-001", now.minusSeconds(3600))),
    )
    println("# Synthetic smoke report; NOT a user-effectiveness experiment")
    println("scenario\tstrategy\tfood_id\tname\tscore\teligible_count")
    for ((scenario, history) in histories) {
        for (strategy in Strategy.entries) {
            val engine = Recommender(Random(42))
            val context = RequestContext(now, ZoneOffset.UTC, Preferences(excludedIngredients = setOf("花生")))
            val d = requireNotNull(engine.recommend(foods, history, context, strategy))
            println(listOf(scenario, strategy.name, d.food.id, d.food.name, "%.4f".format(java.util.Locale.ROOT, d.score), engine.eligible(foods, context.preferences).size).joinToString("\t"))
        }
    }
    println("# Direct-LLM baseline: NOT RUN; needs consented provider, same context and candidate pool. See docs/验证与发布.md.")
}
