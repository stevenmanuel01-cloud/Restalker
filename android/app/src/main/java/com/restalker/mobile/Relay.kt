package com.restalker.mobile

import com.restalker.mobile.core.MiniJson
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

    private fun call(method: String, path: String, body: Map<String, Any?>? = null, timeoutMs: Int = 6_000): Map<String, Any?>? {
        if (!enabled) return null
        return try {
            val c = java.net.URL(base + path).openConnection() as java.net.HttpURLConnection
            try {
                c.requestMethod = method; c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
                c.setRequestProperty("User-Agent", "RestalkerMobile/" + BuildConfig.VERSION_NAME)
                if (body != null) {
                    c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(MiniJson.write(body).toByteArray()) }
                }
                val code = c.responseCode
                val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText().take(200_000) } ?: ""
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
            " · linked devices heard there: " + (if (peersAt > 0) "$peersN (asked ${(System.currentTimeMillis() - peersAt) / 60_000} min ago)" else "not asked yet")
}
