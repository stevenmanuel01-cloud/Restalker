package com.restalker.mobile.core

/** 1.0 (owner, 2026-11-19: "We are close enough now to where we can reset the version number on the next release"): the numbering starts
 *  again at 1.0 - it comes right after 2.15. Only the NAME starts again: the app's version code (build.gradle.kts) keeps counting up,
 *  and updates go by the code alone (UpdatePlan), so every device still sees 1.0 as the newer one. */
const val APP_VERSION = "1.0"
const val ENGINE_FROM = "v120"            // the desktop Restalker version the Amazon page scripts were copied from (unchanged)

/** One product the phone watches (Amazon for now). The purchase fields are the one-per-product lock: once a Place order was pressed
 *  for it (attempted) or it was bought (purchased), it is never bought again until you press Re-arm. */
data class Product(
    val id: String,
    val asin: String,
    val name: String = "",
    val cap: Double = 0.0,
    val kind: String = "other",          // box / bundle / pack / other - the page must show the same kind (desktop rkShape)
    val watch: Boolean = true,
    val buy: Boolean = false,
    val tokens: List<String> = emptyList(),
    // what was last seen
    val status: String = "not checked yet",
    val price: Double? = null,
    val lastCheck: Long = 0,
    val buyable: Boolean = false,
    val episodeStart: Long = 0,         // when the current "buyable" stretch began (one restock = one episode)
    val alertedEpisode: Long = 0,       // the episode a restock message was sent for (one message per restock)
    // the purchase lock
    val inProgress: Boolean = false,
    val attempted: Boolean = false,
    val purchased: Boolean = false,
    val submittedAt: Long = 0,
    val orderNumber: String? = null,
    val fails: Int = 0,
    val failEpisode: Long = 0,
    val lockedUntil: Long = 0,
    val practiceOkAt: Long = 0,
    // 1.4: the desktop's groups for the home screen (release year, then set) - empty until a linked desktop says
    val year: String = "",
    val set: String = "",
    // 1.5: the product's picture (Amazon's own, read from its page), Buy 1 / 2 / 3 (separate orders, counted from qtySince), for sale over
    // the cap (seen here), and what the linked desktop shows now: its stock ("in" / "over" / "out"), price and when it saw it, the MSRP,
    // the TCG price and the product's other stores (each: s = store, st, pr, at)
    val image: String = "",
    val qty: Int = 1,
    val qtySince: Long = 0,
    val overCap: Boolean = false,
    val dSt: String = "",
    val dPr: Double? = null,
    val dAt: Long = 0,
    val msrp: Double? = null,
    val tcg: Double? = null,
    val tcgUrl: String = "",              // 2.4: the TCGplayer page the TCG price is from (the linked PC sends it)
    val others: List<Map<String, Any?>> = emptyList(),
    val picTriedAt: Long = 0,             // 1.6: when its page was last opened only for its picture
    val dImg: String = "",                // 1.7: the linked desktop's picture of the product (its table's)
    val cImg: String = "",                // 2.8: the central list's picture (TCGplayer's catalogue photo of exactly this product)
    val dropRef: Double? = null,          // 2.12: the price a price drop is measured from (PriceDrop) and when it was set
    val dropAt: Long = 0,
) {
    val url: String get() = "https://www.amazon.com/dp/$asin"
    val label: String get() = name.ifBlank { "Amazon $asin" }

    /** What the desktop's content.js needs to check the page and buy (a "custom" product: everything comes from here). */
    fun forPage(): Map<String, Any?> = linkedMapOf(
        // 2.15 (owner: "the CAP should not be required to watch, but is required for buying"): no cap = watch only - the page counts it in
        // stock at any price (nothing is ever bought without a cap: Engine.canAutoBuy needs one)
        "id" to "rkm-$id", "customProduct" to true, "asin" to asin, "url" to url, "cap" to (if (cap > 0) cap else NO_CAP),
        "name" to label, "tokens" to tokens, "forbid" to emptyList<String>(),
    ).also { if (kind in SHAPES) it["rkShape"] = kind }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "asin" to asin, "name" to name, "cap" to cap, "kind" to kind, "watch" to watch, "buy" to buy, "tokens" to tokens,
        "status" to status, "price" to price, "lastCheck" to lastCheck, "buyable" to buyable, "episodeStart" to episodeStart,
        "alertedEpisode" to alertedEpisode, "inProgress" to inProgress, "attempted" to attempted, "purchased" to purchased,
        "submittedAt" to submittedAt, "orderNumber" to orderNumber, "fails" to fails, "failEpisode" to failEpisode,
        "lockedUntil" to lockedUntil, "practiceOkAt" to practiceOkAt, "year" to year, "set" to set,
        "image" to image, "qty" to qty, "qtySince" to qtySince, "overCap" to overCap, "dSt" to dSt, "dPr" to dPr, "dAt" to dAt,
        "msrp" to msrp, "tcg" to tcg, "tcgUrl" to tcgUrl, "others" to others, "picTriedAt" to picTriedAt, "dImg" to dImg, "cImg" to cImg,
        "dropRef" to dropRef, "dropAt" to dropAt,
    )

    /** What is known now - this phone's own look or the linked desktop's, whichever is newer: (state "in" / "over" / "out" / "", price, when). */
    fun stock(): Triple<String, Double?, Long> {
        val mine = when { lastCheck == 0L -> ""; buyable -> "in"; overCap -> "over"; else -> "out" }
        return if (dAt > lastCheck && dSt.isNotEmpty()) Triple(dSt, dPr, dAt) else Triple(mine, price, lastCheck)
    }
    val inStock: Boolean get() = stock().first.let { it == "in" || it == "over" }

    companion object {
        val SHAPES = setOf("box", "bundle", "pack")
        fun fromMap(m: Map<String, Any?>): Product? {
            val id = m["id"].asStr() ?: return null
            val asin = m["asin"].asStr() ?: return null
            return Product(
                id = id, asin = asin, name = m["name"].asStr() ?: "", cap = m["cap"].asDouble() ?: 0.0, kind = m["kind"].asStr() ?: "other",
                watch = m["watch"] != false, buy = m["buy"].asBool(), tokens = m["tokens"].asList().mapNotNull { it.asStr() },
                status = m["status"].asStr() ?: "not checked yet", price = m["price"].asDouble(), lastCheck = m["lastCheck"].asLong() ?: 0,
                buyable = m["buyable"].asBool(), episodeStart = m["episodeStart"].asLong() ?: 0, alertedEpisode = m["alertedEpisode"].asLong() ?: 0,
                inProgress = m["inProgress"].asBool(), attempted = m["attempted"].asBool(), purchased = m["purchased"].asBool(),
                submittedAt = m["submittedAt"].asLong() ?: 0, orderNumber = m["orderNumber"].asStr(), fails = (m["fails"].asLong() ?: 0).toInt(),
                failEpisode = m["failEpisode"].asLong() ?: 0, lockedUntil = m["lockedUntil"].asLong() ?: 0, practiceOkAt = m["practiceOkAt"].asLong() ?: 0,
                year = m["year"].asStr() ?: "", set = m["set"].asStr() ?: "",
                // 1.7: a kept address that is not a product picture (an Amazon logo / placeholder from 1.5-1.6) is dropped and looked for again
                image = Pics.canon(m["image"].asStr()) ?: "",            // (1.8: kept in its plain form)
                // (2.0: Buy 1/2/3 is read again - 1.8 and 1.9 lost it at every start, and a linked desktop got Buy 1 back from the phone)
                qty = ((m["qty"].asLong() ?: 1).toInt()).coerceIn(1, 3), qtySince = m["qtySince"].asLong() ?: 0,
                overCap = m["overCap"].asBool(), dSt = m["dSt"].asStr() ?: "", dPr = m["dPr"].asDouble(), dAt = m["dAt"].asLong() ?: 0,
                msrp = m["msrp"].asDouble(), tcg = m["tcg"].asDouble(), tcgUrl = m["tcgUrl"].asStr()?.takeIf { Tcg.okUrl(it) } ?: "",
                others = m["others"].asList().mapNotNull { it.asMap() }.take(8),
                picTriedAt = if (Pics.canon(m["image"].asStr()) != null || m["image"].asStr().isNullOrBlank()) m["picTriedAt"].asLong() ?: 0 else 0,
                dImg = Pics.desk(m["dImg"].asStr()) ?: "",
                cImg = m["cImg"].asStr()?.takeIf { Pics.validAny(it) } ?: "",
                dropRef = m["dropRef"].asDouble()?.takeIf { it > 0 && it < 100_000 }, dropAt = m["dropAt"].asLong() ?: 0,
            )
        }
    }
}

data class Purchase(val at: Long, val asin: String, val name: String, val price: Double?, val order: String?, val verified: Boolean, val note: String = "") {
    fun toMap(): Map<String, Any?> = linkedMapOf("at" to at, "asin" to asin, "name" to name, "price" to price, "order" to order, "verified" to verified, "note" to note)
    companion object {
        fun fromMap(m: Map<String, Any?>) = Purchase(m["at"].asLong() ?: 0, m["asin"].asStr() ?: "", m["name"].asStr() ?: "", m["price"].asDouble(),
            m["order"].asStr(), m["verified"].asBool(), m["note"].asStr() ?: "")
    }
}

data class Settings(
    val watching: Boolean = false,
    // 2.2 (owner: "The app should automatically install and load up with the option to be buying (i shouldnt have to turn off test mode
    // and turn on buying mode manually)"): a new install buys for real - still only products YOU set to Buy, at or under YOUR cap
    val buying: Boolean = true,           // master switch: nothing is bought while off
    val practice: Boolean = false,        // ON = every checkout stops right before "Place your order" (nothing is bought)
    val intervalSec: Int = 90,            // each product is looked at once every this many seconds
    val snoozeUntil: Long = 0,            // restock messages silenced until then
    val wifiOnly: Boolean = false,        // look only while on Wi-Fi (saves mobile data)
    val signinNeededAt: Long = 0,         // Amazon asked to sign in: no checkout until you opened the sign-in (or 1 hour passed)
    val reportTopic: String = "",         // this phone's private report channel (made once): problem reports go to Claude through it
    val autoReport: Boolean = true,       // send a problem report by itself when something goes wrong (at most one every 30 minutes)
    val reportAt: Long = 0,
    val cooldownUntil: Long = 0,          // Amazon showed a robot check / slowed us down: no page until then
    val cooldownStreak: Int = 0,
    val needYouAt: Long = 0,              // last "Amazon needs you" message (at most one an hour)
    val ordersReadAt: Long = 0,
    val ordersKnown: Map<String, List<String>> = emptyMap(),   // ASIN -> order numbers already in your Amazon orders (before a checkout)
    // 1.5: the "watching" notice shows its icon in the status bar (off = tucked away, no icon). 2.12 (owner: "Notifications should only pop
    // up when the user needs to see something. We do not need a constant notification that the app is running and watching"): off by
    // default, and switched off once on every device that had it on (noticeV) - it can be switched back on in Settings
    val runIcon: Boolean = false,
    val autoUpdate: Boolean = true,       // 1.7: new versions install by themselves
    val buyMode: String = "pc",           // linked - who buys on Amazon here: "pc" (this device only watches) | "both" | "phone" (instead of the PC)
    val buyModeAt: Long = 0,              //   (2.3: each device its own choice - changed here or from another linked device; the newest change wins)
    val failover: Boolean = true,         // 2.3: "pc": this device takes over when the PC stops
    val deviceName: String = "",          // 2.3: the name the other linked devices show (empty = the phone's model)
    val crowd: Boolean = true,            // 2.6: share in-stock sightings with the Restalker crowd (and look when the crowd sees one)
    val masterCode: String = "",          // 2.7: the owner's master code typed here (Settings, About) - no purchase limit
    val alertTopic: String = "",          // 2.9: this phone's own ntfy alerts channel (made when you set it up; NtfyAlerts.channel picks the one used)
    val alertsOn: Boolean = false,        // 2.9: send alerts to that channel too (Settings, "Alerts on any device")
    // 2.12: which messages this device shows (NotifyPrefs): restocks, price drops, purchases (and practice runs), "Restalker needs you"
    val nRestock: Boolean = true,
    val nDrop: Boolean = false,
    val nBuy: Boolean = true,
    val nNeed: Boolean = true,
    val noticeHidden: Boolean = false,    // 2.12: the watching notice was swiped away - it stays away (kept across restarts)
    val modeV: Int = 0,                   // 2.15: 1 = the "race to checkout" default was applied once (Who buys: both)
    // 2.15 (owner: "anything that requires user input should be flagged with a small red triangle ... Once clicked, the icon should go
    // away. Icons should refresh for anything left outstanding ... each day unless the user manually disables the option")
    val attnDaily: Boolean = true,
    val attnSeen: Map<String, String> = emptyMap(),      // what was tapped -> on which day (yyyy-MM-dd)
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("watching" to watching, "buying" to buying, "practice" to practice, "intervalSec" to intervalSec,
        "snoozeUntil" to snoozeUntil, "wifiOnly" to wifiOnly, "signinNeededAt" to signinNeededAt, "reportTopic" to reportTopic, "autoReport" to autoReport, "reportAt" to reportAt, "cooldownUntil" to cooldownUntil, "cooldownStreak" to cooldownStreak, "needYouAt" to needYouAt,
        "ordersReadAt" to ordersReadAt, "ordersKnown" to ordersKnown, "runIcon" to runIcon, "autoUpdate" to autoUpdate,
        "buyMode" to buyMode, "buyModeAt" to buyModeAt, "failover" to failover, "deviceName" to deviceName, "crowd" to crowd, "masterCode" to masterCode,
        "alertTopic" to alertTopic, "alertsOn" to alertsOn, "nRestock" to nRestock, "nDrop" to nDrop, "nBuy" to nBuy, "nNeed" to nNeed,
        "noticeHidden" to noticeHidden, "noticeV" to 1, "modeV" to modeV, "attnDaily" to attnDaily, "attnSeen" to attnSeen)
    companion object {
        const val MIN_INTERVAL = 60
        val BUY_MODES = listOf("pc", "phone", "both")
        fun fromMap(m: Map<String, Any?>?): Settings {
            if (m == null) return Settings()
            return Settings(m["watching"].asBool(), if (m.containsKey("buying")) m["buying"].asBool() else true, m["practice"] == true,
                ((m["intervalSec"].asLong() ?: 90).toInt()).coerceAtLeast(MIN_INTERVAL), m["snoozeUntil"].asLong() ?: 0, m["wifiOnly"].asBool(), m["signinNeededAt"].asLong() ?: 0, m["reportTopic"].asStr() ?: "", m["autoReport"] != false, m["reportAt"].asLong() ?: 0, m["cooldownUntil"].asLong() ?: 0,
                (m["cooldownStreak"].asLong() ?: 0).toInt(), m["needYouAt"].asLong() ?: 0, m["ordersReadAt"].asLong() ?: 0,
                (m["ordersKnown"].asMap() ?: emptyMap()).mapValues { (_, v) -> v.asList().mapNotNull { it.asStr() } }, (m["noticeV"].asLong() ?: 0) >= 1 && m["runIcon"] == true, m["autoUpdate"] != false,
                (m["buyMode"].asStr() ?: "pc").takeIf { it in BUY_MODES } ?: "pc", m["buyModeAt"].asLong() ?: 0, m["failover"] != false,
                (m["deviceName"].asStr() ?: "").take(40), m["crowd"] != false, (m["masterCode"].asStr() ?: "").take(40),
                (m["alertTopic"].asStr() ?: "").takeIf { NtfyAlerts.ok(it) } ?: "", m["alertsOn"] == true,
                nRestock = m["nRestock"] != false, nDrop = m["nDrop"] == true, nBuy = m["nBuy"] != false, nNeed = m["nNeed"] != false,
                noticeHidden = m["noticeHidden"] == true, modeV = (m["modeV"].asLong() ?: 0).toInt(), attnDaily = m["attnDaily"] != false,
                attnSeen = (m["attnSeen"].asMap() ?: emptyMap()).entries.take(50).mapNotNull { (k, v) -> v.asStr()?.let { k.take(60) to it.take(10) } }.toMap())
        }
    }
}

/** Reads an Amazon link or a bare ASIN (the 10-letter product code). Short links (amzn.to) are refused: they hide the product. */
object Asin {
    private val PATH = Regex("""/(?:dp|gp/product|gp/aw/d|exec/obidos/asin|o/ASIN|product)/([A-Z0-9]{10})(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""^[A-Z0-9]{10}$""", RegexOption.IGNORE_CASE)
    /** Amazon's short share links (a.co/d/..., amzn.to/..., amzn.com/...): they hide the product - [follow] opens them. */
    val SHORT = Regex("""^(?:https?://)?(?:www\.)?(a\.co|amzn\.to|amzn\.com|amzn\.eu)/\S+""", RegexOption.IGNORE_CASE)

    /** The first web address in a shared text ("Check out this item on Amazon https://a.co/d/xyz"). */
    fun firstLink(text: String): String? = Regex("""https?://[^\s<>"']+""").find(text)?.value?.trimEnd('.', ',', ')')

    /** Reads a link or code, following Amazon's short links. [follow] = the address a link sends to (null = could not open it).
     *  At most 5 hops, and only through Amazon's own addresses. */
    fun resolve(input: String, follow: (String) -> String?): String? {
        parse(input)?.let { return it }
        var url = (firstLink(input) ?: input.trim()).let { if (it.startsWith("http", true)) it else "https://$it" }
        repeat(5) {
            parse(url)?.let { return it }
            val h = Regex("^https?://([^/?#]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase() ?: return null
            val amazonish = h == "a.co" || h == "amzn.to" || h == "amzn.com" || h == "www.amzn.com" || h == "amzn.eu" || h == "amazon.com" || h.endsWith(".amazon.com")
            if (!amazonish) return null
            url = follow(url) ?: return null
        }
        return parse(url)
    }

    fun parse(input: String): String? {
        val s = input.trim()
        if (BARE.matches(s)) return s.uppercase().takeIf { it.any(Char::isDigit) || it.startsWith("B0") }
        val host = Regex("""^(?:https?://)?([^/?#]+)""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.lowercase() ?: return null
        if (!(host == "amazon.com" || host.endsWith(".amazon.com"))) return null
        return PATH.find(s)?.groupValues?.get(1)?.uppercase()
    }
}

/** The product's words, taken from its Amazon title - the same rule as the desktop's content.js titleTokens(). */
/** 1.4: the home screen's groups - (year, set). The linked desktop's when it gave them; otherwise the set from the product's title
 *  ("Magic: The Gathering Nauctis: The Sunken Realm Collector Booster Display" -> "Nauctis: The Sunken Realm") under "Your products". */
object Groups {
    const val MINE = "Your products"
    private val CUT = Regex("(?i)\\b(collector|play|draft|set|jumpstart|prerelease|commander|starter|gift|bundle|booster|display|box|pack|case|deck|kit|edition|secret lair|sld)\\b")
    fun setOf(name: String): String {
        var s = name.replace(Regex("(?i)magic\\s*:?\\s*the\\s+gathering|\\bmtg\\b|trading card game|\\btcg\\b"), " ")
        s = s.split('|', '(', '[', ',').firstOrNull { it.trim().trim('-', ':', '–').isNotBlank() } ?: ""
        CUT.find(s)?.let { s = s.substring(0, it.range.first) }
        s = s.replace(Regex("\\s+"), " ").trim().trim('-', ':', '–', ' ').trim()
        return s.ifBlank { "Other" }
    }
    fun of(p: Product): Pair<String, String> = (p.year.ifBlank { MINE }) to (p.set.ifBlank { setOf(p.label) })
    /** Years newest first; "Other products" and "Your products" last. */
    fun yearRank(y: String): Int = y.toIntOrNull()?.let { -it } ?: if (y == MINE) 100_000 else 50_000
}

object Tokens {
    private val STOP = setOf("the", "and", "for", "with", "from", "this", "that", "pack", "packs", "box", "magic", "gathering")
    fun fromTitle(title: String): List<String> =
        title.replace(Regex("\\s+"), " ").trim().lowercase().replace(Regex("[^a-z0-9' -]"), " ").split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in STOP }.take(12)
}

/** 2.4 (owner: "include a hyperlink for the TCG price to the actual page so the user can view that"): the TCGplayer page of a product -
 *  the one the linked PC reads its price from, else TCGplayer's own search for the product's name. */
object Tcg {
    fun okUrl(u: String): Boolean = u.length <= 300 && Regex("^https://(www\\.)?tcgplayer\\.com/[^\\s\"<>]*$").matches(u)
    fun link(p: Product): String = p.tcgUrl.takeIf { okUrl(it) }
        ?: ("https://www.tcgplayer.com/search/magic/product?q=" + java.net.URLEncoder.encode(query(p.label), "UTF-8"))
    /** The product's name without "Magic: The Gathering" and the shop's extras after a "|". */
    fun query(name: String): String = name.replace(Regex("(?i)magic\\s*:?\\s*the\\s+gathering|\\bmtg\\b"), " ").split('|').firstOrNull { it.isNotBlank() }
        ?.replace(Regex("\\s+"), " ")?.trim()?.trim('-', ':', ' ')?.take(100) ?: name.take(100)
}

/** 2.5 (owner: 'another line of text below the MSRP which shows "Best Price: [$$$] @ [store]" with a hyperlink to the store'): the
 *  cheapest place that has the product in stock right now - this device's or the PC's look at Amazon, or one of the other stores the
 *  linked PC watches. (price, store, that store's page) or null when nowhere has it. Pure: CoreTests. */
object BestPrice {
    data class Offer(val price: Double, val store: String, val url: String)
    /** 2.10 (owner: "The low price still doesn't match the store price listed. Confirm where you are getting the low price information and
     *  make sure these are consistent"): only a price SEEN in stock within the last [FRESH_MS] counts. 2.5-2.9 also took a store's last
     *  price after that store was no longer looked at - e.g. Walmart's $369.99 for the Star Trek box the PC had bought there (the PC stops
     *  watching a store after it bought there, so its last "in stock at $369.99" stayed). */
    const val FRESH_MS = 2 * 3_600_000L
    fun of(p: Product, now: Long = System.currentTimeMillis()): Offer? {
        val out = ArrayList<Offer>()
        val (st, pr, at) = p.stock()
        if ((st == "in" || st == "over") && pr != null && pr > 0 && now - at <= FRESH_MS) out += Offer(pr, "Amazon", p.url)
        for (o in p.others) {
            val s = o["st"].asStr() ?: continue
            val v = o["pr"].asDouble() ?: continue
            val u = o["u"].asStr()?.takeIf { it.startsWith("https://") } ?: continue
            val a0 = o["at"].asDouble() ?: 0.0
            val aMs = if (a0 < 1e11) (a0 * 1000).toLong() else a0.toLong()        // (the PC sends seconds)
            if ((s == "in" || s == "over") && v > 0 && now - aMs <= FRESH_MS) out += Offer(v, (o["s"].asStr() ?: "").ifBlank { "store" }, u)
        }
        return out.minByOrNull { it.price }
    }
}

/** 2.15: the page's cap for a product watched without a cap (any price counts as in stock; never used to buy). */
const val NO_CAP = 1_000_000.0
