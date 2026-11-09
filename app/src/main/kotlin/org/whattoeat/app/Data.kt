package org.whattoeat.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import org.whattoeat.core.*
import java.time.Instant

@Entity(tableName = "events")
data class EventRow(
    @PrimaryKey val id: String,
    val type: String,
    val foodId: String,
    val foodName: String,
    val time: Long,
    val source: String,
    val confidence: Double,
    val sessionId: String?,
    val detail: String = "",
) {
    fun domain() = MealEvent(id, EventType.valueOf(type), foodId, Instant.ofEpochMilli(time), source, confidence, sessionId)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY time DESC, id") fun observe(): Flow<List<EventRow>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(rows: List<EventRow>)
    @Query("DELETE FROM events WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM events") suspend fun clear()
    @Query("SELECT * FROM events ORDER BY time, id") suspend fun all(): List<EventRow>
}

@Database(entities = [EventRow::class, TrialConfigRow::class, TrialSessionRow::class], version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)], exportSchema = true)
abstract class MealDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun trials(): TrialDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, MealDatabase::class.java, "meals.db").build()
    }
}

class SettingsStore(context: Context) {
    private val storage = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    fun read() = Preferences(
        excludedIngredients = storage.getString("excluded", "").orEmpty().toValues(),
        preferredGroups = storage.getString("preferred", "").orEmpty().toValues(),
        context = storage.getString("context", "外卖").orEmpty(),
        learningEnabled = storage.getBoolean("learning", true),
        excludedFoodIds = storage.getString("excludedFoods", "").orEmpty().toValues(),
    )
    fun write(p: Preferences) {
        check(storage.edit().putString("excluded", p.excludedIngredients.joinToString(","))
            .putString("preferred", p.preferredGroups.joinToString(","))
            .putString("excludedFoods", p.excludedFoodIds.joinToString(","))
            .putString("context", p.context).putBoolean("learning", p.learningEnabled).commit())
    }
    fun clear() { check(storage.edit().clear().commit()) }
}
fun String.toValues() = split(',', '，', '、', '\n').map(String::trim).filter(String::isNotBlank).toSet()

object DataExport {
    fun encode(rows: List<EventRow>, preferences: Preferences): String {
        val events = JSONArray()
        rows.forEach { row -> events.put(JSONObject().put("id", row.id).put("type", row.type)
            .put("foodId", row.foodId).put("foodName", row.foodName).put("time", row.time)
            .put("source", row.source).put("confidence", row.confidence)
            .put("sessionId", row.sessionId ?: JSONObject.NULL).put("detail", row.detail)) }
        return JSONObject().put("formatVersion", 1).put("events", events)
            .put("preferences", JSONObject().put("excludedIngredients", JSONArray(preferences.excludedIngredients.toList()))
                .put("preferredGroups", JSONArray(preferences.preferredGroups.toList()))
                .put("excludedFoodIds", JSONArray(preferences.excludedFoodIds.toList()))
                .put("context", preferences.context).put("learningEnabled", preferences.learningEnabled)).toString(2)
    }
}
