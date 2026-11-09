package org.whattoeat.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.whattoeat.core.*
import java.util.UUID

class FoodLibraryStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun food(name: String = "自建测试焖饭") = Food(FoodLibrary.newId(), name, setOf("牛肉", "稻米"), setOf("肉类"), setOf("外卖"), ingredientsKnown = true)
    private fun fixture(block: suspend (MealDatabase, FoodLibrary) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MealDatabase::class.java).build()
        try { block(db, FoodLibrary(db)) } finally { db.close() }
    }
    @Test fun editingHidingAndRestoringDefaultsKeepOldEventsAndSnapshots() = fixture { db, library ->
        val original = Catalog.load().first()
        val row = EventRow("kept", "EATEN", original.id, original.name, 1234, "user", 1.0, null,
            groupsSnapshot = JSONArray(original.groups.toList()).toString())
        db.events().put(listOf(row))
        library.save(original.copy(name = "我的番茄炒蛋", groups = setOf("蛋类")), true)
        assertEquals("我的番茄炒蛋", library.snapshot().visible.first { it.id == original.id }.name)
        library.hide(original, true)
        assertFalse(library.snapshot().visible.any { it.id == original.id })
        assertEquals(row, db.events().all().single())
        library.restore(original)
        assertEquals(original, library.snapshot().visible.first { it.id == original.id })
        assertTrue(original.id in library.snapshot().favoriteIds)
        assertEquals(row, db.events().all().single())
    }
    @Test fun customFoodAndFamiliarMembershipNeverCreateConsumptionAndSurviveReconstruction() = fixture { db, library ->
        val food = food(); library.save(food, true)
        val reopened = FoodLibrary(db).snapshot()
        assertEquals(food.name, reopened.visible.first { it.id == food.id }.name)
        assertTrue(food.id in reopened.favoriteIds)
        assertTrue(db.events().all().isEmpty())
        library.hide(food, true)
        assertFalse(library.snapshot().visible.any { it.id == food.id })
        assertTrue(library.snapshot().all.any { it.id == food.id })
        library.hide(food, false)
        assertTrue(library.snapshot().visible.any { it.id == food.id })
    }
    @Test fun roundAndExplorationQuotaPersistAcrossDatabaseReaders() = fixture { db, _ ->
        val round = LibraryRound(sessionId = "round", shown = "[\"food-001\"]", unavailable = "[\"food-002\"]", explored = true, draws = 10, lastAt = 1234)
        db.library().putRound(round)
        assertEquals(round, db.library().round())
        val selected = PersonalPool.select(Catalog.load(), emptySet(), emptySet(), emptySet(), db.library().round()!!.draws + 9, db.library().round()!!.explored)
        assertEquals("STARTER", selected.source)
    }
    @Test fun backupRoundTripPreservesIdsPatchesHiddenAndFavoritesWithoutMealOrSecrets() = fixture { db, library ->
        val food = food(); library.save(food, true); library.hide(Catalog.load().first(), true)
        val content = library.export()
        assertFalse(content.contains("events")); assertFalse(content.contains("apiKey"))
        db.library().clear(); library.import(content); library.import(content)
        assertEquals(2, db.library().rows().size)
        assertTrue(food.id in library.snapshot().favoriteIds)
        assertTrue(library.snapshot().isHidden(Catalog.load().first().id))
        assertTrue(db.events().all().isEmpty())
    }
    @Test fun conflictingOrInvalidImportIsAtomic() = fixture { db, library ->
        val original = food(); library.save(original, true)
        val saved = db.library().rows()
        val root = JSONObject(library.export())
        val entries = root.getJSONArray("entries")
        entries.getJSONObject(0).getJSONObject("food").put("name", "冲突修改")
        val new = food("另一个新菜")
        entries.put(JSONObject().put("id", new.id).put("food", FoodCodec.encode(new)).put("favorite", false).put("hidden", false))
        assertTrue(runCatching { library.import(root.toString()) }.isFailure)
        assertEquals(saved, db.library().rows())
        entries.getJSONObject(0).put("id", "../../unsafe")
        assertTrue(runCatching { library.import(root.toString()) }.isFailure)
        assertEquals(saved, db.library().rows())
    }
    @Test fun repeatedNamesAndUnconfirmedIngredientsDoNotInventMetadata() = fixture { _, library ->
        val food = Food(FoodLibrary.newId(), "自建未知餐", emptySet(), emptySet(), setOf("外卖"), ingredientsKnown = false)
        library.save(food, true)
        assertTrue(runCatching { library.save(food.copy(id = FoodLibrary.newId()), false) }.isFailure)
        val loaded = library.snapshot().visible.first { it.id == food.id }
        assertTrue(loaded.groups.isEmpty()); assertFalse(loaded.ingredientsKnown)
        assertTrue(Recommender().eligible(listOf(loaded), Preferences(excludedIngredients = setOf("虾"))).isEmpty())
    }
    @Test fun versionTwoUpgradePreservesEventsAndAddsEmptyLibraryWithoutEnrollment() = runBlocking<Unit> {
        val name = "library-upgrade-${UUID.randomUUID()}.db"
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val schema = JSONObject(testContext.assets.open("org.whattoeat.app.MealDatabase/2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val tables = schema.getJSONArray("entities")
            for (i in 0 until tables.length()) {
                val table = tables.getJSONObject(i)
                old.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
            }
            old.execSQL("INSERT INTO events VALUES ('kept-v2','PLANNED','food-001','原来的餐名',1234,'user',1.0,NULL,'旧详情')")
            old.version = 2
        }
        val db = Room.databaseBuilder(context, MealDatabase::class.java, name).build()
        try {
            val event = db.events().all().single()
            assertEquals("kept-v2", event.id); assertEquals("原来的餐名", event.foodName); assertEquals("旧详情", event.detail)
            assertNull(event.groupsSnapshot)
            assertTrue(db.library().rows().isEmpty()); assertNull(db.trials().config())
            FoodLibrary(db).save(food(), true)
            assertEquals(event, db.events().all().single())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
