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

    /** 2.15: the group code of one person's linked devices for the shared checkout lock (the relay) - made from the link secret, so no
     *  one else can guess it; the PC makes the same one (phonelink.py lock_group). */
    fun lockGroup(secret: ByteArray): String = prf(secret, "rk-lock".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
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
    /** 1.0.6: this device's round with the Restalker relay in ONE request - "I am here", and what is new on [reads] ({channel: the last
     *  id read, "" = from the start}). -> {channel: the new posts, each as ntfy hands a message out (id, time, message)}, or null when
     *  the relay was not reached (the link then works over ntfy alone, as before 1.0.6). */
    fun sync(reads: Map<String, String>): Map<String, List<Map<String, Any?>>>? = null
    /** 1.0.6: a sealed post on a channel of the relay -> true when it took it. */
    fun relayPost(channel: String, body: String): Boolean = false
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

class Link(private val store: Store, private val net: LinkNet,
           /** 2.0: what this phone tells the others: amz = it may buy (buying + watching on), av = app version code, au = it updates itself */
           private val me: () -> Map<String, Any?> = { emptyMap() },
           /** 1.0.4: few of the day's free messages are left (NtfyQuota) - the hourly "still here" post waits; a change still goes out. */
           private val saveMessages: () -> Boolean = { false },
           private val clockMs: () -> Long = System::currentTimeMillis) {
    companion object {
        const val SERVER = "https://ntfy.sh"
        const val FILE = "link.json"
        const val BEAT = 60 * 60.0            // the phone posts at least every hour (its products; the desktop needs no faster sign)
        const val DESK_SILENT = 45 * 60.0     // the desktop not heard for this long -> the phone buys
        const val PC_WATCHING = 25 * 60.0     // 1.0.9: the PC heard this lately (post or relay beat) = it is watching now
        const val INLINE_MAX = 3500
        const val TOMB_KEEP = 60 * 86400.0
        const val PEND_RETRY = 5 * 60.0
        const val PHONE_ALIVE = BEAT + 15 * 60.0  // 2.0: another phone / tablet counts as there while heard within this (it posts every hour)
        const val PRES_FRESH = 35 * 60.0          // 1.0.4: a device's beat on the relay (every 15 minutes) counts as "running now" this long
        // 1.0.6 (owner, 2026-11-20: "The ntfy messages arent really needed anymore since the app can handle notifications"): the link
        // runs on the Restalker relay (one request a minute reads it; no daily limit - the free message service allows a home 250
        // messages a day for ALL its devices, and the linked devices' updates alone used them up). The posts are the same sealed
        // posts. ntfy is still used, and only then, for a linked PC / phone that runs a version from before (it reads and posts only
        // there: heard without the mark "rl", it gets this device's posts there too while it is heard), right after linking (the
        // other side's version is not known yet), and whenever the relay is not reached. No device has to update for the link to work.
        const val NTFY_PEER = 3 * 86400.0         // a device that reads only ntfy gets posts there while heard this lately
        const val NTFY_SLOW = 15 * 60.0           // with no such device, ntfy is still read this often (one may link, or come back)
        const val SETTLE = 15 * 60.0              // right after linking, ntfy is read with every round
        fun truthy(x: Any?): Boolean = x == true || ((x as? Number)?.toDouble() ?: 0.0) != 0.0
        private val ASIN = Regex("[A-Z0-9]{10}")
    }

    private val lock = Any()
    private fun nowS() = clockMs() / 1000.0
    /** What the buy decision needs, kept outside the lock (a round can wait on the network; a checkout must never wait for it). */
    @Volatile private var snap: Triple<Boolean, Map<String, Any?>, Long>? = null     // (linked, peer, when read)
    @Volatile private var snapPhones: Pair<String, Map<String, Map<String, Any?>>> = "" to emptyMap()    // 2.0: (my device id, the other phones)
    /** 1.0.4: this link's code and this device's id, kept outside the lock (the presence beat must never wait for a round). */
    @Volatile private var ident: Pair<String, String>? = null
    private fun remember(s: St) {
        snap = Triple(LinkCrypto.parse(s.code) != null, s.peer, clockMs()); snapPhones = s.dev to s.phones.toMap()
        ident = if (LinkCrypto.parse(s.code) != null) s.code to s.dev else null
        onlyNtfy = try { LinkCrypto.parse(s.code) != null && ntfyOnly(s, nowS()) } catch (e: Exception) { false }
    }
    /** 1.0.6: a linked PC / phone still reads only ntfy (a version from before the relay carried the link). */
    @Volatile var onlyNtfy = false; private set
    /** 1.0.6: the free message service still matters for this link - the relay is not reached, or a device reads only ntfy. */
    fun usesNtfy(): Boolean = !relayOk || onlyNtfy

    // ---- 1.0.4: presence (owner, 2026-11-20: "it's showing old data for my pc and tablet. Both of these devices are currently active
    // ... Confirm the devices can see and report active links"). "Heard" was only the time of a device's last post on the link channel -
    // and the free message service refuses posts once its day's 250 are used, so a PC that was running looked silent for hours. Now
    // every linked device also says "I am here" to the Restalker relay every 15 minutes (with the "active now" beat it already sends -
    // no message is used): its link group (made from the link secret; no one else can guess it) and a few words about itself (its name,
    // version and kind), SEALED with the link secret - the relay sees only noise. Each device gets the others' beats back and shows the
    // newest sign of life. Buying decisions still go by the link channel only, as before.
    data class Pres(val d: String, val r: String, val nm: String, val vn: String, val at: Double, val rl: Boolean = false) {
        fun toMap(): Map<String, Any?> = linkedMapOf("d" to d, "r" to r, "nm" to nm, "vn" to vn, "at" to at, "rl" to rl)
    }
    private val PRES_FILE = "presence.json"
    @Volatile private var pres: Map<String, Pres> = try {
        (MiniJson.obj(store.readFile(PRES_FILE)) ?: emptyMap()).values.mapNotNull { v ->
            val m = v.asMap() ?: return@mapNotNull null
            Pres(m["d"].asStr() ?: return@mapNotNull null, m["r"].asStr() ?: "", m["nm"].asStr() ?: "", m["vn"].asStr() ?: "", m["at"].asDouble() ?: 0.0, truthy(m["rl"]))
        }.associateBy { it.d }
    } catch (e: Exception) { emptyMap() }

    /** What this device says about itself on the relay: sealed with the link secret (null when not linked). */
    fun who(): String? {
        val (code, dev) = ident ?: return null
        val (_, sec) = LinkCrypto.parse(code) ?: return null
        val mine = try { me() } catch (e: Exception) { emptyMap() }
        return LinkCrypto.seal(sec, MiniJson.write(linkedMapOf("d" to dev, "r" to "phone", "nm" to (mine["nm"] as? String ?: "").take(40),
            "vn" to (mine["vn"] as? String ?: "").take(12), "rl" to 1)))          // (1.0.6: rl - it reads the relay)
    }

    /** The other devices' beats from the relay: (what each said - sealed, when the relay heard it in seconds). Anything not sealed with
     *  THIS link's secret is ignored. -> how many were taken. */
    fun takePresence(beats: List<Pair<String, Double>>): Int {
        val (code, dev) = ident ?: return 0
        val (_, sec) = LinkCrypto.parse(code) ?: return 0
        val now = nowS()
        val out = HashMap(pres)
        var n = 0
        for ((w, at) in beats.take(40)) {
            val m = MiniJson.obj(LinkCrypto.unseal(sec, w) ?: continue) ?: continue
            val d = (m["d"].asStr() ?: continue).take(20)
            if (d.isEmpty() || d == dev || at <= 0 || at > now + 600) continue
            val old = out[d]
            if (old == null || at >= old.at) out[d] = Pres(d, (m["r"].asStr() ?: "").take(10), (m["nm"].asStr() ?: "").take(40), (m["vn"].asStr() ?: "").take(12), at, truthy(m["rl"]))
            n++
        }
        out.keys.filter { now - (out[it]?.at ?: 0.0) > 30 * 86400 }.forEach { out.remove(it) }
        if (out != pres) { pres = out; try { store.writeFile(PRES_FILE, MiniJson.write(out.mapValues { it.value.toMap() })) } catch (e: Exception) { } }
        return n
    }
    private fun forgetPresence() { pres = emptyMap(); try { store.writeFile(PRES_FILE, "{}") } catch (e: Exception) { } }

    /** The PC's newest sign of life in seconds: its last post on the link channel or its beat on the relay (0 = never). */
    private fun pcSeen(peer: Map<String, Any?>): Double =
        maxOf(peer["at"].asDouble() ?: 0.0, pres.values.filter { it.r == "desktop" }.maxOfOrNull { it.at } ?: 0.0)

    // ---- the saved state (link.json)
    private class St(var code: String = "", var dev: String = "", var since: String = "", val recs: LinkedHashMap<String, LinkRec> = LinkedHashMap(),
                     var peer: Map<String, Any?> = emptyMap(), var linkedAt: Double = 0.0, var pubAt: Double = 0.0, var dirty: Boolean = false,
                     val phones: LinkedHashMap<String, Map<String, Any?>> = LinkedHashMap(),
                     /** 1.0.6: where reading goes on at the relay - the link channel, and its fast channel */
                     var rsince: String = "", var gsince: String = "",
                     /** when ntfy was last read, and since when a post is owed to a device that reads only ntfy (0 = none) */
                     var nAt: Double = 0.0, var owed: Double = 0.0)

    private fun load(): St {
        val m = MiniJson.obj(store.readFile(FILE)) ?: return St()
        val st = St(m["code"].asStr() ?: "", m["dev"].asStr() ?: "", m["since"].asStr() ?: "", LinkedHashMap(), m["peer"].asMap() ?: emptyMap(),
            m["linkedAt"].asDouble() ?: 0.0, m["pubAt"].asDouble() ?: 0.0, m["dirty"].asBool())
        m["recs"].asMap()?.values?.forEach { v -> v.asMap()?.let(LinkRec::fromMap)?.let { st.recs[it.a] = it } }
        m["phones"].asMap()?.forEach { (k, v) -> v.asMap()?.let { st.phones[k] = it } }
        st.rsince = m["rsince"].asStr() ?: ""; st.gsince = m["gsince"].asStr() ?: ""
        st.nAt = m["nAt"].asDouble() ?: 0.0; st.owed = m["owed"].asDouble() ?: 0.0
        return st
    }
    private fun save(s: St) = store.writeFile(FILE, MiniJson.write(linkedMapOf("code" to s.code, "dev" to s.dev, "since" to s.since,
        "recs" to s.recs.mapValues { it.value.toMap() }, "peer" to s.peer, "linkedAt" to s.linkedAt, "pubAt" to s.pubAt, "dirty" to s.dirty,
        "phones" to s.phones, "rsince" to s.rsince, "gsince" to s.gsince, "nAt" to s.nAt, "owed" to s.owed)))

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
        save(s); remember(s); forgetPresence()
        store.log("Linked with the desktop. Products are shared from now on; the desktop buys while it runs.")
        null
    }

    fun unlink() = synchronized(lock) {
        val s = load()
        if (LinkCrypto.parse(s.code) != null) { try { postNow(s, bye = true) } catch (e: Exception) {} }
        store.writeFile(FILE, "{}"); snap = Triple(false, emptyMap(), clockMs()); ident = null; forgetPresence()
        store.log("Unlinked from the desktop - this phone buys on its own again.")
    }

    // ---- what this phone has now
    private fun localItems(): Map<String, LinkRec> {
        val bought = HashMap<String, Double>()
        for (pu in store.purchases()) if (pu.asin.isNotBlank()) bought[pu.asin] = maxOf(bought[pu.asin] ?: 0.0, pu.at / 1000.0)
        val out = LinkedHashMap<String, LinkRec>()
        for (p in store.products()) {
            if (!ASIN.matches(p.asin) || p.cap <= 0 || out.containsKey(p.asin) || p.id.startsWith(Engine.PRACTICE_ID)) continue   // (2.0: a practice item is not shared)
            // (a checkout running here, or a Place order pressed: not "Buy" for the desktop - the same product is never ordered twice)
            out[p.asin] = LinkRec(p.asin, p.label, p.cap, p.buy && !p.attempted && !p.inProgress, p.watch, p.kind, 0.0, false, bought[p.asin] ?: 0.0, here = true, q = p.qty)
        }
        return out
    }

    /** 1.0.8: does the PC's record come from after this phone's last order? A purchase here: the record carries its time (ba). An order
     *  whose result is unclear (no purchase written): the record is newer than the moment Place order was pressed. */
    private fun rearmHeard(n: LinkRec, p: Product): Boolean {
        val mine = store.purchases().filter { it.asin == n.a }.maxOfOrNull { it.at / 1000.0 } ?: 0.0
        if (mine > 0) return n.ba + 1 >= mine
        return p.submittedAt <= 0 || n.t * 1000 > p.submittedAt
    }

    /** A change from the desktop, made here. -> true when this phone's own state must go back to the PC at once (1.0.8: a PC record
     *  from before it heard of this phone's latest order). */
    private fun apply(n: LinkRec, old: LinkRec?, linkedAt: Double): Boolean {
        val p = store.products().firstOrNull { it.asin == n.a }
        if (n.g) {
            if (p != null) { store.remove(p.id); store.log("From the desktop: ${p.label} removed.") }
            return false
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
            return false
        }
        val what = ArrayList<String>()
        // 1.0.8: "Buy on" in a PC record that does not know yet that this phone ordered it is not a re-arm - Buy stays as it is here and
        // this phone's "bought, Buy off" goes to the PC in this same round (before 1.0.8 it waited for the retry, minutes)
        val stale = n.b && !boughtThere && (p.purchased || p.attempted) && !rearmHeard(n, p)
        if (Math.abs(p.cap - n.c) >= 0.005) what.add("cap \$${Engine.money(n.c)}")
        if (p.watch != n.w) what.add("Watch " + if (n.w) "on" else "off")
        if (p.buy != n.b && !boughtThere && !stale) what.add("Buy " + if (n.b) "on" else "off")
        if (boughtThere) what.add("BOUGHT by the desktop - not bought here again")
        if (p.qty != n.q) what.add("Buy ${n.q}")
        store.update(p.id) {
            var q = it.copy(cap = n.c, watch = n.w, buy = if (boughtThere) false else if (stale) it.buy else n.b)
            if (it.qty != n.q) q = q.copy(qty = n.q, qtySince = clockMs())
            if (n.b && !it.buy && !boughtThere && !stale) q = q.copy(fails = 0, lockedUntil = 0)
            // 1.0.8 (owner: "When I rearm something, it automatically switches back to just watching"): Buy set on the PC for a product
            // this phone bought = re-armed there. Until 1.0.7 Buy came on here but the "bought" lock stayed, so this phone told the PC
            // "Buy off" and the PC went back to watching. The lock is cleared only when the PC's record already knows this phone's latest
            // purchase (n.ba) - a PC record from before it heard of the purchase never re-arms it (one order per product) - and never
            // while a checkout runs here
            if (n.b && !it.buy && !boughtThere && !stale && !it.inProgress && (it.purchased || it.attempted)) {
                q = q.copy(purchased = false, attempted = false, orderNumber = null, fails = 0, lockedUntil = 0, status = "re-armed on the PC - it may be bought again")
                what.add("re-armed (it may be bought again)")
            }
            q
        }
        if (what.isNotEmpty()) store.log("From the desktop: ${p.label}: ${what.joinToString(", ")}.")
        return stale
    }

    // ---- merging (the desktop's merge / scan)
    private fun merge(s: St, msg: Map<String, Any?>, srvTime: Double?, now: Double): List<Triple<String, LinkRec, LinkRec?>> {
        val out = ArrayList<Triple<String, LinkRec, LinkRec?>>()
        if (msg["d"].asStr() == s.dev) return out
        // 2.0 (owner: "Confirm i have the ability to link the app to multiple mobile devices"): the desktop is "the peer"; another phone
        // or tablet on the same code is kept apart (1.4-1.9 took any other device as the peer - a second phone looked like a silent desktop)
        if (msg["r"].asStr() == "phone") {
            val d = (msg["d"].asStr() ?: "").take(20)
            if (d.isNotEmpty() && d !in s.phones && !truthy(msg["rl"])) s.dirty = true     // (1.0.6: new here and reads only ntfy: it gets ours there at once)
            if (d.isNotEmpty()) s.phones[d] = linkedMapOf("at" to (srvTime ?: now), "amz" to msg["amz"].asBool(), "bye" to msg["bye"].asBool(),
                "av" to (msg["av"].asLong() ?: 0L), "nm" to (msg["nm"].asStr() ?: "").take(40), "bm" to (msg["bm"].asStr() ?: "").take(8),
                "fo" to (msg["fo"] != false), "vn" to (msg["vn"].asStr() ?: "").take(12),
                "lk" to msg["lk"].asBool(),                                           // 2.15: it takes part in the shared checkout lock
                "rl" to truthy(msg["rl"]), "nf" to truthy(msg["nf"]),                 // 1.0.6: it reads the relay (nothing on ntfy for it); it knows one that does not
                "nt" to (msg["nt"].asStr()?.takeIf { NtfyAlerts.ok(it) } ?: ""),     // 2.9: that device's alerts channel
                // 2.4: that device's purchases (a post without them keeps the last list heard)
                "buys" to (if (msg.containsKey("buys")) Buys.clean(msg["buys"], Buys.MAX_SENT) else s.phones[d]?.get("buys").asList()))
            // 2.3 (owner: "multiple devices should be able to independently determine who buys ... In settings, there should be an option
            // to see all devices linked, with options to control the above buying selection manually/remotely"): a change of THIS device's
            // choice made on another device
            msg["set"].asMap()?.let { st -> if (st["d"].asStr() == s.dev) takeSet(st, (msg["nm"].asStr() ?: d).take(40)) }
            s.phones.keys.filter { (s.phones[it]?.get("at").asDouble() ?: 0.0) < now - 30 * 86400 }.forEach { s.phones.remove(it) }
        } else {
            s.peer = linkedMapOf("d" to (msg["d"].asStr() ?: "").take(20), "r" to (msg["r"].asStr() ?: "").take(10), "at" to (srvTime ?: now),
                "amz" to msg["amz"].asBool(), "bye" to msg["bye"].asBool(), "n" to (msg["items"].asList().size),
                "hand" to msg["hand"].asBool(),          // 2.3: the PC does not buy on Amazon because a phone / tablet buys instead
                // 2.4: the PC's purchase list - it sends it only when it changed (or every 6 hours): kept until then
                "buys" to (if (msg.containsKey("buys")) Buys.clean(msg["buys"]) else s.peer["buys"].asList()),
                "mst" to msg["mst"].asBool(),            // 2.7: the PC is the owner's (no purchase limit on its linked devices)
                "lk" to msg["lk"].asBool(),              // 2.15: the PC takes part in the shared checkout lock
                "rl" to truthy(msg["rl"]), "nf" to truthy(msg["nf"]),   // 1.0.6: the PC reads the relay (v147 or newer); it knows a device that does not
                "nt" to (msg["nt"].asStr()?.takeIf { NtfyAlerts.ok(it) } ?: ""),     // 2.9: the PC's alerts channel ("Phone: set up")
                // 2.7: the TCG price history the PC recorded - sent only when it changed: kept until then
                "th" to (if (msg.containsKey("th")) cleanHistory(msg["th"]) else s.peer["th"].asMap() ?: emptyMap<String, Any?>()))
            // 1.0.4 (owner: "the pc version does not appear to have an option to see linked devices"): the PC lists the linked devices
            // too and can change a device's buying choice from there - taken the same way as a change made on another phone
            msg["set"].asMap()?.let { st -> if (st["d"].asStr() == s.dev) takeSet(st, "the PC") }
        }
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
                    tcgUrl = m["tu"].asStr()?.takeIf { Tcg.okUrl(it) } ?: q.tcgUrl,          // 2.4: the TCGplayer page of that price
                    others = m["sx"].asList().mapNotNull { it.asMap() }.take(8).map { o ->
                        linkedMapOf("s" to (o["s"].asStr() ?: "").take(20), "st" to (o["st"].asStr() ?: "").take(5), "pr" to o["pr"].asDouble(),
                            "at" to ((o["at"].asDouble() ?: 0.0) * 1000).toLong(),
                            "u" to o["u"].asStr()?.takeIf { it.startsWith("https://") && it.length <= 400 }) })     // 2.2: that store's own page
            }
            // 1.7: the desktop table's picture of the product
            if (m.containsKey("im")) q = q.copy(dImg = Pics.desk(m["im"].asStr()) ?: "")
            if (q != p) store.update(p.id) { cur -> cur.copy(year = q.year, set = q.set, name = q.name, dSt = q.dSt, dPr = q.dPr, dAt = q.dAt,
                msrp = q.msrp, tcg = q.tcg, tcgUrl = q.tcgUrl, others = q.others, dImg = q.dImg) }
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
    private fun postNow(s: St, bye: Boolean = false, hi: Boolean = false, now: Double = nowS(), ntfyOk: Boolean = true) {
        val (ch, sec) = LinkCrypto.parse(s.code) ?: return
        val mine = try { me() } catch (e: Exception) { emptyMap() }
        val o = linkedMapOf<String, Any?>("v" to 1, "d" to s.dev, "r" to "phone", "at" to now, "amz" to (mine["amz"] == true && !bye),
            "av" to mine["av"], "au" to (mine["au"] == true), "um" to (mine["um"].asStr() ?: "").take(10),
            "nt" to (mine["nt"].asStr()?.takeIf { NtfyAlerts.ok(it) } ?: ""), "items" to s.recs.values.map { it.post() },
            "bm" to store.settings().buyMode, "bmAt" to store.settings().buyModeAt / 1000.0,        // 2.3: THIS device's choice (each its own)
            "fo" to store.settings().failover, "nm" to (mine["nm"] as? String ?: "").take(40), "vn" to (mine["vn"] as? String ?: "").take(12),
            "buys" to Buys.post(store.purchases()), "lk" to true,              // 2.15: this app takes part in the shared checkout lock
            "rl" to (if (relayOk) 1 else 0),     // 1.0.6: this device reads the relay right now (0: it is not reached - find it on ntfy)
            // ... and a device that reads only ntfy is on this link (heard there by this one): the others then look there with every round
            "nf" to (if (ntfyOnly(s, now)) 1 else 0))
        val set = pendingSet
        set?.let { o["set"] = it }
        if (bye) o["bye"] = true
        if (hi) o["hi"] = true
        val body = LinkCrypto.seal(sec, MiniJson.write(o))
        // 1.0.6: on the relay - and on ntfy too when a linked device reads only there, the other side is not known yet, or the relay
        // did not take it. ntfyOk = false: few of the day's free messages are left and this post can wait there.
        val took = try { net.relayPost(ch, body) } catch (e: Exception) { false }
        if (took) relayPosts++
        val old = ntfyNeeded(s, now)
        if (!took || (old && (ntfyOk || bye))) {
            val st = try { net.post("$SERVER/$ch", body, body.length > INLINE_MAX) } catch (e: Exception) { if (!took) throw e; -1 }
            if (st in 200..299) { ntfyPosts++; s.owed = 0.0 }
            else if (!took) throw java.io.IOException("the link server answered $st")
            else if (s.owed <= 0) s.owed = now   // (the relay has it; the older device gets it with a later post)
        } else if (old && s.owed <= 0) s.owed = now
        if (set != null && pendingSet === set) pendingSet = null              // (kept for the next post when this one failed)
        s.pubAt = now; s.dirty = false
    }

    // ---- 1.0.6: which way (the relay first; ntfy only when needed)
    @Volatile var relayOk = false; private set
    @Volatile var relayPosts = 0; private set
    @Volatile var ntfyPosts = 0; private set
    @Volatile var relayLines = 0; private set
    @Volatile var ntfyLines = 0; private set
    /** The fast channel's messages from the PC (go / res / al), each once - the app gives what to do with them (GoListener.take). */
    @Volatile var onGo: ((Map<String, Any?>) -> Unit)? = null
    private val seenPosts = LinkedHashSet<String>()
    /** A sealed post not taken in yet this run (the same post may come by the relay and by ntfy). */
    fun fresh(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
        synchronized(seenPosts) {
            if (!seenPosts.add(h)) return false
            if (seenPosts.size > 400) { val old = seenPosts.iterator(); repeat(100) { _ -> if (old.hasNext()) { old.next(); old.remove() } } }
            return true
        }
    }
    private fun rlOf(d: String, rec: Map<String, Any?>?): Boolean {
        val pr = pres[d]
        return truthy(rec?.get("rl")) || (pr != null && pr.rl && pr.at >= (rec?.get("at").asDouble() ?: 0.0))
    }
    /** Something still has to go over ntfy: a linked PC / phone heard in the last 3 days that reads only there (no mark "rl" on its
     *  last post or its beat), or nobody heard yet in the first day after linking (the other side's version is not known). */
    private fun ntfyNeeded(s: St, now: Double): Boolean = ntfyOnly(s, now) || (s.peer.isEmpty() && s.phones.isEmpty() && now - s.linkedAt < 86400)
    /** A linked PC / phone that reads only ntfy, as this device itself heard it. */
    private fun ntfyOnly(s: St, now: Double): Boolean {
        val pd = s.peer["d"].asStr() ?: ""
        if (s.peer.isNotEmpty() && now - (s.peer["at"].asDouble() ?: 0.0) < NTFY_PEER && !rlOf(pd, s.peer)) return true
        return s.phones.any { (d, v) -> d != s.dev && now - (v["at"].asDouble() ?: 0.0) < NTFY_PEER && !rlOf(d, v) }
    }
    /** A device that reads the relay says it heard one that reads only ntfy (its mark "nf"): ntfy is read with every round too. */
    private fun toldOfNtfy(s: St, now: Double): Boolean =
        (truthy(s.peer["nf"]) && truthy(s.peer["rl"]) && now - (s.peer["at"].asDouble() ?: 0.0) < NTFY_PEER) ||
            s.phones.any { (d, v) -> d != s.dev && truthy(v["nf"]) && truthy(v["rl"]) && now - (v["at"].asDouble() ?: 0.0) < NTFY_PEER }
    /** The linked PC reads the relay (v147 or newer) - GoListener: its heartbeat needs no ntfy message then. */
    fun pcReadsRelay(): Boolean { val p = snap?.second ?: return false; return p.isNotEmpty() && rlOf(p["d"].asStr() ?: "", p) }
    /** For the report: how the link travels. */
    fun wayText(): String = "the link's way (1.0.6): relay ${if (relayOk) "reached" else "NOT reached - everything over the message service"}, " +
        "$relayPosts posts and $relayLines read there; message service: $ntfyPosts posts, $ntfyLines read"

    /** 1.0.6: a message on the fast channel from this device (its "I am listening" heartbeat): on the relay when the PC reads it there,
     *  otherwise - or when the relay does not take it - on ntfy as before. Never waits for a round. -> true when one of them took it. */
    fun postGo(o: Map<String, Any?>): Boolean {
        val (code, dev) = ident ?: return false
        val (ch, sec) = LinkCrypto.parse(code) ?: return false
        val body = LinkCrypto.seal(sec, MiniJson.write(LinkedHashMap(o).also { it["v"] = 1; it["d"] = dev; it["r"] = "phone"; it["at"] = nowS() }))
        val took = try { net.relayPost("$ch-go", body) } catch (e: Exception) { false }
        if (took && pcReadsRelay()) return true
        return (try { net.post("$SERVER/$ch-go", body, false) } catch (e: Exception) { -1 }) in 200..299 || took
    }

    /** The relay's round: the link channel and its fast channel in one request. -> the other devices' posts, or null (not reached). */
    private fun readRelay(s: St): List<Pair<Map<String, Any?>, Double?>>? {
        val (ch, sec) = LinkCrypto.parse(s.code) ?: return emptyList()
        val got = net.sync(linkedMapOf(ch to s.rsince, "$ch-go" to s.gsince)) ?: return null
        val out = ArrayList<Pair<Map<String, Any?>, Double?>>()
        for (m in got[ch] ?: emptyList()) {
            m["id"].asStr()?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }?.let { s.rsince = it }
            val text = m["message"].asStr() ?: continue
            val o = MiniJson.obj(LinkCrypto.unseal(sec, text) ?: continue) ?: continue
            if (o["d"].asStr() != s.dev && fresh(text)) { out.add(o to m["time"].asDouble()); relayLines++ }
        }
        for (m in got["$ch-go"] ?: emptyList()) {
            m["id"].asStr()?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }?.let { s.gsince = it }
            val text = m["message"].asStr() ?: continue
            val o = MiniJson.obj(LinkCrypto.unseal(sec, text) ?: continue) ?: continue
            if (o["r"].asStr() == "desktop" && fresh(text)) try { onGo?.invoke(o) } catch (e: Exception) { }
        }
        return out
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
            if (o["d"].asStr() != s.dev && fresh(text)) { out.add(o to m["time"].asDouble()); ntfyLines++ }
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
        // 1.0.6: the relay's round first; ntfy too when a device that reads only there is linked, right after linking, when the relay
        // was not reached, or 15 minutes have passed (an older device may have linked, or come back)
        var why = ""
        val rm = try { readRelay(s) } catch (e: Exception) { why = "relay: ${e.message?.take(60)}"; null }
        relayOk = rm != null
        val old = ntfyNeeded(s, now)
        val nobody = s.peer.isEmpty() && s.phones.isEmpty()
        val ntfyDue = rm == null || old || toldOfNtfy(s, now) || rm.any { truthy(it.first["nf"]) } || now - s.linkedAt < SETTLE || (nobody && now - s.nAt >= 120) || now - s.nAt >= NTFY_SLOW
        val nm = if (!ntfyDue) null else try { read(s).also { s.nAt = now } } catch (e: Exception) { why = e.message?.take(80) ?: "no answer"; null }
        if (rm == null && nm == null) { save(s); return "read failed: $why" }
        val msgs = ((rm ?: emptyList()) + (nm ?: emptyList())).sortedBy { it.second ?: (it.first["at"].asDouble() ?: 0.0) }
        for ((m, srv) in msgs) {
            for ((_, n, old) in merge(s, m, srv, now)) {
                try { if (apply(n, old, s.linkedAt)) s.recs[n.a]?.let { s.recs[n.a] = it.copy(pend = 0.0) } } catch (e: Exception) { store.log("! A change from the desktop could not be made: ${e.message?.take(100)}") }
            }
            try { takeGroups(m) } catch (e: Exception) { }
        }
        scan(s, localItems(), now)
        var r = "ok"
        // (1.0.4: the hourly "still here" waits while few of the day's free messages are left - the relay beat says it meanwhile)
        // (1.0.6: with the relay reached the hourly post always goes - there; only its copy for a device that reads only ntfy waits)
        val low = try { saveMessages() } catch (e: Exception) { false }
        if (s.owed > 0 && !ntfyNeeded(s, now)) s.owed = 0.0
        val owed = s.owed > 0 && !low && now - s.owed >= 600                           // (a post an older device still has to get)
        val urgent = s.dirty || first || owed
        if (urgent || (now - s.pubAt >= BEAT && (relayOk || !low))) {
            try { postNow(s, hi = first, now = now, ntfyOk = urgent || !low) } catch (e: Exception) { r = "post failed: ${e.message?.take(80)}" }
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

    /** 2.0: another phone / tablet on this link is the buyer - one that may buy, heard within PHONE_ALIVE, not stopped, whose device id
     *  comes first (the same rule on every phone: exactly one of them buys while the desktop does not). */
    fun otherPhoneBuys(): Boolean {
        if (snap?.first != true) return false
        val (dev, ph) = snapPhones
        val t = nowS()
        return ph.any { (d, v) -> d != dev && v["amz"].asBool() && !v["bye"].asBool() && t - (v["at"].asDouble() ?: 0.0) < PHONE_ALIVE && d < dev }
    }

    /** 2.15 (owner: "Its a race to checkout, so whichever happens first is the winner and should lock the product normally"): every
     *  linked device that may buy takes part in the shared checkout lock - the PC (when it was ever heard) and every phone / tablet heard
     *  lately. Only then do they race; with a device that does not know the lock yet, the 2.2 rules stay (never two orders). */
    fun raceReady(): Boolean {
        val sn = snap ?: return false
        if (!sn.first) return false
        val p = sn.second
        if (p["r"].asStr() == "desktop" && !p["lk"].asBool()) return false
        val (dev, ph) = snapPhones
        val t = nowS()
        return ph.none { (d, v) -> d != dev && v["amz"].asBool() && !v["bye"].asBool() && t - (v["at"].asDouble() ?: 0.0) < PHONE_ALIVE && !v["lk"].asBool() }
    }

    /** 2.15: this link's group code for the shared checkout lock (null when not linked). */
    /** (1.0.4: read from what is kept outside the lock - 2.15 - 1.0.3 took the link's lock here, so a claim right before Place your order
     *  could wait for a link round that was talking to the network) */
    fun lockGroup(): String? = ident?.first?.let { LinkCrypto.parse(it) }?.second?.let { LinkCrypto.lockGroup(it) }

    /** 2.0: someone else buys (the desktop, or another phone first in line) - this phone stands by. */
    fun othersBuy(): Boolean = desktopBuys() || otherPhoneBuys()

    /** 1.0.9: the PC is watching RIGHT NOW - it buys on Amazon (desktopBuys) and was heard within PC_WATCHING (its relay beat comes
     *  every 15 minutes, its post every 30). Then a "Both buy" phone looks by itself only every Engine.WITH_PC_MS. */
    fun pcWatchesNow(): Boolean {
        val sn = snap ?: return false
        return desktopBuys() && nowS() - pcSeen(sn.second) < PC_WATCHING
    }

    /** 2.0: the other phones / tablets heard within PHONE_ALIVE. */
    fun otherPhones(): Int { val (dev, ph) = snapPhones; val t = nowS(); return ph.count { (d, v) -> d != dev && t - (v["at"].asDouble() ?: 0.0) < PHONE_ALIVE } }

    /** 2.3: a change of this device's choice made on another linked device (the newest change wins). */
    private fun takeSet(st: Map<String, Any?>, from: String) {
        val bm = st["bm"].asStr()?.takeIf { it in Settings.BUY_MODES } ?: return
        val atMs = ((st["at"].asDouble() ?: return) * 1000).toLong()
        val cur = store.settings()
        if (atMs <= cur.buyModeAt) return
        val fo = if (st.containsKey("fo")) st["fo"] != false else cur.failover
        store.setSettings { it.copy(buyMode = bm, buyModeAt = atMs, failover = fo) }
        store.log("Who buys on Amazon here: ${modeWord(bm)} (changed on $from).")
    }

    /** 2.3: the PC does not buy on Amazon because a linked phone / tablet buys there instead (not because it stopped). */
    fun pcHanded(): Boolean {
        val sn = snap ?: return false
        if (!sn.first) return false
        val p = sn.second
        return p["r"].asStr() == "desktop" && !p["amz"].asBool() && p["hand"].asBool() && !p["bye"].asBool() && nowS() - (p["at"].asDouble() ?: 0.0) < DESK_SILENT
    }

    /** 2.3: every linked device for Settings - the PC, this device and the other phones / tablets: (id, kind, name, choice, takes over,
     *  app version, heard seconds ago or -1). */
    data class Device(val id: String, val pc: Boolean, val me: Boolean, val name: String, val mode: String, val failover: Boolean, val av: Long, val agoS: Long,
                      val vn: String = "",
                      /** 1.0.4: seconds since its last post on the link channel (products, caps, choices) - -1 = none yet. [agoS] is the
                       *  newest sign of life (that post or its beat on the relay). */
                      val syncAgoS: Long = 0)

    /** 1.0.4: the PC is running (its beat on the relay is fresh) but its posts on the link channel are not arriving - seconds since the
     *  last one, or -1 when all is well / not linked / never heard. The screen then says so (and why, when known) instead of "silent". */
    fun pcSyncBehindS(): Long {
        val sn = snap ?: return -1
        if (!sn.first) return -1
        val post = sn.second["at"].asDouble() ?: return -1
        val beat = pres.values.filter { it.r == "desktop" }.maxOfOrNull { it.at } ?: return -1
        val t = nowS()
        return if (t - post >= DESK_SILENT && t - beat < PRES_FRESH && beat > post) (t - post).toLong() else -1
    }

    /** 2.9: the alerts channels the linked devices use: (the PC's or null, the other phones' / tablets'). */
    fun alertChannels(): Pair<String?, List<String>> {
        val sn = snap
        val pc = if (sn != null && sn.first) sn.second["nt"].asStr()?.takeIf { NtfyAlerts.ok(it) } else null
        val ph = if (sn != null && sn.first) snapPhones.second.values.mapNotNull { it["nt"].asStr()?.takeIf { t -> NtfyAlerts.ok(t) } } else emptyList()
        return pc to ph
    }

    /** 2.7: the linked PC is the owner's (it said so within the last 30 days). */
    fun pcIsMaster(): Boolean {
        val sn = snap ?: return false
        if (!sn.first) return false
        return sn.second["mst"].asBool() && nowS() - (sn.second["at"].asDouble() ?: 0.0) < 30 * 86400
    }

    /** 2.7: the TCG price history of a product (from the linked PC): [(day number, price)] oldest first, or empty. */
    fun tcgHistory(asin: String): List<Pair<Long, Double>> {
        val sn = snap ?: return emptyList()
        if (!sn.first) return emptyList()
        return sn.second["th"].asMap()?.get(asin).asList().mapNotNull { x ->
            val l = x.asList(); val d = l.getOrNull(0).asLong(); val p = l.getOrNull(1).asDouble()
            if (d != null && p != null) d to p else null }
    }

    private fun cleanHistory(x: Any?): Map<String, Any?> = (x.asMap() ?: emptyMap()).entries.filter { ASIN.matches(it.key) }.take(300).associate { (k, v) ->
        k to v.asList().mapNotNull { pt -> val l = pt.asList(); val d = l.getOrNull(0).asLong(); val p = l.getOrNull(1).asDouble()
            if (d != null && d in 10_000..40_000 && p != null && p > 0 && p < 100000) listOf(d, p) else null }.takeLast(400) }

    /** 2.4: every purchase of every linked device (Bought): this device's own, the PC's and the other phones' / tablets'. Not linked:
     *  this device's own. */
    fun buys(myName: String): List<Buy> {
        val own = Buys.mine(store.purchases(), myName)
        val sn = snap
        if (sn == null || !sn.first) return own
        val pc = Buys.fromLink(sn.second["buys"].asList().mapNotNull { it.asMap() }, "PC")
        val (dev, ph) = snapPhones
        val others = ph.filter { it.key != dev }.map { (d, v) ->
            Buys.fromLink(v["buys"].asList().mapNotNull { it.asMap() }, (v["nm"].asStr() ?: "").ifBlank { "Phone / tablet ${d.takeLast(4)}" }) }
        return Buys.all(listOf(own, pc) + others)
    }

    fun devices(myName: String): List<Device> {
        val sn = snap ?: return emptyList()
        if (!sn.first) return emptyList()
        val (dev, ph) = snapPhones
        val t = nowS()
        val out = ArrayList<Device>()
        // 1.0.4: "heard" = the newest sign of life - a post on the link channel or the beat on the relay; [syncAgoS] = the link post alone
        val pr = pres
        val pcAt = pcSeen(sn.second)
        val pcPost = sn.second["at"].asDouble() ?: 0.0
        if (pcAt > 0) out += Device(sn.second["d"].asStr() ?: "pc", true, false, "PC (Restalker)", "", false, 0, (t - pcAt).toLong(),
            pr.values.filter { it.r == "desktop" }.maxByOrNull { it.at }?.vn ?: "", syncAgoS = if (pcPost > 0) (t - pcPost).toLong() else -1)
        val st = store.settings()
        out += Device(dev, false, true, myName, st.buyMode, st.failover, 0, 0)
        ph.filter { (d, v) -> d != dev && t - maxOf(v["at"].asDouble() ?: 0.0, pr[d]?.at ?: 0.0) < 30 * 86400 }.forEach { (d, v) ->
            val post = v["at"].asDouble() ?: 0.0
            val p2 = pr[d]
            val newest = maxOf(post, p2?.at ?: 0.0)
            // (the version: whichever sign of life is newer says it - a tablet that updated since its last post shows its new version)
            val vn = if (p2 != null && p2.at >= post && p2.vn.isNotBlank()) p2.vn else (v["vn"].asStr() ?: "")
            out += Device(d, false, false, (v["nm"].asStr() ?: "").ifBlank { p2?.nm ?: "" }.ifBlank { "Phone / tablet ${d.takeLast(4)}" }, (v["bm"].asStr() ?: "").ifBlank { "pc" },
                v["fo"] != false, v["av"].asLong() ?: 0, (t - newest).toLong(), vn, syncAgoS = (t - post).toLong())
        }
        // a phone / tablet known only by its beat (linked, but its first post has not arrived here yet): shown, without a choice to change
        pr.values.filter { it.r != "desktop" && it.d != dev && !ph.containsKey(it.d) && t - it.at < 30 * 86400 }.sortedBy { it.d }.forEach { p2 ->
            out += Device(p2.d, false, false, p2.nm.ifBlank { "Phone / tablet ${p2.d.takeLast(4)}" }, "", true, 0, (t - p2.at).toLong(), p2.vn, syncAgoS = -1)
        }
        return out
    }

    /** 2.3: change another device's choice from here (it takes it at its next round; the newest change wins). */
    fun setFor(target: String, bm: String, failover: Boolean): Boolean = synchronized(lock) {
        if (bm !in Settings.BUY_MODES) return false
        val s = load()
        if (LinkCrypto.parse(s.code) == null) return false
        pendingSet = linkedMapOf("d" to target, "bm" to bm, "fo" to failover, "at" to nowS())
        s.phones[target]?.let { s.phones[target] = LinkedHashMap(it).also { m -> m["bm"] = bm; m["fo"] = failover } }   // (shown at once)
        s.dirty = true
        try { postNow(s) } catch (e: Exception) { save(s); remember(s); return false }
        save(s); remember(s)
        true
    }
    @Volatile private var pendingSet: Map<String, Any?>? = null

    /** 2.2: where the fast channel is: (channel, secret, this device's id) - null when not linked. */
    fun goTarget(): Triple<String, ByteArray, String>? = synchronized(lock) {
        val s = load()
        val (ch, sec) = LinkCrypto.parse(s.code) ?: return null
        Triple("$ch-go", sec, s.dev)
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
        val others = otherPhones()
        val also = if (others > 0) " ($others other phone${if (others == 1) "" else "s"} / tablet linked)" else ""
        val mine = if (otherPhoneBuys()) "Another phone / tablet buys; this one stands by." else "This phone buys."
        val behind = pcSyncBehindS()
        return true to when {
            // 1.0.4: the PC IS running (the relay heard it) - only its product updates are not arriving on the link channel
            behind >= 0 -> "Linked - the PC is running, but its product updates are not arriving (last ${Ago.words(behind * 1000)}). $mine$also"
            peer["bye"].asBool() -> "Linked - desktop stopped ($heard). $mine$also"
            nowS() - at >= DESK_SILENT -> "Linked - desktop silent since $heard. $mine$also"
            store.settings().buyMode == "phone" -> "Linked - the PC watches and signals this device within seconds (heard $heard). $mine$also"
            peer["hand"].asBool() && !peer["amz"].asBool() -> "Linked - the PC hands Amazon to another phone / tablet (heard $heard). " +
                (if (store.settings().buyMode == "both") mine else "This one only watches.") + also
            !peer["amz"].asBool() -> "Linked - desktop auto-buy is off. $mine$also"
            store.settings().buyMode == "both" -> "Linked - the PC and this device both buy (heard $heard); this one orders only if the PC's try failed.$also"
            else -> "Linked - desktop buys (heard $heard). This device only watches" + (if (store.settings().failover) " (it takes over if the PC stops)." else ".") + also
        }
    }
}

/** 2.2: Who buys on Amazon, in words. */
fun modeWord(m: String): String = when (m) { "phone" -> "the phone"; "both" -> "both"; else -> "the PC" }
