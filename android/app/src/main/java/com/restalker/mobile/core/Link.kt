package com.restalker.mobile.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The link with the desktop Restalker (1.4; the desktop's phonelink.py, v124 - the SAME rules, tested against each other).
 *
 *  The desktop shows a QR code "RK1.<24 letters>.<secret>". Scanning it links the two: both post their Amazon products (code, name, cap,
 *  Buy, Watch, kind, last bought) to that private ntfy channel, ENCRYPTED with the secret, and read the other's every minute or so.
 *  A newer change wins; a product removed on one side is removed on the other; at the first link the desktop's values win where both have
 *  the same product. ONE buyer: while the desktop runs with Amazon auto-buy on, it buys and this phone only stands by; the phone buys when
 *  the desktop has been silent 45 minutes, was stopped / closed, or has auto-buy off. No sign-in, password or address is ever shared.
 *
 *  Times in the posts are SECONDS (as on the desktop). */
object LinkCrypto {
    private val rnd = SecureRandom()
    private const val ABC = "abcdefghijkmnpqrstuvwxyz23456789"

    fun parse(code: String?): Pair<String, ByteArray>? {
        val m = Regex("RK1\\.([a-z0-9]{24})\\.([A-Za-z0-9_-]{43})").matchEntire((code ?: "").trim()) ?: return null
        val sec = try { Base64.getUrlDecoder().decode(m.groupValues[2]) } catch (e: Exception) { return null }
        return if (sec.size == 32) ("rkl-" + m.groupValues[1]) to sec else null
    }

    /** (tests only: the desktop makes the codes) */
    fun make(): String {
        val ch = (1..24).map { ABC[rnd.nextInt(ABC.length)] }.joinToString("")
        val sec = ByteArray(32).also { rnd.nextBytes(it) }
        return "RK1.$ch." + Base64.getUrlEncoder().withoutPadding().encodeToString(sec)
    }

    private fun prf(k: ByteArray, m: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k, "HmacSHA256")); doFinal(m) }
    private fun be32(i: Int) = byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())
    private fun stream(kenc: ByteArray, nonce: ByteArray, n: Int): ByteArray {
        val out = ByteArray(n); var i = 0; var off = 0
        while (off < n) { val b = prf(kenc, nonce + be32(i++)); val c = minOf(32, n - off); System.arraycopy(b, 0, out, off, c); off += c }
        return out
    }

    fun seal(secret: ByteArray, json: String): String {
        val d = Deflater(9); d.setInput(json.toByteArray(Charsets.UTF_8)); d.finish()
        val buf = java.io.ByteArrayOutputStream(); val tmp = ByteArray(4096)
        while (!d.finished()) { val n = d.deflate(tmp); buf.write(tmp, 0, n) }
        d.end()
        val pt = buf.toByteArray()
        val nonce = ByteArray(16).also { rnd.nextBytes(it) }
        val kenc = prf(secret, "rk-enc".toByteArray()); val kmac = prf(secret, "rk-mac".toByteArray())
        val ks = stream(kenc, nonce, pt.size)
        val ct = ByteArray(pt.size) { (pt[it].toInt() xor ks[it].toInt()).toByte() }
        val tag = prf(kmac, nonce + ct).copyOf(16)
        return "rk1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + ct + tag)
    }

    /** -> the JSON text, or null (not ours / damaged / wrong secret). */
    fun unseal(secret: ByteArray, text: String?): String? = try {
        val s = (text ?: "").trim()
        if (!s.startsWith("rk1:")) null else {
            val raw = Base64.getUrlDecoder().decode(s.substring(4))
            if (raw.size < 33) null else {
                val nonce = raw.copyOfRange(0, 16); val ct = raw.copyOfRange(16, raw.size - 16); val tag = raw.copyOfRange(raw.size - 16, raw.size)
                val kenc = prf(secret, "rk-enc".toByteArray()); val kmac = prf(secret, "rk-mac".toByteArray())
                if (!MessageDigest.isEqual(prf(kmac, nonce + ct).copyOf(16), tag)) null else {
                    val ks = stream(kenc, nonce, ct.size)
                    val pt = ByteArray(ct.size) { (ct[it].toInt() xor ks[it].toInt()).toByte() }
                    val inf = Inflater(); inf.setInput(pt)
                    val out = java.io.ByteArrayOutputStream(); val tmp = ByteArray(4096)
                    while (!inf.finished()) { val n = inf.inflate(tmp); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(tmp, 0, n); if (out.size() > 1 shl 20) break }
                    inf.end()
                    if (out.size() > 1 shl 20) null else out.toString("UTF-8")
                }
            }
        }
    } catch (e: Exception) { null }
}

/** The channel: post a text, read the posts since a mark. The app gives the real one (HTTPS to ntfy.sh); the tests their own. */
interface LinkNet {
    /** -> HTTP status */
    fun post(url: String, body: String, asFile: Boolean): Int
    /** -> the response body (ntfy's JSON lines), or throws */
    fun get(url: String): String
}

/** One shared product as posted. */
data class LinkRec(val a: String, val n: String, val c: Double, val b: Boolean, val w: Boolean, val k: String, val t: Double, val g: Boolean,
                   val ba: Double, val here: Boolean = false, val pend: Double = 0.0, val q: Int = 1) {
    fun same(o: LinkRec?) = o != null && b == o.b && w == o.w && q == o.q && Math.abs(c - o.c) < 0.005
    fun post(): Map<String, Any?> = linkedMapOf("a" to a, "n" to n, "c" to c, "b" to b, "w" to w, "k" to k, "t" to t, "g" to g, "ba" to ba, "q" to q)
    fun toMap(): Map<String, Any?> = post() + mapOf("here" to here, "pend" to pend)
    companion object {
        fun fromMap(m: Map<String, Any?>): LinkRec? {
            val a = m["a"].asStr() ?: return null
            return LinkRec(a, m["n"].asStr() ?: a, m["c"].asDouble() ?: 0.0, m["b"].asBool(), m["w"] != false, m["k"].asStr() ?: "other",
                m["t"].asDouble() ?: 0.0, m["g"].asBool(), m["ba"].asDouble() ?: 0.0, m["here"].asBool(), m["pend"].asDouble() ?: 0.0,
                ((m["q"].asLong() ?: 1).toInt()).coerceIn(1, 3))
        }
    }
}

class Link(private val store: Store, private val net: LinkNet, private val clockMs: () -> Long = System::currentTimeMillis) {
    companion object {
        const val SERVER = "https://ntfy.sh"
        const val FILE = "link.json"
        const val BEAT = 60 * 60.0            // the phone posts at least every hour (its products; the desktop needs no faster sign)
        const val DESK_SILENT = 45 * 60.0     // the desktop not heard for this long -> the phone buys
        const val INLINE_MAX = 3500
        const val TOMB_KEEP = 60 * 86400.0
        const val PEND_RETRY = 5 * 60.0
        private val ASIN = Regex("[A-Z0-9]{10}")
    }

    private val lock = Any()
    private fun nowS() = clockMs() / 1000.0
    /** What the buy decision needs, kept outside the lock (a round can wait on the network; a checkout must never wait for it). */
    @Volatile private var snap: Triple<Boolean, Map<String, Any?>, Long>? = null     // (linked, peer, when read)
    private fun remember(s: St) { snap = Triple(LinkCrypto.parse(s.code) != null, s.peer, clockMs()) }

    // ---- the saved state (link.json)
    private class St(var code: String = "", var dev: String = "", var since: String = "", val recs: LinkedHashMap<String, LinkRec> = LinkedHashMap(),
                     var peer: Map<String, Any?> = emptyMap(), var linkedAt: Double = 0.0, var pubAt: Double = 0.0, var dirty: Boolean = false)

    private fun load(): St {
        val m = MiniJson.obj(store.readFile(FILE)) ?: return St()
        val st = St(m["code"].asStr() ?: "", m["dev"].asStr() ?: "", m["since"].asStr() ?: "", LinkedHashMap(), m["peer"].asMap() ?: emptyMap(),
            m["linkedAt"].asDouble() ?: 0.0, m["pubAt"].asDouble() ?: 0.0, m["dirty"].asBool())
        m["recs"].asMap()?.values?.forEach { v -> v.asMap()?.let(LinkRec::fromMap)?.let { st.recs[it.a] = it } }
        return st
    }
    private fun save(s: St) = store.writeFile(FILE, MiniJson.write(linkedMapOf("code" to s.code, "dev" to s.dev, "since" to s.since,
        "recs" to s.recs.mapValues { it.value.toMap() }, "peer" to s.peer, "linkedAt" to s.linkedAt, "pubAt" to s.pubAt, "dirty" to s.dirty)))

    init {
        remember(load())
        // 1.6: undo 1.5's mistake once - a product marked bought here only because the desktop bought it before the link (no purchase on
        // this phone) is not bought; the desktop's Buy decides
        try {
            if (store.readFile("fix16.done") == null) {
                val mine = store.purchases().map { it.asin }.toSet()
                for (p in store.products()) if (p.purchased && p.asin !in mine && p.orderNumber == null) {
                    store.update(p.id) { it.copy(purchased = false, attempted = false) }
                    store.log("${p.label}: was marked bought by mistake (the desktop's purchase from before the link) - corrected.")
                }
                store.writeFile("fix16.done", "1")
            }
        } catch (e: Exception) { }
    }

    /** (never waits for a round on the network - the screen and the buy decision ask this) */
    fun linked(): Boolean = snap?.first == true

    /** Scanning the desktop's code. -> null when linked, or why not. */
    fun connect(code: String): String? = synchronized(lock) {
        LinkCrypto.parse(code) ?: return "That is not a Restalker link code. On the desktop: Link phone - then scan the code it shows."
        val s = St(code = code.trim(), dev = "p-" + java.lang.Long.toHexString(SecureRandom().nextLong()).take(12), linkedAt = nowS(), dirty = true)
        // our products as the FIRST records (t=1): where the desktop has the same product, the desktop's values win
        for ((a, it) in localItems()) s.recs[a] = it.copy(t = 1.0, here = true)
        save(s); remember(s)
        store.log("Linked with the desktop. Products are shared from now on; the desktop buys while it runs.")
        null
    }

    fun unlink() = synchronized(lock) {
        val s = load()
        if (LinkCrypto.parse(s.code) != null) { try { postNow(s, bye = true) } catch (e: Exception) {} }
        store.writeFile(FILE, "{}"); snap = Triple(false, emptyMap(), clockMs())
        store.log("Unlinked from the desktop - this phone buys on its own again.")
    }

    // ---- what this phone has now
    private fun localItems(): Map<String, LinkRec> {
        val bought = HashMap<String, Double>()
        for (pu in store.purchases()) if (pu.asin.isNotBlank()) bought[pu.asin] = maxOf(bought[pu.asin] ?: 0.0, pu.at / 1000.0)
        val out = LinkedHashMap<String, LinkRec>()
        for (p in store.products()) {
            if (!ASIN.matches(p.asin) || p.cap <= 0 || out.containsKey(p.asin)) continue
            // (a checkout running here, or a Place order pressed: not "Buy" for the desktop - the same product is never ordered twice)
            out[p.asin] = LinkRec(p.asin, p.label, p.cap, p.buy && !p.attempted && !p.inProgress, p.watch, p.kind, 0.0, false, bought[p.asin] ?: 0.0, here = true, q = p.qty)
        }
        return out
    }

    /** A change from the desktop, made here. */
    private fun apply(n: LinkRec, old: LinkRec?, linkedAt: Double) {
        val p = store.products().firstOrNull { it.asin == n.a }
        if (n.g) {
            if (p != null) { store.remove(p.id); store.log("From the desktop: ${p.label} removed.") }
            return
        }
        val kind = if (n.k in Product.SHAPES) n.k else "other"
        // bought by the desktop SINCE the link (1.6: a purchase from before the link - e.g. weeks ago - is not "bought there": 1.5 marked
        // such a product bought here and then told the desktop "Buy off"). Buy goes off here; nothing is latched - the desktop's Buy says
        // when it wants it again
        val boughtThere = n.ba > linkedAt && n.ba > (old?.ba ?: 0.0) && n.ba > ((localItems()[n.a]?.ba ?: 0.0) + 1)
        if (p == null) {
            store.add(Product(id = store.newId(), asin = n.a, name = n.n, cap = n.c, kind = kind, watch = n.w, buy = n.b && !boughtThere,
                tokens = Tokens.fromTitle(n.n), qty = n.q, qtySince = clockMs()))
            store.log("From the desktop: ${n.n} added (cap \$${Engine.money(n.c)}${if (n.b) ", Buy on" else ""}).")
            return
        }
        val what = ArrayList<String>()
        if (Math.abs(p.cap - n.c) >= 0.005) what.add("cap \$${Engine.money(n.c)}")
        if (p.watch != n.w) what.add("Watch " + if (n.w) "on" else "off")
        if (p.buy != n.b && !boughtThere) what.add("Buy " + if (n.b) "on" else "off")
        if (boughtThere) what.add("BOUGHT by the desktop - not bought here again")
        if (p.qty != n.q) what.add("Buy ${n.q}")
        store.update(p.id) {
            var q = it.copy(cap = n.c, watch = n.w, buy = if (boughtThere) false else n.b)
            if (it.qty != n.q) q = q.copy(qty = n.q, qtySince = clockMs())
            if (n.b && !it.buy && !boughtThere) q = q.copy(fails = 0, lockedUntil = 0)
            q
        }
        if (what.isNotEmpty()) store.log("From the desktop: ${p.label}: ${what.joinToString(", ")}.")
    }

    // ---- merging (the desktop's merge / scan)
    private fun merge(s: St, msg: Map<String, Any?>, srvTime: Double?, now: Double): List<Triple<String, LinkRec, LinkRec?>> {
        val out = ArrayList<Triple<String, LinkRec, LinkRec?>>()
        if (msg["d"].asStr() == s.dev) return out
        s.peer = linkedMapOf("d" to (msg["d"].asStr() ?: "").take(20), "r" to (msg["r"].asStr() ?: "").take(10), "at" to (srvTime ?: now),
            "amz" to msg["amz"].asBool(), "bye" to msg["bye"].asBool(), "n" to (msg["items"].asList().size))
        if (msg["hi"].asBool()) s.dirty = true
        for (x in msg["items"].asList().take(500)) {
            val m = x.asMap() ?: continue
            val a = m["a"].asStr() ?: continue
            val t = m["t"].asDouble() ?: continue
            if (!ASIN.matches(a)) continue
            val g = m["g"].asBool()
            val c = m["c"].asDouble()
            if (!g && (c == null || c <= 0 || c >= 100000)) continue
            val old = s.recs[a]
            val n = LinkRec(a, (m["n"].asStr() ?: a).take(200), c ?: old?.c ?: 0.0, m["b"].asBool(), m["w"] != false, (m["k"].asStr() ?: "other").take(20),
                t, g, maxOf(m["ba"].asDouble() ?: 0.0, old?.ba ?: 0.0), here = old?.here ?: false, pend = now,
                q = ((m["q"].asLong() ?: old?.q?.toLong() ?: 1L).toInt()).coerceIn(1, 3))
            if (old == null || t > old.t) {
                if (old != null && old.g && g) { s.recs[a] = old.copy(t = t); continue }
                s.recs[a] = n
                if (!(old == null && g)) out.add(Triple(a, n, old))
            } else if ((m["ba"].asDouble() ?: 0.0) > old.ba) {
                val o2 = old.copy(ba = m["ba"].asDouble() ?: 0.0)
                s.recs[a] = o2
                out.add(Triple(a, o2, old))
            }
        }
        return out
    }

    /** What only the desktop knows, shown here (not a change of the product): its groups (year / set), its name for the product, what its
     *  table shows now (stock / price / when), the MSRP, the TCG price and the product's other stores. After the adds. */
    private fun takeGroups(msg: Map<String, Any?>) {
        if (msg["r"].asStr() != "desktop") return
        for (x in msg["items"].asList().take(500)) {
            val m = x.asMap() ?: continue
            val a = m["a"].asStr() ?: continue
            if (m["g"].asBool()) continue
            val p = store.products().firstOrNull { it.asin == a } ?: continue
            var q = p
            m["gy"].asStr()?.let { gy -> q = q.copy(year = gy.take(40), set = (m["gs"].asStr() ?: "").take(80)) }
            m["n"].asStr()?.takeIf { it.isNotBlank() && it != a }?.let { q = q.copy(name = it.take(160)) }
            if (m.containsKey("st")) {
                val at = ((m["ss"].asDouble() ?: 0.0) * 1000).toLong()
                q = q.copy(dSt = (m["st"].asStr() ?: "").take(5), dPr = m["pr"].asDouble(), dAt = minOf(at, clockMs()),
                    msrp = m["ms"].asDouble(), tcg = m["tc"].asDouble(),
                    others = m["sx"].asList().mapNotNull { it.asMap() }.take(8).map { o ->
                        linkedMapOf("s" to (o["s"].asStr() ?: "").take(20), "st" to (o["st"].asStr() ?: "").take(5), "pr" to o["pr"].asDouble(),
                            "at" to ((o["at"].asDouble() ?: 0.0) * 1000).toLong()) })
            }
            // 1.7: the desktop table's picture of the product
            if (m.containsKey("im")) q = q.copy(dImg = m["im"].asStr()?.takeIf { Pics.validAny(it) } ?: "")
            if (q != p) store.update(p.id) { cur -> cur.copy(year = q.year, set = q.set, name = q.name, dSt = q.dSt, dPr = q.dPr, dAt = q.dAt,
                msrp = q.msrp, tcg = q.tcg, others = q.others, dImg = q.dImg) }
        }
    }

    private fun scan(s: St, cur: Map<String, LinkRec>, now: Double): Boolean {
        var ch = false
        for ((a, it) in cur) {
            var r = s.recs[a]
            if (r != null && r.pend > 0 && !r.g) {
                if (r.same(it) && it.ba >= r.ba - 1) { r = r.copy(pend = 0.0, here = true); s.recs[a] = r }
                else if (now - r.pend < PEND_RETRY) continue
                else { r = r.copy(pend = 0.0); s.recs[a] = r }
            }
            if (r == null || r.g || !r.same(it) || it.ba > r.ba) {
                s.recs[a] = it.copy(t = now, g = false, ba = maxOf(it.ba, r?.ba ?: 0.0), here = true, pend = 0.0); ch = true
            } else if (!r.here) s.recs[a] = r.copy(here = true)
        }
        for ((a, r) in s.recs.entries.toList()) {
            if (cur.containsKey(a)) continue
            if (r.g) { if (now - r.t > TOMB_KEEP) s.recs.remove(a); continue }
            if (r.pend > 0 && now - r.pend < PEND_RETRY) continue
            if (!r.here) continue
            s.recs[a] = r.copy(g = true, t = now, pend = 0.0); ch = true
        }
        if (ch) s.dirty = true
        return ch
    }

    // ---- the channel
    private fun postNow(s: St, bye: Boolean = false, hi: Boolean = false, now: Double = nowS()) {
        val (ch, sec) = LinkCrypto.parse(s.code) ?: return
        val o = linkedMapOf<String, Any?>("v" to 1, "d" to s.dev, "r" to "phone", "at" to now, "amz" to false, "items" to s.recs.values.map { it.post() })
        if (bye) o["bye"] = true
        if (hi) o["hi"] = true
        val body = LinkCrypto.seal(sec, MiniJson.write(o))
        val st = net.post("$SERVER/$ch", body, body.length > INLINE_MAX)
        if (st !in 200..299) throw java.io.IOException("the link server answered $st")
        s.pubAt = now; s.dirty = false
    }

    private fun read(s: St): List<Pair<Map<String, Any?>, Double?>> {
        val (ch, sec) = LinkCrypto.parse(s.code) ?: return emptyList()
        val since = s.since.ifBlank { maxOf(s.linkedAt, nowS() - 12 * 3600).toLong().toString() }
        val raw = net.get("$SERVER/$ch/json?poll=1&since=" + java.net.URLEncoder.encode(since, "UTF-8"))
        val out = ArrayList<Pair<Map<String, Any?>, Double?>>()
        for (line in raw.lines()) {
            val m = MiniJson.obj(line) ?: continue
            if (m["event"].asStr() != "message") continue
            s.since = m["id"].asStr() ?: s.since
            var text = m["message"].asStr() ?: ""
            val att = m["attachment"].asMap()?.get("url").asStr()
            if (att != null && att.startsWith("$SERVER/file/")) text = try { net.get(att) } catch (e: Exception) { continue }
            val o = MiniJson.obj(LinkCrypto.unseal(sec, text) ?: continue) ?: continue
            if (o["d"].asStr() != s.dev) out.add(o to m["time"].asDouble())
        }
        return out
    }

    /** One round: read the desktop's posts, make its changes here, post ours when something changed (or every hour).
     *  -> a short result ("ok" / "not linked" / why it failed). */
    fun cycle(): String = synchronized(lock) {
        val s = load()
        if (LinkCrypto.parse(s.code) == null) return "not linked"
        val now = nowS()
        val first = s.pubAt == 0.0
        val msgs = try { read(s) } catch (e: Exception) { save(s); return "read failed: ${e.message?.take(80)}" }
        for ((m, srv) in msgs) {
            for ((_, n, old) in merge(s, m, srv, now)) {
                try { apply(n, old, s.linkedAt) } catch (e: Exception) { store.log("! A change from the desktop could not be made: ${e.message?.take(100)}") }
            }
            try { takeGroups(m) } catch (e: Exception) { }
        }
        scan(s, localItems(), now)
        var r = "ok"
        if (s.dirty || first || now - s.pubAt >= BEAT) {
            try { postNow(s, hi = first, now = now) } catch (e: Exception) { r = "post failed: ${e.message?.take(80)}" }
        }
        save(s); remember(s)
        r
    }

    /** Called when a product was changed in the app (the next round posts it). */
    fun touched() = synchronized(lock) { val s = load(); if (LinkCrypto.parse(s.code) != null) { s.dirty = true; save(s) } }

    /** True while the desktop is the buyer (linked, heard within 45 minutes, auto-buy on, not stopped). */
    fun desktopBuys(): Boolean {
        val sn = snap ?: return false
        if (!sn.first) return false
        val p = sn.second
        return p["r"].asStr() == "desktop" && p["amz"].asBool() && !p["bye"].asBool() && nowS() - (p["at"].asDouble() ?: 0.0) < DESK_SILENT
    }

    /** For the screen: (linked, a short line). */
    fun state(): Pair<Boolean, String> {
        val sn = snap ?: return false to "Not linked"
        if (!sn.first) return false to "Not linked"
        val peer = sn.second
        val at = peer["at"].asDouble()
        if (at == null) return true to "Linked - waiting for the desktop (it must be running)"
        val min = ((nowS() - at) / 60).toInt()
        val heard = if (min <= 1) "just now" else "$min min ago"
        return true to when {
            peer["bye"].asBool() -> "Linked - desktop stopped ($heard). This phone buys."
            nowS() - at >= DESK_SILENT -> "Linked - desktop silent since $heard. This phone buys."
            !peer["amz"].asBool() -> "Linked - desktop auto-buy is off. This phone buys."
            else -> "Linked - desktop buys (heard $heard). This phone stands by."
        }
    }
}
