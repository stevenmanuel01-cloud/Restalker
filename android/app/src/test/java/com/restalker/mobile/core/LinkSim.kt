package com.restalker.mobile.core

import java.io.File

/** A phone on the command line, for the test that links it with the REAL desktop code (t124/e2e124.py):
 *  java ... LinkSim <dir> <port> <cmd> [args]. The phone's files stay in <dir> between calls; the channel is a stand-in ntfy on <port>. */
object LinkSim {
    class LocalNet(private val port: Int) : LinkNet {
        private fun map(u: String) = u.replace("https://ntfy.sh", "http://127.0.0.1:$port")
        override fun post(url: String, body: String, asFile: Boolean): Int {
            val c = java.net.URL(map(url)).openConnection() as java.net.HttpURLConnection
            c.requestMethod = if (asFile) "PUT" else "POST"; c.doOutput = true
            if (asFile) c.setRequestProperty("Filename", "rk.txt")
            c.outputStream.use { it.write(body.toByteArray()) }
            return c.responseCode.also { c.inputStream.close() }
        }
        override fun get(url: String): String = java.net.URL(map(url)).readText()
        // 1.0.6: the relay (SIM_RELAY = a local copy of the real relay code); without it the relay is "not reached", as before 1.0.6
        var link: Link? = null
        var code = "0123456789ab"
        var took: ((Map<String, List<Map<String, Any?>>>) -> Unit)? = null
        private val relay = System.getenv("SIM_RELAY")
        private fun call(path: String, body: String, type: String): Map<String, Any?>? {
            val c = java.net.URL(relay + path).openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", type); c.connectTimeout = 5000; c.readTimeout = 10000
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            return MiniJson.obj((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() })
        }
        override fun sync(reads: Map<String, String>): Map<String, List<Map<String, Any?>>>? {
            if (relay.isNullOrEmpty()) return null
            val body = linkedMapOf<String, Any?>("c" to code, "r" to reads)
            link?.let { l -> val g = l.lockGroup(); val w = l.who(); if (g != null && w != null) { body["g"] = g; body["w"] = w } }
            val a = try { call("/v1/sync", MiniJson.write(body), "application/json") } catch (e: Exception) { null } ?: return null
            if (a["ok"] != true) return null
            link?.takePresence(a["peers"].asList().mapNotNull { x -> val m = x.asMap() ?: return@mapNotNull null; (m["w"].asStr() ?: return@mapNotNull null) to ((m["at"].asDouble() ?: return@mapNotNull null) / 1000.0) })
            val out = LinkedHashMap<String, List<Map<String, Any?>>>()
            a["m"].asMap()?.forEach { (k, v) -> out[k] = v.asList().mapNotNull { it.asMap() } }
            took?.invoke(out)
            return out
        }
        override fun relayPost(channel: String, body: String): Boolean =
            !relay.isNullOrEmpty() && try { call("/v1/ch/$channel", body, "text/plain")?.get("ok") == true } catch (e: Exception) { false }
    }

    @JvmStatic fun main(args: Array<String>) {
        val dir = File(args[0]); val port = args[1].toInt(); val cmd = args[2]; val a = args.drop(3)
        val clock = System.getenv("SIM_NOW")?.toLongOrNull()?.let { t -> { t } } ?: System::currentTimeMillis
        val store = Store(dir, clock)
        val net = LocalNet(port)
        val link = Link(store, net, me = { mapOf("amz" to true, "nm" to (System.getenv("SIM_NAME") ?: "Sim phone"), "vn" to APP_VERSION, "av" to 31L) }, clockMs = clock)
        net.link = link
        net.code = (store.readFile("sim_code.txt") ?: java.util.UUID.randomUUID().toString().replace("-", "").take(12).also { store.writeFile("sim_code.txt", it) }).trim()
        // (what came on the fast channel with the relay's round - the PC's signals and alerts - is kept for the test to read)
        link.onGo = { o -> File(dir, "go.jsonl").appendText(MiniJson.write(o) + "\n") }
        val cs = CrowdState(store, clock)
        net.took = { m -> m["crowd"]?.takeIf { it.isNotEmpty() }?.let { ev -> val looked = cs.takeRelay(ev, store.products().map { it.asin }.toSet()) { true }
            File(dir, "crowd.jsonl").appendText(MiniJson.write(linkedMapOf("got" to ev.size, "looked" to looked)) + "\n") } }
        fun prod(asin: String) = store.products().first { it.asin == asin }
        when (cmd) {
            "connect" -> println(link.connect(a[0]) ?: "linked")
            "add" -> { store.add(Product(id = store.newId(), asin = a[0], name = a[1], cap = a[2].toDouble(), buy = a[3] == "1", kind = a.getOrElse(4) { "box" })); println("added") }
            "cap" -> { store.update(prod(a[0]).id) { it.copy(cap = a[1].toDouble()) }; println("ok") }
            "buy" -> { store.update(prod(a[0]).id) { it.copy(buy = a[1] == "1") }; println("ok") }
            "qty" -> { store.update(prod(a[0]).id) { it.copy(qty = a[1].toInt(), qtySince = clock()) }; println("ok") }
            "watch" -> { store.update(prod(a[0]).id) { it.copy(watch = a[1] == "1") }; println("ok") }
            "remove" -> { store.remove(prod(a[0]).id); println("ok") }
            "bought" -> { val p = prod(a[0]); store.addPurchase(Purchase(clock(), p.asin, p.label, p.cap, "111-0000000-0000000", true)); store.update(p.id) { it.copy(purchased = true, attempted = true, buy = false) }; println("ok") }   // (1.0.8: as the Engine marks a purchase)
            "pcw" -> println(link.pcWatchesNow())                                            // (1.0.9: the PC is watching now)
            "rearm" -> { store.update(prod(a[0]).id) { it.rearmed() }; println("ok") }       // (1.0.8: the Re-arm button)
            "buyon" -> { store.update(prod(a[0]).id) { it.withBuy(true) }; println("ok") }    // (1.0.8: the Buy switch)
            "cycle" -> println(link.cycle())
            // 1.0.4: presence and choices, for the test with the real desktop code and the real relay code
            "who" -> println(link.who() ?: "")
            "group" -> println(link.lockGroup() ?: "")
            "presence" -> println(link.takePresence(a.chunked(2).map { it[0] to it[1].toDouble() }))
            "devices" -> println(MiniJson.write(link.devices("Sim phone").map { linkedMapOf("id" to it.id, "pc" to it.pc, "me" to it.me, "name" to it.name, "mode" to it.mode,
                "failover" to it.failover, "agoS" to it.agoS, "vn" to it.vn, "syncAgoS" to it.syncAgoS) }))
            "mode" -> println(store.settings().buyMode + " " + store.settings().failover)
            "state" -> println(link.state().second + " | behind " + link.pcSyncBehindS())
            "unlink" -> { link.unlink(); println("ok") }
            // 1.0.6: the link's way, the heartbeat of a device that buys instead of the PC, a round of a device that is not linked
            "way" -> println(link.wayText() + " | pc reads the relay: " + link.pcReadsRelay())
            "hb" -> println(link.postGo(linkedMapOf("hb" to 1, "bm" to (a.getOrNull(0) ?: "phone"))))
            "crowdround" -> println(MiniJson.write(net.sync(linkedMapOf("crowd" to cs.rsince))?.mapValues { it.value.size } ?: mapOf("relay" to "not reached")))
            "dump" -> println(MiniJson.write(linkedMapOf("products" to store.products().associate { it.asin to linkedMapOf("n" to it.name, "c" to it.cap, "b" to it.buy, "w" to it.watch, "k" to it.kind, "purchased" to it.purchased, "year" to it.year, "set" to it.set, "q" to it.qty, "dSt" to it.dSt, "dPr" to it.dPr, "dAt" to it.dAt, "msrp" to it.msrp, "others" to it.others, "stock" to it.stock().first, "dImg" to it.dImg, "image" to it.image) },
                "desktopBuys" to link.desktopBuys(), "state" to link.state().second, "linked" to link.linked(), "log" to store.log().takeLast(12))))
            else -> println("unknown command")
        }
        store.flushLog()
    }
}
