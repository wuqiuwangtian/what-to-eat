package org.whattoeat.core

import org.junit.Assert.*
import org.junit.Test

class TrialRulesTest {
    @Test fun everyBlockIsBalancedAndEveryPrefixDiffersByAtMostOneSession() {
        for (seed in listOf(0L, 7L, -99L, Long.MAX_VALUE)) {
            val counts = Strategy.entries.associateWith { 0 }.toMutableMap()
            repeat(120) { i ->
                val method = TrialRules.strategy(seed, i.toLong())
                counts[method] = counts.getValue(method) + 1
                assertTrue(counts.values.max() - counts.values.min() <= 1)
                if (i % 3 == 2) assertEquals(1, counts.values.distinct().size)
            }
        }
    }
    @Test fun allocationSurvivesReconstructionWithoutReseeding() {
        val uninterrupted = (0L..29L).map { TrialRules.strategy(1234, it) }
        assertEquals(uninterrupted, (0L..8L).map { TrialRules.strategy(1234, it) } +
            (9L..29L).map { TrialRules.strategy(1234, it) })
        assertTrue((0L..29L).map { TrialRules.strategy(5678, it) } != uninterrupted)
    }
    @Test fun idleBoundaryIsExactAndClockRollbackDoesNotCreateNegativeTimeout() {
        assertFalse(TrialRules.expired(100, 100 + TrialRules.IDLE_MILLIS - 1))
        assertTrue(TrialRules.expired(100, 100 + TrialRules.IDLE_MILLIS))
        assertFalse(TrialRules.expired(100, 99))
        assertThrows(IllegalArgumentException::class.java) { TrialRules.strategy(0, -1) }
    }
}
