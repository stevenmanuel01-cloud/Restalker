package com.restalker.mobile.core

/** 2.0: the ticks of the product screen's price bar - $0, MSRP, TCG, the price now and your cap (pure: tested in CoreTests).
 *  2.3 (owner: "place all static pricing on top of the line and leave the dynamic cap slider on the bottom. Currently the text overlaps"):
 *  $0, MSRP, TCG and Now are labelled ABOVE the line, on up to four rows so no two labels overlap; the cap (the slider) is the only
 *  label BELOW the line. Fractions are of the bar's width; the bar ends 12 % past the highest value (or at a fixed [scale]). */
class PriceMarks private constructor(val items: List<Mark>, private val max: Double, val rows: Int) {
    /** [row]: 0 = the row just above the line, 1 and 2 higher up; the cap is always below the line (row 0 there). */
    data class Mark(val kind: String, val value: Double, val label: String, val row: Int) {
        val above: Boolean get() = kind != CAP
    }

    fun frac(v: Double): Float = (v / max).toFloat().coerceIn(0f, 1f)

    companion object {
        const val ZERO = "zero"; const val MSRP = "msrp"; const val TCG = "tcg"; const val CAP = "cap"; const val NOW = "now"
        /** A label's width as a share of the bar (labels whose centres are closer than this go on different rows). */
        const val NEAR = 0.27
        const val MAX_ROWS = 4                      // (four labels at most above the line: they never overlap)

        private fun m(v: Double) = "\$" + if (v >= 1000) String.format(java.util.Locale.US, "%,.0f", v) else String.format(java.util.Locale.US, "%.0f", v)

        /** Where a label's centre ends up: kept inside the bar at both ends (the screen does the same). */
        fun centre(f: Double): Double = f.coerceIn(NEAR / 2, 1 - NEAR / 2)

        /** [scale]: the bar's end, fixed (2.2: while the cap mark is dragged); null = 12 % past the highest value. */
        fun of(now: Double?, cap: Double, msrp: Double?, tcg: Double?, scale: Double? = null): PriceMarks {
            val vals = listOfNotNull(now, cap, msrp, tcg).filter { it.isFinite() && it >= 0 }
            val max = scale?.takeIf { it.isFinite() && it > 0 } ?: ((vals.maxOrNull() ?: 1.0) * 1.12).coerceAtLeast(1.0)
            val top = listOfNotNull(
                Mark(ZERO, 0.0, "\$0", 0),
                msrp?.takeIf { it.isFinite() && it > 0 }?.let { Mark(MSRP, it, "MSRP ${m(it)}", 0) },
                tcg?.takeIf { it.isFinite() && it > 0 }?.let { Mark(TCG, it, "TCG ${m(it)}", 0) },
                now?.takeIf { it.isFinite() && it >= 0 }?.let { Mark(NOW, it, "Now ${m(it)}", 0) },
            ).sortedBy { it.value }
            // each label takes the lowest row where it does not touch the label before it on that row
            val out = ArrayList<Mark>()
            val last = DoubleArray(MAX_ROWS) { -9.0 }
            for (x in top) {
                val c = centre(x.value / max)
                val row = (0 until MAX_ROWS).firstOrNull { c - last[it] >= NEAR - 1e-9 } ?: (0 until MAX_ROWS).minByOrNull { last[it] }!!
                last[row] = c
                out += x.copy(row = row)
            }
            val rows = (out.maxOfOrNull { it.row } ?: 0) + 1
            cap.takeIf { it > 0 && it.isFinite() }?.let { out += Mark(CAP, it, "Cap ${m(it)}", 0) }
            return PriceMarks(out, max, rows)
        }
    }
}
