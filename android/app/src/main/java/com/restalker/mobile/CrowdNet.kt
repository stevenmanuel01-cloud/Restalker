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

    fun tick(force: Boolean = false) {
        val st = RkApp.store().settings()
        if (!st.crowd || busy) return
        busy = true
        Thread({
            try {
                val now = System.currentTimeMillis()
                if (now - catAt > 6 * 3_600_000L || catalog.isEmpty()) refreshCatalog()
                val cs = state()
                if (now - cs.helloAt > Crowd.HELLO_EVERY_MS) { post(Crowd.hello(cs.myId())); cs.helloAt = now; cs.save() }
                if (force || (st.watching && now - lastPoll >= Crowd.POLL_EVERY_MS - 5_000)) { lastPoll = now; poll() }
                lastError = ""
            } catch (e: Exception) { lastError = "${e.javaClass.simpleName}: ${e.message?.take(80)}" } finally { busy = false }
        }, "crowd").start()
    }

    private fun refreshCatalog() {
        try {
            val root = MiniJson.obj(get(Catalog.URL)) ?: return
            val a = root["products"].asList().mapNotNull { it.asMap()?.get("a").asStr() }.filter { Crowd.okAsin(it) }.toSet()
            if (a.isNotEmpty()) { catalog = a; catAt = System.currentTimeMillis() }
        } catch (e: Exception) { catAt = System.currentTimeMillis() - 6 * 3_600_000L + 10 * 60_000L }
    }

    private fun poll() {
        val cs = state()
        val since = cs.since.ifBlank { "10m" }
        val body = get("${Crowd.SERVER}/$TOPIC/json?poll=1&since=$since")
        val mine = RkApp.store().products().filter { it.watch }.map { it.asin }.toSet()
        val looked = cs.take(body.lines(), mine) { a ->
            val e = WatchService.instance?.engine ?: return@take false
            e.send(Engine.Cmd.Crowd(a)); true
        }
        looks += looked.size
    }

    /** This phone's own look saw [asin] come in stock on Amazon: the crowd hears it (central-list products only). */
    fun share(asin: String, price: Double?) {
        if (!RkApp.store().settings().crowd || asin !in catalog) return
        Thread({
            try { post(Crowd.sighting(state().myId(), asin, price, System.currentTimeMillis() / 1000.0)); shared++ }
            catch (e: Exception) { lastError = "share: ${e.message?.take(80)}" }
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
        "this session $shared shared, $looks look(s) because of the crowd; central list ${catalog.size} product(s)" + (if (lastError.isNotEmpty()) "; last problem: $lastError" else "")
}
