package com.restalker.mobile

import com.restalker.mobile.core.NtfyAlerts

/** 2.9: this phone's alerts to the person's ntfy channel (core/NtfyAlerts.kt says which channel and when). Off the main thread; a failure
 *  is only noted (the phone's own notification was shown anyway). */
object NtfyOut {
    @Volatile var sent = 0; private set
    @Volatile var lastError = ""; private set

    /** [kind]: "restock", "bought", "need", "test". [click]: the address opened when the alert is tapped. */
    fun send(kind: String, title: String, text: String, click: String? = null, force: Boolean = false) {
        val st = RkApp.store().settings()
        val ch = DeskLink.alertChannel()
        if (!force && !NtfyAlerts.shouldSend(st.alertsOn, ch, kind, DeskLink.pcHasChannel())) return
        if (!NtfyAlerts.ok(ch)) return
        Thread({
            try {
                val c = java.net.URL(NtfyAlerts.webLink(ch!!)).openConnection() as java.net.HttpURLConnection
                try {
                    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 20_000
                    c.setRequestProperty("User-Agent", "RestalkerMobile")
                    // (ntfy headers must be plain ASCII: the title is also in the message, so nothing is lost)
                    c.setRequestProperty("Title", title.replace(Regex("[^\\x20-\\x7E]"), "?").take(200))
                    c.setRequestProperty("Priority", when (kind) { "restock", "bought" -> "5"; "need" -> "4"; else -> "3" })
                    c.setRequestProperty("Tags", when (kind) { "restock" -> "rotating_light"; "bought" -> "tada"; "need" -> "warning"; else -> "white_check_mark" })
                    if (click != null && click.startsWith("https://")) c.setRequestProperty("Click", click)
                    val b = text.take(3000).toByteArray()
                    c.setFixedLengthStreamingMode(b.size); c.outputStream.use { it.write(b) }
                    if (c.responseCode !in 200..299) throw java.io.IOException("ntfy answered ${c.responseCode}")
                    sent++; lastError = ""
                } finally { c.disconnect() }
            } catch (e: Exception) { lastError = (e.message ?: e.javaClass.simpleName).take(100) }
        }, "ntfy-out").start()
    }

    fun status(): String = "ntfy alerts (2.9): ${if (RkApp.store().settings().alertsOn) "on" else "off"}; channel ${if (DeskLink.alertChannel() != null) "set" else "none"}" +
        (if (DeskLink.pcHasChannel()) " (the linked PC's)" else "") + "; sent $sent" + (if (lastError.isNotEmpty()) "; last problem: $lastError" else "")
}
