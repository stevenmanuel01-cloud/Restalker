package com.restalker.mobile.core

/** 2.8 (owner, 2026-11-17 11:10 PM: "include the tcg price graph for each product under each product page. This gives the user an idea of
 *  the price trends before they have to go to TCG to check. Essentially just reposting this information is sufficient, and does not need
 *  to be refreshed more than once per day during the off peak hours"). TCGplayer's own market-price history of every central-list product
 *  is read once a day at ~2 AM Pacific (GitHub, tools/tcg_history.py) into mobile/tcg_history.json; this phone reads that one small file
 *  at most once a day and keeps it (tcg_history.json). Every phone has the chart - linked to a PC or not. Pure: CoreTests. */
object TcgHist {
    const val URL = "https://raw.githubusercontent.com/stevenmanuel01-cloud/Restalker/main/mobile/tcg_history.json"
    const val FILE = "tcg_history.json"
    const val EVERY_MS = 24 * 3_600_000L
    private val ASIN = Regex("^[A-Z0-9]{10}$")
    @Volatile private var cache: Pair<String, Map<String, List<Pair<Long, Double>>>>? = null

    /** The file -> {asin: [(day, price), ...]} (oldest first; anything odd dropped). */
    fun parse(json: String?): Map<String, List<Pair<Long, Double>>> {
        val root = MiniJson.obj(json ?: return emptyMap()) ?: return emptyMap()
        val h = root["h"].asMap() ?: return emptyMap()
        val out = LinkedHashMap<String, List<Pair<Long, Double>>>()
        for ((a, v) in h.entries.take(2000)) {
            if (!ASIN.matches(a)) continue
            val pts = v.asList().mapNotNull { x -> val l = x.asList(); val d = l.getOrNull(0).asLong(); val p = l.getOrNull(1).asDouble()
                if (d != null && d in 10_000..40_000 && p != null && p > 0 && p < 100000) d to p else null }
                .distinctBy { it.first }.sortedBy { it.first }.takeLast(400)
            if (pts.size >= 2) out[a] = pts
        }
        return out
    }

    /** A good file (at least one product's history) - only then is the kept one replaced. */
    fun ok(json: String?): Boolean = parse(json).isNotEmpty()

    /** This product's TCGplayer history from the kept file (empty when none). */
    fun of(store: Store, asin: String): List<Pair<Long, Double>> {
        val raw = store.readFile(FILE) ?: return emptyList()
        val c = cache
        val m = if (c != null && c.first.length == raw.length && c.first == raw) c.second else parse(raw).also { cache = raw to it }
        return m[asin] ?: emptyList()
    }

    /** 2.8: a phone NOT linked to a PC takes the TCG market price (the newest point) from it - the PC's live value wins when linked.
     *  -> how many products changed. */
    fun apply(store: Store, hist: Map<String, List<Pair<Long, Double>>>, linked: Boolean): Int {
        if (linked) return 0
        var n = 0
        for (p in store.products()) {
            val last = hist[p.asin]?.lastOrNull()?.second ?: continue
            if (p.tcg != last) { store.update(p.id) { it.copy(tcg = last) }; n++ }
        }
        return n
    }

    /** What the chart shows: TCGplayer's own history when there is one, else what the linked PC recorded. -> (points, from TCGplayer). */
    fun pick(central: List<Pair<Long, Double>>, pc: List<Pair<Long, Double>>): Pair<List<Pair<Long, Double>>, Boolean> =
        if (central.size >= 2) central to true else pc to false
}
