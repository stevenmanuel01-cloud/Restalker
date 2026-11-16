package com.restalker.mobile.core

import java.security.SecureRandom

/** Problem reports to Claude: the app's diagnostics, sent as a file to this phone's own private ntfy channel ("rkr-" + 20 random
 *  letters, made once). Claude reads it with the report code. No password, card number or address is ever in it (the app never has them). */
object Report {
    const val SERVER = "https://ntfy.sh"
    const val AUTO_GAP = 30 * 60_000L
    private const val ABC = "abcdefghijkmnpqrstuvwxyz23456789"

    fun newTopic(rnd: SecureRandom = SecureRandom()): String = "rkr-" + (1..20).map { ABC[rnd.nextInt(ABC.length)] }.joinToString("")
    fun valid(topic: String) = Regex("^rkr-[a-z0-9]{20}$").matches(topic)

    /** The report code you send Claude (the channel's name). Made the first time it is needed. */
    fun code(store: Store): String {
        val t = store.settings().reportTopic
        if (valid(t)) return t
        val n = newTopic()
        store.setSettings { it.copy(reportTopic = n) }
        return n
    }

    /** Sends [text] as a file. [put] = (url, headers, body) -> HTTP status (the Android side / the tests give it). true = delivered. */
    fun send(store: Store, title: String, text: String, why: String, put: (String, Map<String, String>, ByteArray) -> Int): Boolean {
        val topic = code(store)
        val body = text.toByteArray(Charsets.UTF_8)
        val st = try {
            put("$SERVER/$topic", mapOf("Title" to title.take(120).replace(Regex("[\\r\\n]"), " "), "Filename" to "restalker-mobile-report.txt",
                "Tags" to "restalker-report", "Message" to why.take(300).replace(Regex("[\\r\\n]"), " ")), body)
        } catch (e: Exception) { -1 }
        val ok = st in 200..299
        store.log(if (ok) "Report sent to Claude (code $topic): $why" else "! Report could not be sent (no internet?) - try again.")
        if (ok) store.setSettings { it.copy(reportAt = System.currentTimeMillis()) }
        return ok
    }

    /** A report by itself after a problem - only when switched on, and at most one every 30 minutes. */
    fun autoDue(s: Settings, now: Long): Boolean = s.autoReport && now - s.reportAt > AUTO_GAP
}
