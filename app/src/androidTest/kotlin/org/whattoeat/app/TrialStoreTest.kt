package org.whattoeat.app

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.whattoeat.core.*
import java.io.File
import java.util.UUID

class TrialStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val foods = Catalog.load()
    private val now = 1_800_000_000_000L
    private suspend fun prepare(store: TrialStore, at: Long = now) = requireNotNull(store.prepare(at, foods, Preferences(), 0, foods.size))
    private fun fixture(block: suspend (MealDatabase, TrialStore) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MealDatabase::class.java).build()
        try { block(db, TrialStore(db)) } finally { db.close() }
    }
    @Test fun concurrentPreparationConsumesExactlyOneAssignment() = fixture { db, store ->
        assertFalse(store.snapshot(now).enabled)
        store.enroll()
        val ids = coroutineScope { (1..10).map { async { prepare(store).id } }.awaitAll() }
        assertEquals(1, ids.distinct().size)
        assertEquals(1L, db.trials().config()!!.nextOrdinal)
        assertEquals(1, db.trials().sessions().size)
    }
    @Test fun repeatedFrameCallbacksAreIdempotentAndAdoptionIsExplicit() = fixture { db, store ->
        store.enroll(); val s = prepare(store)
        val p = TrialPresentation("frame", s.id, foods.first().id, 0, "rules-v1")
        store.presented(p, now + 10); store.presented(p, now + 20)
        assertEquals(1, JSONArray(db.trials().active()!!.presentations).length())
        assertNull(db.trials().active()!!.selectedIndex)
        val ended = store.finish("ADOPTED", now + 30, p.foodId)!!
        assertEquals(0, ended.selectedIndex)
        assertNull(store.finish("ADOPTED", now + 40, p.foodId))
        assertEquals(1L, db.trials().config()!!.nextOrdinal)
    }
    @Test fun timeoutAfterRestartIsMissingFeedbackAndLateFramesCannotReviveIt() = fixture { db, store ->
        store.enroll(); val s = prepare(store)
        val deadline = now + TrialRules.IDLE_MILLIS
        assertNotNull(TrialStore(db).snapshot(deadline - 1).active)
        assertNull(TrialStore(db).snapshot(deadline).active)
        store.presented(TrialPresentation("late", s.id, foods.first().id, 0, "rules-v1"), deadline + 1)
        val ended = db.trials().session(s.id)!!
        assertEquals("IDLE", ended.endReason)
        assertEquals(deadline, ended.endedAt)
        assertNull(ended.selectedFoodId)
        assertEquals(0, JSONArray(ended.presentations).length())
        assertEquals(1L, prepare(store, deadline + 2).ordinal)
    }
    @Test fun withdrawalAndReentryKeepCounterbalancingButClearUnlinksDietRecords() = fixture { db, store ->
        store.enroll(); val s = prepare(store)
        db.events().put(listOf(EventRow("diet", "PLANNED", foods.first().id, "private-meal-name", now, "user", 1.0, s.id)))
        val participant = db.trials().config()!!.participantId
        store.withdraw(now + 100)
        assertFalse(store.snapshot(now + 100).enabled)
        assertEquals("WITHDRAWN", db.trials().session(s.id)!!.endReason)
        store.enroll()
        assertEquals(participant, db.trials().config()!!.participantId)
        assertEquals(1L, prepare(store, now + 101).ordinal)
        store.clear()
        assertNull(db.trials().config())
        assertTrue(db.trials().sessions().isEmpty())
        assertEquals("diet", db.events().all().single().id)
        assertNull(db.events().all().single().sessionId)
    }
    @Test fun exportOmitsRawDietTextSeedsAndAbsoluteTimesAndDoesNotInferEating() = fixture { db, store ->
        store.enroll(); val s = prepare(store)
        val p = TrialPresentation("private-frame-token", s.id, foods.first().id, 0, "rules-v1")
        store.presented(p, now + 10); store.finish("ADOPTED", now + 20, p.foodId)
        val row = EventRow("diet", "PLANNED", p.foodId, "private-meal-name", now + 30,
            "private-source", 1.0, s.id, "private-detail-key")
        db.events().put(listOf(row))
        var root = JSONObject(store.export(now + 40))
        assertTrue(root.getJSONArray("sessions").getJSONObject(0).isNull("confirmedEaten"))
        db.events().put(listOf(row.copy(type = "EATEN")))
        val content = store.export(now + 50); root = JSONObject(content)
        assertTrue(root.getJSONArray("sessions").getJSONObject(0).getBoolean("confirmedEaten"))
        assertFalse(content.contains("private-"))
        assertFalse(content.contains("startedAt")); assertFalse(root.has("seed"))
        db.events().delete("diet")
        assertTrue(JSONObject(store.export(now + 60)).getJSONArray("sessions").getJSONObject(0).isNull("confirmedEaten"))
        File(context.cacheDir, "trial-validation").apply { mkdirs() }.resolve("export.json").writeText(store.export(now + 60))
    }
    @Test fun changedRestrictionsCloseTheOldSessionAndStartANewAssignment() = fixture { db, store ->
        store.enroll(); val s = prepare(store)
        val next = store.prepare(now + 10, foods, Preferences(excludedIngredients = setOf("花生")), 0, 100)!!
        assertNotEquals(s.id, next.id)
        assertEquals("CONTEXT_CHANGED", db.trials().session(s.id)!!.endReason)
        assertEquals(1L, next.ordinal)
    }
    @Test fun databaseUpgradeKeepsVersionOneEventsAndDoesNotAutoEnroll() = runBlocking<Unit> {
        val name = "trial-migration-${UUID.randomUUID()}.db"
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE events (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, foodId TEXT NOT NULL, foodName TEXT NOT NULL, time INTEGER NOT NULL, source TEXT NOT NULL, confidence REAL NOT NULL, sessionId TEXT, detail TEXT NOT NULL)")
            old.execSQL("INSERT INTO events VALUES ('kept', 'PLANNED', 'food-001', '旧记录', 1234, 'user', 1.0, NULL, '')")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, MealDatabase::class.java, name).build()
        try {
            assertEquals("kept", db.events().all().single().id)
            assertFalse(TrialStore(db).snapshot(now).enabled)
            TrialStore(db).enroll(); prepare(TrialStore(db))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
