package org.whattoeat.app

import androidx.room.*
import org.json.JSONArray
import org.json.JSONObject
import org.whattoeat.core.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Entity(tableName = "trial_config")
data class TrialConfigRow(
    @PrimaryKey val id: Int = 1,
    val participantId: String,
    val seed: Long,
    val nextOrdinal: Long,
    val enabled: Boolean,
    val protocol: String = TrialRules.PROTOCOL,
)

@Entity(tableName = "trial_sessions")
data class TrialSessionRow(
    @PrimaryKey val id: String,
    val ordinal: Long,
    val strategy: String,
    val startedAt: Long,
    val lastActiveAt: Long,
    val mealPeriod: String,
    val catalogHash: String,
    val contextHash: String,
    val candidateCount: Int,
    val historyCount: Int,
    val learningEnabled: Boolean,
    val presentations: String = "[]",
    val endedAt: Long? = null,
    val endReason: String? = null,
    val selectedFoodId: String? = null,
    val selectedIndex: Int? = null,
)

@Dao
interface TrialDao {
    @Query("SELECT * FROM trial_config WHERE id = 1") suspend fun config(): TrialConfigRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConfig(row: TrialConfigRow)
    @Query("SELECT * FROM trial_sessions WHERE endedAt IS NULL ORDER BY ordinal DESC LIMIT 1") suspend fun active(): TrialSessionRow?
    @Query("SELECT * FROM trial_sessions WHERE id = :id") suspend fun session(id: String): TrialSessionRow?
    @Query("SELECT * FROM trial_sessions ORDER BY ordinal") suspend fun sessions(): List<TrialSessionRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSession(row: TrialSessionRow)
    @Query("DELETE FROM trial_sessions") suspend fun clearSessions()
    @Query("DELETE FROM trial_config") suspend fun clearConfig()
    @Query("UPDATE events SET sessionId = NULL WHERE sessionId IN (SELECT id FROM trial_sessions)") suspend fun unlinkMealEvents()
}

data class TrialSnapshot(val enabled: Boolean = false, val active: TrialSessionRow? = null, val hasData: Boolean = false)
data class TrialPresentation(val token: String, val sessionId: String, val foodId: String, val historyCount: Int, val algorithmVersion: String)

/** 分配、展示、结束都在Room事务内串行；应用重启不会消耗新的分配。 */
class TrialStore(private val db: MealDatabase) {
    private val dao get() = db.trials()
    suspend fun snapshot(now: Long): TrialSnapshot = db.withTransaction {
        expireInside(now)
        val config = dao.config()
        TrialSnapshot(config?.enabled == true, dao.active(), config != null)
    }
    suspend fun enroll() = db.withTransaction {
        val old = dao.config()
        require(old == null || old.protocol == TrialRules.PROTOCOL) { "Export and delete previous protocol first" }
        dao.putConfig(old?.copy(enabled = true) ?: TrialConfigRow(participantId = UUID.randomUUID().toString(),
            seed = SecureRandom().nextLong(), nextOrdinal = 0, enabled = true))
    }
    suspend fun prepare(now: Long, foods: List<Food>, preferences: Preferences, historyCount: Int,
                        candidateCount: Int): TrialSessionRow? = db.withTransaction {
        val config = dao.config()?.takeIf { it.enabled } ?: return@withTransaction null
        check(config.protocol == TrialRules.PROTOCOL)
        expireInside(now)
        val contextHash = digest(listOf(BuildConfig.VERSION_NAME, preferences.context, preferences.learningEnabled.toString(),
            preferences.excludedIngredients.sorted().joinToString(","), preferences.excludedFoodIds.sorted().joinToString(","),
            preferences.preferredGroups.sorted().joinToString(",")).joinToString("|"))
        val catalogHash = digest(foods.joinToString("\n") { "${it.id}|${it.name}|${it.ingredients.sorted()}|${it.groups.sorted()}|${it.contexts.sorted()}" })
        dao.active()?.let { active ->
            if (active.contextHash == contextHash && active.catalogHash == catalogHash) return@withTransaction active
            dao.putSession(active.copy(endedAt = now, endReason = "CONTEXT_CHANGED"))
        }
        val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
        val session = TrialSessionRow(UUID.randomUUID().toString(), config.nextOrdinal,
            TrialRules.strategy(config.seed, config.nextOrdinal).name, now, now,
            when (hour) { in 5..10 -> "breakfast"; in 11..14 -> "lunch"; in 17..21 -> "dinner"; else -> "other" },
            catalogHash, contextHash, candidateCount, historyCount, preferences.learningEnabled)
        dao.putSession(session)
        dao.putConfig(config.copy(nextOrdinal = config.nextOrdinal + 1))
        session
    }
    suspend fun presented(p: TrialPresentation, now: Long) = db.withTransaction {
        expireInside(now)
        val session = dao.session(p.sessionId)?.takeIf { it.endedAt == null } ?: return@withTransaction
        val entries = JSONArray(session.presentations)
        if ((0 until entries.length()).any { entries.getJSONObject(it).getString("token") == p.token }) return@withTransaction
        entries.put(JSONObject().put("token", p.token).put("foodId", p.foodId)
            .put("elapsedMs", (now - session.startedAt).coerceAtLeast(0)).put("historyCount", p.historyCount)
            .put("algorithmVersion", p.algorithmVersion).put("appVersion", BuildConfig.VERSION_NAME))
        dao.putSession(session.copy(presentations = entries.toString(), lastActiveAt = maxOf(session.lastActiveAt, now)))
    }
    suspend fun finish(reason: String, now: Long, foodId: String? = null): TrialSessionRow? = db.withTransaction {
        expireInside(now)
        val session = dao.active() ?: return@withTransaction null
        val entries = JSONArray(session.presentations)
        if (reason == "ADOPTED") check(entries.length() > 0 && entries.getJSONObject(entries.length() - 1).getString("foodId") == foodId)
        val finished = session.copy(endedAt = maxOf(now, session.startedAt), endReason = reason, selectedFoodId = foodId,
            selectedIndex = if (reason == "ADOPTED") entries.length() - 1 else null)
        dao.putSession(finished)
        finished
    }
    suspend fun touch(id: String, now: Long) = db.withTransaction {
        expireInside(now)
        val session = dao.session(id)?.takeIf { it.endedAt == null } ?: return@withTransaction
        dao.putSession(session.copy(lastActiveAt = maxOf(session.lastActiveAt, now)))
    }
    suspend fun withdraw(now: Long) = db.withTransaction {
        finish("WITHDRAWN", now)
        dao.config()?.let { dao.putConfig(it.copy(enabled = false)) }
    }
    suspend fun clear() = db.withTransaction {
        dao.unlinkMealEvents()
        dao.clearSessions()
        dao.clearConfig()
    }
    private suspend fun expireInside(now: Long) {
        dao.active()?.takeIf { TrialRules.expired(it.lastActiveAt, now) }?.let {
            dao.putSession(it.copy(endedAt = it.lastActiveAt + TrialRules.IDLE_MILLIS, endReason = "IDLE"))
        }
    }
    suspend fun export(now: Long): String = db.withTransaction {
        expireInside(now)
        val config = dao.config() ?: error("No trial data")
        val sessions = dao.sessions()
        val events = db.events().all()
        val origin = sessions.firstOrNull()?.startedAt ?: now
        val result = JSONArray()
        for (s in sessions) {
            val presentations = JSONArray(s.presentations)
            val shown = JSONArray()
            for (i in 0 until presentations.length()) {
                val p = presentations.getJSONObject(i)
                shown.put(JSONObject().put("foodId", p.getString("foodId")).put("elapsedMs", p.getLong("elapsedMs"))
                    .put("historyCount", p.getInt("historyCount")).put("algorithmVersion", p.getString("algorithmVersion")).put("appVersion", p.getString("appVersion")))
            }
            result.put(JSONObject().put("sessionId", s.id).put("ordinal", s.ordinal).put("strategy", s.strategy)
                .put("dayIndex", ((s.startedAt - origin) / 86_400_000).coerceAtLeast(0)).put("mealPeriod", s.mealPeriod)
                .put("catalogHash", s.catalogHash).put("appVersion", if (presentations.length() > 0) presentations.getJSONObject(0).getString("appVersion") else BuildConfig.VERSION_NAME).put("learningEnabled", s.learningEnabled)
                .put("candidateCount", s.candidateCount).put("historyCount", s.historyCount).put("presentations", shown)
                .put("endReason", s.endReason ?: JSONObject.NULL).put("selectedFoodId", s.selectedFoodId ?: JSONObject.NULL)
                .put("selectedIndex", s.selectedIndex ?: JSONObject.NULL)
                .put("elapsedMs", s.endedAt?.let { (it - s.startedAt).coerceAtLeast(0) } ?: JSONObject.NULL)
                .put("confirmedEaten", if (s.selectedFoodId != null && events.any { it.type == "EATEN" && it.sessionId == s.id &&
                    it.foodId == s.selectedFoodId && it.time >= s.startedAt && it.time <= now }) true else JSONObject.NULL))
        }
        JSONObject().put("formatVersion", 1).put("protocol", config.protocol).put("participantId", config.participantId)
            .put("appVersion", BuildConfig.VERSION_NAME).put("sessions", result).toString(2)
    }
    companion object {
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun skipped(session: TrialSessionRow): List<MealEvent> {
            val entries = JSONArray(session.presentations)
            return (0 until (entries.length() - 1).coerceAtLeast(0)).map { index ->
                val p = entries.getJSONObject(index)
                MealEvent(p.getString("token"), EventType.SKIPPED, p.getString("foodId"),
                    Instant.ofEpochMilli(session.startedAt + p.getLong("elapsedMs")), sessionId = session.id)
            }
        }
        fun shownIds(session: TrialSessionRow): Set<String> {
            val entries = JSONArray(session.presentations)
            val shown = mutableSetOf<String>()
            for (i in 0 until entries.length()) {
                val id = entries.getJSONObject(i).getString("foodId")
                if (id in shown) shown.clear()
                shown += id
            }
            return shown
        }
    }
}
