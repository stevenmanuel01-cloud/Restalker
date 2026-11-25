package com.restalker.mobile.core

/** 1.1.8: the buttons the PC sends with one of its messages (phonelink._app_buttons): at most 3; one that answers the PC carries a
 *  one-time ticket ("rk1 ..."), one that opens a page an https address. Anything else is left out. */
object PcButtons {
    data class B(val label: String, val ticket: String?, val url: String?)
    private val TICKET = Regex("^rk1 [A-Za-z0-9_-]{22,64}$")
    fun take(list: List<Map<String, Any?>>?): List<B> {
        val out = ArrayList<B>()
        for (a in list ?: emptyList()) {
            val l = (a["l"] as? String)?.trim()?.take(40) ?: continue
            if (l.isEmpty()) continue
            val t = (a["b"] as? String)?.trim(); val u = (a["u"] as? String)?.trim()
            if (t != null && TICKET.matches(t)) out += B(l, t, null)
            else if (u != null && u.startsWith("https://") && u.length <= 500 && !u.contains(' ')) out += B(l, null, u)
            if (out.size == 3) break
        }
        return out
    }
}
