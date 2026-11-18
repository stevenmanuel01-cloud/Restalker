package com.restalker.mobile

import com.restalker.mobile.core.Link
import com.restalker.mobile.core.LinkNet

/** 1.4: the link with the desktop (core/Link.kt) on the phone: the real channel (HTTPS to ntfy.sh) and the rounds - every minute while
 *  watching runs, and when the app is opened. */
object DeskLink {
    private val net = object : LinkNet {
        override fun post(url: String, body: String, asFile: Boolean): Int {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.requestMethod = if (asFile) "PUT" else "POST"; c.doOutput = true; c.connectTimeout = 20_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "RestalkerMobile")
            if (asFile) c.setRequestProperty("Filename", "rk.txt") else c.setRequestProperty("Title", "rk")
            val b = body.toByteArray(); c.setFixedLengthStreamingMode(b.size)
            c.outputStream.use { it.write(b) }
            val code = c.responseCode
            try { (if (code < 400) c.inputStream else c.errorStream)?.close() } catch (e: Exception) {}
            c.disconnect()
            return code
        }
        override fun get(url: String): String {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 30_000; c.setRequestProperty("User-Agent", "RestalkerMobile")
            try { return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        }
    }

    @Volatile private var link: Link? = null
    @Synchronized fun get(): Link = link ?: Link(RkApp.store(), net, me = {
        val st = RkApp.store().settings()
        // 2.3: amz = this device may buy NOW (its own choice: "pc" only when the PC stopped and it takes over); nm = its name for the others
        val l = link
        val may = st.buying && st.watching && (st.buyMode != "pc" || (st.failover && l != null && !l.desktopBuys() && !l.pcHanded()))
        mapOf("amz" to may, "av" to BuildConfig.VERSION_CODE.toLong(), "au" to (Updater.mode(RkApp.app) == "silent"), "um" to Updater.mode(RkApp.app), "nt" to (alertChannel() ?: ""), "nm" to name(), "vn" to com.restalker.mobile.core.APP_VERSION)
    }).also { link = it }

    /** 2.3: this device only stands by - another phone / tablet is first in line, or (its choice "pc") the PC buys, handed Amazon to
     *  another device, or this one does not take over. Not linked: never. */
    fun standsBy(): Boolean = try {
        val l = get(); val st = RkApp.store().settings()
        l.linked() && (l.otherPhoneBuys() || (st.buyMode == "pc" && (!st.failover || l.desktopBuys() || l.pcHanded())))
    } catch (e: Exception) { false }

    /** 2.3: this device's name for the other linked devices (Settings; empty = the phone's maker and model). */
    fun name(): String = RkApp.store().settings().deviceName.ifBlank {
        (android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + android.os.Build.MODEL).trim().take(40) }

    /** 2.7: this device is the owner's (no purchase limit) - its report code, the master code typed here, or the owner's linked PC. */
    fun master(): Boolean = try { com.restalker.mobile.core.Limit.isMaster(RkApp.store().settings(), get().pcIsMaster()) } catch (e: Exception) { false }
    /** 2.7: a product's purchases on every linked device (this one, the PC, the other phones / tablets). */
    fun boughtOf(p: com.restalker.mobile.core.Product): Int = try { com.restalker.mobile.core.Buys.of(get().buys(name()), p.asin, p.label).size } catch (e: Exception) { 0 }

    /** 2.9: the person's one ntfy alerts channel (the linked PC's, else the same one on every linked phone) - null when none yet. */
    fun alertChannel(): String? = try {
        val (pc, ph) = if (get().linked()) get().alertChannels() else (null to emptyList())
        com.restalker.mobile.core.NtfyAlerts.channel(RkApp.store().settings().alertTopic, pc, ph)
    } catch (e: Exception) { null }
    /** 2.9: the linked PC has its own alerts channel (it sends its own restock alerts there). */
    fun pcHasChannel(): Boolean = try { get().linked() && com.restalker.mobile.core.NtfyAlerts.ok(get().alertChannels().first) } catch (e: Exception) { false }

    /** Tests: a fresh link object (after the store was reset). */
    @Synchronized fun reset() { link = null }

    @Volatile var lastResult: String = ""; private set
    /** 1.7: rounds in a row that failed (the self-check reports 10 in a row). */
    @Volatile var failsInRow = 0; private set
    @Volatile private var running = false

    /** One round in the background (skipped when one is running). [done] on the main thread. */
    fun cycleAsync(done: ((String) -> Unit)? = null) {
        if (running) return
        running = true
        Thread({
            val r = try { get().cycle() } catch (e: Exception) { "failed: ${e.message?.take(80)}" } finally { running = false }
            lastResult = r
            failsInRow = if (r == "ok" || r == "not linked") 0 else failsInRow + 1
            if (done != null) android.os.Handler(android.os.Looper.getMainLooper()).post { done(r) }
        }, "desk-link").start()
    }
}
