package org.whattoeat.app

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.whattoeat.core.Catalog
import java.time.Instant

/** 仅隔离的benchmark变体接受此预置；在测量区间外准备1000条合成历史。 */
internal fun seedBenchmarkHistory(context: Context) {
    check(BuildConfig.BUILD_TYPE == "benchmark" && context.packageName.endsWith(".benchmark"))
    runBlocking(Dispatchers.IO) {
        val foods = Catalog.load()
        val now = Instant.now().toEpochMilli()
        val rows = (0 until 1000).map { index ->
            val food = foods[index % foods.size]
            EventRow("benchmark-$index", "EATEN", food.id, food.name,
                now - (index + 1L) * 3600_000L, "benchmark-fixture", 1.0, null)
        }
        val db = MealDatabase.open(context)
        try { db.withTransaction { db.trials().clearSessions(); db.trials().clearConfig(); db.events().clear(); db.events().put(rows) } }
        finally { db.close() }
    }
}
