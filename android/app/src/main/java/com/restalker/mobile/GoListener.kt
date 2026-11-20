package com.restalker.mobile

import com.restalker.mobile.core.Engine
import com.restalker.mobile.core.LinkCrypto
import com.restalker.mobile.core.MiniJson
import com.restalker.mobile.core.asMap
import com.restalker.mobile.core.asStr
import java.util.concurrent.ConcurrentHashMap

/** 2.2 (owner: "buy only on app (so the desktop is doing the searching work but pushing purchasing to the app for user input/review) or
 *  both"): the fast channel with the linked PC (<link channel>-go, sealed with the link's secret, the PC's phonelink.py v132).
 *  While Who buys is "phone" or "both" (and watching + buying are on) ONE quiet connection stays open to it, like the ntfy app does:
 *   - the PC's {"go": {"a": asin}} -> this phone looks at that product at once (it buys only if IT sees it in stock under the cap);
 *   - the PC's {"res": {"a": asin, "k": started|bought|failed|busy}} -> kept for the engine (Who buys = both: Place your order waits for it);
 *   - every 5 minutes this phone says it is listening ({"hb": 1}); without that for 20 minutes the PC buys again by itself. */
object GoListener {
    private val verdicts = ConcurrentHashMap<String, Pair<String, Long>>()
    @Volatile private var thread: Thread? = null
    @Volatile var state = "off"; private set
    @Volatile var heard = 0; private set
    @Volatile private var conn: java.net.HttpURLConnection? = null
    private const val HB_MS = 5 * 60_000L

    /** The PC's latest news about a product (Engine.pcVerdict). */
    fun verdict(asin: String): Pair<String, Long>? = verdicts[asin]

    /** 1.0.3 (2026-11-20: ntfy.sh allows each home internet address 250 messages a day for ALL its Restalker devices together; this
     *  heartbeat, every 5 minutes, was 288 a day per phone - the home's 250 were gone by morning, then the PC's link, its reports and
     *  the phone messages were refused for the rest of the day): only a phone that buys INSTEAD of the PC says it is listening - the PC
     *  stands by only for that one. "Both buy" needs no heartbeat (the shared checkout lock decides; the PC sees the phone by its hourly
     *  link post - PC v144). The connection itself stays open as before (reading costs no messages). */
    private fun hbWanted(): Boolean = try { RkApp.store().settings().buyMode == "phone" } catch (e: Exception) { true }

    private fun wanted(): Boolean {
        val st = RkApp.store().settings()
        // (1.0.4: outside this device's watch hours it does not listen - the PC buys again by itself)
        return st.watching && st.buying && st.buyMode != "pc" && DeskLink.get().linked() && com.restalker.mobile.core.WatchHours.inside(st, System.currentTimeMillis())
    }

    /** WatchService: called every minute - starts / stops the connection as the settings say. */
    @Synchronized fun check() {
        val on = try { wanted() } catch (e: Exception) { false }
        val t = thread
        if (on && (t == null || !t.isAlive)) {
            thread = Thread({ loop() }, "link-go").also { it.isDaemon = true; it.start() }
        } else if (!on && t != null) {
            thread = null
            try { conn?.disconnect() } catch (e: Exception) { }
            t.interrupt()
            state = "off"
        }
    }

    @Synchronized fun stop() { val t = thread; thread = null; try { conn?.disconnect() } catch (e: Exception) { }; t?.interrupt(); state = "off" }

    private fun loop() {
        var wait = 5_000L
        var lastHb = 0L
        var since = (System.currentTimeMillis() / 1000 - 60).toString()
        while (thread === Thread.currentThread()) {
            val tgt = DeskLink.get().goTarget()
            if (tgt == null || !wanted()) { state = "off"; return }
            val (ch, sec, dev) = tgt
            try {
                if (hbWanted() && System.currentTimeMillis() - lastHb > HB_MS) { post(ch, sec, dev); lastHb = System.currentTimeMillis() }
                val c = java.net.URL("${com.restalker.mobile.core.Link.SERVER}/$ch/json?since=$since").openConnection() as java.net.HttpURLConnection
                conn = c
                c.connectTimeout = 20_000; c.readTimeout = 100_000                 // (ntfy sends a keepalive every 45 s)
                c.setRequestProperty("User-Agent", "RestalkerMobile")
                state = "listening"
                c.inputStream.bufferedReader().use { r ->
                    while (thread === Thread.currentThread()) {
                        val line = r.readLine() ?: break
                        val m = MiniJson.obj(line) ?: continue
                        m["id"].asStr()?.let { since = it }
                        if (hbWanted() && System.currentTimeMillis() - lastHb > HB_MS) { post(ch, sec, dev); lastHb = System.currentTimeMillis() }
                        if (m["event"].asStr() != "message") continue
                        val o = MiniJson.obj(LinkCrypto.unseal(sec, m["message"].asStr()) ?: continue) ?: continue
                        if (o["r"].asStr() != "desktop") continue
                        take(o)
                        if (!wanted()) break
                    }
                }
                wait = 5_000L
            } catch (e: Exception) {
                state = "reconnecting (${e.javaClass.simpleName})"
                try { Thread.sleep(wait) } catch (ie: InterruptedException) { return }
                wait = minOf(wait * 2, 5 * 60_000L)
            } finally { try { conn?.disconnect() } catch (e: Exception) { }; conn = null }
        }
    }

    /** One message from the PC (public for the tests). */
    fun take(o: Map<String, Any?>) {
        o["go"].asMap()?.let { g ->
            val a = g["a"].asStr() ?: return@let
            if (!Regex("[A-Z0-9]{10}").matches(a)) return@let
            heard++
            try { WatchService.instance?.engine?.send(Engine.Cmd.Go(a)) } catch (e: Exception) { }      // (the engine may not be made yet)
        }
        o["res"].asMap()?.let { r ->
            val a = r["a"].asStr() ?: return@let
            val k = r["k"].asStr() ?: return@let
            if (k in setOf("started", "bought", "failed", "busy")) { verdicts[a] = k to System.currentTimeMillis(); heard++ }
        }
    }

    private fun post(ch: String, sec: ByteArray, dev: String) {
        val st = RkApp.store().settings()
        val body = LinkCrypto.seal(sec, MiniJson.write(linkedMapOf("v" to 1, "d" to dev, "r" to "phone", "hb" to 1,
            "bm" to st.buyMode, "bmAt" to st.buyModeAt / 1000.0, "fo" to st.failover, "nm" to DeskLink.name(),
            "at" to System.currentTimeMillis() / 1000.0)))
        val c = java.net.URL("${com.restalker.mobile.core.Link.SERVER}/$ch").openConnection() as java.net.HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "RestalkerMobile"); c.setRequestProperty("Title", "rk")
            val b = body.toByteArray(); c.setFixedLengthStreamingMode(b.size); c.outputStream.use { it.write(b) }
            c.responseCode
        } finally { c.disconnect() }
    }
}
