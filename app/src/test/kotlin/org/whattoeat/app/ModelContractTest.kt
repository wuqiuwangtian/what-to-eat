package org.whattoeat.app

import org.junit.Assert.*
import org.junit.Test
import org.whattoeat.core.Preferences

class ModelContractTest {
    @Test fun modelCannotIntroduceFoodOutsideTheApprovedPool() {
        assertThrows(IllegalArgumentException::class.java) {
            CompatibleModelProvider.parseFoodIds("""{"foodIds":["invented-shop-menu"]}""", setOf("food-001"))
        }
    }
    @Test fun unknownAndOversizedModelOutputsFailClosed() {
        assertThrows(Exception::class.java) { CompatibleModelProvider.parseFoodIds("not json", setOf("food-001")) }
        assertThrows(IllegalArgumentException::class.java) {
            CompatibleModelProvider.parseFoodIds("""{"foodIds":[${List(11) { "\"food-001\"" }.joinToString(",") }]}""", setOf("food-001"))
        }
    }
    @Test fun validIdsAreDeduplicatedAndNoMatchIsAllowed() {
        assertEquals(listOf("food-001"), CompatibleModelProvider.parseFoodIds("""{"foodIds":["food-001","food-001"]}""", setOf("food-001")))
        assertTrue(CompatibleModelProvider.parseFoodIds("""{"foodIds":[]}""", emptySet()).isEmpty())
    }
    @Test fun endpointRejectsCleartextCredentialsAndQuerySecrets() {
        listOf("http://example.com/v1", "https://user:password@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/v1#secret").forEach {
            assertThrows(IllegalArgumentException::class.java) { ModelConfig.validate(ModelConfig(it, "model", "key")) }
        }
        ModelConfig.validate(ModelConfig("https://example.com/v1", "model", "key"))
    }
    @Test fun exportContainsRecordsAndPreferencesButNoModelConfiguration() {
        val rows = listOf(EventRow("1", "PLANNED", "food-001", "番茄炒蛋配米饭", 1234, "user", 1.0, null))
        val json = DataExport.encode(rows, Preferences(excludedIngredients = setOf("花生")))
        val root = org.json.JSONObject(json)
        assertEquals(2, root.getInt("formatVersion"))
        assertTrue(root.has("foodLibrary"))
        assertEquals("PLANNED", root.getJSONArray("events").getJSONObject(0).getString("type"))
        assertFalse(root.has("apiKey"))
        assertFalse(root.has("model"))
    }
}
