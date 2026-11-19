package com.restalker.mobile.core

/** 2.15 (owner, 2026-11-19: "anything that requires user input should be flagged with a small red triangle/exclamation point ... for
 *  things that had errors, or for things that are not connects, or for things missing sign ins. Once clicked, the icon should go away.
 *  Icons should refresh for anything left outstanding (sign in links most likely) each day unless the user manually disables the
 *  option"): which of the things that need you show their red mark now. Pure: CoreTests. */
object Attention {
    /** [key]: what it is (e.g. "signin", "product:B0..."); [where]: where its mark shows ("amazon", "notes", "phone", "watch"). */
    data class Item(val key: String, val where: String, val text: String)

    fun day(now: Long): String = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

    /** The items whose mark shows: never tapped, or tapped on an earlier day while "remind me daily" is on. */
    fun showing(items: List<Item>, seen: Map<String, String>, daily: Boolean, today: String): List<Item> =
        items.filter { val d = seen[it.key]; d == null || (daily && d < today) }

    /** After tapping a part: its items are marked seen today (old entries of things no longer outstanding are dropped). */
    fun tapped(seen: Map<String, String>, items: List<Item>, where: String, today: String): Map<String, String> {
        val keys = items.map { it.key }.toSet()
        return seen.filterKeys { it in keys } + items.filter { it.where == where }.associate { it.key to today }
    }
}

/** 2.15 (owner: "the activity log is still showing too much information. This should only be displaying checkout activities, instock
 *  alerts, etc. Not information about changing things to watch or buy, or communications with the desktop" - and "a navy bar that
 *  displays the date, with all activity then separated by date"). Pure: CoreTests. */
object ActivityLog {
    private val KEEP = listOf(
        Regex(": in stock"), Regex("CHECKOUT"), Regex("BOUGHT"), Regex("\\bbought\\b.*(order|of \\d)"), Regex("not bought"), Regex(": stopped - "),
        Regex("PRACTICE"), Regex("[Pp]ractice checkout"), Regex("price drop"), Regex("Stopped trying"), Regex("order unclear"),
        Regex("not ordered here"), Regex("Place your order allowed"), Regex("^Updated to v"), Regex("^! "))
    private val DROP = listOf(Regex("^ {2}"), Regex("switched (ON|OFF)"), Regex(": cap \\$"), Regex(": (Buy|Watch) (ON|OFF)"))

    fun important(line: String): Boolean {
        if (line.contains("  " + Store.QUIET)) return false
        val t = if (line.length > 16 && line[2] == '-' && line[5] == ' ') line.substring(16) else line
        if (DROP.any { it.containsMatchIn(t) }) return false
        return KEEP.any { it.containsMatchIn(t) }
    }

    /** Lines (oldest first, as kept) -> days, newest first: (day "MM-dd", its lines newest first). */
    fun byDay(lines: List<String>): List<Pair<String, List<String>>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (l in lines.asReversed()) {
            val d = if (l.length >= 5 && l[2] == '-') l.substring(0, 5) else "?"
            out.getOrPut(d) { ArrayList() }.add(l)
        }
        return out.map { it.key to it.value }
    }

    /** "11-18" -> "Wednesday, November 18" (this year, or last year for a later month). */
    fun dayLabel(md: String, now: Long): String = try {
        val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val m = md.substring(0, 2).toInt(); val d = md.substring(3, 5).toInt()
        var date = java.time.LocalDate.of(today.year, m, d)
        if (date.isAfter(today.plusDays(1))) date = date.minusYears(1)
        when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US) + ", " +
                date.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US) + " " + d
        }
    } catch (e: Exception) { md }

    /** The time part of a line and its text: ("17:22", "Nauctis ...: in stock at $89.99 ..."). */
    fun split(line: String): Pair<String, String> =
        if (line.length > 16 && line[2] == '-' && line[5] == ' ') line.substring(6, 11) to line.substring(16).trimStart() else "" to line
}
