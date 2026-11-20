package com.restalker.mobile.core

/** 2.12: how long the pull-to-refresh circle turns - at least MIN_MS (so it is seen turning, never a frozen arrow), at most MAX_MS. */
object RefreshTiming {
    const val MIN_MS = 800L
    const val MAX_MS = 15_000L
}

/** 2.12 (owner: "There should be a series of settings that allow the user to decide if they want all notifications, restock only, price
 *  drop alerts, purchase alerts, etc."): which kinds of message this device shows (and sends to the ntfy alerts channel).
 *  Kinds: "restock", "drop" (price drop), "bought", "practice" (goes with purchases), "need" (Restalker needs you). Pure: CoreTests. */
object NotifyPrefs {
    const val ALL = "all"; const val USUAL = "usual"; const val RESTOCKS = "restocks"; const val BUYS = "buys"; const val CUSTOM = "custom"; const val NONE = "none"

    fun allowed(st: Settings, kind: String): Boolean = when (kind) {
        "restock" -> st.nRestock
        "drop" -> st.nDrop
        "bought", "practice" -> st.nBuy
        "need" -> st.nNeed
        else -> true                       // (the "test" message and anything new: always)
    }

    /** The quick choice that matches the switches (Custom when none matches). */
    fun preset(st: Settings): String = when (listOf(st.nRestock, st.nDrop, st.nBuy, st.nNeed)) {
        listOf(true, true, true, true) -> ALL
        listOf(true, false, true, true) -> USUAL       // (the default: all but price drops)
        listOf(true, false, false, true) -> RESTOCKS
        listOf(false, false, true, true) -> BUYS
        listOf(false, false, false, false) -> NONE
        else -> CUSTOM
    }

    /** A quick choice sets the switches ("Restalker needs you" stays on with each of them - it is how a sign-in or a robot check reaches you). */
    fun apply(st: Settings, preset: String): Settings = when (preset) {
        ALL -> st.copy(nRestock = true, nDrop = true, nBuy = true, nNeed = true)
        USUAL -> st.copy(nRestock = true, nDrop = false, nBuy = true, nNeed = true)
        RESTOCKS -> st.copy(nRestock = true, nDrop = false, nBuy = false, nNeed = true)
        BUYS -> st.copy(nRestock = false, nDrop = false, nBuy = true, nNeed = true)
        else -> st
    }

    /** One line for the folded Settings section. */
    fun summary(st: Settings): String = when (preset(st)) {
        ALL -> "All alerts"
        USUAL -> "Recommended: restocks, purchases, needs you"
        RESTOCKS -> "Restocks only (and when it needs you)"
        BUYS -> "Purchases only (and when it needs you)"
        NONE -> "No alerts"
        else -> listOfNotNull("restocks".takeIf { st.nRestock }, "price drops".takeIf { st.nDrop }, "purchases".takeIf { st.nBuy },
            "needs you".takeIf { st.nNeed }).joinToString(", ").replaceFirstChar { it.uppercase() }
    }
}

/** 2.12: a price drop alert - a watched product that is for sale gets clearly cheaper (at least 5% and at least \$5 below the price it was
 *  last measured from). Once alerted, that lower price is the new mark; a price that goes back up does not reset the mark for a day, so a
 *  price bouncing between two values alerts once, not every time.
 *  1.0.4 (owner, 2026-11-20: "the alert option should include things that are under our cap as well. There might be an item that is being
 *  watched but not armed and the user might want to know there is a price drop so they can manually go in and purchase"): 2.12 - 1.0.3
 *  alerted only OVER the cap. Now also at or under the cap (or watched without a cap) while the product is NOT set to Buy - set to Buy
 *  it is bought by itself, and the moment it comes under the cap (or back in stock) is a restock message, not a drop: under the cap only
 *  a FURTHER drop alerts ([Product.dropSt] says where the mark was measured). Pure: CoreTests. */
object PriceDrop {
    const val PCT = 0.05
    const val MIN_USD = 5.0
    const val HOLD_MS = 24 * 3_600_000L

    /** -> (alert now?, the new mark, the new mark's time). [ref]/[refAt]: the product's mark; [price]: for sale now (else null). */
    fun check(ref: Double?, refAt: Long, price: Double?, now: Long): Triple<Boolean, Double?, Long> {
        if (price == null || price <= 0) return Triple(false, ref, refAt)
        if (ref == null || ref <= 0) return Triple(false, price, now)                   // (the first price seen: the mark, no alert)
        val drop = ref - price
        if (drop >= MIN_USD && drop >= ref * PCT) return Triple(true, price, now)
        if (price > ref && now - refAt >= HOLD_MS) return Triple(false, price, now)       // (higher for over a day: measured from there)
        return Triple(false, ref, refAt)
    }

    /** Every watched product that is for sale now (this device's look or the linked PC's, whichever is newer): marks kept, [alert]
     *  called for each clear drop (product, the price before, the price now). -> how many alerts. */
    fun scan(store: Store, now: Long, alert: (Product, Double, Double) -> Unit): Int {
        var n = 0
        for (p in store.products()) {
            if (!p.watch) continue
            val (state, pr, _) = p.stock()
            if (state == "out") {                       // (sold out: the next time under the cap is a restock, not a drop)
                if (p.dropSt.isNotEmpty()) store.update(p.id) { it.copy(dropSt = "") }
                continue
            }
            if (state != "over" && state != "in") continue
            val price = pr?.takeIf { it > 0 } ?: continue
            if (state == "in" && (p.buy || p.dropSt != "in")) {
                // set to Buy (bought by itself), or it only now came under the cap / back in stock (the restock message says so):
                // the mark is set here without an alert - only a further drop from here alerts
                if (p.dropRef != price || p.dropSt != "in") store.update(p.id) { it.copy(dropRef = price, dropAt = now, dropSt = "in") }
                continue
            }
            val (go, mark, at) = check(p.dropRef, p.dropAt, price, now)
            if (mark != p.dropRef || at != p.dropAt || p.dropSt != state) store.update(p.id) { it.copy(dropRef = mark, dropAt = at, dropSt = state) }
            if (go) { alert(p, p.dropRef ?: price, price); n++ }
        }
        return n
    }
}

/** 1.0.4 (owner, 2026-11-20: "Is it possible to allow the user to select times of the day to start and stop the watcher automatically?
 *  It might be nice to allow the device to look hard overnight when it's not otherwise in use"): watch hours - off = all day; on = only
 *  from [Settings.hoursFrom] to [Settings.hoursTo] (minutes after midnight on this device's own clock; From later than To = over
 *  midnight). Outside them nothing is looked at, said or bought by itself (what you start yourself - Check now, Buy now - still runs).
 *  Pure: CoreTests. */
object WatchHours {
    fun minuteOfDay(nowMs: Long, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): Int {
        val local = nowMs + tz.getOffset(nowMs)
        return (((local % 86_400_000L) + 86_400_000L) % 86_400_000L / 60_000L).toInt()
    }
    fun inside(on: Boolean, from: Int, to: Int, minute: Int): Boolean =
        !on || from == to || (if (from < to) minute in from until to else (minute >= from || minute < to))
    fun inside(st: Settings, nowMs: Long, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): Boolean =
        inside(st.hoursOn, st.hoursFrom, st.hoursTo, minuteOfDay(nowMs, tz))
    /** "10:00 PM" */
    fun label(min: Int): String {
        val m = ((min % 1440) + 1440) % 1440
        val h = m / 60
        return "${if (h % 12 == 0) 12 else h % 12}:${"%02d".format(m % 60)} ${if (h < 12) "AM" else "PM"}"
    }
    /** One line for Settings: "All day" / "10:00 PM - 7:00 AM". */
    fun words(st: Settings): String = if (!st.hoursOn || st.hoursFrom == st.hoursTo) "All day" else "${label(st.hoursFrom)} - ${label(st.hoursTo)}"
    /** A time moved by [by] minutes, round the clock. */
    fun step(min: Int, by: Int): Int = (((min + by) % 1440) + 1440) % 1440
}

/** 1.0.4 (owner: "there should be an indicator of how old the data is. "6 hours ago" or something might be fine"): how long ago, in
 *  words - "just now", "45s ago", "12 min ago", "6 h ago", "3 days ago". Pure: CoreTests. */
object Ago {
    fun words(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 5 -> "just now"
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 48 * 3600 -> "${s / 3600} h ago"
            else -> "${s / 86400} days ago"
        }
    }
}

/** 1.0.4: the free message service (ntfy.sh) the linked devices, the alerts and the owner's reports go through gives each home internet
 *  address 250 messages a day for ALL its devices together (its own count: GET /v1/account - limit, how many are left, when the count
 *  starts again). When few are left, what can wait is not sent (the hourly "still here", automatic reports), so that what cannot wait -
 *  a product you changed, a purchase, an alert - still gets through; and the screen says so instead of calling a device "silent".
 *  Pure: CoreTests. */
object NtfyQuota {
    const val URL = "https://ntfy.sh/v1/account"
    const val KEEP_FOR_URGENT = 40        // the hourly post waits when no more than this many are left
    const val KEEP_FOR_NORMAL = 100       // automatic reports wait when no more than this many are left
    data class Q(val limit: Int, val left: Int, val resetAt: Long, val at: Long) {
        fun toMap(): Map<String, Any?> = linkedMapOf("limit" to limit, "left" to left, "resetAt" to resetAt, "at" to at)
    }
    /** ntfy's answer -> what it says (times in ms), or null when it is not that answer. */
    fun parse(json: String?, now: Long): Q? {
        val m = MiniJson.obj(json) ?: return null
        val lim = m["limits"].asMap()?.get("messages").asLong() ?: return null
        val st = m["stats"].asMap() ?: return null
        val left = st["messages_remaining"].asLong() ?: return null
        val reset = st["reset_at"].asLong() ?: 0L
        if (lim <= 0 || left < 0) return null
        return Q(lim.toInt(), left.toInt().coerceAtMost(lim.toInt()), reset * 1000, now)
    }
    fun fromMap(m: Map<String, Any?>?): Q? {
        if (m == null) return null
        return Q((m["limit"].asLong() ?: return null).toInt(), (m["left"].asLong() ?: return null).toInt(), m["resetAt"].asLong() ?: 0L, m["at"].asLong() ?: 0L)
    }
    /** What is known is still true: read within the last 6 hours and the count has not started again since. */
    fun current(q: Q?, now: Long): Q? = q?.takeIf { now - it.at < 6 * 3_600_000L && (it.resetAt <= 0 || now < it.resetAt) }
    /** Used up for today (as far as known). */
    fun usedUp(q: Q?, now: Long): Boolean = current(q, now)?.let { it.left <= 0 } ?: false
    /** May something that can wait be sent now? [keep]: how many are kept back for what cannot wait. Not known = yes. */
    fun may(q: Q?, now: Long, keep: Int): Boolean = current(q, now)?.let { it.left > keep } ?: true
}
