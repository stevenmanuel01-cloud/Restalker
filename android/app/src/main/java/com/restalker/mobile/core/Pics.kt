package com.restalker.mobile.core

/** 1.7 (owner 2026-11-16: "The pictures aren't populating still"): which picture addresses are real product pictures, and the small
 *  version to download. An Amazon product picture always lives under /images/I/ - anything else on an Amazon page (/images/G/ = the
 *  site's own graphics: logos, share images, grey placeholder pixels) is never a product picture. */
object Pics {
    /** 1.8: any address of an Amazon product picture (http/https, any size or kind) -> its plain form .../images/I/<id>.jpg; null = not one. */
    private val AMZ_ANY = Regex("^(?:https?:)?//([a-z0-9.-]*\\.)?(media-amazon\\.com|ssl-images-amazon\\.com|images-amazon\\.com)/images/I/([A-Za-z0-9+%-]{5,40})(?:\\.[^/?#]*)?\\.(jpe?g|png|webp|avif|gif)(?:[?#].*)?$",
        RegexOption.IGNORE_CASE)
    fun canon(u: String?): String? {
        val t = u?.trim() ?: return null
        if (t.length > 600) return null
        val m = AMZ_ANY.find(t) ?: return null
        return "https://m.media-amazon.com/images/I/" + m.groupValues[3] + ".jpg"
    }
    private val AMZ = Regex("^https://m\\.media-amazon\\.com/images/I/[A-Za-z0-9+%-]{5,40}\\.jpg$")
    /** Other stores' picture servers (the linked desktop's picture of a product can come from them). */
    // (2.0: the same picture servers the PC takes its pictures from - TCGplayer's catalogue photo first, then the stores)
    private val OTHER = Regex("^https://([a-z0-9-]+\\.)*(scene7\\.com|bbystatic\\.com|walmartimages\\.com|gamestop\\.com|tcgplayer\\.com|target\\.com|bestbuy\\.com)/[^\\s\"'<>]+$",
        RegexOption.IGNORE_CASE)

    /** A picture this phone read from an Amazon page (kept in its plain form). */
    fun valid(u: String?): Boolean = u != null && AMZ.matches(u)
    /** A picture the linked desktop gave (Amazon's or another store's). */
    fun validAny(u: String?): Boolean = u != null && u.length < 500 && (AMZ.matches(u) || OTHER.matches(u))
    /** A desktop's picture address as kept here (Amazon's in its plain form), or null. */
    fun desk(u: String?): String? = canon(u) ?: u?.takeIf { validAny(it) }

    /** Amazon: ..../images/I/81abc._AC_SL1500_.jpg -> ..../images/I/81abc._AC_SL<px>_.jpg (less to download). Others: as given. */
    fun small(u: String, px: Int = 240): String {
        val c = canon(u) ?: return u
        return c.removeSuffix(".jpg") + "._AC_SL${px}_.jpg"
    }

    /** What to try, in order: the small version, then the picture as given. */
    fun tries(u: String): List<String> = listOf(small(u), u).distinct()

    /** The short name of a picture for the diagnostics (the part after /images/I/, or the host). */
    fun short(u: String): String = u.substringAfter("/images/I/", "").ifEmpty { u.substringAfter("://").substringBefore('/') }.take(60)

    /** 1.8.1: the picture id (the part after /images/I/ of the plain form). */
    fun id(u: String?): String? = canon(u)?.substringAfter("/images/I/")?.removeSuffix(".jpg")

    /** 1.8.1 (the 1.8 report: one picture id, a 404 on Amazon's picture server, was taken for 7 different products): the first of a
     *  page's candidates that is not a known bad id and not already another product's picture. */
    fun choose(cands: List<String?>, asin: String, products: List<Product>, bad: Set<String>): String? {
        val others = products.filter { it.asin != asin }.mapNotNull { id(it.image) }.toSet()
        return cands.mapNotNull { canon(it) }.firstOrNull { c -> id(c).let { it != null && it !in bad && it !in others } }
    }

    /** 1.8.1: ids that are no product picture: kept for 2+ different products, or answered "404" twice by the picture server.
     *  -> (bad ids, the products whose picture must be dropped and looked for again). */
    fun repair(products: List<Product>, status: (String) -> String?, fails: (String) -> Int): Pair<Set<String>, List<String>> {
        val byId = products.filter { valid(it.image) }.groupBy { id(it.image)!! }
        val bad = HashSet<String>()
        for ((k, ps) in byId) {
            if (ps.map { it.asin }.distinct().size >= 2) bad += k
            val u = ps.first().image
            if (fails(u) >= 2 && (status(u) ?: "").contains("404")) bad += k
        }
        return bad to products.filter { id(it.image) in bad }.map { it.id }
    }

    /** The picture to show: this phone's own (from Amazon's page), else the linked desktop's. */
    // 2.0 (owner: "Some of the app pictures dont match the PC ones (The PC ones are correct)"): the linked PC's picture first - the
    // exact picture its table shows (the PC checks the product's shape and page); this phone's own when not linked or the PC has none
    fun shown(p: Product): List<String> = listOfNotNull(p.dImg.takeIf { validAny(it) }, p.image.takeIf { valid(it) && it != p.dImg })
}
