package com.restalker.mobile.core

/** 2.14 (phone report 2026-11-19: "high data use: about 74 MB an hour", then 158 MB an hour, on mobile data while the phone covered
 *  for a stopped PC - and the looks alone explain only a part of it): where this app's data goes, for the diagnostics - Amazon looks,
 *  checkouts, reading the orders, pictures, and everything else (between looks, the PC link, the crowd, the app's own screens).
 *  [rx]: the bytes this app has received so far (-1 = not known). Only the outermost activity counts (a look inside a checkout is the
 *  checkout's). Pure: CoreTests. */
class DataMeter(private val rx: () -> Long) {
    private val bytes = LinkedHashMap<String, Long>()
    private val counts = LinkedHashMap<String, Int>()
    @Volatile private var depth = 0

    fun <T> run(kind: String, f: () -> T): T {
        if (depth > 0) return f()
        depth++
        val a = rx()
        try { return f() } finally {
            depth--
            val b = rx()
            if (a >= 0 && b >= a) synchronized(bytes) { bytes[kind] = (bytes[kind] ?: 0L) + (b - a); counts[kind] = (counts[kind] ?: 0) + 1 }
        }
    }

    fun of(kind: String): Long = synchronized(bytes) { bytes[kind] ?: 0L }
    fun count(kind: String): Int = synchronized(bytes) { counts[kind] ?: 0 }

    /** One line: each activity's MB (how many, the average) and "other" = [total] (all this app received since watching began) minus those. */
    fun text(total: Long): String = synchronized(bytes) {
        fun mb(b: Long) = String.format(java.util.Locale.US, "%.1f MB", b / 1_048_576.0)
        val parts = bytes.map { (k, b) -> val n = counts[k] ?: 0; "$k ${mb(b)}" + if (n > 0) " ($n, about ${b / n / 1024} KB each)" else "" }
        val other = if (total >= 0) " · other (between looks, PC link, crowd, the app's screens) ${mb((total - bytes.values.sum()).coerceAtLeast(0))}" else ""
        "Data by activity: " + (if (parts.isEmpty()) "nothing measured yet" else parts.joinToString(" · ")) + other
    }
}
