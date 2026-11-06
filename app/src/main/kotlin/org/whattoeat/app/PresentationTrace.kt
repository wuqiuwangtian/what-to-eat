package org.whattoeat.app

import android.os.Trace

/** 只向系统性能跟踪写区间，不存储或上传用户数据。 */
internal class PresentationTrace(
    private val beginSection: (String, Int) -> Unit = { name, id -> Trace.beginAsyncSection(name, id) },
    private val endSection: (String, Int) -> Unit = { name, id -> Trace.endAsyncSection(name, id) },
) {
    private var sequence = 0
    private var pending: Pair<String, Int>? = null

    fun begin(switching: Boolean): Int {
        close()
        val id = ++sequence
        val name = if (switching) "meal.switch" else "meal.recommend"
        pending = name to id
        beginSection(name, id)
        return id
    }
    fun presented(id: Int) {
        val current = pending ?: return
        if (current.second == id) {
            endSection(current.first, current.second)
            pending = null
        }
    }
    fun close() { pending?.let { endSection(it.first, it.second) }; pending = null }
}
