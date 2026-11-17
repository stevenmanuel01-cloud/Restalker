package com.restalker.mobile.core

/** 2.0: the ticks of the product screen's price bar - $0, MSRP, TCG, your cap and the price now - and which labels go on a second row
 *  so that none overlap (pure: tested in CoreTests). Fractions are of the bar's width; the bar ends 12 % past the highest value. */
class PriceMarks private constructor(val items: List<Mark>, private val max: Double) {
    data class Mark(val kind: String, val value: Double, val label: String, val row: Int)

    fun frac(v: Double): Float = (v / max).toFloat().coerceIn(0f, 1f)

    companion object {
        const val ZERO = "zero"; const val MSRP = "msrp"; const val TCG = "tcg"; const val CAP = "cap"; const val NOW = "now"
        /** Labels closer than this (share of the width) go on two rows. */
        const val NEAR = 0.2

        private fun m(v: Double) = "\$" + if (v >= 1000) String.format(java.util.Locale.US, "%,.0f", v) else String.format(java.util.Locale.US, "%.0f", v)

        fun of(now: Double?, cap: Double, msrp: Double?, tcg: Double?): PriceMarks {
            val vals = listOfNotNull(now, cap, msrp, tcg).filter { it.isFinite() && it >= 0 }
            val max = ((vals.maxOrNull() ?: 1.0) * 1.12).coerceAtLeast(1.0)
            val below = listOfNotNull(
                Mark(ZERO, 0.0, "\$0", 0),
                msrp?.takeIf { it.isFinite() && it > 0 }?.let { Mark(MSRP, it, "MSRP ${m(it)}", 0) },
                tcg?.takeIf { it.isFinite() && it > 0 }?.let { Mark(TCG, it, "TCG ${m(it)}", 0) },
                Mark(CAP, cap, "Cap ${m(cap)}", 0),
            ).sortedBy { it.value }
            // two rows: a label too near the last one on row 0 goes to row 1 (and back when row 1 is taken there too)
            val out = ArrayList<Mark>()
            val lastX = doubleArrayOf(-1.0, -1.0)
            for (x in below) {
                val f = x.value / max
                val row = if (f - lastX[0] >= NEAR || lastX[0] < 0) 0 else if (f - lastX[1] >= NEAR || lastX[1] < 0) 1 else 0
                lastX[row] = f
                out += x.copy(row = row)
            }
            now?.takeIf { it.isFinite() && it >= 0 }?.let { out += Mark(NOW, it, "Now ${m(it)}", 0) }
            return PriceMarks(out, max)
        }
    }
}
