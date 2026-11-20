package com.restalker.mobile.core

/** 2.4 (owner: 'the "bought" section of the app should include the data from the PC version as well. So the user can see their total
 *  bought data. This should include time, device, price, store, and browser'): every purchase of every linked device in one list -
 *  this device's own, the PC's purchase list (its phonelink.py v135 sends it) and the other phones' / tablets'. Pure: CoreTests. */
data class Buy(val at: Long, val store: String, val name: String, val price: Double?, val by: String, val device: String, val asin: String,
               val mine: Boolean, val verified: Boolean? = null, val note: String = "")

object Buys {
    const val MAX_SENT = 100

    /** A purchase list from the link, cleaned (only these fields, only sane values) - kept as plain maps in the link's file. */
    fun clean(list: Any?, max: Int = 200): List<Map<String, Any?>> = list.asList().mapNotNull { it.asMap() }.mapNotNull { m ->
        val t = m["t"].asDouble()?.takeIf { it > 1.0e9 && it < 1.0e11 } ?: return@mapNotNull null
        linkedMapOf<String, Any?>("t" to t, "s" to (m["s"].asStr() ?: "").take(20), "n" to (m["n"].asStr() ?: "").take(160),
            "p" to m["p"].asDouble()?.takeIf { it > 0 && it < 100000 }, "by" to (m["by"].asStr() ?: "").take(60),
            "a" to (m["a"].asStr() ?: "").take(30))
    }.take(max)

    /** What this device sends: its own purchases (newest first). */
    fun post(own: List<Purchase>): List<Map<String, Any?>> = own.sortedByDescending { it.at }.take(MAX_SENT).map {
        linkedMapOf("t" to it.at / 1000.0, "s" to "Amazon", "n" to it.name.take(160), "p" to it.price, "by" to "the app", "a" to it.asin)
    }

    fun fromLink(list: List<Map<String, Any?>>, device: String): List<Buy> = list.map { m ->
        Buy(((m["t"].asDouble() ?: 0.0) * 1000).toLong(), (m["s"].asStr() ?: "").ifBlank { "Amazon" }, m["n"].asStr() ?: "", m["p"].asDouble(),
            (m["by"].asStr() ?: "").ifBlank { "the buying browser" }, device, m["a"].asStr() ?: "", false)
    }

    fun mine(own: List<Purchase>, myName: String): List<Buy> = own.map {
        Buy(it.at, "Amazon", it.name, it.price, "the app", myName, it.asin, true, it.verified, it.note)
    }

    /** Everything, newest first; the same purchase heard twice (the same product and store within 2 minutes) is shown once - this
     *  device's own record first. */
    fun all(groups: List<List<Buy>>): List<Buy> {
        val out = ArrayList<Buy>()
        for (b in groups.flatten().sortedWith(compareBy<Buy>({ !it.mine }).thenByDescending { it.at })) {
            if (out.any { o -> o.store == b.store && Math.abs(o.at - b.at) < 120_000 && (o.asin == b.asin && b.asin.isNotEmpty() || o.name == b.name) }) continue
            out += b
        }
        return out.sortedByDescending { it.at }
    }

    /** 1.0 (owner, 2026-11-19: '"Bought" should be sortable by all, product, store, etc.'): the Bought list in groups - ALL: by month
     *  (the newest month first); PRODUCT and STORE: by name, A to Z; DEVICE: this device first, then by name. Inside a group the newest
     *  purchase is first. [key] is the group's name for folding (kept), [total] what its purchases cost together. */
    const val ALL = 0; const val PRODUCT = 1; const val STORE = 2; const val DEVICE = 3
    data class Group(val key: String, val title: String, val rows: List<Buy>, val total: Double)
    fun grouped(list: List<Buy>, by: Int, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<Group> {
        val rows = list.sortedByDescending { it.at }
        val out = LinkedHashMap<String, Pair<String, MutableList<Buy>>>()
        for (b in rows) {
            val (key, title) = when (by) {
                PRODUCT -> b.name.ifBlank { b.asin }.ifBlank { "?" }.trim().let { "p:" + it.lowercase() to it }
                STORE -> b.store.ifBlank { "?" }.trim().let { "s:" + it.lowercase() to it }
                DEVICE -> b.device.ifBlank { "?" }.trim().let { "d:" + it.lowercase() to it }
                else -> java.time.Instant.ofEpochMilli(b.at).atZone(zone).let { d ->
                    "m:%04d-%02d".format(d.year, d.monthValue) to d.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US) + " " + d.year }
            }
            out.getOrPut(key) { title to ArrayList() }.second.add(b)
        }
        val groups = out.map { (k, v) -> Group(k, if (by == DEVICE && v.second.any { it.mine }) v.first + " (this device)" else v.first, v.second,
            v.second.mapNotNull { it.price }.sum()) }
        return when (by) {
            PRODUCT, STORE -> groups.sortedBy { it.key }
            DEVICE -> groups.sortedWith(compareBy<Group>({ g -> !g.rows.any { it.mine } }).thenBy { it.key })
            else -> groups                                   // (months: already the newest first)
        }
    }

    /** One product's purchases (its page): by its Amazon code, or by its name for the other stores' purchases. */
    fun of(all: List<Buy>, asin: String, name: String): List<Buy> = all.filter { it.asin == asin || (name.isNotBlank() && it.name.equals(name, true)) }
}
