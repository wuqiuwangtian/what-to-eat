package org.whattoeat.core

import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class RecommenderTest {
    private val now = Instant.parse("2026-11-04T12:00:00Z")
    private val foods = Catalog.load()
    private val engine = Recommender(Random(23))
    private fun context(p: Preferences = Preferences()) = RequestContext(now, ZoneOffset.UTC, p, sessionId = "test")
    private fun event(type: EventType, food: Food, hoursAgo: Long = 1) =
        MealEvent("event", type, food.id, now.minusSeconds(hoursAgo * 3600), sessionId = "test")

    @Test fun catalogHasRealUniqueEntriesAndSources() {
        assertTrue(foods.size >= 100)
        assertEquals(foods.size, foods.map(Food::name).distinct().size)
        assertTrue(foods.all { it.source.isNotBlank() })
    }
    @Test fun restrictionsHoldAcrossAllStrategiesAndExhaustion() {
        val p = Preferences(excludedIngredients = setOf("花生", "鸡蛋"))
        Strategy.entries.forEach { strategy ->
            val ctx = context(p).copy(shownIds = foods.map(Food::id).toSet())
            repeat(100) {
                val d = requireNotNull(engine.recommend(foods, emptyList(), ctx, strategy))
                assertTrue(d.food.ingredients.none { it in p.excludedIngredients })
                assertTrue(d.startsNewRound)
            }
        }
    }
    @Test fun noEligibleFoodReturnsNoAnswer() {
        assertNull(engine.recommend(foods, emptyList(), context(Preferences(excludedFoodIds = foods.map(Food::id).toSet()))))
    }
    @Test fun unseenCandidatesAreUsedBeforeStartingAnotherRound() {
        val first = foods.first()
        val ctx = context().copy(shownIds = foods.drop(1).map(Food::id).toSet())
        assertEquals(first.id, engine.recommend(foods, emptyList(), ctx)?.food?.id)
        assertFalse(engine.recommend(foods, emptyList(), ctx)!!.startsNewRound)
    }
    @Test fun onlyConfirmedMealsCauseRecentEatingPenalty() {
        val food = foods.first()
        EventType.entries.forEach { type ->
            val d = engine.rank(foods, listOf(event(type, food)), context()).first { it.food.id == food.id }
            if (type == EventType.EATEN) assertTrue(d.factors.getValue("近期重复") < 0)
            else assertEquals(0.0, d.factors.getValue("近期重复"), 0.0001)
        }
    }
    @Test fun futureEventsDoNotLeakIntoPastDecisions() {
        val history = listOf(event(EventType.PREFERRED, foods.first(), -5))
        assertEquals(engine.rank(foods, emptyList(), context()), engine.rank(foods, history, context()))
    }
    @Test fun switchingDoesNotCreatePermanentDislike() {
        val anotherSession = event(EventType.SKIPPED, foods.first()).copy(sessionId = "previous")
        assertEquals(engine.rank(foods, emptyList(), context()), engine.rank(foods, listOf(anotherSession), context()))
    }
    @Test fun disablingLearningIgnoresHistoryButKeepsExplicitRestrictions() {
        val p = Preferences(learningEnabled = false, excludedFoodIds = setOf(foods.last().id))
        val history = listOf(event(EventType.DISLIKED, foods.first()))
        assertEquals(engine.rank(foods, emptyList(), context(p)), engine.rank(foods, history, context(p)))
        assertFalse(engine.rank(foods, history, context(p)).any { it.food.id == foods.last().id })
    }
    @Test fun explicitFeedbackUpdatesPreferenceAndRemovingItRestoresScores() {
        val food = foods.first()
        val base = engine.rank(foods, emptyList(), context()).first { it.food.id == food.id }
        val learned = engine.rank(foods, listOf(event(EventType.PREFERRED, food)), context()).first { it.food.id == food.id }
        assertTrue(learned.score > base.score)
        assertEquals(base, engine.rank(foods, emptyList(), context()).first { it.food.id == food.id })
    }
    @Test fun unknownMealIsNotGivenInventedNutrition() {
        val analysis = MealAnalyzer(foods).analyze("未命名神秘饭")
        assertTrue(analysis.foods.isEmpty())
        assertNotNull(analysis.unknownText)
        assertTrue(analysis.suggestions.first().contains("无法确认"))
    }
    @Test fun overlappingAliasesDoNotDuplicateTheMeal() {
        val result = MealAnalyzer(foods).analyze("红烧牛肉面")
        assertEquals(1, result.foods.size)
        assertEquals("红烧牛肉面", result.foods.single().name)
    }
    @Test fun aliasesRecognizeCommonMealWithoutInferringConsumption() {
        val analysis = MealAnalyzer(foods).analyze("打算吃西红柿炒鸡蛋")
        assertTrue(analysis.foods.any { it.id == foods.first().id })
    }
    @Test fun porkAndEggRestrictionsIncludeExplicitCatalogVariants() {
        for ((restriction, ids) in mapOf("猪肉" to listOf("food-026", "food-031", "food-091", "food-093", "food-113"),
                                        "鸡蛋" to listOf("food-036"))) {
            val eligible = engine.eligible(foods, Preferences(excludedIngredients = setOf(restriction))).map { it.id }
            assertTrue(ids.none { it in eligible })
        }
    }
    @Test fun shellfishMealIsNotReportedAsMissingAProteinSource() {
        val shrimp = foods.single { it.id == "food-017" }
        assertFalse(MealAnalyzer(foods).assess(listOf(shrimp)).suggestions.any { "蛋白质来源" in it })
    }
}
