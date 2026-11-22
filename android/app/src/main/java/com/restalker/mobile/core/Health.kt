package com.restalker.mobile.core

/** 1.7 (owner 2026-11-16: "The error report didn't automatically send"): the app's self-check. It looks for anything you would
 *  notice as wrong - pictures that do not show, watching that stopped, the desktop link failing, high data use, a product marked
 *  bought without an order, a crash - and the app sends a report by itself (HealthRunner). Each problem must still be there 10 minutes
 *  later (no report for a moment's hiccup; a crash at once), and the same problem is reported at most once a day. */
data class HealthIn(
    val now: Long,
    val paceMs: Long = 0,                 // 1.1.0: the engine's time between looks right now (battery, the PC watching)
    val products: List<Product>,
    val purchases: List<Purchase>,
    val settings: Settings,
    val picFails: (String) -> Int = { 0 },
    val picStatus: (String) -> String? = { null },
    val picInfo: (String) -> String? = { null },
    val serviceRunning: Boolean = false,
    val serviceSince: Long = 0,
    val net: String? = "wifi",
    val deskBuys: Boolean = false,
    val linked: Boolean = false,
    val linkFailsInRow: Int = 0,
    val linkLast: String = "",
    val mbPerHour: Double? = null,
    val hoursWatching: Double = 0.0,
    val busy: Boolean = false,
    val crash: String? = null,
    val updateFail: String? = null,       // 1.7: the last self-update failed
    val outsideHours: Boolean = false,    // 1.0.4: outside the watch hours you set (nothing is looked at then, on purpose)
)

object Health {
    const val PERSIST_MS = 10 * 60_000L
    const val AGAIN_MS = 24 * 3_600_000L
    const val DATA_MB_H = 60.0

    /** -> (problem key, what is wrong), in the order to report them. */
    fun check(i: HealthIn): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        i.crash?.takeIf { it.isNotBlank() }?.let { out += "crash" to "the app crashed: ${it.lineSequence().firstOrNull { l -> l.isNotBlank() }?.take(160)}" }

        // pictures that are known but do not load (every address tried at least twice)
        val noLoad = i.products.filter { p -> Pics.shown(p).let { u -> u.isNotEmpty() && u.all { i.picFails(it) >= 2 } } }
        if (noLoad.isNotEmpty()) out += "pics-load" to "pictures do not load for ${noLoad.size} product(s): " +
            noLoad.take(3).joinToString("; ") { p -> "${p.asin} ${Pics.short(Pics.shown(p).first())} ${i.picStatus(Pics.shown(p).first()) ?: "?"}" }

        // products looked at whose page gave no picture (and the desktop has none either)
        val noPic = i.products.filter { p -> Pics.shown(p).isEmpty() && i.picInfo(p.asin).let { it != null && it != "found" } }
        if (noPic.isNotEmpty()) out += "pics-missing" to "no picture found on the Amazon page for ${noPic.size} product(s): " +
            noPic.take(3).joinToString("; ") { "${it.asin} [${i.picInfo(it.asin)?.take(140)}]" }

        val st = i.settings
        val watched = i.products.filter { it.watch }
        if (st.watching && watched.isNotEmpty()) {
            if (!i.serviceRunning) out += "service" to "watching is switched on but its service is not running (Android stopped it?)"
            else {
                val limit = if (i.deskBuys) 50 * 60_000L else maxOf(15 * 60_000L, 3L * st.intervalSec * 1000, 3L * i.paceMs)
                val canLook = i.net != null && !(st.wifiOnly && i.net != "wifi") && st.cooldownUntil < i.now && !i.busy && !i.outsideHours
                val newest = watched.maxOf { it.lastCheck }
                if (canLook && i.serviceSince > 0 && i.now - i.serviceSince > limit && i.now - newest > limit)
                    out += "stalled" to "watching runs but no product was looked at for ${(i.now - newest) / 60_000} min" +
                        " (limit ${limit / 60_000} min; desktop buys=${i.deskBuys}; network=${i.net})"
            }
        }
        if (i.linked && i.linkFailsInRow >= 10) out += "link" to "the desktop link failed ${i.linkFailsInRow} rounds in a row: ${i.linkLast.take(120)}"
        i.mbPerHour?.let { if (i.hoursWatching >= 1.0 && it > DATA_MB_H) out += "data" to "high data use: about ${it.toInt()} MB an hour" }
        i.updateFail?.let { out += "update" to it.take(200) }
        val bought = i.purchases.map { it.asin }.toSet()
        val odd = i.products.filter { it.purchased && it.asin !in bought }
        if (odd.isNotEmpty()) out += "latch" to "marked bought with no purchase record: ${odd.take(5).joinToString(", ") { it.asin }}"
        return out
    }

    /** Which of [now]'s problems to report: still there after PERSIST_MS (a crash at once) and not reported in the last day.
     *  [firstSeen] is updated (problems gone are forgotten). */
    fun due(problems: List<Pair<String, String>>, firstSeen: MutableMap<String, Long>, reported: Map<String, Long>, now: Long): List<Pair<String, String>> {
        val keys = problems.map { it.first }.toSet()
        firstSeen.keys.retainAll(keys)
        for (k in keys) firstSeen.putIfAbsent(k, now)
        return problems.filter { (k, _) -> (k == "crash" || now - (firstSeen[k] ?: now) >= PERSIST_MS) && now - (reported[k] ?: 0L) >= AGAIN_MS }
    }
}
