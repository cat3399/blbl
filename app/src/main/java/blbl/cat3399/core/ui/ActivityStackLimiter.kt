package blbl.cat3399.core.ui

import android.app.Activity
import java.lang.ref.WeakReference

object ActivityStackLimiter {
    private data class Entry(
        val group: String,
        val activity: WeakReference<Activity>,
    )

    private val lock = Any()
    private val entries = ArrayList<Entry>()

    fun register(group: String, activity: Activity, maxDepth: Int) {
        if (group.isBlank()) return
        if (maxDepth <= 0) return

        val toFinish = ArrayList<Activity>(2)
        synchronized(lock) {
            cleanupLocked()
            entries.add(Entry(group = group, activity = WeakReference(activity)))

            while (countLocked(group) > maxDepth) {
                val idx = entries.indexOfFirst { it.group == group && it.activity.get() != null }
                if (idx < 0) break
                val oldest = entries.removeAt(idx).activity.get()
                if (oldest != null && oldest !== activity && !oldest.isFinishing) {
                    toFinish.add(oldest)
                }
            }
        }

        toFinish.forEach { it.finish() }
    }

    fun unregister(group: String, activity: Activity) {
        if (group.isBlank()) return
        synchronized(lock) {
            entries.removeAll { e ->
                if (e.group != group) return@removeAll false
                val a = e.activity.get()
                a == null || a === activity
            }
        }
    }

    private fun cleanupLocked() {
        entries.removeAll { it.activity.get() == null }
    }

    private fun countLocked(group: String): Int {
        var c = 0
        for (e in entries) {
            if (e.group == group && e.activity.get() != null) c++
        }
        return c
    }
}

