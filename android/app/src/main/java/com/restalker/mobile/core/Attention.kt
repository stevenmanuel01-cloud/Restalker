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
    private val REPORT = Regex("Report sent|report could not be sent|Report could not be sent", RegexOption.IGNORE_CASE)
    private val DROP = listOf(Regex("^ {2}"), Regex("switched (ON|OFF)"), Regex(": cap \\$"), Regex(": (Buy|Watch) (ON|OFF)"))

    fun important(line: String): Boolean {
        if (line.contains("  " + Store.QUIET)) return false
        if (REPORT.containsMatchIn(line)) return false             // 1.0.2: problem reports are not events (and their code is never shown)
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

    // ---- 1.0.2 (owner, 2026-11-19: "each notification should have a basic one line of what happened. Second, notifications should only
    // be given once for each event ... It should show a restock happened, what action was taken, and the ultimate result in the same
    // notification. Keep these updates simple"): one entry per event - a restock, the checkout tries and how it ended together.
    /** [tone]: 1 good (bought), -1 needs a look (missed, not bought, unclear, a problem), 0 news. */
    data class Event(val day: String, val time: String, val product: String, val text: String, val tone: Int)

    private class Ep(val day: String, val time: String, val product: String, var lastMin: Int) {
        var price: String? = null; var spotted = false; var action: String? = null
        var tries = 0; var practice = false; var result: String? = null; var tone = 0; var closed = false
        fun text(): String {
            val parts = ArrayList<String>()
            if (price != null || action != null || tries == 0) parts += "In stock" + (price?.let { " $it" } ?: "") + if (spotted) " (another Restalker spotted it)" else ""
            if (tries > 0) parts += (if (practice) "practice checkout" else "checkout") + if (tries > 1) " ($tries tries)" else ""
            else action?.let { parts += it }
            result?.let { parts += it }
            return parts.joinToString(" → ").replaceFirstChar { it.uppercase() }
        }
    }

    private fun minutes(day: String, time: String): Int = try {
        (day.substring(0, 2).toInt() * 31 + day.substring(3, 5).toInt()) * 1440 + time.substring(0, 2).toInt() * 60 + time.substring(3, 5).toInt()
    } catch (e: Exception) { 0 }

    private val HEAD = Regex("^(.+?): (?=in stock|CHECKOUT|PRACTICE|[Pp]ractice checkout|BOUGHT|not bought|stopped - |order unclear|price drop|" +
        "Place your order|at Place your order|the PC|another of your devices|\\d+ of \\d+ bought|all \\d+ bought)")
    private val SOLD_OUT = Regex("sold out|no longer available|unavailable|out of stock", RegexOption.IGNORE_CASE)
    private val PRICE = Regex("\\$[0-9][0-9,]*(\\.[0-9]{2})?")
    /** A reason, short: up to its first bracket / dash / full stop (at most 70 letters). */
    private fun short(why: String): String = why.split(" (", " - ", ". ", " — ").first().trim().trimEnd('.').let { if (it.length > 70) it.take(68) + "…" else it }

    /** The important lines (oldest first, as kept) -> events, newest first. */
    fun events(lines: List<String>): List<Event> {
        val out = ArrayList<Pair<Int, Event>>()                  // (start minute, event) - an episode is written when it closes
        val open = LinkedHashMap<String, Ep>()
        var n = 0
        fun close(e: Ep) { if (!e.closed) { e.closed = true; out += minutes(e.day, e.time) * 10000 + (n++ % 10000) to Event(e.day, e.time, e.product, e.text(), e.tone) } }
        for (line in lines) {
            val day = if (line.length >= 5 && line[2] == '-') line.substring(0, 5) else "?"
            val (time, text0) = split(line)
            val warn = text0.startsWith("! ")
            val text = text0.removePrefix("! ")
            val now = minutes(day, time)
            for (e in open.values.toList()) if (now - e.lastMin > 30) { close(e); open.remove(e.product) }
            val m = HEAD.find(text)                               // (a name may itself hold ": " - "Magic: The Gathering | ...")
            val product = m?.groupValues?.get(1) ?: ""
            val rest = if (m != null) text.substring(m.range.last + 1) else text
            fun ep(): Ep = open[product]?.takeIf { !it.closed } ?: Ep(day, time, product, now).also { open[product] = it }
            when {
                product.isNotEmpty() && rest.startsWith("in stock") -> {
                    open[product]?.let { if (it.result != null || it.tries > 0) { close(it); open.remove(product) } }
                    val e = ep(); e.lastMin = now
                    e.price = e.price ?: PRICE.find(rest)?.value
                    if (rest.contains("another Restalker spotted it first")) e.spotted = true
                    val act = rest.substringAfter(" - ", "").trimEnd('.')
                    e.action = when {
                        act.isEmpty() || act.startsWith("buying it now") -> e.action
                        act.contains("message") -> "you were sent a message"
                        act.contains("stands by") || act.contains("buys") || act.contains("Not buying here") -> "another of your devices buys it"
                        else -> short(act)
                    }
                }
                product.isNotEmpty() && (rest.startsWith("CHECKOUT started") || rest.startsWith("PRACTICE checkout started") || rest.startsWith("practice checkout started")) -> {
                    val e = ep(); e.lastMin = now; e.tries++
                    if (!rest.startsWith("CHECKOUT")) e.practice = true
                    if (e.price == null && e.tries == 1 && !e.practice) e.price = PRICE.find(rest)?.value
                    e.result = null; e.tone = 0
                }
                product.isNotEmpty() && rest.startsWith("BOUGHT") -> {
                    val e = ep(); e.lastMin = now
                    e.result = "bought" + (PRICE.find(rest)?.let { " at ${it.value}" } ?: "") + (Regex("order ([0-9-]+)").find(rest)?.let { " · order ${it.groupValues[1]}" } ?: "")
                    e.tone = 1
                }
                product.isNotEmpty() && Regex("^(\\d+ of \\d+ bought|all \\d+ bought)").containsMatchIn(rest) -> { open[product]?.let { it.result = (it.result ?: "bought") + " · " + rest.trimEnd('.'); it.lastMin = now } }
                product.isNotEmpty() && (rest.startsWith("not bought - ") || rest.startsWith("stopped - ")) -> {
                    val e = ep(); e.lastMin = now
                    val why = rest.substringAfter(" - ")
                    e.result = when {
                        SOLD_OUT.containsMatchIn(why) -> "missed (sold out)"
                        rest.startsWith("stopped") -> "stopped (" + short(why) + ")"
                        else -> "not bought (" + short(why) + ")"
                    }
                    if (why.contains("stopped trying")) e.result += " · paused for a while"
                    e.tone = -1
                }
                product.isNotEmpty() && rest.startsWith("order unclear") -> { val e = ep(); e.lastMin = now; e.result = "order unclear - check your Amazon orders"; e.tone = -1; close(e); open.remove(product) }
                product.isNotEmpty() && rest.startsWith("Practice checkout reached") -> { val e = ep(); e.lastMin = now; e.practice = true; if (e.tries == 0) e.tries = 1; e.result = "reached Place your order (nothing bought)"; close(e); open.remove(product) }
                product.isNotEmpty() && rest.contains("got to Place your order first") -> { val e = ep(); e.lastMin = now; e.result = "another of your devices ordered it"; close(e); open.remove(product) }
                product.isNotEmpty() && rest.contains("PC bought it") -> { val e = ep(); e.lastMin = now; e.result = "your PC bought it"; e.tone = 1; close(e); open.remove(product) }
                product.isNotEmpty() && (rest.startsWith("Place your order allowed") || rest.startsWith("at Place your order") || rest.startsWith("the PC")) -> { open[product]?.lastMin = now }
                product.isNotEmpty() && rest.startsWith("price drop") -> out += now * 10000 + (n++ % 10000) to Event(day, time, product, rest.trimEnd('.').replaceFirstChar { it.uppercase() }, 0)
                else -> out += now * 10000 + (n++ % 10000) to Event(day, time, product, rest.trimEnd('.').replaceFirstChar { it.uppercase() }, if (warn) -1 else 0)
            }
        }
        for (e in open.values) close(e)
        return out.sortedByDescending { it.first }.map { it.second }
    }

    /** Events -> days, newest first. */
    fun eventsByDay(ev: List<Event>): List<Pair<String, List<Event>>> = ev.groupBy { it.day }.toList()
}
