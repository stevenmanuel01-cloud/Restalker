package com.restalker.mobile.core

/** 2.2 (owner: "a slider option for the bar to change the cap - this would be limited to $5 increments"): the cap's $5 steps and the
 *  bar's scale while the cap mark is dragged (pure: CoreTests). */
object CapStep {
    const val STEP = 5.0

    /** A dragged value -> the nearest $5 (at least $5). */
    fun snap(v: Double): Double = maxOf(STEP, Math.round(v / STEP) * STEP)

    /** + : the next $5 mark above (449.99 -> 450, 450 -> 455). */
    fun up(c: Double): Double = (Math.floor(c / STEP + 1e-9) + 1) * STEP

    /** - : the next $5 mark below, never under $5 (450.01 -> 450, 450 -> 445, 7 -> 5). */
    fun down(c: Double): Double = maxOf(STEP, (Math.ceil(c / STEP - 1e-9) - 1) * STEP)

    /** 2.8 (owner: "what value is used as the far right side of the gradient bar? it seems arbitrary, but should be related somehow to maybe
     *  the highest price available"): the bar ends at the HIGHEST price known for the product - the price now, every store's price, the
     *  TCG price and TCG's highest of the last 3 months [high], the cap and the MSRP - plus 10% room for the labels, on a $5 mark (at
     *  least $50). (2.2-2.7: 1.6 x the highest of the price now, the cap, the MSRP and the TCG price - room to drag the cap.) */
    const val ROOM = 1.10
    fun scale(now: Double?, cap: Double, msrp: Double?, tcg: Double?, high: Double? = null): Double {
        val m = top(now, cap, msrp, tcg, high)
        return maxOf(50.0, Math.ceil(m * ROOM / STEP) * STEP)
    }
    /** 2.10 (owner, 2026-11-18 12:22 PM: "The app slider bar still has overlapping information. Let's make the left side start around
     *  50% of whatever msrp price is for a product. Similarly, let's make the right side effectively double whatever the msrp price is
     *  (these numbers can be rounded to easier readability). This should zoom us in on the bar and help prevent overlap"): the bar runs
     *  from half the MSRP to twice the MSRP, rounded to an easy step ($5 / 10 / 25 / 50 / 100 by size). No MSRP: the same around the TCG
     *  price, else the cap, else the price now. A price outside it sits at that end (its label still says the real price).
     *  (2.8-2.9: $0 to the highest price seen + 10%.) */
    fun range(now: Double?, cap: Double, msrp: Double?, tcg: Double?): Pair<Double, Double> {
        val ok = { v: Double? -> v?.takeIf { it.isFinite() && it > 0 && it < 100000 } }
        val base = ok(msrp) ?: ok(tcg) ?: ok(cap) ?: ok(now) ?: return 0.0 to 50.0
        val step = when { base < 40 -> 5.0; base < 100 -> 10.0; base < 250 -> 25.0; base < 600 -> 50.0; else -> 100.0 }
        val lo = Math.floor(base * 0.5 / step) * step
        val hi = maxOf(Math.ceil(base * 2.0 / step) * step, lo + step)
        return lo to hi
    }

    /** The highest of them (what the bar's right end stands for). */
    fun top(now: Double?, cap: Double, msrp: Double?, tcg: Double?, high: Double? = null): Double =
        listOfNotNull(now, cap, msrp, tcg, high).filter { it.isFinite() && it > 0 && it < 100000 }.maxOrNull() ?: 0.0
    /** The highest price seen for [p] apart from its own listing: every store's last price and TCG's market prices of the last 3 months. */
    fun high(p: Product, tcgHist: List<Pair<Long, Double>>): Double? =
        (p.others.mapNotNull { it["pr"].asDouble() } + tcgHist.map { it.second } + listOfNotNull(p.dPr)).filter { it > 0 && it < 100000 }.maxOrNull()
}
