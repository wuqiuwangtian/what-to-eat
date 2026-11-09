package org.whattoeat.app

import androidx.room.*
import org.json.JSONArray
import org.json.JSONObject
import org.whattoeat.core.*
import java.util.UUID

@Entity(tableName = "food_library")
data class LibraryRow(@PrimaryKey val id: String, val foodJson: String = "", val favorite: Boolean = false, val hidden: Boolean = false)

@Entity(tableName = "library_round")
data class LibraryRound(@PrimaryKey val id: Int = 1, val sessionId: String = "", val shown: String = "[]",
    val unavailable: String = "[]", val explored: Boolean = false, val draws: Long = 0, val lastAt: Long = 0)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM food_library ORDER BY id") suspend fun rows(): List<LibraryRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: LibraryRow)
    @Query("DELETE FROM food_library") suspend fun clear()
    @Query("SELECT * FROM library_round WHERE id = 1") suspend fun round(): LibraryRound?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRound(row: LibraryRound)
    @Query("DELETE FROM library_round") suspend fun clearRound()
}

data class LibrarySnapshot(val all: List<Food> = Catalog.load(), val rows: List<LibraryRow> = emptyList()) {
    val visible get() = all.filter { food -> rows.none { it.id == food.id && it.hidden } }
    val favoriteIds get() = rows.filter { it.favorite }.map { it.id }.toSet()
    fun isHidden(id: String) = rows.any { it.id == id && it.hidden }
}

object FoodCodec {
    fun strings(array: JSONArray): Set<String> = (0 until array.length()).map { array.getString(it) }.toSet()
    fun encode(food: Food): JSONObject = JSONObject().put("id", food.id).put("name", food.name)
        .put("ingredients", JSONArray(food.ingredients.sorted())).put("groups", JSONArray(food.groups.sorted()))
        .put("contexts", JSONArray(food.contexts.sorted())).put("aliases", JSONArray(food.aliases.sorted()))
        .put("ingredientsKnown", food.ingredientsKnown)
    fun decode(json: JSONObject): Food {
        val food = Food(json.getString("id"), json.getString("name").trim(), strings(json.getJSONArray("ingredients")),
            strings(json.getJSONArray("groups")), strings(json.getJSONArray("contexts")), strings(json.getJSONArray("aliases")),
            source = "user-library", ingredientsKnown = json.getBoolean("ingredientsKnown"))
        validate(food)
        return food
    }
    fun validate(food: Food) {
        require(food.name.isNotBlank() && food.name.length <= 80)
        listOf(food.ingredients, food.groups, food.contexts, food.aliases).forEach { values ->
            require(values.size <= 30 && values.all { it.isNotBlank() && it.length <= 80 && '\n' !in it })
        }
        require(food.contexts.isNotEmpty() && food.contexts.all { it in setOf("外卖", "食堂", "自炊") })
        require(!food.ingredientsKnown || food.ingredients.isNotEmpty())
    }
}

class FoodLibrary(private val db: MealDatabase) {
    private val dao get() = db.library()
    private val bundled = Catalog.load()
    suspend fun snapshot(): LibrarySnapshot {
        val rows = dao.rows()
        val patches = rows.filter { it.foodJson.isNotBlank() }.associate { it.id to FoodCodec.decode(JSONObject(it.foodJson)) }
        return LibrarySnapshot(bundled.map { patches[it.id] ?: it } + patches.values.filter { it.id.startsWith("user:") }, rows)
    }
    suspend fun save(food: Food, favorite: Boolean) = db.withTransaction {
        FoodCodec.validate(food)
        val snapshot = snapshot()
        require(snapshot.all.none { it.id != food.id && it.name.equals(food.name.trim(), true) }) { "A food with this name already exists" }
        require(food.id in bundled.map { it.id } || validUserId(food.id))
        dao.put(LibraryRow(food.id, FoodCodec.encode(food).toString(), favorite))
        dao.clearRound()
    }
    suspend fun favorite(food: Food, enabled: Boolean) = db.withTransaction {
        val old = dao.rows().find { it.id == food.id } ?: LibraryRow(food.id)
        dao.put(old.copy(favorite = enabled, hidden = if (enabled) false else old.hidden)); dao.clearRound()
    }
    suspend fun hide(food: Food, hidden: Boolean) = db.withTransaction {
        val old = dao.rows().find { it.id == food.id } ?: LibraryRow(food.id)
        dao.put(old.copy(hidden = hidden)); dao.clearRound()
    }
    suspend fun restore(food: Food) = db.withTransaction {
        require(food.id in bundled.map { it.id })
        val old = dao.rows().find { it.id == food.id } ?: LibraryRow(food.id)
        dao.put(old.copy(foodJson = "", hidden = false)); dao.clearRound()
    }
    suspend fun export(): String = JSONObject().put("formatVersion", 1).put("kind", "food-library")
        .put("entries", JSONArray(dao.rows().map { row -> JSONObject().put("id", row.id).put("food", if (row.foodJson.isBlank()) JSONObject.NULL else JSONObject(row.foodJson))
            .put("favorite", row.favorite).put("hidden", row.hidden) })).toString(2)

    /** 全量校验完成后一次写入；同ID完全相同则去重，冲突拒绝，不覆盖用户现有修改。 */
    suspend fun import(content: String) = db.withTransaction {
        require(content.length <= 1_000_000)
        val root = JSONObject(content)
        require(root.getInt("formatVersion") == 1 && root.getString("kind") == "food-library")
        val entries = root.getJSONArray("entries"); require(entries.length() <= 2000)
        val knownIds = bundled.map { it.id }.toSet()
        val imported = (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index); val id = item.getString("id")
            require(id in knownIds || validUserId(id))
            val food = if (item.isNull("food")) null else FoodCodec.decode(item.getJSONObject("food"))
            require(food?.id == id || (food == null && id in knownIds))
            LibraryRow(id, food?.let { FoodCodec.encode(it).toString() }.orEmpty(), item.getBoolean("favorite"), item.getBoolean("hidden"))
        }
        require(imported.map { it.id }.distinct().size == imported.size)
        val existing = dao.rows().associateBy { it.id }
        imported.forEach { require(existing[it.id] == null || existing[it.id] == it) { "Conflicting library entries" } }
        val merged = existing + imported.associateBy { it.id }
        val names = (bundled.map { merged[it.id]?.foodJson?.takeIf(String::isNotBlank)?.let { text -> FoodCodec.decode(JSONObject(text)) } ?: it } +
            merged.values.filter { it.id.startsWith("user:") }.map { FoodCodec.decode(JSONObject(it.foodJson)) }).map { it.name.lowercase() }
        require(names.distinct().size == names.size)
        imported.forEach { dao.put(it) }; dao.clearRound()
    }
    companion object {
        fun newId() = "user:${UUID.randomUUID()}"
        private fun validUserId(id: String) = id.startsWith("user:") && runCatching { UUID.fromString(id.removePrefix("user:")).toString() == id.removePrefix("user:") }.getOrDefault(false)
    }
}
