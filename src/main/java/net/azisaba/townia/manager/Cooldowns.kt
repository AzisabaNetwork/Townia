package net.azisaba.townia.manager

import java.util.concurrent.ConcurrentHashMap

/** Short "not again so soon" timers (spawn travel, PvP toggles). Kept in memory only, as in Towny. */
object Cooldowns {
    private val started = ConcurrentHashMap<String, Long>()

    fun start(key: String) {
        started[key] = System.currentTimeMillis()
        if (started.size > 4096) {
            val cutoff = System.currentTimeMillis() - 3_600_000L
            started.values.removeIf { it < cutoff }
        }
    }

    /** Seconds still to wait for [key] given a cooldown of [seconds]; 0 when it may be used. */
    fun remaining(key: String, seconds: Int): Int {
        if (seconds <= 0) return 0
        val since = started[key] ?: return 0
        val left = seconds * 1000L - (System.currentTimeMillis() - since)
        return if (left <= 0) 0 else ((left + 999L) / 1000L).toInt()
    }

    fun clear() {
        started.clear()
    }
}
