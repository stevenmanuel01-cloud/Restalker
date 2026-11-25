package com.restalker.mobile.core

/**
 * 1.1.8 (owner, 2026-11-24: "trackers on the phone - this should allow user to add/link additional trackers they might already be using
 * which will report to discord and feed our data"). A tracker the user already has on this phone (a Discord server, a restock app, a
 * channel ...) tells them with a notification. For the apps the user chose, Restalker reads that notification's words and asks one
 * thing only: is this about a product I watch? If so the phone looks at that product at once (and buys it if it is in stock at or
 * under the cap - the same as when the PC or the crowd says "go").
 *
 * What counts as "about a product I watch" - the sure signs first:
 *   1. an Amazon product address or a product number (B0...) in the words that is one of the watched products;
 *   2. else the product's NAME: every identifying word of exactly one watched product is in the alert, and the alert does not name
 *      only other stores (an alert about Walmart or Target is not an Amazon restock).
 * An alert that fits two watched products by name is not guessed at (both are looked at - a look costs one page).
 */
object TrackerMatch {
    private val LINK = Regex("amazon\\.[a-z.]{2,6}/(?:[^\\s/?#]+/)?(?:dp|gp/product|gp/aw/d|gp/offer-listing)/([A-Za-z0-9]{10})", RegexOption.IGNORE_CASE)
    private val ASIN = Regex("(?<![A-Za-z0-9])(B0[0-9A-Z]{8})(?![A-Za-z0-9])")
    private val OTHER = Regex("\\b(walmart|target|best\\s?buy|gamestop|game\\s?stop|tcgplayer|tcg\\s?player|ebay|costco|sam'?s club|barnes|pokemon\\s?center|card\\s?kingdom|star city)\\b", RegexOption.IGNORE_CASE)
    private val AMAZON = Regex("\\bamazon\\b|\\bamzn\\b|\\ba\\.co/", RegexOption.IGNORE_CASE)
    /** words too common to tell two products apart (each still has to be there; they just cannot be the ONLY thing that fits) */
    private val COMMON = setOf("collector", "booster", "boosters", "play", "set", "draft", "bundle", "display", "commander", "deck", "decks", "edition", "english", "sealed", "new", "mtg")

    private val KIND = Regex("\\b(box|bundle|display|case|pack|deck|kit|tin)\\b")

    data class Hit(val product: Product, val how: String, /** the alert names Amazon / carries an Amazon address (it may be fed to the data) */ val amazon: Boolean)

    fun match(text: String, products: List<Product>): List<Hit> {
        val t = text.take(4000)
        if (t.isBlank() || products.isEmpty()) return emptyList()
        val byAsin = products.associateBy { it.asin.uppercase() }
        val ids = LinkedHashSet<String>()
        LINK.findAll(t).forEach { ids += it.groupValues[1].uppercase() }
        ASIN.findAll(t).forEach { ids += it.groupValues[1] }
        val sure = ids.mapNotNull { byAsin[it] }.distinctBy { it.id }
        if (sure.isNotEmpty()) return sure.map { Hit(it, "its Amazon address / product number", true) }
        if (ids.isNotEmpty()) return emptyList()                  // (it names another Amazon product: not a watched one)
        val amazon = AMAZON.containsMatchIn(t)
        if (!amazon && OTHER.containsMatchIn(t)) return emptyList()  // (another store's restock)
        val words = Tokens.fromTitle(t.replace('\n', ' ')).toSet() + Regex("[a-z0-9']+").findAll(t.lowercase()).map { it.value }.toSet()
        val out = ArrayList<Hit>(); val tks = HashMap<String, List<String>>()
        for (p in products) {
            // (the product's identifying words, and what KIND of thing it is - "box" tells a booster box from a single booster)
            val tk = ((p.tokens.ifEmpty { Tokens.fromTitle(Tcg.query(p.name)) }).map { it.lowercase() } + KIND.findAll(p.name.lowercase()).map { it.value }).distinct()
            val own = tk.filter { it !in COMMON }
            if (tk.size < 2 || own.isEmpty()) continue            // (a name too plain to be told by)
            if (tk.all { it in words }) { out += Hit(p, "its name", amazon); tks[p.id] = tk }
        }
        // a longer name that fits wins over a shorter one it contains ("... Collector Booster Box" over "... Collector Booster")
        val best = out.filter { h -> out.none { o -> o !== h && tks[o.product.id]!!.size > tks[h.product.id]!!.size && tks[o.product.id]!!.containsAll(tks[h.product.id]!!) } }
        return best.take(3)
    }

    /** The tracker's name as it is shared: letters, digits and a few signs only; never anything of the alert's text. */
    fun cleanName(label: String): String = label.replace(Regex("[^A-Za-z0-9 ._+&'-]"), "").trim().take(40)
}
