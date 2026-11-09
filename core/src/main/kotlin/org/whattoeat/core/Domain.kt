package org.whattoeat.core

import java.time.Instant
import java.time.ZoneId

/** 原料与类别为常见做法标签，不保证外部商家的实际配方。 */
data class Food(
    val id: String,
    val name: String,
    val ingredients: Set<String>,
    val groups: Set<String>,
    val contexts: Set<String>,
    val aliases: Set<String> = emptySet(),
    val source: String = "project-curated-v2",
    val ingredientsKnown: Boolean = true,
)

enum class EventType { SHOWN, SKIPPED, PLANNED, EATEN, ORDERED, PREFERRED, DISLIKED, COMPARED }
data class MealEvent(
    val id: String,
    val type: EventType,
    val foodId: String,
    val at: Instant,
    val source: String = "user",
    val confidence: Double = 1.0,
    val sessionId: String? = null,
    val groupsSnapshot: Set<String>? = null,
) {
    init { require(confidence in 0.0..1.0) }
}
data class Preferences(
    val excludedIngredients: Set<String> = emptySet(),
    val excludedFoodIds: Set<String> = emptySet(),
    val preferredGroups: Set<String> = emptySet(),
    val context: String = "外卖",
    val learningEnabled: Boolean = true,
)
data class RequestContext(
    val at: Instant = Instant.now(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val preferences: Preferences = Preferences(),
    val shownIds: Set<String> = emptySet(),
    val sessionId: String = "",
)
enum class Strategy { RANDOM, SIMPLE, PERSONALIZED }
data class Decision(
    val food: Food,
    val score: Double,
    val factors: Map<String, Double>,
    val reasons: List<String>,
    val strategy: Strategy,
    val algorithmVersion: String = "rules-v1",
    val startsNewRound: Boolean = false,
)

object Catalog {
    fun load(): List<Food> = loadResource("foods.tsv")
    fun legacy(): List<Food> = loadResource("foods-legacy-v1.tsv")
    private fun loadResource(resource: String): List<Food> = requireNotNull(Catalog::class.java.getResourceAsStream("/$resource")) {
        "Missing bundled food catalog"
    }.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val cells = line.split('\t')
            require(cells.size == 6) { "Invalid catalog row" }
            fun values(i: Int) = cells[i].split(',').filter(String::isNotBlank).toSet()
            Food(cells[0], cells[1], values(2), values(3) - "配料不定", values(4), values(5), ingredientsKnown = "配料不定" !in values(3))
        }.toList().also { foods ->
            require(foods.isNotEmpty() && foods.map { it.id }.distinct().size == foods.size)
            require(foods.all { it.ingredients.isNotEmpty() && it.contexts.isNotEmpty() })
        }
    }
}
