package com.restalker.mobile.core

/** 2.9 (owner, 2026-11-18 8:09 AM: "The app will also need a way to connect the user to ntfy alerts. This code should be unique for each
 *  user, but identical to all devices used by the user if the devices are linked"). One alerts channel (an ntfy topic) per person: the
 *  linked PC's ("Phone: set up" on the PC) when it has one; otherwise the one a phone made - and every linked phone / tablet uses the same
 *  one (the smallest of theirs, so they all agree without asking). The channel name travels only inside the sealed link. Anyone with the
 *  ntfy app (any phone, tablet, PC) who subscribes to it gets the restock, purchase and "needs you" alerts. Pure: CoreTests. */
object NtfyAlerts {
    const val SERVER = "https://ntfy.sh"
    private val OK = Regex("^[A-Za-z0-9_-]{6,64}$")
    fun ok(t: String?): Boolean = t != null && OK.matches(t)

    /** A new channel name: "rka-" + 20 random letters and digits (not guessable). */
    fun make(rnd: java.util.Random = java.security.SecureRandom()): String = "rka-" + (1..20).map { "abcdefghijkmnpqrstuvwxyz23456789"[rnd.nextInt(32)] }.joinToString("")

    /** The person's one channel: the linked PC's, else the smallest of this phone's own and the other linked phones' (null = none yet). */
    fun channel(own: String, pc: String?, phones: List<String>): String? =
        pc?.takeIf { ok(it) } ?: (phones.filter { ok(it) } + listOfNotNull(own.takeIf { ok(it) })).minOrNull()

    /** Where to subscribe: the ntfy app's own link (opens it on this phone) and the web address (any device / QR). */
    fun appLink(t: String) = "ntfy://ntfy.sh/$t"
    fun webLink(t: String) = "$SERVER/$t"

    /** Should THIS phone send this alert to the channel? Not a restock the linked PC also alerts about (the PC sends its own). */
    fun shouldSend(on: Boolean, channel: String?, kind: String, pcHasChannel: Boolean): Boolean =
        on && ok(channel) && !((kind == "restock" || kind == "drop") && pcHasChannel)
}
