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
    /** The highest of them (what the bar's right end stands for). */
    fun top(now: Double?, cap: Double, msrp: Double?, tcg: Double?, high: Double? = null): Double =
        listOfNotNull(now, cap, msrp, tcg, high).filter { it.isFinite() && it > 0 && it < 100000 }.maxOrNull() ?: 0.0
    /** The highest price seen for [p] apart from its own listing: every store's last price and TCG's market prices of the last 3 months. */
    fun high(p: Product, tcgHist: List<Pair<Long, Double>>): Double? =
        (p.others.mapNotNull { it["pr"].asDouble() } + tcgHist.map { it.second } + listOfNotNull(p.dPr)).filter { it > 0 && it < 100000 }.maxOrNull()
}
