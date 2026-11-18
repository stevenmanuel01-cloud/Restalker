package com.restalker.mobile.core

/** 2.8 (owner, 2026-11-17 11:10 PM: "include the tcg price graph for each product under each product page. This gives the user an idea of
 *  the price trends before they have to go to TCG to check. Essentially just reposting this information is sufficient, and does not need
 *  to be refreshed more than once per day during the off peak hours" - and 11:26 PM, TCGplayer's own "Market Price History" chart: the
 *  market price as a line, the items sold as bars, 3 months / 1 year). TCGplayer's own history of every central-list product is read once
 *  a day at ~2 AM Pacific (GitHub, tools/tcg_history.py) into mobile/tcg_history.json; this phone reads that one small file at most once a
 *  day and keeps it (tcg_history.json). Every phone has the chart - linked to a PC or not. Pure: CoreTests. */
object TcgHist {
    const val URL = "https://raw.githubusercontent.com/stevenmanuel01-cloud/Restalker/main/mobile/tcg_history.json"
    const val FILE = "tcg_history.json"
    const val EVERY_MS = 24 * 3_600_000L
    private val ASIN = Regex("^[A-Z0-9]{10}$")
    @Volatile private var cache: Pair<String, Pair<Map<String, Series>, Map<String, Series>>>? = null

    /** One product's TCGplayer history: the market price line and the items sold per point (the bars of TCGplayer's own chart). */
    data class Series(val pts: List<Pair<Long, Double>>, val sold: List<Pair<Long, Int>>) {
        /** The change from the first to the last price, in percent (TCGplayer shows it next to the price). */
        val changePct: Double? get() = if (pts.size >= 2 && pts.first().second > 0) (pts.last().second / pts.first().second - 1) * 100 else null
    }

    /** The file -> {asin: Series} of 3 months ([key] "h") or 1 year ("y"); oldest first, anything odd dropped. (A v1 file: no sold counts.) */
    fun parseSeries(json: String?, key: String = "h"): Map<String, Series> {
        val root = MiniJson.obj(json ?: return emptyMap()) ?: return emptyMap()
        val h = root[key].asMap() ?: return emptyMap()
        val out = LinkedHashMap<String, Series>()
        for ((a, v) in h.entries.take(2000)) {
            if (!ASIN.matches(a)) continue
            val rows = v.asList().mapNotNull { x ->
                val l = x.asList(); val d = l.getOrNull(0).asLong(); val p = l.getOrNull(1).asDouble()
                val q = (l.getOrNull(2).asLong() ?: 0L).coerceIn(0L, 1_000_000L).toInt()
                if (d != null && d in 10_000..40_000 && p != null && p > 0 && p < 100000) Triple(d, p, q) else null
            }.distinctBy { it.first }.sortedBy { it.first }.takeLast(400)
            if (rows.size >= 2) out[a] = Series(rows.map { it.first to it.second }, rows.map { it.first to it.third })
        }
        return out
    }

    /** The file -> {asin: [(day, price), ...]} (3 months). */
    fun parse(json: String?): Map<String, List<Pair<Long, Double>>> = parseSeries(json).mapValues { it.value.pts }

    /** A good file (at least one product's history) - only then is the kept one replaced. */
    fun ok(json: String?): Boolean = parse(json).isNotEmpty()

    /** This product's TCGplayer series - 3 months, or 1 year when [year] (null when none). */
    fun series(store: Store, asin: String, year: Boolean = false): Series? {
        val raw = store.readFile(FILE) ?: return null
        val c = cache
        val m = if (c != null && c.first.length == raw.length && c.first == raw) c.second
            else (parseSeries(raw, "h") to parseSeries(raw, "y")).also { cache = raw to it }
        return (if (year) m.second else m.first)[asin]
    }

    /** This product's TCGplayer history (3 months) from the kept file (empty when none). */
    fun of(store: Store, asin: String): List<Pair<Long, Double>> = series(store, asin)?.pts ?: emptyList()

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
