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

    /** The bar's end while dragging: 1.6 x the highest of the price now, the cap, the MSRP and the TCG price, on a $5 mark, at least $50. */
    fun scale(now: Double?, cap: Double, msrp: Double?, tcg: Double?): Double {
        val m = listOfNotNull(now, cap, msrp, tcg).filter { it.isFinite() && it > 0 }.maxOrNull() ?: 0.0
        return maxOf(50.0, Math.ceil(m * 1.6 / STEP) * STEP)
    }
}
