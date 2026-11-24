package com.restalker.mobile

import com.restalker.mobile.core.MiniJson
import com.restalker.mobile.core.Ws
import com.restalker.mobile.core.asBool
import com.restalker.mobile.core.asDouble
import com.restalker.mobile.core.asList
import com.restalker.mobile.core.asLong
import com.restalker.mobile.core.asMap
import com.restalker.mobile.core.asStr

/** 2.15: the Restalker relay (Cloudflare - relay/ in the repository): the live "active now" count, the shared checkout lock (the race to
 *  checkout between your devices), problem reports from everyone (compiled once a day on Discord), product suggestions, the Discord
 *  invite and "connect my Discord". The test build never talks to it (the 4 test phones must not be counted, lock or report). */
object Relay {
    const val BASE = "https://restalker-relay.restalker.workers.dev"
    val enabled: Boolean get() = !BuildConfig.DEBUG || testOn
    @Volatile var testOn = false                 // (screen tests may switch it on against a stand-in)
    @Volatile var base = BASE

    @Volatile var active: Int = -1; private set          // devices heard in the last 30 minutes (-1 = not known yet)
    @Volatile var activeAt = 0L; private set
    @Volatile var lastError = ""; private set
    @Volatile private var beatAt = 0L

    private fun device(): String = try { CrowdNet.state().myId() } catch (e: Exception) { "" }

    private fun call(method: String, path: String, body: Map<String, Any?>? = null, timeoutMs: Int = 6_000, raw: String? = null, list: List<Any?>? = null): Map<String, Any?>? {
        if (!enabled) return null
        return try {
            val c = java.net.URL(base + path).openConnection() as java.net.HttpURLConnection
            try {
                c.requestMethod = method; c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
                c.setRequestProperty("User-Agent", "RestalkerMobile/" + BuildConfig.VERSION_NAME)
                if (raw != null) {                       // (1.0.6: a sealed post goes as it is)
                    c.doOutput = true; c.setRequestProperty("Content-Type", "text/plain")
                    c.outputStream.use { it.write(raw.toByteArray()) }
                } else if (body != null || list != null) {
                    c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(MiniJson.write(body ?: list).toByteArray()) }
                }
                val code = c.responseCode
                val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText().take(4_000_000) } ?: ""
                lastError = if (code in 200..299) "" else "HTTP $code"
                MiniJson.obj(txt)
            } finally { c.disconnect() }
        } catch (e: Exception) { lastError = "${e.javaClass.simpleName}: ${e.message?.take(60)}"; null }
    }

    /** Every 15 minutes (WatchService) and when the app opens: "this device is working" -> the live count of active devices.
     *  1.0.4 (owner, 2026-11-20: "Both of these devices are currently active ... Confirm the devices can see and report active links"):
     *  a linked device also gives its link group and a few sealed words about itself (Link.who) and gets the other devices of its group
     *  back - "heard" in Linked devices no longer depends on the free message service alone. Linked: sent also while crowd sharing is
     *  off (nothing about restocks is in it). [force]: now (the app was opened) - at most once a minute. */
    fun heartbeat(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (wsUp()) return                       // (1.1.2: the open connection is the sign of life; the relay sends the count down it)
        if (now - beatAt < (if (force) 60_000L else 15 * 60_000L)) return
        val linked = try { DeskLink.get().linked() } catch (e: Exception) { false }
        if (!RkApp.store().settings().crowd && !linked) return
        beatAt = now
        Thread({
            val body = linkedMapOf<String, Any?>("c" to device())
            if (linked) try {
                val l = DeskLink.get(); val g = l.lockGroup(); val w = l.who()
                if (g != null && w != null) { body["g"] = g; body["w"] = w }
            } catch (e: Exception) { }
            val r = call("POST", "/v1/h", body)
            r?.get("active").asLong()?.let { active = it.toInt(); activeAt = System.currentTimeMillis() }
            try {
                val peers = r?.get("peers").asList().mapNotNull { x ->
                    val m = x.asMap() ?: return@mapNotNull null
                    val w = m["w"].asStr() ?: return@mapNotNull null
                    val at = m["at"].asDouble() ?: return@mapNotNull null
                    w to at / 1000.0 }
                if (r?.containsKey("peers") == true) { peersAt = System.currentTimeMillis(); peersN = DeskLink.get().takePresence(peers); RkApp.changed() }
            } catch (e: Exception) { }
        }, "relay-beat").start()
    }
    @Volatile var peersAt = 0L; private set          // 1.0.4: when the relay last told us about the other linked devices (0 = not yet)
    @Volatile var peersN = 0; private set

    // ---- 1.0.6 (owner, 2026-11-20: "The ntfy messages arent really needed anymore since the app can handle notifications"): what this
    // device and its linked devices tell each other (core/Link.kt - sealed there, the relay cannot read it) and the crowd's sightings
    // go through the relay, not through the free message service (a home's 250 messages a day for all its devices were used up by
    // the linked devices' updates alone). ONE request does the round: "I am here" (the active count, the linked devices' signs of
    // life), what is new on the link's channels, and the crowd's line; the answer says when to ask again.
    @Volatile var syncAt = 0L; private set
    @Volatile var syncs = 0; private set
    @Volatile var syncFails = 0; private set
    @Volatile var nextS = 60; private set

    /** [reads]: {channel: the last id read}. The crowd's line is added when sharing is on. -> {channel: new posts} or null (not reached). */
    fun sync(reads: Map<String, String>): Map<String, List<Map<String, Any?>>>? {
        if (!enabled) return null
        val st = RkApp.store().settings()
        val r = LinkedHashMap(reads)
        val cs = if (st.crowd) try { CrowdNet.state() } catch (e: Exception) { null } else null
        if (cs != null) r["crowd"] = cs.rsince
        val t0 = System.currentTimeMillis()
        if (wsUp() && t0 - wsHttpAt < wsSafetyS * 1000L) {
            // 1.1.2: the connection is open - what is new was pushed already (the crowd's lines went to CrowdNet as they came)
            val out = LinkedHashMap<String, List<Map<String, Any?>>>()
            synchronized(wsBuf) {
                for ((ch, last) in reads) {
                    val lines = wsBuf.remove(ch) ?: continue
                    val from = last.toLongOrNull() ?: -1L
                    val seen = HashSet<Long>()
                    out[ch] = lines.sortedBy { it["id"].asStr()?.toLongOrNull() ?: 0L }.filter { m ->
                        val i = m["id"].asStr()?.toLongOrNull() ?: return@filter true
                        i > from && seen.add(i) }
                }
            }
            for (ch in reads.keys) if (ch !in out) out[ch] = emptyList()
            syncAt = t0; beatAt = t0; nextS = 60
            return out
        }
        // (a full round the old way: after every new connection, and every few hours; pushed lines kept meanwhile are in its answer too)
        val body = linkedMapOf<String, Any?>("c" to device(), "r" to r)
        try {
            val l = DeskLink.get()
            if (l.linked()) { val g = l.lockGroup(); val w = l.who(); if (g != null && w != null) { body["g"] = g; body["w"] = w } }
        } catch (e: Exception) { }
        val a = call("POST", "/v1/sync", body, timeoutMs = 12_000)
        if (a == null || a["ok"] != true) {
            syncFails++
            a?.get("next").asLong()?.let { nextS = it.toInt().coerceIn(20, 900) }
            return null
        }
        val now = System.currentTimeMillis()
        syncAt = now; beatAt = now; syncs++
        wsHttpAt = t0                            // (1.1.2: only a round that worked counts - a failed one is asked again)
        synchronized(wsBuf) { for (ch in reads.keys) wsBuf.remove(ch) }
        nextS = (a["next"].asLong() ?: 60L).toInt().coerceIn(20, 900)
        a["active"].asLong()?.let { active = it.toInt(); activeAt = now }
        try {
            if (a.containsKey("peers")) {
                val peers = a["peers"].asList().mapNotNull { x -> val m = x.asMap() ?: return@mapNotNull null; (m["w"].asStr() ?: return@mapNotNull null) to ((m["at"].asDouble() ?: return@mapNotNull null) / 1000.0) }
                peersAt = now; peersN = DeskLink.get().takePresence(peers)
            }
        } catch (e: Exception) { }
        val out = LinkedHashMap<String, List<Map<String, Any?>>>()
        a["m"].asMap()?.forEach { (k, v) -> out[k] = v.asList().mapNotNull { it.asMap() } }
        try { out["crowd"]?.takeIf { it.isNotEmpty() }?.let { CrowdNet.relayTake(it) } } catch (e: Exception) { }
        try { RkApp.changed() } catch (e: Exception) { }
        return out
    }

    /** A sealed post on a channel of the relay -> true when it took it. */
    fun postCh(channel: String, text: String): Boolean {
        val a = wsAsk(mapOf("t" to "p", "ch" to channel, "m" to text))        // 1.1.2: up the open connection, else the old way
        if (a != null && a["t"] == "ok" && a["ch"] == channel) return true
        return call("POST", "/v1/ch/$channel", timeoutMs = 12_000, raw = text)?.get("ok") == true
    }

    /** This device's own sighting for the crowd, straight to the relay -> true when taken. */
    fun share(sighting: Map<String, Any?>): Boolean {
        val a = wsAsk(mapOf("t" to "s", "l" to listOf(sighting)))             // 1.1.2: up the open connection, else the old way
        if (a != null && a["t"] == "ok" && !a.containsKey("ch")) return true
        return call("POST", "/v1/s", timeoutMs = 8_000, list = listOf(sighting))?.get("ok") == true
    }

    // ---- 1.1.2 (owner, 2026-11-24: "the capacity to receive thousands of users as well as send messages back to them in near zero
    // latency"): ONE connection to the relay, held open while this device watches. The relay pushes each new line the moment it has it:
    // a crowd sighting goes straight to CrowdNet, a linked device's post starts the link round at once. What this device says goes up
    // the same connection. Until 1.1.1 the app asked once a minute: a line waited up to a minute, and every device cost the relay
    // 1,440 requests a day (its free plan: 100,000 a day for everybody together). An open connection costs its connects only.
    // Asking the old way stays: whenever the connection is down (it is opened again by itself), once after every new connection (so
    // nothing sent meanwhile is missed), and every few hours as a check.
    private const val WS_PING_MS = 120_000L       // a protocol ping: answered by Cloudflare itself, not counted, keeps routers from dropping the line
    private const val WS_QUIET_MS = 270_000L      // nothing heard this long (not even a ping's answer): opened anew
    private const val WS_HERE_MS = 15 * 60_000L   // linked only: this device's sign of life for the other linked devices
    private const val WS_PUSH_GAP_MS = 5_000L     // pushed lines start at most one link round every 5 s
    @Volatile var wsBase: String? = null          // (tests: another relay's address, ws://...)
    @Volatile private var ws: Ws? = null
    @Volatile private var wsThread: Thread? = null
    @Volatile private var wsHello = 0L
    @Volatile private var wsSafetyS = 21_600
    @Volatile private var wsHttpAt = 0L
    @Volatile private var wsKey = ""
    @Volatile private var wsHereAt = 0L
    @Volatile private var wsRoundAt = 0L
    @Volatile private var wsRoundDue = false
    @Volatile var wsConnects = 0; private set
    @Volatile var wsFails = 0; private set
    @Volatile var wsLines = 0; private set
    @Volatile var wsSent = 0; private set
    @Volatile var wsError = ""; private set
    @Volatile var wsSince = 0L; private set
    @Volatile var wsLastLineMs = -1L; private set   // how long the newest pushed line was on its way (relay's clock to this device's)
    private val wsBuf = HashMap<String, MutableList<Map<String, Any?>>>()
    private val wsAcks = java.util.concurrent.LinkedBlockingQueue<Map<String, Any?>>()
    private val wsAskLock = Any()

    fun wsUp(): Boolean { val w = ws; return w != null && w.open && wsHello > 0 && System.currentTimeMillis() - w.lastRx < WS_QUIET_MS }

    private fun wsLinkChannel(): String? = try { DeskLink.get().relayChannel() } catch (e: Exception) { null }

    /** What this device listens to right now ("" = nothing: no connection is kept). */
    private fun wsWant(): String {
        if (!enabled) return ""
        return try {
            val st = RkApp.store().settings()
            if (!st.watching) return ""
            val ch = wsLinkChannel()
            val g = if (ch != null) DeskLink.get().lockGroup() else null
            if (!st.crowd && (ch == null || g == null)) return ""
            "c=" + device() + (if (ch != null && g != null) "&g=$g&ch=$ch,$ch-go" else "")
        } catch (e: Exception) { "" }
    }

    /** WatchService, every minute (and when watching is switched): opens / closes the connection as the settings say. */
    @Synchronized fun wsCheck() {
        val want = wsWant()
        val t = wsThread
        if (want.isNotEmpty() && (t == null || !t.isAlive)) {
            wsThread = Thread({ wsLoop() }, "relay-ws").also { it.isDaemon = true; it.start() }
        } else if (want.isEmpty() && t != null) {
            wsThread = null
            try { ws?.close() } catch (e: Exception) { }
            t.interrupt()
        } else if (want.isNotEmpty() && wsKey.isNotEmpty() && want != wsKey) {
            try { ws?.close() } catch (e: Exception) { }            // (linked / unlinked / sharing switched: opened anew with the right channels)
        }
        if (wsRoundDue && System.currentTimeMillis() - wsRoundAt >= WS_PUSH_GAP_MS) wsRound()
    }

    @Synchronized fun wsStop() { val t = wsThread; wsThread = null; try { ws?.close() } catch (e: Exception) { }; t?.interrupt() }

    private fun wsRound() {
        wsRoundDue = false; wsRoundAt = System.currentTimeMillis()
        try { if (DeskLink.get().linked()) DeskLink.cycleAsync() } catch (e: Exception) { }
    }

    private fun wsLine(o: Map<String, Any?>) {
        val ch = o["ch"].asStr() ?: return
        wsLines++
        o["time"].asDouble()?.let { wsLastLineMs = (System.currentTimeMillis() - (it * 1000).toLong()).coerceIn(-1L, 3_600_000L) }
        if (ch == "crowd") {
            try {
                if (RkApp.store().settings().crowd) {
                    val last = CrowdNet.state().rsince.toLongOrNull()
                    val id = o["id"].asStr()?.toLongOrNull()
                    if (last == null || id == null || id > last) CrowdNet.relayTake(listOf(o))
                }
            } catch (e: Exception) { }
            return
        }
        synchronized(wsBuf) { val l = wsBuf.getOrPut(ch) { ArrayList() }; l.add(o); while (l.size > 400) l.removeAt(0) }
        // (a linked device's post: the link round runs now - at most one such round every 5 s; the rest is read by that round)
        if (System.currentTimeMillis() - wsRoundAt >= WS_PUSH_GAP_MS) wsRound() else if (!wsRoundDue) {
            wsRoundDue = true
            Thread({ try { Thread.sleep(WS_PUSH_GAP_MS - (System.currentTimeMillis() - wsRoundAt).coerceIn(0L, WS_PUSH_GAP_MS)) } catch (e: Exception) { }
                     if (wsRoundDue) wsRound() }, "relay-ws-round").start()
        }
    }

    private fun wsTake(text: String) {
        val o = try { MiniJson.obj(text) } catch (e: Exception) { null } ?: return
        val now = System.currentTimeMillis()
        when (o["t"].asStr()) {
            "m" -> wsLine(o)
            "hello" -> { wsHello = now; wsSafetyS = (o["next"].asLong() ?: 21_600L).toInt().coerceIn(600, 86_400)
                         o["active"].asLong()?.let { active = it.toInt(); activeAt = now }
                         wsHttpAt = 0L                                  // (one full round the old way follows: nothing sent meanwhile is missed)
                         try { if (DeskLink.get().linked()) wsRound() else Thread({ try { sync(emptyMap()) } catch (e: Exception) { } }, "relay-round").start() } catch (e: Exception) { }
                         try { RkApp.changed() } catch (e: Exception) { } }
            "a", "here" -> {
                o["active"].asLong()?.let { if (active != it.toInt()) { active = it.toInt(); try { RkApp.changed() } catch (e: Exception) { } }; activeAt = now }
                try {
                    if (o.containsKey("peers")) {
                        val peers = o["peers"].asList().mapNotNull { x -> val m = x.asMap() ?: return@mapNotNull null; (m["w"].asStr() ?: return@mapNotNull null) to ((m["at"].asDouble() ?: return@mapNotNull null) / 1000.0) }
                        peersAt = now; peersN = DeskLink.get().takePresence(peers); RkApp.changed()
                    }
                } catch (e: Exception) { }
            }
            "ok", "no" -> wsAcks.offer(o)
        }
    }

    /** One thing up the connection, and the relay's answer to it -> the answer, or null (then it goes the old way). */
    private fun wsAsk(o: Map<String, Any?>, waitMs: Long = 4_000): Map<String, Any?>? {
        if (!wsUp()) return null
        synchronized(wsAskLock) {
            val w = ws ?: return null
            wsAcks.clear()
            try { w.send(MiniJson.write(o)); wsSent++ } catch (e: Exception) { wsError = "send: " + (e.message ?: "").take(60); return null }
            return try { wsAcks.poll(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) { null }
        }
    }

    private fun wsLoop() {
        var wait = 5_000L
        while (wsThread === Thread.currentThread()) {
            val want = wsWant()
            if (want.isEmpty()) return
            val t0 = System.currentTimeMillis()
            var was = false
            try {
                val b = wsBase ?: base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
                val w = Ws("$b/v1/ws?$want", 20_000)
                ws = w; wsKey = want; wsHello = 0L; wsError = ""; wsConnects++; wsSince = t0
                var lastPing = t0
                while (wsThread === Thread.currentThread() && w.open) {
                    val m = w.recv(5_000)
                    val now = System.currentTimeMillis()
                    if (m != null) wsTake(m)
                    if (now - lastPing >= WS_PING_MS) { lastPing = now; w.ping() }
                    if (wsHello > 0 && now - wsHereAt >= WS_HERE_MS) {
                        wsHereAt = now
                        val who = try { val l = DeskLink.get(); if (l.linked()) l.who() else null } catch (e: Exception) { null }
                        if (who != null) { w.send(MiniJson.write(mapOf("t" to "h", "w" to who))); wsSent++ }
                    }
                    if (now - w.lastRx > WS_QUIET_MS) throw Ws.Closed("nothing heard for ${(now - w.lastRx) / 1000} s")
                }
            } catch (e: Exception) {
                wsError = (e.message ?: e.javaClass.simpleName).take(100)
            } finally {
                was = wsHello > 0
                if (!was) wsFails++
                wsHello = 0L
                try { ws?.close() } catch (e: Exception) { }
                ws = null
            }
            wait = if (was && System.currentTimeMillis() - t0 > 60_000) 5_000L else minOf(300_000L, wait * 2)
            try { Thread.sleep(wait) } catch (e: InterruptedException) { return }
        }
    }

    fun wsStatus(): String = "connection (1.1.2): " + (if (wsUp()) "open for ${(System.currentTimeMillis() - wsSince) / 1000} s" else "not open") +
        "; opened $wsConnects time(s), $wsFails failed; $wsLines line(s) pushed here" + (if (wsLastLineMs >= 0) " (the newest was ${wsLastLineMs} ms on its way)" else "") +
        ", $wsSent sent up" + (if (wsError.isNotEmpty()) "; last problem: $wsError" else "")

    /** A device that is not linked still reads the crowd's line (and says "I am here"): every 3 minutes, or as the relay says. A linked
     *  one does it with its link round (DeskLink), so nothing is asked twice. */
    fun crowdRound() {
        if (!enabled || !RkApp.store().settings().crowd) return
        if (try { DeskLink.get().linked() } catch (e: Exception) { false }) return
        if (wsUp() && System.currentTimeMillis() - wsHttpAt < wsSafetyS * 1000L) return       // (1.1.2: the crowd's lines are pushed)
        if (System.currentTimeMillis() - syncTry < maxOf(180, nextS) * 1000L) return
        syncTry = System.currentTimeMillis()
        Thread({ try { sync(emptyMap()) } catch (e: Exception) { } }, "relay-round").start()
    }
    @Volatile private var syncTry = 0L

    /** The race (engine thread): true = this device may place the order, false = another of your devices has it, null = not asked. */
    fun lock(asin: String): Boolean? {
        val g = DeskLink.get().lockGroup() ?: return null
        val r = call("POST", "/v1/lock", mapOf("c" to device(), "g" to g, "a" to asin), timeoutMs = 4_000) ?: return null
        if (r["ok"] != true) return null
        return r["granted"].asBool()
    }

    fun release(asin: String, result: String) {
        val g = DeskLink.get().lockGroup() ?: return
        Thread({ call("POST", "/v1/release", mapOf("c" to device(), "g" to g, "a" to asin, "result" to result)) }, "relay-release").start()
    }

    /** (owner, 2026-11-19: reports from everyone go to Discord, compiled once a day - only the owner's devices send theirs directly) */
    fun report(title: String, note: String, text: String): Boolean =
        call("POST", "/v1/report", mapOf("c" to device(), "app" to "phone", "ver" to BuildConfig.VERSION_NAME, "title" to title.take(200),
            "note" to note.take(1000), "text" to text.take(250_000)), timeoutMs = 15_000)?.get("ok") == true

    fun suggest(asin: String, name: String): Boolean =
        call("POST", "/v1/suggest", mapOf("c" to device(), "a" to asin, "name" to name.take(150)))?.get("ok") == true

    /** The Discord invite (kept for a day). */
    fun invite(): String? {
        val s = RkApp.store()
        val kept = s.readFile("discord_invite.txt")?.trim()
        val at = s.readFile("discord_invite_at.txt")?.trim()?.toLongOrNull() ?: 0L
        if (kept != null && kept.startsWith("https://discord.gg/") && System.currentTimeMillis() - at < 24 * 3_600_000L) return kept
        val u = call("GET", "/v1/links")?.get("discord").asStr()?.takeIf { it.startsWith("https://discord.gg/") }
        if (u != null) { s.writeFile("discord_invite.txt", u); s.writeFile("discord_invite_at.txt", System.currentTimeMillis().toString()) }
        return u ?: kept
    }

    /** "Connect my Discord": a 6-letter code to type as /link CODE in the Restalker server (good for 15 minutes). */
    fun linkCode(): String? = call("POST", "/v1/linkcode", mapOf("c" to device()))?.get("code").asStr()

    /** -> the Discord name this device is connected to, "" when not connected, null when it could not be asked. */
    fun linkedName(): String? {
        val r = call("GET", "/v1/linked?c=" + device()) ?: return null
        return if (r["linked"] == true) (r["name"].asStr() ?: "connected") else ""
    }

    fun status(): String = if (!enabled) "relay: off (test build)" else
        "relay: active now ${if (active >= 0) active.toString() else "?"}" + (if (lastError.isNotEmpty()) " - last error $lastError" else "") +
            " · linked devices heard there: " + (if (peersAt > 0) "$peersN (asked ${(System.currentTimeMillis() - peersAt) / 60_000} min ago)" else "not asked yet") +
            " · rounds (1.0.6, one request each): $syncs ok, $syncFails failed, last " + (if (syncAt > 0) "${(System.currentTimeMillis() - syncAt) / 60_000} min ago" else "not yet") + ", asks again every $nextS s" +
            " · " + wsStatus()
}
