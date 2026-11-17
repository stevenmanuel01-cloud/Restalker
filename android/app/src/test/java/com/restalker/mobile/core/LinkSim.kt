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
    }

    @JvmStatic fun main(args: Array<String>) {
        val dir = File(args[0]); val port = args[1].toInt(); val cmd = args[2]; val a = args.drop(3)
        val clock = System.getenv("SIM_NOW")?.toLongOrNull()?.let { t -> { t } } ?: System::currentTimeMillis
        val store = Store(dir, clock)
        val link = Link(store, LocalNet(port), clock)
        fun prod(asin: String) = store.products().first { it.asin == asin }
        when (cmd) {
            "connect" -> println(link.connect(a[0]) ?: "linked")
            "add" -> { store.add(Product(id = store.newId(), asin = a[0], name = a[1], cap = a[2].toDouble(), buy = a[3] == "1", kind = a.getOrElse(4) { "box" })); println("added") }
            "cap" -> { store.update(prod(a[0]).id) { it.copy(cap = a[1].toDouble()) }; println("ok") }
            "buy" -> { store.update(prod(a[0]).id) { it.copy(buy = a[1] == "1") }; println("ok") }
            "qty" -> { store.update(prod(a[0]).id) { it.copy(qty = a[1].toInt(), qtySince = clock()) }; println("ok") }
            "watch" -> { store.update(prod(a[0]).id) { it.copy(watch = a[1] == "1") }; println("ok") }
            "remove" -> { store.remove(prod(a[0]).id); println("ok") }
            "bought" -> { val p = prod(a[0]); store.addPurchase(Purchase(clock(), p.asin, p.label, p.cap, "111-0000000-0000000", true)); store.update(p.id) { it.copy(purchased = true, buy = false) }; println("ok") }
            "cycle" -> println(link.cycle())
            "unlink" -> { link.unlink(); println("ok") }
            "dump" -> println(MiniJson.write(linkedMapOf("products" to store.products().associate { it.asin to linkedMapOf("n" to it.name, "c" to it.cap, "b" to it.buy, "w" to it.watch, "k" to it.kind, "purchased" to it.purchased, "year" to it.year, "set" to it.set, "q" to it.qty, "dSt" to it.dSt, "dPr" to it.dPr, "dAt" to it.dAt, "msrp" to it.msrp, "others" to it.others, "stock" to it.stock().first) },
                "desktopBuys" to link.desktopBuys(), "state" to link.state().second, "linked" to link.linked(), "log" to store.log().takeLast(12))))
            else -> println("unknown command")
        }
        store.flushLog()
    }
}
