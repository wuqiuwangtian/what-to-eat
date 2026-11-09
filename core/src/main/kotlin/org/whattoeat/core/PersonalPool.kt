package org.whattoeat.core

/** 候选池的选择与排序策略分离；同一状态下三种策略看见相同候选。 */
object PersonalPool {
    const val VERSION = "personal-pool-v1"
    val starterIds = setOf(1, 2, 3, 9, 10, 18, 25, 28, 34, 39, 40, 46, 48, 57, 61, 62,
        67, 70, 71, 72, 74, 75, 81, 84, 85, 97, 107, 114, 115, 116, 117, 125).map { "food-%03d".format(it) }.toSet()
    data class Selection(val foods: List<Food>, val source: String)

    fun select(eligible: List<Food>, favoriteIds: Set<String>, shownIds: Set<String>,
               unavailable: Set<String>, draws: Long, explored: Boolean, forceExplore: Boolean = false): Selection {
        val available = eligible.filter { it.id !in unavailable }
        // 用户已建立常吃池时，不因过滤后为空而悄悄恢复默认池。
        val regularIds = favoriteIds.ifEmpty { starterIds }
        val regular = available.filter { it.id in regularIds }
        val discovery = available.filter { it.id !in regularIds && it.id !in shownIds }
        if ((forceExplore || (draws % 10 == 9L && !explored)) && discovery.isNotEmpty()) {
            val meaningful = setOf("肉类", "蛋类", "鱼类", "水产", "豆类", "奶类", "面食", "蔬菜", "粤味", "川味")
            val regularGroups = regular.flatMap { it.groups }.toSet().intersect(meaningful)
            val nearest = discovery.groupBy { it.groups.intersect(regularGroups).size }.maxByOrNull { it.key }!!.value
            return Selection(nearest, if (forceExplore) "EXPLICIT_DISCOVERY" else "DISCOVERY")
        }
        return Selection(regular, if (favoriteIds.isEmpty()) "STARTER" else "FAMILIAR")
    }
}
