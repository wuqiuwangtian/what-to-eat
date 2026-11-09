package org.whattoeat.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class PersonalPoolTest {
    private val foods = Catalog.load()
    private val favorites = setOf("food-114", "food-115")
    private fun pool(draws: Long = 0, explored: Boolean = false, force: Boolean = false, eligible: List<Food> = foods,
        favorites: Set<String> = this.favorites, shown: Set<String> = emptySet(), unavailable: Set<String> = emptySet()) =
        PersonalPool.select(eligible, favorites, shown, unavailable, draws, explored, force)

    @Test fun initialPoolIsSmallAndLibraryGrowthDoesNotExpandIt() {
        val starter = pool(favorites = emptySet())
        assertEquals("STARTER", starter.source)
        assertEquals(32, starter.foods.size)
        val larger = foods + Food("extra", "新菜", setOf("土豆"), setOf("蔬菜"), setOf("外卖"))
        assertEquals(starter, pool(eligible = larger, favorites = emptySet()))
    }
    @Test fun ordinaryOpportunitiesStayInsideExplicitFamiliarPool() {
        (0L..8L).forEach { assertEquals(favorites, pool(draws = it).foods.map(Food::id).toSet()) }
        assertEquals("FAMILIAR", pool().source)
    }
    @Test fun discoveryIsBoundedAndExplicitExplorationCanRequestMore() {
        assertEquals("DISCOVERY", pool(draws = 9).source)
        assertTrue(pool(draws = 9).foods.none { it.id in favorites })
        assertEquals("FAMILIAR", pool(draws = 19, explored = true).source)
        assertEquals("EXPLICIT_DISCOVERY", pool(draws = 19, explored = true, force = true).source)
    }
    @Test fun unavailableFavoritesDoNotSilentlyRestoreDefaultCandidates() {
        assertTrue(pool(eligible = foods.filter { it.id !in favorites }).foods.isEmpty())
        assertTrue(pool(unavailable = favorites).foods.isEmpty())
        assertFalse(pool(unavailable = favorites, force = true).foods.isEmpty())
    }
    @Test fun discoveryNeverIncludesExcludedUnknownSeenOrTemporarilyUnavailableFood() {
        val prefs = Preferences(excludedIngredients = setOf("牛肉"))
        val eligible = Recommender().eligible(foods, prefs)
        val unseen = eligible.take(5).map(Food::id).toSet()
        val removed = eligible.drop(5).take(5).map(Food::id).toSet()
        val result = pool(eligible = eligible, shown = unseen, unavailable = removed, force = true)
        assertTrue(result.foods.none { !it.ingredientsKnown || "牛肉" in it.ingredients || it.id in unseen || it.id in removed })
    }
    @Test fun missingIngredientInformationDoesNotBypassRestrictionsOrInventNutrition() {
        val custom = Food("test", "自建火锅", emptySet(), emptySet(), setOf("外卖"), ingredientsKnown = false)
        assertEquals(listOf(custom), Recommender().eligible(listOf(custom), Preferences()))
        assertTrue(Recommender().eligible(listOf(custom), Preferences(excludedIngredients = setOf("虾"))).isEmpty())
        val result = MealAnalyzer(listOf(custom)).assess(listOf(custom))
        assertTrue(result.suggestions.any { "尚未确认" in it })
        assertFalse(result.suggestions.any { "可能蔬菜较少" in it || "包含多类" in it })
    }
    @Test fun archivedFoodGroupsAndEventSnapshotsSurviveNarrowCandidatePool() {
        val candidate = Food("new", "青菜", setOf("青菜"), setOf("蔬菜"), setOf("外卖"))
        val old = Food("old", "旧菜", setOf("菜"), setOf("肉类"), setOf("外卖"))
        val now = Instant.parse("2026-11-09T12:00:00Z")
        val event = MealEvent("event", EventType.PREFERRED, old.id, now.minusSeconds(60), groupsSnapshot = setOf("蔬菜"))
        val engine = Recommender(Random(1)); val ctx = RequestContext(at = now)
        val scores = engine.rank(listOf(candidate), listOf(event), ctx, historyFoods = listOf(old))
        assertTrue(scores.single().factors.getValue("历史与偏好") > 0)
    }
    @Test fun requestedFoodsAndNaturalDishNamesExistWhileLegacyRemainsReadable() {
        listOf("肥牛饭", "驴肉火烧", "汉堡", "火锅", "番茄炒蛋").forEach { name -> assertTrue(foods.any { it.name == name }) }
        assertEquals("番茄炒蛋配米饭", Catalog.legacy().first().name)
        assertFalse("稻米" in foods.first().ingredients)
        assertTrue("稻米" in Catalog.legacy().first().ingredients)
    }
}
