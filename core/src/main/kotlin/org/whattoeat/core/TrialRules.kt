package org.whattoeat.core

import java.util.Random

/** 协议升级须保留已分配策略，不重算旧会话。每三个会话三种方法各出现一次。 */
object TrialRules {
    const val PROTOCOL = "local-crossover-v1"
    const val IDLE_MILLIS = 30 * 60_000L
    private val orders = listOf(
        listOf(Strategy.RANDOM, Strategy.SIMPLE, Strategy.PERSONALIZED),
        listOf(Strategy.RANDOM, Strategy.PERSONALIZED, Strategy.SIMPLE),
        listOf(Strategy.SIMPLE, Strategy.RANDOM, Strategy.PERSONALIZED),
        listOf(Strategy.SIMPLE, Strategy.PERSONALIZED, Strategy.RANDOM),
        listOf(Strategy.PERSONALIZED, Strategy.RANDOM, Strategy.SIMPLE),
        listOf(Strategy.PERSONALIZED, Strategy.SIMPLE, Strategy.RANDOM),
    )
    fun strategy(seed: Long, ordinal: Long): Strategy {
        require(ordinal >= 0)
        val random = Random(seed xor ((ordinal / 3) * -7046029254386353131L))
        return orders[random.nextInt(orders.size)][(ordinal % 3).toInt()]
    }
    fun expired(lastActivity: Long, now: Long) = now >= lastActivity && now - lastActivity >= IDLE_MILLIS
}
