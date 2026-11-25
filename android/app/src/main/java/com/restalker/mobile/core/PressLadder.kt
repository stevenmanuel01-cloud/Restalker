package com.restalker.mobile.core

/**
 * 1.1.7 - the ladder (owner, 2026-11-25: "it should always start with the fastest option as the default, switching to slower methods after
 * being proven (not assumed) wrong, but then being able to automatically reset back to the fastest option (and not stuck on the slower ones
 * forever)"). WHEN Buy Now is pressed - the same rule as the PC's v159:
 *   1. the default is the fastest: as soon as the buy box has been read, while the page is still arriving ("early").
 *   2. only when pages without the product follow early presses (3 or more, after at least 1 press in 5) is the slower way - press when
 *      the page has been read - TRIED: 10 presses or 30 minutes, whichever comes first.
 *   3. the trial is judged by the numbers. Such pages follow the slower presses at most half as often -> proven: the slower way is kept
 *      for 30 minutes (after each further proof twice as long, at most 8 hours), then the fastest is tried again by itself. Not clearly
 *      better (or too few presses to tell) -> not proven: back to the fastest at once; no new trial for 2 hours (then 4 ... at most 24).
 *   4. 20 early presses with such a page after fewer than 1 in 10 -> everything is set back.
 * It decides nothing else. 1.1.6 pressed the slower way on a suspicion (EARLY_PRESS = false); that is turned round here.
 */
class PressLadder(private val now: () -> Long, saved: String? = null) {
    var mode = "early"; private set
    var stage = "default"; private set
    var until = 0L; private set
    var hold = 0L; private set
    var quiet = 0L; private set
    var quietUntil = 0L; private set
    var base = 0.0; private set
    var wPresses = 0; private set
    var wJunk = 0; private set
    var trials = 0; private set
    var proven = 0; private set
    var why = ""; private set
    var earlyPresses = 0; private set
    var earlyJunk = 0; private set
    var readPresses = 0; private set
    var readJunk = 0; private set
    private var lastAt = 0L
    private var lastEarly = true
    private var lastJunk = true

    init {
        MiniJson.obj(saved)?.let { m ->
            mode = (m["mode"] as? String)?.takeIf { it == "early" || it == "read" } ?: "early"
            stage = m["stage"] as? String ?: "default"
            until = (m["until"] as? Number)?.toLong() ?: 0; hold = (m["hold"] as? Number)?.toLong() ?: 0
            quiet = (m["quiet"] as? Number)?.toLong() ?: 0; quietUntil = (m["quietUntil"] as? Number)?.toLong() ?: 0
            base = (m["base"] as? Number)?.toDouble() ?: 0.0
            wPresses = (m["wP"] as? Number)?.toInt() ?: 0; wJunk = (m["wJ"] as? Number)?.toInt() ?: 0
            trials = (m["trials"] as? Number)?.toInt() ?: 0; proven = (m["proven"] as? Number)?.toInt() ?: 0
            why = m["why"] as? String ?: ""
            earlyPresses = (m["eP"] as? Number)?.toInt() ?: 0; earlyJunk = (m["eJ"] as? Number)?.toInt() ?: 0
            readPresses = (m["rP"] as? Number)?.toInt() ?: 0; readJunk = (m["rJ"] as? Number)?.toInt() ?: 0
        }
    }

    fun save(): String = MiniJson.write(linkedMapOf("mode" to mode, "stage" to stage, "until" to until, "hold" to hold, "quiet" to quiet, "quietUntil" to quietUntil,
        "base" to base, "wP" to wPresses, "wJ" to wJunk, "trials" to trials, "proven" to proven, "why" to why, "eP" to earlyPresses, "eJ" to earlyJunk,
        "rP" to readPresses, "rJ" to readJunk))

    /** May Buy Now be pressed while the page is still arriving? (the fastest way) */
    fun early(): Boolean { judge(); return mode == "early" }

    /** Buy Now was pressed; `early` = while the page was still arriving. */
    fun press(early: Boolean) {
        if (early) earlyPresses++ else readPresses++
        if ((if (early) "early" else "read") == mode) wPresses++
        lastAt = now(); lastEarly = early; lastJunk = false
        judge()
    }

    /** Amazon sent a page without the product. Counted once for the press before it (within 2 minutes). */
    fun junk() {
        if (lastJunk || now() - lastAt > 120_000L) return
        lastJunk = true
        if (lastEarly) earlyJunk++ else readJunk++
        if ((if (lastEarly) "early" else "read") == mode) wJunk++
        judge()
    }

    private fun to(m: String, s: String, w: String, ms: Long) { mode = m; stage = s; until = if (ms > 0) now() + ms else 0; wPresses = 0; wJunk = 0; why = w }
    private fun pct(x: Double) = "${Math.round(x * 100)} %"

    fun judge() {
        val t = now(); val sh = if (wPresses > 0) wJunk.toDouble() / wPresses else 0.0
        if (mode == "early") {
            if (stage != "default" && wPresses >= CLEAN && wJunk < 0.1 * wPresses) { hold = 0; quiet = 0; quietUntil = 0; stage = "default"; wPresses = 0; wJunk = 0; why = ""; return }
            if (wJunk >= JUNK && wJunk >= SHARE * wPresses && t >= quietUntil) {
                base = sh; trials++
                to("read", "trial", "pages without the product followed $wJunk of $wPresses early presses - pressing after the page was read is being tried ($TRIAL presses or 30 minutes), then judged by the numbers", TRIAL_MS)
            } else if (wPresses >= 40) { wPresses = 20; wJunk /= 2 }
            return
        }
        if (stage == "trial" && (wPresses >= TRIAL || t >= until)) {
            if (wPresses >= MIN && sh <= 0.5 * base) {
                proven++; hold = if (hold > 0) minOf(HOLD_MAX, hold * 2) else HOLD
                to("read", "hold", "proven: such pages followed ${pct(sh)} of the presses made after reading (${pct(base)} of the early ones) - the slower way is kept for ${hold / 60_000} minutes, then the fastest is tried again by itself", hold)
            } else {
                quiet = if (quiet > 0) minOf(QUIET_MAX, quiet * 2) else QUIET; quietUntil = t + quiet
                val w = if (wPresses < MIN) "not proven (only $wPresses press${if (wPresses == 1) "" else "es"} in the trial)" else "not proven: such pages followed ${pct(sh)} of the presses made after reading too (${pct(base)} of the early ones)"
                to("early", "cleared", "$w - back to the fastest way; the slower one is not tried again for ${quiet / 3_600_000} hours", 0)
            }
            return
        }
        if (stage == "hold" && t >= until) to("early", "retry", "the slower way was kept for its time - the fastest way is tried again", 0)
    }

    /** One line for the diagnostics. */
    fun text(): String = "Buy Now is pressed " + (if (mode == "early") "as soon as the buy box is read (the fastest way)" else "when the page has been read (the slower way)") +
        " - " + when (stage) { "default" -> "the default"; "trial" -> "the slower way is being tried"; "hold" -> "the slower way was proven better and is kept about ${maxOf(0, (until - now()) / 60_000)} more minutes"
            "cleared" -> "the slower way was tried and not proven better"; "retry" -> "the fastest way is being tried again"; else -> stage } +
        (if (why.isNotBlank()) " ($why)" else "") + ". Early presses $earlyPresses, followed by a page without the product $earlyJunk; presses after reading $readPresses, followed by such a page $readJunk; trials $trials, proven $proven."

    companion object {
        const val JUNK = 3; const val SHARE = 0.2; const val TRIAL = 10; const val TRIAL_MS = 30 * 60_000L; const val MIN = 5
        const val HOLD = 30 * 60_000L; const val HOLD_MAX = 8 * 3_600_000L; const val QUIET = 2 * 3_600_000L; const val QUIET_MAX = 24 * 3_600_000L; const val CLEAN = 20
    }
}
