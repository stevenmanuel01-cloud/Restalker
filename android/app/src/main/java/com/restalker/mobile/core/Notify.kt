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
    const val ALL = "all"; const val RESTOCKS = "restocks"; const val BUYS = "buys"; const val CUSTOM = "custom"; const val NONE = "none"

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
        listOf(true, false, false, true) -> RESTOCKS
        listOf(false, false, true, true) -> BUYS
        listOf(false, false, false, false) -> NONE
        else -> CUSTOM
    }

    /** A quick choice sets the switches ("Restalker needs you" stays on with each of them - it is how a sign-in or a robot check reaches you). */
    fun apply(st: Settings, preset: String): Settings = when (preset) {
        ALL -> st.copy(nRestock = true, nDrop = true, nBuy = true, nNeed = true)
        RESTOCKS -> st.copy(nRestock = true, nDrop = false, nBuy = false, nNeed = true)
        BUYS -> st.copy(nRestock = false, nDrop = false, nBuy = true, nNeed = true)
        else -> st
    }

    /** One line for the folded Settings section. */
    fun summary(st: Settings): String = when (preset(st)) {
        ALL -> "All alerts"
        RESTOCKS -> "Restocks only (and when it needs you)"
        BUYS -> "Purchases only (and when it needs you)"
        NONE -> "No alerts"
        else -> listOfNotNull("restocks".takeIf { st.nRestock }, "price drops".takeIf { st.nDrop }, "purchases".takeIf { st.nBuy },
            "needs you".takeIf { st.nNeed }).joinToString(", ").replaceFirstChar { it.uppercase() }
    }
}

/** 2.12: a price drop alert - a watched product that is for sale but OVER your cap gets clearly cheaper (at least 5% and at least \$5 below
 *  the price it was last measured from). Once alerted, that lower price is the new mark; a price that goes back up does not reset the mark
 *  for a day, so a price bouncing between two values alerts once, not every time. Under the cap it is a restock (or a purchase) instead.
 *  Pure: CoreTests. */
object PriceDrop {
    const val PCT = 0.05
    const val MIN_USD = 5.0
    const val HOLD_MS = 24 * 3_600_000L

    /** -> (alert now?, the new mark, the new mark's time). [ref]/[refAt]: the product's mark; [price]: for sale now over the cap (else null). */
    fun check(ref: Double?, refAt: Long, price: Double?, now: Long): Triple<Boolean, Double?, Long> {
        if (price == null || price <= 0) return Triple(false, ref, refAt)
        if (ref == null || ref <= 0) return Triple(false, price, now)                   // (the first price seen: the mark, no alert)
        val drop = ref - price
        if (drop >= MIN_USD && drop >= ref * PCT) return Triple(true, price, now)
        if (price > ref && now - refAt >= HOLD_MS) return Triple(false, price, now)       // (higher for over a day: measured from there)
        return Triple(false, ref, refAt)
    }

    /** Every watched product that is for sale over its cap now (this device's look or the linked PC's, whichever is newer): marks kept,
     *  [alert] called for each clear drop. -> how many alerts. */
    fun scan(store: Store, now: Long, alert: (Product, Double, Double) -> Unit): Int {
        var n = 0
        for (p in store.products()) {
            if (!p.watch) continue
            val (state, pr, _) = p.stock()
            val price = if (state == "over") pr else null
            if (price == null) continue
            val (go, mark, at) = check(p.dropRef, p.dropAt, price, now)
            if (mark != p.dropRef || at != p.dropAt) store.update(p.id) { it.copy(dropRef = mark, dropAt = at) }
            if (go) { alert(p, p.dropRef ?: price, price); n++ }
        }
        return n
    }
}
