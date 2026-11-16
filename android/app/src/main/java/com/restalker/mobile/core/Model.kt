package com.restalker.mobile.core

const val APP_VERSION = "1.3"
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
) {
    val url: String get() = "https://www.amazon.com/dp/$asin"
    val label: String get() = name.ifBlank { "Amazon $asin" }

    /** What the desktop's content.js needs to check the page and buy (a "custom" product: everything comes from here). */
    fun forPage(): Map<String, Any?> = linkedMapOf(
        "id" to "rkm-$id", "customProduct" to true, "asin" to asin, "url" to url, "cap" to cap,
        "name" to label, "tokens" to tokens, "forbid" to emptyList<String>(),
    ).also { if (kind in SHAPES) it["rkShape"] = kind }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "asin" to asin, "name" to name, "cap" to cap, "kind" to kind, "watch" to watch, "buy" to buy, "tokens" to tokens,
        "status" to status, "price" to price, "lastCheck" to lastCheck, "buyable" to buyable, "episodeStart" to episodeStart,
        "alertedEpisode" to alertedEpisode, "inProgress" to inProgress, "attempted" to attempted, "purchased" to purchased,
        "submittedAt" to submittedAt, "orderNumber" to orderNumber, "fails" to fails, "failEpisode" to failEpisode,
        "lockedUntil" to lockedUntil, "practiceOkAt" to practiceOkAt,
    )

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
    val buying: Boolean = false,          // master switch: nothing is bought while off
    val practice: Boolean = true,         // ON = every checkout stops right before "Place your order" (nothing is bought)
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
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("watching" to watching, "buying" to buying, "practice" to practice, "intervalSec" to intervalSec,
        "snoozeUntil" to snoozeUntil, "wifiOnly" to wifiOnly, "signinNeededAt" to signinNeededAt, "reportTopic" to reportTopic, "autoReport" to autoReport, "reportAt" to reportAt, "cooldownUntil" to cooldownUntil, "cooldownStreak" to cooldownStreak, "needYouAt" to needYouAt,
        "ordersReadAt" to ordersReadAt, "ordersKnown" to ordersKnown)
    companion object {
        const val MIN_INTERVAL = 60
        fun fromMap(m: Map<String, Any?>?): Settings {
            if (m == null) return Settings()
            return Settings(m["watching"].asBool(), m["buying"].asBool(), m["practice"] != false,
                ((m["intervalSec"].asLong() ?: 90).toInt()).coerceAtLeast(MIN_INTERVAL), m["snoozeUntil"].asLong() ?: 0, m["wifiOnly"].asBool(), m["signinNeededAt"].asLong() ?: 0, m["reportTopic"].asStr() ?: "", m["autoReport"] != false, m["reportAt"].asLong() ?: 0, m["cooldownUntil"].asLong() ?: 0,
                (m["cooldownStreak"].asLong() ?: 0).toInt(), m["needYouAt"].asLong() ?: 0, m["ordersReadAt"].asLong() ?: 0,
                (m["ordersKnown"].asMap() ?: emptyMap()).mapValues { (_, v) -> v.asList().mapNotNull { it.asStr() } })
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
object Tokens {
    private val STOP = setOf("the", "and", "for", "with", "from", "this", "that", "pack", "packs", "box", "magic", "gathering")
    fun fromTitle(title: String): List<String> =
        title.replace(Regex("\\s+"), " ").trim().lowercase().replace(Regex("[^a-z0-9' -]"), " ").split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in STOP }.take(12)
}
