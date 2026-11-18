package com.restalker.mobile.core

/** 2.2 (owner: "The app should have an option for downloading all existing products from the central server, in case the next user wants
 *  to review all magic products as well. These should come in with all information and links, but without any caps. The user should be
 *  prompted to apply caps for any product they wish to purchase. No products should be monitored until manually turned on by the user.")
 *  The list is mobile/catalog.json in the release home (made from the PC's product list: names, Amazon codes, kinds, release year and set,
 *  the published MSRP and the other stores' links - never a cap, a purchase or anyone's switches). Pure: CoreTests. */
object Catalog {
    const val URL = "https://raw.githubusercontent.com/stevenmanuel01-cloud/Restalker/main/mobile/catalog.json"
    private val ASIN = Regex("[A-Z0-9]{10}")

    data class Result(val added: Int, val already: Int, val bad: Int)

    /** Adds every product of the list that is not in this phone's list yet: no cap, not watched, not set to Buy. */
    fun import(json: String?, store: Store, now: Long = System.currentTimeMillis()): Result? {
        val root = MiniJson.obj(json ?: return null) ?: return null
        val list = root["products"].asList()
        if (list.isEmpty()) return null
        var added = 0; var already = 0; var bad = 0
        val have = store.products().map { it.asin }.toMutableSet()
        for (x in list.take(1000)) {
            val m = x.asMap()
            if (m == null) { bad++; continue }
            val a = m["a"].asStr()
            if (a == null || !ASIN.matches(a)) { bad++; continue }
            if (a in have) { already++; continue }
            val name = (m["n"].asStr() ?: "").take(160)
            val kind = (m["k"].asStr() ?: "other").let { if (it in Product.SHAPES) it else "other" }
            val others = m["sx"].asList().mapNotNull { it.asMap() }.mapNotNull { o ->
                val u = o["u"].asStr()?.takeIf { it.startsWith("https://") && it.length <= 400 } ?: return@mapNotNull null
                linkedMapOf<String, Any?>("s" to (o["s"].asStr() ?: "").take(20), "st" to "", "pr" to null, "at" to 0L, "u" to u)
            }.take(8)
            val ms = m["ms"].asDouble()?.takeIf { it > 0 && it < 100000 }
            val pic = m["i"].asStr()?.takeIf { Pics.validAny(it) } ?: ""
            val tp = m["tp"].asStr()?.takeIf { Regex("^[0-9]{1,9}$").matches(it) }?.let { "https://www.tcgplayer.com/product/$it" } ?: ""
            store.add(Product(id = store.newId(), asin = a, name = name, cap = 0.0, kind = kind, watch = false, buy = false, cImg = pic, tcgUrl = tp,
                tokens = Tokens.fromTitle(name), year = (m["gy"].asStr() ?: "").take(40), set = (m["gs"].asStr() ?: "").take(80),
                msrp = ms, others = others, qtySince = now))
            have += a
            added++
        }
        store.log("Got all products: $added added (no cap, not watched)" + (if (already > 0) ", $already already in your list" else "") +
            ". Set a cap and switch Watch / Buy on for the ones you want.")
        return Result(added, already, bad)
    }

    /** 2.8: the central list's pictures ("i": TCGplayer's catalogue photo of exactly that product, the one the PC shows too) for every
     *  product of this phone with that Amazon code - also products added before, and your own ones. -> how many changed. */
    fun pictures(json: String?, store: Store): Int {
        val root = MiniJson.obj(json ?: return 0) ?: return 0
        val pics = HashMap<String, String>(); val tps = HashMap<String, String>()
        for (x in root["products"].asList().take(1000)) {
            val m = x.asMap() ?: continue
            val a = m["a"].asStr() ?: continue
            if (!ASIN.matches(a)) continue
            m["i"].asStr()?.takeIf { Pics.validAny(it) }?.let { pics[a] = it }
            m["tp"].asStr()?.takeIf { Regex("^[0-9]{1,9}$").matches(it) }?.let { tps[a] = "https://www.tcgplayer.com/product/$it" }
        }
        var n = 0
        for (p in store.products()) {
            val i = pics[p.asin] ?: p.cImg
            val u = if (p.tcgUrl.isBlank()) tps[p.asin] ?: "" else p.tcgUrl            // (the PC's TCG link, when linked, stays)
            if (p.cImg != i || p.tcgUrl != u) { store.update(p.id) { it.copy(cImg = i, tcgUrl = u) }; if (p.cImg != i) n++ }
        }
        return n
    }
}
