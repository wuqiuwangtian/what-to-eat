package org.whattoeat.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StorageTest {
    @Test fun correctionsReplaceTheEventAndDeletionClearsIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MealDatabase::class.java).build()
        try {
            val planned = EventRow("same-id", "PLANNED", "food-001", "番茄炒蛋配米饭", 1, "user", 1.0, null)
            db.events().put(listOf(planned))
            assertEquals("PLANNED", db.events().observe().first().single().type)
            db.events().put(listOf(planned.copy(type = "EATEN", source = "user-corrected")))
            assertEquals("EATEN", db.events().observe().first().single().type)
            db.events().delete(planned.id)
            assertTrue(db.events().observe().first().isEmpty())
        } finally { db.close() }
    }
    @Test fun apiKeyIsNotStoredInPlaintextAndCanBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = SecureVault(context)
        val key = "local-instrumentation-fixture-key"
        try {
            vault.save(ModelConfig("https://example.com/v1", "fixture", key))
            assertEquals(key, vault.read()?.apiKey)
            val stored = context.getSharedPreferences("model-vault", 0).all.values.joinToString()
            assertFalse(stored.contains(key))
            vault.clear()
            assertNull(vault.read())
        } finally { vault.clear() }
    }
}
