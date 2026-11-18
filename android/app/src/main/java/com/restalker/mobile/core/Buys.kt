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

    /** One product's purchases (its page): by its Amazon code, or by its name for the other stores' purchases. */
    fun of(all: List<Buy>, asin: String, name: String): List<Buy> = all.filter { it.asin == asin || (name.isNotBlank() && it.name.equals(name, true)) }
}
