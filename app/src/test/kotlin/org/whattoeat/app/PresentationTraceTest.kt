package org.whattoeat.app

import org.junit.Assert.*
import org.junit.Test

class PresentationTraceTest {
    @Test fun staleFrameCannotFinishANewerRequest() {
        val ended = mutableListOf<Pair<String, Int>>()
        val trace = PresentationTrace({ _, _ -> }, { name, id -> ended += name to id })
        val first = trace.begin(false)
        val second = trace.begin(true)
        assertEquals(listOf("meal.recommend" to first), ended)
        trace.presented(first)
        assertEquals(1, ended.size)
        trace.presented(second)
        trace.presented(second)
        assertEquals(listOf("meal.recommend" to first, "meal.switch" to second), ended)
    }
    @Test fun closingEndsAPendingTraceOnce() {
        val ended = mutableListOf<Int>()
        val trace = PresentationTrace({ _, _ -> }, { _, id -> ended += id })
        val id = trace.begin(false)
        trace.close(); trace.close(); trace.presented(id)
        assertEquals(listOf(id), ended)
    }
}
