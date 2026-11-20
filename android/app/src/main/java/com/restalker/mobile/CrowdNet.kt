package com.restalker.mobile

import com.restalker.mobile.core.Catalog
import com.restalker.mobile.core.Crowd
import com.restalker.mobile.core.CrowdState
import com.restalker.mobile.core.Engine
import com.restalker.mobile.core.MiniJson
import com.restalker.mobile.core.asList
import com.restalker.mobile.core.asMap
import com.restalker.mobile.core.asStr

/** 2.6: the Restalker crowd on this phone (core/Crowd.kt says what is shared and taken). WatchService calls tick() every minute:
 *  while watching it reads the channel every 2 minutes; it says hello every 6 hours; the central list's codes are refreshed every 6
 *  hours (only those products are ever shared). Sharing off: nothing is read or sent. */
object CrowdNet {
    /** The channel: the real crowd's - except in the test build (the 4 test phones must never be counted or heard by real users). */
    val TOPIC: String get() = if (BuildConfig.DEBUG) "restalker-crowd-ci-" + Crowd.TOPIC.takeLast(6) else Crowd.TOPIC
    @Volatile private var state: CrowdState? = null
    @Volatile private var lastPoll = 0L
    @Volatile private var catAt = 0L
    @Volatile private var catalog: Set<String> = emptySet()
    @Volatile private var busy = false
    @Volatile var lastError = ""; private set
    @Volatile var shared = 0; private set
    @Volatile var looks = 0; private set

    fun state(): CrowdState = state ?: synchronized(this) { state ?: CrowdState(RkApp.store()).also { state = it } }
    /** Tests: forget the state (after the store was reset). */
    fun reset() { state = null; lastPoll = 0; catAt = 0; catalog = emptySet() }
    fun count(): Int = try { state().count() } catch (e: Exception) { 1 }
    /** 2.15: on the central list (shared with the crowd, posted on Discord) - null while the list is not read yet. */
    fun onList(asin: String): Boolean? = if (catalog.isEmpty()) null else asin in catalog

    /** [force]: the app was opened / you asked - read the channel now (at most once a minute), also while not watching. */
    fun tick(force: Boolean = false) {
        val st = RkApp.store().settings()
        if (busy) return
        val now0 = System.currentTimeMillis()
        // (2.8: the central list - its pictures too - is read every 6 hours whether sharing is on or off)
        if (!st.crowd && now0 - catAt <= 6 * 3_600_000L && catalog.isNotEmpty()) return
        busy = true
        Thread({
            try {
                val now = System.currentTimeMillis()
                if (now - catAt > 6 * 3_600_000L || catalog.isEmpty()) refreshCatalog()
                try { refreshHistory() } catch (e: Exception) { }
                if (!st.crowd) return@Thread
                val cs = state()
                // 1.0.6: no "hello" every 6 hours any more - the count is the relay's live one (this device says "I am here" there
                // with every round), and each hello was one of the home's 250 free messages a day
                Relay.crowdRound()                           // (not linked: the crowd's line on the relay, every 3 minutes)
                // 2.8 (owner: "The app does not seem to count total devices"): read also while not watching - every time the app is opened
                // (at most once a minute) - not only every 2 minutes while watching
                val due = if (force) now - lastPoll >= 60_000L else st.watching && now - lastPoll >= Crowd.POLL_EVERY_MS - 5_000
                if (due) { lastPoll = now; poll() }
                lastError = ""
            } catch (e: Exception) { lastError = "${e.javaClass.simpleName}: ${e.message?.take(80)}" } finally { busy = false }
        }, "crowd").start()
    }

    /** 2.8: TCGplayer's price history of the central list - read at most once a day (the file is made once a day, ~2 AM Pacific). */
    private fun refreshHistory() {
        val s = RkApp.store()
        val at = s.readFile("tcg_history_at.txt")?.trim()?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (now - at < com.restalker.mobile.core.TcgHist.EVERY_MS && s.readFile(com.restalker.mobile.core.TcgHist.FILE) != null) return
        s.writeFile("tcg_history_at.txt", (now - com.restalker.mobile.core.TcgHist.EVERY_MS + 3_600_000L).toString())   // (a failure: again in an hour)
        val body = try { get(com.restalker.mobile.core.TcgHist.URL) } catch (e: Exception) { return }
        if (!com.restalker.mobile.core.TcgHist.ok(body)) return
        s.writeFile(com.restalker.mobile.core.TcgHist.FILE, body); s.writeFile("tcg_history_at.txt", now.toString())
        com.restalker.mobile.core.TcgHist.apply(s, com.restalker.mobile.core.TcgHist.parse(body), DeskLink.get().linked())
    }

    private fun refreshCatalog() {
        try {
            val body = get(Catalog.URL)
            val root = MiniJson.obj(body) ?: return
            val n = Catalog.pictures(body, RkApp.store())                   // 2.8: the central list's pictures
            if (n > 0) RkApp.store().log("Pictures: $n product(s) got their picture from the central list.")
            val a = root["products"].asList().mapNotNull { it.asMap()?.get("a").asStr() }.filter { Crowd.okAsin(it) }.toSet()
            if (a.isNotEmpty()) { catalog = a; catAt = System.currentTimeMillis() }
        } catch (e: Exception) { catAt = System.currentTimeMillis() - 6 * 3_600_000L + 10 * 60_000L }
    }

    private fun poll() {
        val cs = state()
        val since = cs.since.ifBlank { Crowd.FIRST_SINCE }          // 2.8: the first read hears every hello of the last 12 hours
        val body = get("${Crowd.SERVER}/$TOPIC/json?poll=1&since=$since")
        val mine = RkApp.store().products().filter { it.watch }.map { it.asin }.toSet()
        val looked = cs.take(body.lines(), mine) { a ->
            val e = WatchService.instance?.engine ?: return@take false
            e.send(Engine.Cmd.Crowd(a)); true
        }
        looks += looked.size
    }

    /** 1.0.6: the crowd's new lines from the relay (Relay.sync) - taken like the old channel's. */
    fun relayTake(events: List<Map<String, Any?>>) {
        val mine = RkApp.store().products().filter { it.watch }.map { it.asin }.toSet()
        val looked = state().takeRelay(events, mine) { a ->
            val e = WatchService.instance?.engine ?: return@takeRelay false
            e.send(Engine.Cmd.Crowd(a)); true
        }
        looks += looked.size; relayHeard += events.size
    }
    @Volatile var relayHeard = 0; private set
    @Volatile var relayShared = 0; private set

    /** This phone's own look saw [asin] come in stock on Amazon: the crowd hears it (central-list products only).
     *  1.0.6: on the Restalker relay first (no daily limit; every Restalker from 1.0.6 / PC v147 reads the crowd there), and on the old
     *  crowd channel too while that takes it - versions before read only there. */
    fun share(asin: String, price: Double?) {
        if (!RkApp.store().settings().crowd || asin !in catalog) return
        Thread({
            val sg = Crowd.sighting(state().myId(), asin, price, System.currentTimeMillis() / 1000.0)
            val took = try { Relay.share(sg) } catch (e: Exception) { false }
            if (took) relayShared++
            try { post(sg); shared++ }
            catch (e: Exception) { if (took) shared++ else lastError = "share: ${e.message?.take(80)}" }
        }, "crowd-share").start()
    }

    private fun get(url: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 25_000; c.useCaches = false
        c.setRequestProperty("User-Agent", "RestalkerMobile"); c.setRequestProperty("Cache-Control", "no-cache")
        try { if (c.responseCode != 200) throw java.io.IOException("answer ${c.responseCode}"); return c.inputStream.bufferedReader().use { it.readText().take(2_000_000) } } finally { c.disconnect() }
    }

    private fun post(o: Map<String, Any?>) {
        val b = MiniJson.write(o).toByteArray()
        val c = java.net.URL("${Crowd.SERVER}/$TOPIC").openConnection() as java.net.HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "RestalkerMobile"); c.setRequestProperty("Title", "rk-crowd")
            c.setFixedLengthStreamingMode(b.size); c.outputStream.use { it.write(b) }
            if (c.responseCode !in 200..299) throw java.io.IOException("the crowd channel answered ${c.responseCode}")
        } finally { c.disconnect() }
    }

    fun status(): String = "Restalker crowd (2.6): sharing ${if (RkApp.store().settings().crowd) "on" else "off"}; ${count()} contributor(s) heard; " +
        "this session $shared shared ($relayShared on the relay), $relayHeard read on the relay, $looks look(s) because of the crowd; central list ${catalog.size} product(s)" + (if (lastError.isNotEmpty()) "; last problem: $lastError" else "")
}
