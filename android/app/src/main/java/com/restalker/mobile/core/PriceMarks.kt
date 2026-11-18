package com.restalker.mobile.core

/** 2.0: the ticks of the product screen's price bar - $0, MSRP, TCG, the price now and your cap (pure: tested in CoreTests).
 *  2.3 (owner: "place all static pricing on top of the line and leave the dynamic cap slider on the bottom"): $0, MSRP, TCG and Now are
 *  labelled ABOVE the line, on up to four rows; the cap (the slider) is the only label BELOW the line.
 *  2.4 (owner: "There is still some overlap in the slider bar numbers. Just make sure these text boxes are able to adjust so that they
 *  dont overlap"): each label's REAL width is used (the screen measures it - bigger text in the phone's settings makes it wider; 2.3
 *  assumed a fixed width, which bigger text overflowed). Fractions are of the bar's width; the bar ends 12 % past the highest value (or
 *  at a fixed [scale]). */
class PriceMarks private constructor(val items: List<Mark>, val max: Double, val rows: Int, private val widths: Map<String, Double>) {
    /** [row]: 0 = the row just above the line, 1..3 higher up; the cap is always below the line (row 0 there). */
    data class Mark(val kind: String, val value: Double, val label: String, val row: Int) {
        val above: Boolean get() = kind != CAP
    }

    fun frac(v: Double): Float = (v / max).toFloat().coerceIn(0f, 1f)
    /** A label's width (share of the bar). */
    fun width(m: Mark): Double = widths[m.kind] ?: NEAR
    /** Where a label's middle goes (share of the bar): over its tick, but kept inside the bar at both ends. */
    fun centre(m: Mark): Double = centre(frac(m.value).toDouble(), width(m))

    companion object {
        const val ZERO = "zero"; const val MSRP = "msrp"; const val TCG = "tcg"; const val CAP = "cap"; const val NOW = "now"
        /** The width assumed for a label the screen did not measure (share of the bar). */
        const val NEAR = 0.27
        /** Room left between two labels on one row (share of the bar). */
        const val GAP = 0.02
        const val MAX_ROWS = 4                      // (four labels at most above the line: they never overlap)

        private fun m(v: Double) = "\$" + if (v >= 1000) String.format(java.util.Locale.US, "%,.0f", v) else String.format(java.util.Locale.US, "%.0f", v)

        /** 2.5 (owner: "the slider bar a gradient: dark green on the left (signifying a cheaper price) and dark red on the right
         *  (signifying a very expensive price) with a light gradient in between"): where the colors change, as shares of the bar -
         *  (light green, the light middle, light red): green up to the MSRP, light around it, red past the TCG price (or past your cap /
         *  the MSRP when the TCG price is not known). Always increasing, inside the bar. */
        const val SPREAD = 0.40
        fun gradient(max: Double, cap: Double, msrp: Double?, tcg: Double?): Triple<Double, Double, Double> {
            val f = { v: Double -> (v / max).coerceIn(0.0, 1.0) }
            val lo = msrp?.takeIf { it > 0 }?.let(f) ?: if (cap > 0) f(cap) * 0.85 else 0.45
            val hi0 = tcg?.takeIf { it > 0 }?.let(f) ?: maxOf(if (cap > 0) f(cap) else 0.0, lo * 1.25)
            // 2.8 (owner: "the switch from green to red should also be slightly more gradual"): green and red are at least 40% of the bar
            // apart (2.5-2.7: 10%) - widened evenly around their middle, kept inside the bar
            var a = lo.coerceIn(0.08, 0.80)
            var c = hi0.coerceIn(a + 0.02, 0.95)
            if (c - a < SPREAD) { val mid = ((a + c) / 2).coerceIn(0.08 + SPREAD / 2, 0.95 - SPREAD / 2); a = mid - SPREAD / 2; c = mid + SPREAD / 2 }
            return Triple(a, (a + c) / 2, c)
        }

        fun centre(f: Double, w: Double = NEAR): Double { val h = (w / 2).coerceAtMost(0.5); return f.coerceIn(h, 1 - h) }

        /** The labels' texts (the screen measures them before calling [of]). */
        fun labels(now: Double?, cap: Double, msrp: Double?, tcg: Double?): Map<String, String> = linkedMapOf<String, String>().apply {
            put(ZERO, "\$0")
            msrp?.takeIf { it.isFinite() && it > 0 }?.let { put(MSRP, "MSRP ${m(it)}") }
            tcg?.takeIf { it.isFinite() && it > 0 }?.let { put(TCG, "TCG ${m(it)}") }
            now?.takeIf { it.isFinite() && it >= 0 }?.let { put(NOW, "Now ${m(it)}") }
            cap.takeIf { it > 0 && it.isFinite() }?.let { put(CAP, "Cap ${m(it)}") }
        }

        /** [scale]: the bar's end, fixed (2.2: while the cap mark is dragged); null = 12 % past the highest value. [widths]: each label's
         *  measured width as a share of the bar (kind -> share); a label not in it counts as [NEAR]. */
        fun of(now: Double?, cap: Double, msrp: Double?, tcg: Double?, scale: Double? = null, widths: Map<String, Double> = emptyMap()): PriceMarks {
            val vals = listOfNotNull(now, cap, msrp, tcg).filter { it.isFinite() && it >= 0 }
            val max = scale?.takeIf { it.isFinite() && it > 0 } ?: ((vals.maxOrNull() ?: 1.0) * 1.12).coerceAtLeast(1.0)
            val txt = labels(now, cap, msrp, tcg)
            val vOf = mapOf(ZERO to 0.0, MSRP to (msrp ?: 0.0), TCG to (tcg ?: 0.0), NOW to (now ?: 0.0), CAP to cap)
            val top = txt.keys.filter { it != CAP }.map { Mark(it, vOf[it]!!, txt[it]!!, 0) }.sortedBy { it.value }
            fun w(k: String) = (widths[k] ?: NEAR).coerceIn(0.01, 1.0)
            // each label takes the lowest row where it clears the label before it on that row (its right edge + a small gap)
            val out = ArrayList<Mark>()
            val rightEdge = DoubleArray(MAX_ROWS) { -9.0 }
            for (x in top) {
                val wx = w(x.kind)
                val c = centre((x.value / max).coerceIn(0.0, 1.0), wx)
                val left = c - wx / 2
                val row = (0 until MAX_ROWS).firstOrNull { left >= rightEdge[it] + GAP - 1e-9 } ?: (0 until MAX_ROWS).minByOrNull { rightEdge[it] }!!
                rightEdge[row] = c + wx / 2
                out += x.copy(row = row)
            }
            val rows = (out.maxOfOrNull { it.row } ?: 0) + 1
            txt[CAP]?.let { out += Mark(CAP, cap, it, 0) }
            return PriceMarks(out, max, rows, widths)
        }
    }
}
