package com.restalker.mobile.core

/** 2.6 (owner, 2026-11-17: "XXX total contributors and counting" ... "the importance of crowdsourcing the information for everyone to
 *  share" - chose "Build real sharing"): the Restalker crowd, the same channel the PC program uses (its crowd.py v137).
 *  Every Restalker that shares tells the others when it sees a product of the central list (mobile/catalog.json) come in stock: the
 *  product's Amazon code, the store, the price and the time - nothing else (no name, account, order, address, no products of your own).
 *  Each device has a random contributor number (made here, tied to nothing) so the devices can be counted.
 *  A sighting from the crowd makes this phone LOOK at that product itself at once; it never buys on the crowd's word - only what its
 *  own look sees in stock under YOUR cap. A false or old sighting costs one extra look at most (once per product every 2 minutes,
 *  40 an hour in all). Pure (the network is outside): CoreTests. */
object Crowd {
    const val SERVER = "https://ntfy.sh"
    const val TOPIC = "restalker-crowd-5e1f0c7a92b4"
    val STORES = setOf("Amazon", "Target", "Walmart", "Best Buy", "GameStop", "TCGplayer", "Barnes & Noble", "Costco", "Sam's Club", "Pokemon Center")
    const val HELLO_EVERY_MS = 6 * 3_600_000L
    const val POLL_EVERY_MS = 2 * 60_000L
    const val LOOK_GAP_MS = 120_000L
    const val LOOKS_PER_HOUR = 40
    const val SEEN_MAX = 20000
    /** 2.8: the first read of the channel goes back 12 hours (the channel keeps 12 hours; everyone says hello every 6): the count is right
     *  at once, not after the others' next hello. */
    const val FIRST_SINCE = "12h"
    private val ASIN = Regex("^B[0-9A-Z]{9}$")
    private val ID = Regex("^[0-9a-f]{12}$")

    data class Msg(val k: String, val c: String, val a: String = "", val s: String = "", val st: String = "", val p: Double? = null, val t: Double = 0.0)

    /** One message from the channel -> clean, or null (anything odd is dropped). Sightings must be fresh (within the hour). */
    fun clean(m: Map<String, Any?>?, nowS: Double): Msg? {
        if (m == null || m["v"].asDouble() != 1.0) return null
        val c = m["c"].asStr() ?: return null
        if (!ID.matches(c)) return null
        when (m["k"].asStr()) {
            "h" -> return Msg("h", c)
            "s" -> {
                val a = m["a"].asStr() ?: return null
                val s = m["s"].asStr() ?: return null
                val st = m["st"].asStr() ?: return null
                val t = m["t"].asDouble() ?: return null
                if (!ASIN.matches(a) || s !in STORES || st !in setOf("in", "out") || Math.abs(nowS - t) > 3600) return null
                return Msg("s", c, a, s, st, m["p"].asDouble()?.takeIf { it > 0 && it < 100000 }, t)
            }
        }
        return null
    }

    fun sighting(id: String, asin: String, price: Double?, nowS: Double): Map<String, Any?> = linkedMapOf<String, Any?>(
        "v" to 1, "c" to id, "k" to "s", "a" to asin, "s" to "Amazon", "st" to "in", "t" to Math.round(nowS * 10) / 10.0).also {
        if (price != null && price > 0 && price < 100000) it["p"] = Math.round(price * 100) / 100.0
    }

    fun hello(id: String): Map<String, Any?> = linkedMapOf("v" to 1, "c" to id, "k" to "h")
    fun okAsin(a: String) = ASIN.matches(a)
}

/** The crowd's memory on this phone (crowd.json): this device's contributor number, every contributor heard so far, where reading
 *  goes on, when it last said hello, and the looks it took because of the crowd. */
class CrowdState(private val store: Store, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val lock = Any()
    private var id = ""
    private val seen = LinkedHashMap<String, Long>()
    var since = ""; private set
    var helloAt = 0L
    private val looked = HashMap<String, Long>()
    private val hour = ArrayList<Long>()

    init {
        val m = MiniJson.obj(store.readFile(FILE) ?: "") ?: emptyMap()
        id = m["id"].asStr()?.takeIf { Regex("^[0-9a-f]{12}$").matches(it) } ?: newId()
        m["seen"].asMap()?.forEach { (k, v) -> if (Regex("^[0-9a-f]{12}$").matches(k)) seen[k] = v.asLong() ?: 0L }
        since = m["since"].asStr() ?: ""
        helloAt = m["hello"].asLong() ?: 0L
        save()
    }

    private fun newId(): String { val r = java.security.SecureRandom(); return (1..12).map { "0123456789abcdef"[r.nextInt(16)] }.joinToString("") }
    fun myId(): String = id
    /** Every contributor heard so far (this one included). */
    fun count(): Int = synchronized(lock) { (seen.keys + id).size }

    fun save() = synchronized(lock) {
        store.writeFile(FILE, MiniJson.write(linkedMapOf("id" to id, "seen" to LinkedHashMap(seen), "since" to since, "hello" to helloAt)))
    }

    /** ntfy's json lines -> contributors counted; a fresh in-stock sighting on Amazon by another contributor, of a product in [mine],
     *  makes [look](asin) run (once per product in LOOK_GAP_MS, LOOKS_PER_HOUR in all). -> the products looked at. */
    fun take(lines: List<String>, mine: Set<String>, look: (String) -> Boolean): List<String> {
        val now = clock()
        val out = ArrayList<String>()
        for (line in lines) {
            val ev = MiniJson.obj(line) ?: continue
            ev["id"].asStr()?.let { synchronized(lock) { since = it.take(40) } }
            if (ev["event"].asStr() != "message") continue
            val m = Crowd.clean(MiniJson.obj(ev["message"].asStr() ?: ""), now / 1000.0) ?: continue
            synchronized(lock) {
                seen[m.c] = now / 1000
                if (seen.size > Crowd.SEEN_MAX) seen.entries.sortedBy { it.value }.take(seen.size - Crowd.SEEN_MAX).forEach { seen.remove(it.key) }
            }
            if (m.k != "s" || m.st != "in" || m.s != "Amazon" || m.c == id || m.a !in mine) continue
            hour.removeAll { now - it > 3_600_000L }
            if (now - (looked[m.a] ?: 0L) < Crowd.LOOK_GAP_MS || hour.size >= Crowd.LOOKS_PER_HOUR) continue
            looked[m.a] = now; hour += now
            if (look(m.a)) out += m.a
        }
        save()
        return out
    }

    companion object { const val FILE = "crowd.json" }
}
