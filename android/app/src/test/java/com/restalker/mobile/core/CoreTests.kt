package com.restalker.mobile.core

import java.io.File

/** The app's rules, tested on a computer with a stand-in Amazon that answers like the desktop's content.js / amz_part.js do.
 *  Run by Gradle (CoreTestsJUnit) and directly (main) where no Android tools exist. */
object CoreTests {
    class FakeClock(var t: Long = 1_800_000_000_000L) : Clock { override fun now() = t; override fun sleep(ms: Long) { t += ms } }

    /** A stand-in Amazon: per-product stock/price/robot check, your order list, and the checkout messages content.js sends. */
    class FakeAmazon(val clock: FakeClock) : Page {
        data class Item(var stock: Boolean, var price: Double, var title: String, var captcha: Boolean = false, var wrongPage: Boolean = false)
        val items = HashMap<String, Item>()
        val orders = ArrayList<Pair<String, String>>()      // order number -> ASIN
        var url = ""; var places = 0; var placeFail = 0; var noConfirm = false; var signinOrders = false; var silent = false; var signinCheckout = false; var loadFails = false
        var gotoCount = 0; var begins = 0; var pictureUrl: String? = null
        var lockSeenAtReply: Boolean? = null; var store: Store? = null; var replyOk: Boolean? = null
        private val msgs = ArrayList<Map<String, Any?>>(); private var n = 0L; private var waitingAsin: String? = null
        private fun push(m: Map<String, Any?>): Long { n++; msgs.add(m + ("n" to n)); return n }
        override fun goto(url: String, timeoutMs: Long): Boolean { this.url = url; gotoCount++; clock.sleep(800); return !loadFails }
        override fun images(on: Boolean) {}
        val seen = ArrayList<String>()
        override fun visited(): List<String> { val l = seen.toList(); seen.clear(); return l }
        private fun asinHere() = Regex("/dp/([A-Z0-9]{10})").find(url)?.groupValues?.get(1)
        override fun ask(msg: Map<String, Any?>, timeoutMs: Long): Map<String, Any?>? {
            if (silent) { clock.sleep(timeoutMs); return null }
            clock.sleep(20)
            when (msg["bb"]) {
                "reset" -> { msgs.clear(); waitingAsin = null; return mapOf("ok" to true) }
                "picture" -> return mapOf("picture" to (pictureUrl ?: "https://m.media-amazon.com/images/I/${asinHere()}._AC_SL1500_.jpg"))
                "eligible" -> {
                    val p = msg["product"].asMap()!!; val a = asinHere(); val it = items[a] ?: return mapOf("ok" to true, "eligible" to false, "reason" to "product identity changed", "title" to "Amazon.com")
                    if (it.captcha) return mapOf("ok" to true, "eligible" to false, "reason" to "Amazon challenge/throttle", "title" to "Amazon.com")
                    if (it.wrongPage || p["asin"] != a) return mapOf("ok" to true, "eligible" to false, "reason" to "product identity changed", "title" to it.title)
                    if (!it.stock) return mapOf("ok" to true, "eligible" to false, "price" to null, "reason" to "purchase control no longer available", "title" to it.title)
                    val cap = p["cap"].asDouble() ?: 0.0
                    if (it.price > cap + 0.001) return mapOf("ok" to true, "eligible" to false, "price" to it.price, "reason" to "price above cap", "kind" to "buy_now", "title" to it.title)
                    return mapOf("ok" to true, "eligible" to true, "price" to it.price, "reason" to null, "kind" to "buy_now", "title" to it.title)
                }
                "begin" -> {
                    begins++; val a = asinHere()!!; val it = items[a]!!
                    if (signinCheckout) { seen.add("/ap/signin"); seen.add("/gp/cart/view.html"); push(mapOf("type" to "FLOW_BLOCKED", "reason" to "Proceed to checkout unavailable", "page" to "/gp/cart/view.html")); return mapOf("ok" to true, "started" to true) }
                    push(mapOf("type" to "CHECKOUT_PRICE_EVIDENCE", "reason" to "MATCHED_EXACT_ITEM"))
                    push(mapOf("type" to "SUBMITTING", "fees" to mapOf("base" to it.price, "tax" to it.price * 0.0775, "shipping" to 0.0, "total" to it.price * 1.0775)))
                    waitingAsin = a
                    return mapOf("ok" to true, "started" to true)
                }
                "msgs" -> { val since = msg["since"].asLong() ?: 0; return mapOf("ok" to true, "msgs" to msgs.filter { (it["n"].asLong() ?: 0) > since }) }
                "reply" -> {
                    val v = msg["value"].asMap()!!; val ok = v["ok"] == true; replyOk = ok
                    val a = waitingAsin ?: return mapOf("ok" to true)
                    lockSeenAtReply = store?.products()?.firstOrNull { it.asin == a }?.attempted
                    if (!ok) { push(mapOf("type" to "FLOW_BLOCKED", "reason" to "Purchase latch was not confirmed")); return mapOf("ok" to true) }
                    places++
                    if (placeFail > 0) { placeFail--; push(mapOf("type" to "DEFINITE_SUBMISSION_FAILURE", "reason" to "Amazon says the product is unavailable")); return mapOf("ok" to true) }
                    val o = "111-${1000000 + orders.size}-${2000000 + places}"; orders.add(o to a)
                    if (noConfirm) push(mapOf("type" to "SUBMISSION_UNCLEAR", "price" to items[a]!!.price))
                    else push(mapOf("type" to "PURCHASE_SUCCESS", "price" to items[a]!!.price, "orderNumber" to o))
                    return mapOf("ok" to true)
                }
                "orders" -> {
                    if (signinOrders) return mapOf("ok" to true, "found" to false, "ids" to emptyList<String>(), "signin" to true)
                    val a = msg["asin"]; val ids = orders.filter { it.second == a }.map { it.first }.reversed()
                    return mapOf("ok" to true, "found" to ids.isNotEmpty(), "ids" to ids, "signin" to false)
                }
            }
            return mapOf("ok" to false)
        }
    }

    class Said : Alerts {
        val restocks = ArrayList<String>(); val bought = ArrayList<String>(); val needYou = ArrayList<String>(); val practice = ArrayList<String>(); var last = ""
        override fun restock(p: Product, price: Double?) { restocks.add(p.asin) }
        override fun bought(p: Product, price: Double?, order: String?, verified: Boolean) { bought.add("${p.asin}|$order|$verified") }
        override fun needYou(title: String, text: String, p: Product?) { needYou.add("$title: $text") }
        override fun practice(p: Product, text: String) { practice.add(text) }
        override fun status(text: String) { last = text }
        val problems = ArrayList<String>()
        override fun problem(what: String) { problems.add(what) }
    }

    class World(practice: Boolean = false, buying: Boolean = true, var net: String? = "wifi", var desk: Boolean = false) {
        val dir: File = kotlin.io.path.createTempDirectory("rkm").toFile()
        val clock = FakeClock()
        val store = Store(dir) { clock.t }
        val amz = FakeAmazon(clock).also { it.store = store }
        val said = Said()
        var mode = "pc"; var other = false; var fo = true; var handed = false
        val verdicts = HashMap<String, Pair<String, Long>>()
        var verdictFn: ((String) -> Pair<String, Long>?)? = null
        val engine = Engine(store, amz, said, clock, { net }, { desk }, { other }, { mode }, { a -> verdictFn?.invoke(a) ?: verdicts[a] },
            failover = { fo }, pcHanded = { handed })
        init { store.setSettings { it.copy(watching = true, buying = buying, practice = practice) } }
        fun add(asin: String, cap: Double, buy: Boolean, stock: Boolean = true, price: Double = 99.99): Product {
            amz.items[asin] = FakeAmazon.Item(stock, price, "Magic: The Gathering Test Product $asin Collector Booster Box")
            val p = Product(id = "id$asin", asin = asin, cap = cap, buy = buy, watch = true)
            store.add(p); return p
        }
        fun p(asin: String) = store.products().first { it.asin == asin }
        fun check(asin: String) = engine.check(p(asin))
    }

    private val results = LinkedHashMap<String, Boolean>()
    /** 2.4: no two labels on one row of the price bar touch (their real widths and a small gap). */
    private fun clear(pm: PriceMarks): Boolean = pm.items.filter { it.above }.groupBy { it.row }.values.all { r ->
        r.sortedBy { pm.centre(it) }.zipWithNext().all { (a, b) -> pm.centre(a) + pm.width(a) / 2 + PriceMarks.GAP <= pm.centre(b) - pm.width(b) / 2 + 1e-9 } }
    private fun t(name: String, f: () -> Boolean) {
        val ok = try { f() } catch (e: Throwable) { println("  $name crashed: $e"); e.printStackTrace(); false }
        results[name] = ok; println((if (ok) "PASS " else "FAIL ") + name)
    }

    fun run(): Map<String, Boolean> {
        results.clear()
        t("01 links and product codes are read") {
            Asin.parse("https://www.amazon.com/Magic-Gathering-Hobbit/dp/B0GWSDDFFJ/ref=sr_1_1?crid=x") == "B0GWSDDFFJ" &&
            Asin.parse("amazon.com/gp/product/B0GXC89N66") == "B0GXC89N66" && Asin.parse("b0gxc89n66") == "B0GXC89N66" &&
            Asin.parse("https://smile.amazon.com/dp/B0GXC89N66?th=1") == "B0GXC89N66" && Asin.parse("https://www.amazon.com/gp/aw/d/B0GXC89N66") == "B0GXC89N66" &&
            Asin.parse("https://www.evil.com/dp/B0GXC89N66") == null && Asin.parse("https://amzn.to/3xYz") == null && Asin.parse("HELLOWORLD") == null &&
            Asin.parse("https://www.amazon.com.evil.com/dp/B0GXC89N66") == null
        }
        t("02 product words match the desktop's titleTokens()") {
            Tokens.fromTitle("Magic: The Gathering | The Hobbit Collector Booster Box | 12 Packs + 1 Foil Box Topper Card | Collectible Trading Card Game") ==
                listOf("hobbit", "collector", "booster", "foil", "topper", "card", "collectible", "trading", "card", "game") &&
            Tokens.fromTitle("  Magic: The Gathering Bloomburrow Bundle – 9 Play Boosters, 30 Lands  ") == listOf("bloomburrow", "bundle", "play", "boosters", "lands") &&
            Tokens.fromTitle("Wizards’ Duskmourn: House of Horror Collector's Booster Display (12 packs)") == listOf("wizards", "duskmourn", "house", "horror", "collector's", "booster", "display")
        }
        t("03 JSON written and read back") {
            val m = mapOf("a" to 1L, "b" to 2.5, "c" to "x\"y\\z\né ", "d" to listOf(true, null, mapOf("e" to -3L)), "f" to emptyMap<String, Any?>())
            MiniJson.read(MiniJson.write(m)) == m && MiniJson.obj("{bad") == null && MiniJson.read("[1e3, -0.5]") == listOf(1000.0, -0.5)
        }
        t("04 products and settings survive a restart; a checkout left running is not running after it") {
            val w = World(); w.add("B0TEST0001", 120.0, true)
            w.store.update("idB0TEST0001") { it.copy(inProgress = true, attempted = true, status = "x") }
            val s2 = Store(w.dir)
            val p = s2.products().single()
            p.asin == "B0TEST0001" && p.cap == 120.0 && p.buy && !p.inProgress && p.attempted && s2.settings().watching
        }
        t("05 out of stock: noted, no message, no checkout") {
            val w = World(); w.add("B0TEST0002", 120.0, true, stock = false); w.check("B0TEST0002")
            w.p("B0TEST0002").status.startsWith("out of stock") && w.said.restocks.isEmpty() && w.amz.begins == 0
        }
        t("06 restock (not set to buy): ONE message per restock; snooze silences") {
            val w = World(); w.add("B0TEST0001", 120.0, false)
            w.check("B0TEST0001"); w.check("B0TEST0001")
            val one = w.said.restocks.size == 1
            w.amz.items["B0TEST0001"]!!.stock = false; w.check("B0TEST0001"); w.amz.items["B0TEST0001"]!!.stock = true; w.check("B0TEST0001")
            val two = w.said.restocks.size == 2
            w.store.setSettings { it.copy(snoozeUntil = w.clock.t + 3 * 3600_000) }
            w.amz.items["B0TEST0001"]!!.stock = false; w.check("B0TEST0001"); w.amz.items["B0TEST0001"]!!.stock = true; w.check("B0TEST0001")
            one && two && w.said.restocks.size == 2 && w.amz.begins == 0
        }
        t("07 over the cap: not bought, no message") {
            val w = World(); w.add("B0TEST0003", 120.0, true, price = 199.99); w.check("B0TEST0003")
            w.amz.begins == 0 && w.said.restocks.isEmpty() && w.p("B0TEST0003").status.contains("over your cap")
        }
        t("08 practice mode: the checkout stops before Place your order; once per restock") {
            val w = World(practice = true); w.add("B0TEST0001", 120.0, true); w.check("B0TEST0001")
            val p = w.p("B0TEST0001")
            val first = w.amz.places == 0 && w.amz.replyOk == false && w.said.practice.size == 1 && w.said.practice[0].contains("total \$107.74") && !p.attempted && p.practiceOkAt > 0
            w.check("B0TEST0001")
            first && w.amz.begins == 1 && w.amz.places == 0
        }
        t("09 real buy: lock written BEFORE Place your order, confirmed, found in your orders, then told") {
            val w = World(); w.add("B0TEST0001", 120.0, true); w.check("B0TEST0001")
            val p = w.p("B0TEST0001")
            w.amz.lockSeenAtReply == true && w.amz.places == 1 && p.purchased && p.attempted && !p.buy && !p.inProgress &&
                w.said.bought == listOf("B0TEST0001|111-1000000-2000001|true") && w.store.purchases().single().verified
        }
        t("10 one purchase per product: still in stock after the purchase -> no second order") {
            val w = World(); w.add("B0TEST0001", 120.0, true); w.check("B0TEST0001"); w.check("B0TEST0001"); w.check("B0TEST0001")
            w.store.update("idB0TEST0001") { it.copy(buy = true) }        // even switched back to Buy without Re-arm
            w.check("B0TEST0001")
            w.amz.places == 1 && w.amz.begins == 1
        }
        t("11 buying switched off: nothing bought, a restock message instead") {
            val w = World(buying = false); w.add("B0TEST0001", 120.0, true); w.check("B0TEST0001")
            w.amz.begins == 0 && w.said.restocks.size == 1
        }
        t("12 robot check: never answered; rests; one message; no page opened while resting") {
            val w = World(); w.add("B0TEST0004", 120.0, true); w.amz.items["B0TEST0004"]!!.captcha = true
            w.check("B0TEST0004")
            val g = w.amz.gotoCount
            w.engine.step(); w.engine.step()
            w.store.settings().cooldownUntil > w.clock.t && w.said.needYou.size == 1 && w.amz.gotoCount == g && w.amz.begins == 0
        }
        t("13 Amazon says no order: not placed, may be tried again") {
            val w = World(); w.add("B0TEST0001", 120.0, true); w.amz.placeFail = 1; w.check("B0TEST0001")
            val p = w.p("B0TEST0001")
            !p.purchased && !p.attempted && p.fails == 1 && p.status.startsWith("NOT BOUGHT") && w.said.bought.isEmpty()
        }
        t("14 three failed checkouts in one restock: stops trying for 10 min and tells you") {
            val w = World(); w.add("B0TEST0001", 120.0, true); w.amz.placeFail = 3
            w.check("B0TEST0001"); w.check("B0TEST0001"); w.check("B0TEST0001"); w.check("B0TEST0001")
            val p = w.p("B0TEST0001")
            w.amz.begins == 3 && p.lockedUntil > w.clock.t && w.said.needYou.any { it.startsWith("Stopped trying") }
        }
        t("15 no confirmation, but the order is in your orders: counted as bought") {
            val w = World(); w.add("B0TEST0001", 120.0, true); w.amz.noConfirm = true; w.check("B0TEST0001")
            val p = w.p("B0TEST0001")
            p.purchased && w.said.bought.size == 1 && w.said.bought[0].endsWith("|true")
        }
        t("16 no confirmation and not in your orders: not bought again, you are told to check") {
            val w = World(); w.add("B0TEST0001", 120.0, true); w.amz.noConfirm = true
            val orig = w.amz.orders
            w.check("B0TEST0001".also { })
            // (make the order invisible: an order list that does not show it)
            val p0 = w.p("B0TEST0001")
            val w2 = World(); w2.add("B0TEST0001", 120.0, true); w2.amz.noConfirm = true; w2.amz.signinOrders = true; w2.check("B0TEST0001")
            val p = w2.p("B0TEST0001"); w2.store.update(p.id) { it.copy(buy = true) }; w2.check("B0TEST0001")
            orig.size == 1 && p0.purchased && p.attempted && !p.purchased && w2.amz.places == 1 && w2.said.needYou.any { it.startsWith("Check your Amazon orders") }
        }
        t("17 an older order of the same product is not taken for the new one") {
            val w = World(); w.add("B0TEST0001", 120.0, true)
            w.amz.orders.add("111-0000001-0000001" to "B0TEST0001")
            w.engine.readOrders()
            w.amz.noConfirm = true; w.check("B0TEST0001")
            w.said.bought.size == 1 && w.said.bought[0].contains("111-1000001-") && w.store.settings().ordersKnown["B0TEST0001"] == listOf("111-0000001-0000001")
        }
        t("18 order list needs a sign-in: you are told to sign in") {
            val w = World(); w.add("B0TEST0001", 120.0, true); w.amz.signinOrders = true; w.engine.readOrders()
            w.said.needYou.any { it.startsWith("Sign in to Amazon") }
        }
        t("19 a page that shows another product: never bought") {
            val w = World(); w.add("B0TEST0001", 120.0, true); w.amz.items["B0TEST0001"]!!.wrongPage = true; w.check("B0TEST0001")
            w.amz.begins == 0 && w.p("B0TEST0001").status.startsWith("NOT THIS PRODUCT")
        }
        t("20 Buy now on a restock message: set to buy and bought at once") {
            val w = World(buying = false); w.add("B0TEST0001", 120.0, false); w.check("B0TEST0001")
            w.engine.send(Engine.Cmd.BuyNow("idB0TEST0001")); w.engine.step()
            w.said.restocks.size == 1 && w.amz.places == 1 && w.p("B0TEST0001").purchased
        }
        t("21 a page that never answers: no crash, noted") {
            val w = World(); w.add("B0TEST0001", 120.0, true); w.amz.silent = true; w.check("B0TEST0001")
            w.p("B0TEST0001").status == "the page did not answer" && w.amz.begins == 0
        }
        t("22 the product name and words are taken from Amazon's title the first time") {
            val w = World(); w.add("B0TEST0002", 120.0, false, stock = false); w.check("B0TEST0002")
            val p = w.p("B0TEST0002")
            p.name.contains("Test Product B0TEST0002") && p.tokens.contains("collector") && p.forPage()["tokens"] == p.tokens
        }
        t("23 the schedule: each product once per interval, the most overdue first; nothing while watching is off") {
            val w = World(); w.add("B0TEST0001", 120.0, false, stock = false); w.add("B0TEST0002", 120.0, false, stock = false)
            w.engine.step(); w.engine.step()      // (first step reads nothing: no product set to buy -> no order list)
            val a = w.p("B0TEST0001").lastCheck; val b = w.p("B0TEST0002").lastCheck
            val g = w.amz.gotoCount
            w.engine.step()          // both were just looked at: it waits (the test clock jumps)
            val waited = w.amz.gotoCount == g
            w.store.setSettings { it.copy(watching = false) }; val g2 = w.amz.gotoCount; repeat(3) { w.engine.step() }
            a > 0 && b > 0 && waited && w.amz.gotoCount == g2
        }
        t("24 the page gets the product's kind (box / bundle / pack) to check, like the desktop's table row") {
            val p = Product(id = "x", asin = "B0TEST0001", cap = 10.0, kind = "box")
            p.forPage()["rkShape"] == "box" && Product(id = "y", asin = "B0TEST0001", kind = "other").forPage()["rkShape"] == null
        }

        t("25 damaged data files: the app still starts, they are set aside and told") {
            val d = kotlin.io.path.createTempDirectory("rkbad").toFile()
            java.io.File(d, "products.json").writeText("[{\"id\":\"x\", broken")
            java.io.File(d, "settings.json").writeText("{nope")
            java.io.File(d, "purchases.json").writeText("{\"not\":\"a list\"}")
            val st = Store(d)
            st.products().isEmpty() && !st.settings().watching && st.purchases().isEmpty() && java.io.File(d, "products.json.bad").exists() &&
                st.damaged.size == 3 && st.log().last().contains("damaged") && st.add(Product(id = "n", asin = "B0TEST0001", cap = 1.0))
        }
        t("26 Amazon short links (a.co / amzn.to) and shared text are followed to the product; other sites are not") {
            val hops = mapOf("https://a.co/d/abc123" to "https://www.amazon.com/dp/B0GXC89N66?ref=share", "https://amzn.to/3xYz" to "https://a.co/d/abc123",
                "https://a.co/d/evil" to "https://evil.com/dp/B0GXC89N66", "https://a.co/d/loop" to "https://a.co/d/loop")
            val f: (String) -> String? = { hops[it] }
            Asin.resolve("Check out this item on Amazon https://a.co/d/abc123", f) == "B0GXC89N66" && Asin.resolve("amzn.to/3xYz", f) == "B0GXC89N66" &&
                Asin.resolve("https://a.co/d/evil", f) == null && Asin.resolve("https://a.co/d/loop", f) == null && Asin.resolve("https://a.co/d/none", f) == null &&
                Asin.resolve("https://bit.ly/x", f) == null && Asin.resolve("B0GXC89N66", f) == "B0GXC89N66"
        }
        t("27 no internet: no page opened; told once after 10 minutes") {
            val w = World(net = null); w.add("B0TEST0001", 120.0, true)
            repeat(60) { w.engine.step() }       // (each waits 15 s on the test clock = 15 minutes)
            w.amz.gotoCount == 0 && w.said.needYou.count { it.startsWith("No internet") } == 1
        }
        t("28 Wi-Fi only on mobile data: no page opened; on Wi-Fi it looks again") {
            val w = World(net = "mobile"); w.add("B0TEST0002", 120.0, false, stock = false); w.store.setSettings { it.copy(wifiOnly = true) }
            repeat(5) { w.engine.step() }
            val none = w.amz.gotoCount == 0
            w.net = "wifi"; repeat(3) { w.engine.step() }
            none && w.amz.gotoCount > 0
        }
        t("29 Amazon not loading 10 times in a row: you are told once") {
            val w = World(); w.add("B0TEST0002", 120.0, false, stock = false); w.amz.loadFails = true
            repeat(15) { w.check("B0TEST0002") }
            w.said.needYou.count { it.startsWith("Amazon is not loading") } == 1 && w.p("B0TEST0002").status == "the page did not load"
        }
        t("30 Amazon asks to sign in at the checkout: you are told to sign in; nothing ordered") {
            val w = World(); w.add("B0TEST0001", 120.0, true); w.amz.signinCheckout = true; w.check("B0TEST0001")
            w.amz.places == 0 && w.said.needYou.any { it.startsWith("Sign in to Amazon") } && !w.p("B0TEST0001").attempted
        }
        t("31 the product is removed while it is being bought: no crash, the purchase is still recorded") {
            val w = World(); w.add("B0TEST0001", 120.0, true)
            val s0 = w.store
            w.amz.store = null
            val p = w.p("B0TEST0001")
            // remove it at the moment Place your order is allowed
            val amz2 = object : Page by w.amz {
                override fun ask(msg: Map<String, Any?>, timeoutMs: Long): Map<String, Any?>? {
                    if (msg["bb"] == "reply") s0.remove(p.id)
                    return w.amz.ask(msg, timeoutMs)
                }
            }
            val e2 = Engine(w.store, amz2, w.said, w.clock)
            e2.check(p)
            w.amz.places == 1 && w.store.purchases().size == 1 && w.said.bought.size == 1
        }
        t("32 Re-arm after a purchase: it may be bought again (a new order)") {
            val w = World(); w.add("B0TEST0001", 120.0, true); w.check("B0TEST0001")
            w.store.update("idB0TEST0001") { it.copy(purchased = false, attempted = false, buy = true, orderNumber = null) }
            w.amz.items["B0TEST0001"]!!.stock = false; w.check("B0TEST0001"); w.amz.items["B0TEST0001"]!!.stock = true; w.check("B0TEST0001")
            w.amz.places == 2 && w.store.purchases().size == 2 && w.said.bought.all { it.endsWith("|true") }
        }
        t("33 the price cap is never raised by the app, and a cap of 0 never buys") {
            val w = World(); w.add("B0TEST0001", 0.0, true, price = 0.0); w.check("B0TEST0001")
            w.amz.begins == 0 && w.p("B0TEST0001").cap == 0.0
        }
        t("34 Amazon wants a sign-in: told ONCE, no checkout tries until the sign-in browser was used (or 1 hour)") {
            val w = World(); w.add("B0TEST0001", 120.0, true); w.amz.signinCheckout = true
            w.check("B0TEST0001"); w.check("B0TEST0001"); w.check("B0TEST0001")
            val once = w.said.needYou.count { it.startsWith("Sign in to Amazon") } == 1 && w.amz.begins == 1
            w.store.setSettings { it.copy(signinNeededAt = 0) }; w.amz.signinCheckout = false      // (you signed in)
            w.check("B0TEST0001")
            once && w.amz.places == 1
        }
        t("35 the sign-in hold ends by itself after 1 hour") {
            val w = World(); w.add("B0TEST0001", 120.0, true); w.amz.signinCheckout = true; w.check("B0TEST0001")
            w.amz.signinCheckout = false; w.clock.t += 61 * 60_000L; w.check("B0TEST0001")
            w.amz.places == 1
        }
        t("36 report: a private code made once and kept; the whole log sent as a file") {
            val w = World(); val c1 = Report.code(w.store); val c2 = Report.code(Store(w.dir))
            val sent = ArrayList<Triple<String, Map<String, String>, ByteArray>>()
            val ok = Report.send(w.store, "Restalker Mobile report", "LOG " + "x".repeat(200_000) + " END", "sent by you") { u, h, b -> sent.add(Triple(u, h, b)); 200 }
            Report.valid(c1) && c1 == c2 && ok && sent.single().first == "https://ntfy.sh/$c1" && sent.single().second["Filename"] == "restalker-mobile-report.txt" &&
                String(sent.single().third).endsWith(" END") && w.store.log().last().contains("Report sent")
        }
        t("37 report: no internet -> told, no crash; automatic reports at most every 30 minutes and only when on") {
            val w = World()
            var tries = 0
            val bad = Report.send(w.store, "t", "x", "y", retryMs = 1) { _, _, _ -> tries++; throw java.io.IOException("no internet") }
            val s0 = w.store.settings().copy(reportAt = 1_000_000L)
            !bad && tries == 3 && w.store.log().last().contains("could not be sent") && Report.autoDue(s0, 1_000_000L + 31 * 60_000L) &&
                !Report.autoDue(s0, 1_000_000L + 10 * 60_000L) && !Report.autoDue(s0.copy(autoReport = false), 9_000_000_000L)
        }
        t("38 a checkout that does not buy is a problem (a report may go); its Amazon messages are in the log") {
            val w = World(); w.add("B0TEST0001", 120.0, true); w.amz.placeFail = 1; w.check("B0TEST0001")
            w.said.problems.size == 1 && w.said.problems[0].contains("not bought") && w.store.log().any { it.contains("checkout step: DEFINITE_SUBMISSION_FAILURE") }
        }
        t("39 report: a report you send does not hold back the automatic one; a refusal for a moment is tried again") {
            val w = World(); var n = 0
            val ok = Report.send(w.store, "t", "x", "sent by you", retryMs = 1) { _, _, _ -> n++; if (n < 3) 503 else 200 }
            ok && n == 3 && Report.autoDue(w.store.settings(), System.currentTimeMillis())
        }
        t("40 link code: read, refused when wrong; sealed posts open only with the secret") {
            val c = LinkCrypto.make(); val (ch, sec) = LinkCrypto.parse(c)!!
            val w = LinkCrypto.seal(sec, "{\"a\":\"B0TEST0001\",\"x\":\"é ✓\"}")
            ch.startsWith("rkl-") && LinkCrypto.parse("RK1.abc") == null && LinkCrypto.parse(c + "x") == null && LinkCrypto.parse(null) == null &&
                LinkCrypto.unseal(sec, w) == "{\"a\":\"B0TEST0001\",\"x\":\"é ✓\"}" && LinkCrypto.unseal(ByteArray(32), w) == null &&
                LinkCrypto.unseal(sec, w.substring(0, 12) + (if (w[12] == 'A') 'B' else 'A') + w.substring(13)) == null && LinkCrypto.unseal(sec, "hello") == null && !w.contains("B0TEST0001")
        }
        t("41 a wrong link code is explained, nothing changes") {
            val w = World(); val l = Link(w.store, object : LinkNet { override fun post(url: String, body: String, asFile: Boolean) = 200; override fun get(url: String) = "" })
            l.connect("https://example.com/not-a-code") != null && !l.linked() && l.cycle() == "not linked" && !l.desktopBuys()
        }
        t("42 linked desktop buys: this phone does not check out, stands by every 10 min, no restock message for a Buy product") {
            val w = World(desk = true); w.add("B0TEST0001", 120.0, true); w.check("B0TEST0001")
            w.amz.begins == 0 && w.said.restocks.isEmpty() && w.store.log().any { it.contains("the linked desktop buys it") } &&
                run { w.clock.t += 2 * 60_000; w.amz.gotoCount = 0; w.engine.step(); w.amz.gotoCount == 0 && w.said.last.contains("stands by") }
        }
        t("43 desktop stopped / silent: this phone buys again") {
            val w = World(desk = true); w.add("B0TEST0001", 120.0, true); w.check("B0TEST0001")
            w.desk = false; w.clock.t += 120_000; w.check("B0TEST0001")
            w.amz.places == 1
        }
        t("44 Buy now on the phone's message buys at once even while the desktop buys") {
            val w = World(desk = true); w.add("B0TEST0001", 120.0, false); w.check("B0TEST0001")
            w.engine.send(Engine.Cmd.BuyNow(w.p("B0TEST0001").id)); w.engine.step()
            w.amz.places == 1
        }
        t("45 home screen groups: the set from the title; the desktop's year / set when given") {
            Groups.setOf("Magic: The Gathering Nauctis: The Sunken Realm Collector Booster Display") == "Nauctis: The Sunken Realm" &&
            Groups.setOf("Magic: The Gathering | The Hobbit Collector Booster Box | 12 Packs") == "The Hobbit" &&
            Groups.setOf("MTG Star Trek Collector Booster Box") == "Star Trek" && Groups.setOf("Dragon Shield Sleeves") == "Dragon Shield Sleeves" &&
            Groups.setOf("Collector Booster Box") == "Other" &&
            Groups.of(Product(id = "x", asin = "B0TEST0001", name = "MTG Star Trek Collector Booster Box")) == (Groups.MINE to "Star Trek") &&
            Groups.of(Product(id = "x", asin = "B0TEST0001", name = "n", year = "2026", set = "Star Trek")) == ("2026" to "Star Trek") &&
            listOf("2025", Groups.MINE, "2026", "Other products").sortedBy { Groups.yearRank(it) } == listOf("2026", "2025", "Other products", Groups.MINE)
        }
        t("46 the product's picture is taken from its page once (Amazon's own image server only)") {
            val w = World(); w.add("B0TEST0001", 120.0, false); w.check("B0TEST0001")
            val ok1 = w.p("B0TEST0001").image == "https://m.media-amazon.com/images/I/B0TEST0001.jpg"      // (1.8: kept in its plain form)
            val w2 = World(); w2.amz.pictureUrl = "https://evil.example/x.jpg"; w2.add("B0TEST0002", 120.0, false); w2.check("B0TEST0002")
            ok1 && w2.p("B0TEST0002").image.isEmpty()
        }
        t("47 Buy 2: bought twice in separate orders, then stops; Buy 1 stays one order") {
            val w = World(); w.add("B0TEST0001", 120.0, true)
            w.store.update("idB0TEST0001") { it.copy(qty = 2, qtySince = w.clock.t - 1) }
            w.check("B0TEST0001"); val after1 = w.p("B0TEST0001")
            w.clock.t += 60_000; w.check("B0TEST0001")
            w.clock.t += 60_000; w.check("B0TEST0001")
            after1.buy && !after1.purchased && w.amz.places == 2 && w.p("B0TEST0001").purchased && !w.p("B0TEST0001").buy
        }
        t("48 in stock over the cap counts as in stock; the linked desktop's newer look wins") {
            val w = World(); w.add("B0TEST0001", 50.0, false, price = 99.99); w.check("B0TEST0001")
            val p = w.p("B0TEST0001")
            val over = p.overCap && p.inStock && p.stock().first == "over"
            val d = p.copy(dSt = "out", dPr = null, dAt = p.lastCheck + 1000)
            val older = p.copy(dSt = "in", dAt = p.lastCheck - 1000)
            over && d.stock().first == "out" && !d.inStock && older.stock().first == "over"
        }
        t("49 a picture for a product that is not watched: one look, Wi-Fi only, not again for a day") {
            val w = World(net = "mobile"); w.add("B0TEST0002", 50.0, false); w.store.update("idB0TEST0002") { it.copy(watch = false) }
            w.add("B0TEST0001", 120.0, false); w.check("B0TEST0001")
            w.clock.t += 5_000; w.amz.gotoCount = 0; w.engine.step()
            val noMobile = w.amz.gotoCount == 0 && w.p("B0TEST0002").image.isEmpty()
            w.net = "wifi"; w.clock.t += 5_000; w.engine.step()
            val got = w.p("B0TEST0002").image.contains("B0TEST0002")
            w.store.update("idB0TEST0002") { it.copy(image = "") }; w.amz.gotoCount = 0; w.clock.t += 5_000; w.engine.step()
            noMobile && got && w.amz.gotoCount == 0
        }
        t("50 linked: the desktop's purchase from BEFORE the link does not mark the product bought here; one after the link turns Buy off") {
            val w = World()
            var posts = ArrayList<String>()
            val code = LinkCrypto.make(); val (ch, sec) = LinkCrypto.parse(code)!!
            val inbox = ArrayList<String>()
            val net = object : LinkNet {
                override fun post(url: String, body: String, asFile: Boolean): Int { posts.add(body); return 200 }
                override fun get(url: String): String = inbox.joinToString("\n")
            }
            val l = Link(w.store, net) { w.clock.t }
            l.connect(code)
            val linkedAt = w.clock.t / 1000.0
            fun desk(ba: Double, t: Double) {
                val o = mapOf("v" to 1, "d" to "d-x", "r" to "desktop", "at" to t, "amz" to true, "items" to listOf(mapOf("a" to "B0TEST0005", "n" to "Old Buy",
                    "c" to 80.0, "b" to true, "w" to true, "k" to "bundle", "t" to t, "g" to false, "ba" to ba)))
                inbox.add(MiniJson.write(mapOf("event" to "message", "id" to "m${inbox.size + 1}", "time" to t, "message" to LinkCrypto.seal(sec, MiniJson.write(o)))))
            }
            desk(linkedAt - 30 * 86400, 2.0); l.cycle()
            val p1 = w.p("B0TEST0005")
            w.clock.t += 60_000; desk(w.clock.t / 1000.0, w.clock.t / 1000.0); l.cycle()
            val p2 = w.p("B0TEST0005")
            !p1.purchased && p1.buy && !p2.purchased && !p2.buy
        }
        // ---------------- 1.7: pictures (owner: "The pictures aren't populating still") and the self-check (owner: "The error report didn't automatically send")
        val RAW = "https://m.media-amazon.com/images/I/81Xk2+abcL._AC_SL1500_.jpg"
        val I = "https://m.media-amazon.com/images/I/81Xk2+abcL.jpg"           // (1.8: the plain form a picture is kept in)
        val GREY = "https://images-na.ssl-images-amazon.com/images/G/01/x-locale/common/grey-pixel.gif"
        val LOGO = "https://m.media-amazon.com/images/G/01/social_share/amazon_logo._CB633267191_.png"
        t("70_pics_every_form_of_a_product_picture_becomes_its_plain_form") { listOf(RAW, I, "http://m.media-amazon.com/images/I/81Xk2+abcL._AC_SX679_.jpg",
            "//m.media-amazon.com/images/I/81Xk2+abcL._AC_UL495_SR495,495_.jpg", "https://images-na.ssl-images-amazon.com/images/I/81Xk2+abcL.png",
            "https://ecx.images-amazon.com/images/I/81Xk2+abcL._SL500_.webp", "https://m.media-amazon.com/images/I/81Xk2+abcL._AC_SX300_SY300_QL70_FMwebp_.avif",
            "https://m.media-amazon.com/images/I/81Xk2+abcL._AC_SL1500_.jpg?crop=1", " $RAW ", "https://m.media-amazon.com/images/I/81Xk2+abcL._AC_SL1500_.JPG").all { Pics.canon(it) == I } &&
            Pics.valid(I) && !Pics.valid(RAW) }
        t("71_pics_grey_pixel_logo_css_js_never") { listOf(GREY, LOGO, "https://m.media-amazon.com/images/I/61+btpCHNOL.css?AUIClients/VSEComponentAssets",
            "https://m.media-amazon.com/images/I/61xJcNKKLXL.js?AUIClients/AmazonUIjQuery", "https://m.media-amazon.com/images/I/21-lHLRbUgL._RC|51fSDNsfwdL.css_.css?AUIClients/AmazonUI")
            .none { Pics.canon(it) != null || Pics.valid(it) || Pics.validAny(it) } }
        t("72_pics_lookalike_hosts_refused") { listOf("https://m.media-amazon.com.evil.example/images/I/81Xk2abc.jpg", "https://evil.example/images/I/81Xk2abc.jpg",
            "https://evil.example/x?u=https://m.media-amazon.com/images/I/81Xk2abc.jpg", "https://m.media-amazon.com/images/I/" + "x".repeat(450) + ".jpg",
            "https://m.media-amazon.com/images/I/ab.jpg", "", "javascript:alert(1)", "ftp://m.media-amazon.com/images/I/81Xk2abc.jpg").none { Pics.canon(it) != null } && Pics.canon(null) == null }
        t("73_pics_small_version") { Pics.small(I) == "https://m.media-amazon.com/images/I/81Xk2+abcL._AC_SL240_.jpg" && Pics.small(RAW) == Pics.small(I) &&
            Pics.tries(I) == listOf("https://m.media-amazon.com/images/I/81Xk2+abcL._AC_SL240_.jpg", I) &&
            Pics.small("https://target.scene7.com/is/image/Target/x") == "https://target.scene7.com/is/image/Target/x" }
        t("74_pics_other_stores_only_from_desktop") { Pics.validAny("https://target.scene7.com/is/image/Target/GUEST_1?wid=200") && !Pics.valid("https://target.scene7.com/is/image/Target/GUEST_1")
            && Pics.validAny("https://pisces.bbystatic.com/image2/BestBuy_US/images/products/6686/6686924_sd.jpg") && !Pics.validAny("https://evil.example/x.jpg")
            && Pics.desk(RAW) == I && Pics.desk("https://evil.example/x.jpg") == null
            && Pics.validAny("https://tcgplayer-cdn.tcgplayer.com/product/12345_200w.jpg") && Pics.validAny("https://i5.walmartimages.com/seo/x.jpeg?odnHeight=192")
            && !Pics.validAny("https://tcgplayer.com.evil.example/x.jpg") && !Pics.validAny("http://tcgplayer-cdn.tcgplayer.com/x.jpg") }
        t("75_kept_pictures_put_in_plain_form_placeholder_dropped") {
            val p = Product.fromMap(Product("a", "B0TEST0070", image = GREY, picTriedAt = 99).toMap())!!
            val q = Product.fromMap(Product("a", "B0TEST0070", image = "https://m.media-amazon.com/images/I/810ZB7WLddL._AC_UL495_SR495,495_.jpg", picTriedAt = 99).toMap())!!
            val r = Product.fromMap(Product("a", "B0TEST0070", dImg = "https://evil.example/x.jpg").toMap())!!
            val d = Product.fromMap(Product("a", "B0TEST0070", dImg = RAW).toMap())!!
            p.image == "" && p.picTriedAt == 0L && q.image == "https://m.media-amazon.com/images/I/810ZB7WLddL.jpg" && q.picTriedAt == 99L && r.dImg == "" && d.dImg == I }
        t("76_shown_own_first_then_desktop") {
            val d = "https://target.scene7.com/is/image/Target/GUEST_1"
            Pics.shown(Product("a", "B0TEST0071", image = I, dImg = d)) == listOf(d, I) && Pics.shown(Product("a", "B0TEST0071", dImg = d)) == listOf(d) &&
                Pics.shown(Product("a", "B0TEST0071", image = GREY)).isEmpty() && Pics.shown(Product("a", "B0TEST0071", image = I, dImg = I)) == listOf(I) }
        t("77_short_name_for_diagnostics") { Pics.short(I) == "81Xk2+abcL.jpg" && Pics.short("https://target.scene7.com/is/x") == "target.scene7.com" }
        val now0 = 1_800_000_000_000L
        fun hin(vararg ps: Product, f: (HealthIn) -> HealthIn = { it }) = f(HealthIn(now = now0, products = ps.toList(), purchases = emptyList(), settings = Settings()))
        t("78_health_nothing_wrong") { Health.check(hin(Product("a", "B0TEST0072", image = I))).isEmpty() }
        t("79_health_pictures_do_not_load") {
            val r = Health.check(hin(Product("a", "B0TEST0072", image = I)) { it.copy(picFails = { 2 }, picStatus = { "failed: the picture server answered 403" }) })
            r.size == 1 && r[0].first == "pics-load" && r[0].second.contains("403") && r[0].second.contains("B0TEST0072") }
        t("80_health_one_failure_is_not_a_problem_yet") { Health.check(hin(Product("a", "B0TEST0072", image = I)) { it.copy(picFails = { 1 }) }).isEmpty() }
        t("81_health_desktop_picture_that_loads_is_fine") {
            val d = "https://target.scene7.com/is/image/Target/GUEST_1"
            Health.check(hin(Product("a", "B0TEST0072", image = I, dImg = d)) { it.copy(picFails = { u -> if (u == I) 3 else 0 }) }).isEmpty() }
        t("82_health_no_picture_on_page") {
            val r = Health.check(hin(Product("a", "B0TEST0073", lastCheck = 5)) { it.copy(picInfo = { "els=1 src-,meta- url=/dp/B0TEST0073" }) })
            r.map { it.first } == listOf("pics-missing") && r[0].second.contains("src-") }
        t("83_health_not_looked_yet_or_found_is_fine") { Health.check(hin(Product("a", "B0TEST0073"))).isEmpty() &&
            Health.check(hin(Product("a", "B0TEST0073", image = I)) { it.copy(picInfo = { "found" }) }).isEmpty() }
        val wOn = Settings(watching = true, intervalSec = 90)
        t("84_health_service_stopped") { Health.check(hin(Product("a", "B0TEST0074", image = I)) { it.copy(settings = wOn) }).map { it.first } == listOf("service") }
        t("85_health_stalled_and_its_exceptions") {
            val p = Product("a", "B0TEST0074", image = I, lastCheck = now0 - 20 * 60_000)
            val base = { x: HealthIn -> x.copy(settings = wOn, serviceRunning = true, serviceSince = now0 - 3_600_000) }
            Health.check(hin(p, f = base)).map { it.first } == listOf("stalled") &&
                Health.check(hin(p) { base(it).copy(deskBuys = true) }).isEmpty() &&            // (the desktop buys: a look every 30 min)
                Health.check(hin(p) { base(it).copy(net = null) }).isEmpty() &&
                Health.check(hin(p) { base(it).copy(net = "mobile", settings = wOn.copy(wifiOnly = true)) }).isEmpty() &&
                Health.check(hin(p) { base(it).copy(busy = true) }).isEmpty() &&
                Health.check(hin(p) { base(it).copy(settings = wOn.copy(cooldownUntil = now0 + 1)) }).isEmpty() &&
                Health.check(hin(p) { base(it).copy(serviceSince = now0 - 60_000) }).isEmpty() &&      // (just started)
                Health.check(hin(p.copy(lastCheck = now0 - 60_000), f = base)).isEmpty() &&
                Health.check(hin(p.copy(watch = false), f = base)).isEmpty() }
        t("86_health_link_data_latch_crash") {
            val r = Health.check(hin(Product("a", "B0TEST0075", image = I, purchased = true)) {
                it.copy(linked = true, linkFailsInRow = 12, linkLast = "read failed: timeout", mbPerHour = 79.0, hoursWatching = 2.0, crash = "java.lang.IllegalStateException: x\n at y") })
            r.map { it.first } == listOf("crash", "link", "data", "latch") && r[0].second.contains("IllegalStateException") }
        t("87_health_bought_with_record_and_low_data_fine") {
            Health.check(hin(Product("a", "B0TEST0075", image = I, purchased = true)) {
                it.copy(purchases = listOf(Purchase(1, "B0TEST0075", "x", 1.0, "111-1", true)), mbPerHour = 79.0, hoursWatching = 0.5, linked = true, linkFailsInRow = 9) }).isEmpty() }
        t("88_due_waits_10_min_then_once_a_day_crash_at_once") {
            val seen = HashMap<String, Long>(); val rep = HashMap<String, Long>()
            val pr = listOf("stalled" to "x", "crash" to "c")
            val a = Health.due(pr, seen, rep, now0)                                   // crash at once, stalled not yet
            val b = Health.due(pr, seen, rep, now0 + 9 * 60_000)
            val c = Health.due(pr, seen, rep, now0 + 10 * 60_000)
            rep["stalled"] = now0 + 10 * 60_000; rep["crash"] = now0
            val d = Health.due(pr, seen, rep, now0 + 3_600_000)                       // reported: not again within a day
            val e = Health.due(listOf(), seen, rep, now0 + 3_700_000)                 // gone: forgotten
            val forgot = seen.isEmpty()
            val f = Health.due(listOf("stalled" to "x"), seen, rep, now0 + 25 * 3_600_000)   // back a day later: waits 10 min again
            val g = Health.due(listOf("stalled" to "x"), seen, rep, now0 + 25 * 3_600_000 + 10 * 60_000)
            a.map { it.first } == listOf("crash") && b.map { it.first } == listOf("crash") && c.map { it.first } == listOf("stalled", "crash") &&
                d.isEmpty() && e.isEmpty() && forgot && f.isEmpty() && g.map { it.first } == listOf("stalled") }
        t("89_link_takes_the_desktop_picture") {
            val w = World()
            w.store.add(Product("p1", "B0TEST0076", name = "Pic Test", cap = 50.0))
            val code = LinkCrypto.make(); val (_, sec) = LinkCrypto.parse(code)!!
            val inbox = ArrayList<String>()
            val net = object : LinkNet {
                override fun post(url: String, body: String, asFile: Boolean): Int = 200
                override fun get(url: String): String = inbox.joinToString("\n")
            }
            val l = Link(w.store, net) { w.clock.t }
            l.connect(code)
            fun desk(im: String?, t: Double) {
                val it = linkedMapOf<String, Any?>("a" to "B0TEST0076", "n" to "Pic Test", "c" to 50.0, "b" to false, "w" to true, "k" to "box", "t" to 1.0, "g" to false, "ba" to 0,
                    "st" to "out", "pr" to null, "ss" to t)
                if (im != null) it["im"] = im
                val o = mapOf("v" to 1, "d" to "d-x", "r" to "desktop", "at" to t, "amz" to true, "items" to listOf(it))
                inbox.add(MiniJson.write(mapOf("event" to "message", "id" to "m${inbox.size + 1}", "time" to t, "message" to LinkCrypto.seal(sec, MiniJson.write(o)))))
            }
            val d = "https://pisces.bbystatic.com/image2/BestBuy_US/images/products/6686/6686924_sd.jpg"
            desk(d, w.clock.t / 1000.0); l.cycle(); val a = w.p("B0TEST0076").dImg
            w.clock.t += 60_000; desk("https://evil.example/x.jpg", w.clock.t / 1000.0); l.cycle(); val b = w.p("B0TEST0076").dImg
            w.clock.t += 60_000; desk(d, w.clock.t / 1000.0); l.cycle()
            w.clock.t += 60_000; desk(null, w.clock.t / 1000.0); l.cycle(); val c = w.p("B0TEST0076").dImg      // (an older desktop: kept)
            a == d && b == "" && c == d }
        // ---------------- 1.7: the app updates itself (owner: "Why do i have to manually download everything still?")
        val SHA = "a".repeat(64)
        fun note(code: Any?, apk: String = "https://github.com/stevenmanuel01-cloud/Restalker/releases/download/mobile-v1.8/restalker-mobile-1.8.apk", sha: String? = SHA, v: String? = "1.8") =
            MiniJson.write(linkedMapOf("version" to v, "code" to code, "apk" to apk, "sha256" to sha))
        t("90_update_newer_taken") { UpdatePlan.parse(note(9), 8)?.let { it.code == 9L && it.version == "1.8" && it.sha256 == SHA } == true }
        t("91_update_same_or_older_ignored") { UpdatePlan.parse(note(8), 8) == null && UpdatePlan.parse(note(6), 8) == null }
        t("92_update_only_from_the_programs_own_releases") { listOf("https://evil.example/restalker.apk", "http://github.com/stevenmanuel01-cloud/Restalker/releases/download/mobile-v1.8/x.apk",
            "https://github.com/someoneelse/Restalker/releases/download/mobile-v1.8/x.apk", "https://github.com/stevenmanuel01-cloud/Restalker/releases/download/mobile-v1.8/../../x.apk",
            "https://github.com/stevenmanuel01-cloud/Restalker/releases/download/mobile-v1.8/x.apk?u=1", "https://github.com/stevenmanuel01-cloud/Restalker/releases/download/mobile-v1.8/x.zip",
            "https://github.com/stevenmanuel01-cloud/Restalker/releases/download/mobile-v1.8.evil.example/x.apk".replace("download/mobile-v", "download/mobile-v")
        ).all { UpdatePlan.parse(note(9, apk = it), 8) == null || it.contains("mobile-v1.8.evil") } }
        t("93_update_needs_a_real_fingerprint") { listOf(null, "", "abc", "g".repeat(64), "a".repeat(63)).all { UpdatePlan.parse(note(9, sha = it), 8) == null } &&
            UpdatePlan.parse(note(9, sha = "A".repeat(64)), 8)?.sha256 == SHA }
        t("94_update_odd_notes_ignored") { listOf(null, "", "not json", "[]", note(null), note("9x"), note(9, v = null), note(9, v = "1.8; rm"), note(5000), note(9, apk = "")).all { UpdatePlan.parse(it, 8) == null } }
        t("95_update_never_during_a_purchase") { UpdatePlan.canInstallNow(listOf(Product("a", "B0TEST0090")), "idle") && UpdatePlan.canInstallNow(emptyList(), null) &&
            !UpdatePlan.canInstallNow(listOf(Product("a", "B0TEST0090", inProgress = true)), "idle") && !UpdatePlan.canInstallNow(emptyList(), "buying Star Trek") &&
            !UpdatePlan.canInstallNow(emptyList(), "at the checkout") && !UpdatePlan.canInstallNow(emptyList(), "checking out Star Trek (practice)") && UpdatePlan.canInstallNow(emptyList(), "looking at Star Trek Buy Box") }
        t("96_update_failure_goes_into_the_selfcheck") { Health.check(hin(Product("a", "B0TEST0091", image = I)) { it.copy(updateFail = "update download failed: answer 404") })
            .map { it.first } == listOf("update") }
        t("97_settings_update_by_itself_on_by_default_and_kept") { Settings.fromMap(null).autoUpdate && Settings.fromMap(emptyMap()).autoUpdate &&
            !Settings.fromMap(Settings(autoUpdate = false).toMap()).autoUpdate }
        // ---------------- 1.9 (the 1.8 report: one picture id - a 404 on Amazon's picture server - was taken for 7 different products)
        val B = "https://m.media-amazon.com/images/I/31uI7WnC0LL.jpg"
        t("98_picture_of_another_product_or_bad_never_taken") {
            val ps = listOf(Product("a", "B0TEST0098", image = B), Product("b", "B0TEST0099"))
            Pics.choose(listOf(B, RAW), "B0TEST0099", ps, emptySet()) == I && Pics.choose(listOf(B), "B0TEST0099", ps, emptySet()) == null &&
                Pics.choose(listOf(B), "B0TEST0098", ps, emptySet()) == B && Pics.choose(listOf(RAW, B), "B0TEST0099", ps, setOf("81Xk2+abcL")) == null &&
                Pics.choose(listOf(null, "junk", RAW), "B0TEST0099", ps, emptySet()) == I && Pics.id(RAW) == "81Xk2+abcL" }
        t("99_repair_drops_shared_and_404_pictures") {
            val ps = listOf(Product("a", "B0TEST0101", image = B), Product("b", "B0TEST0102", image = B), Product("c", "B0TEST0103", image = I),
                Product("d", "B0TEST0104", image = "https://m.media-amazon.com/images/I/41goodPicL.jpg"))
            val (bad, ids) = Pics.repair(ps, { u -> if (u == I) "failed: the picture server answered 404" else "ok" }, { u -> if (u == I) 2 else 0 })
            val (bad2, ids2) = Pics.repair(ps.drop(2), { u -> if (u == I) "failed: no internet" else "ok" }, { 5 })
            bad == setOf("31uI7WnC0LL", "81Xk2+abcL") && ids.toSet() == setOf("a", "b", "c") && bad2.isEmpty() && ids2.isEmpty() }
        // ---------------- 2.0 (owner's notes of Nov 17)
        t("100_price_bar_ticks_and_rows") {
            val pm = PriceMarks.of(399.99, 380.0, 455.88, 410.0)
            val k = pm.items.map { it.kind }
            val near = clear(pm)
            k.containsAll(listOf("zero", "msrp", "tcg", "cap", "now")) && pm.items.first { it.kind == "now" }.label == "Now \$400" && pm.frac(0.0) == 0f &&
                pm.frac(455.88) < 1f && near && PriceMarks.of(null, 90.0, null, null).items.map { it.kind } == listOf("zero", "cap") &&
                PriceMarks.of(1299.0, 1200.0, null, null).items.first { it.kind == "cap" }.label == "Cap \$1,200" }
        t("101_practice_over_the_cap_runs_and_never_orders") {
            val w = World(practice = false); w.add("B0TEST0201", 50.0, buy = false, price = 99.99)
            w.engine.send(Engine.Cmd.Practice("idB0TEST0201")); w.engine.step()
            w.amz.begins == 1 && w.amz.places == 0 && w.amz.replyOk == false && w.said.practice.any { it.contains("stopped") } && w.p("B0TEST0201").cap == 50.0 &&
                !w.p("B0TEST0201").attempted && !w.p("B0TEST0201").purchased }
        t("102_practice_out_of_stock_explains") {
            val w = World(); w.add("B0TEST0202", 50.0, buy = false, stock = false)
            w.engine.send(Engine.Cmd.Practice("idB0TEST0202")); w.engine.step()
            w.amz.begins == 0 && w.said.practice.any { it.contains("in stock (at any price)") } }
        t("103_practice_with_any_item_not_kept_not_bought") {
            val w = World(practice = false)
            w.amz.items["B0TEST0203"] = FakeAmazon.Item(true, 12.99, "Dragon Shield Matte Sleeves Black 100 count")
            w.engine.send(Engine.Cmd.PracticeAny("B0TEST0203")); w.engine.step()
            w.amz.begins == 1 && w.amz.places == 0 && w.store.products().none { it.asin == "B0TEST0203" } && w.said.practice.any { it.contains("stopped") } }
        t("104_practice_with_any_item_already_in_the_list_uses_it") {
            val w = World(practice = false); w.add("B0TEST0204", 500.0, buy = true, price = 99.99)
            w.engine.send(Engine.Cmd.PracticeAny("B0TEST0204")); w.engine.step()
            w.amz.begins == 1 && w.amz.places == 0 && w.store.products().count { it.asin == "B0TEST0204" } == 1 && !w.p("B0TEST0204").attempted }
        t("105_two_phones_one_buyer_and_a_phone_is_never_taken_for_the_desktop") {
            val code = LinkCrypto.make(); val (_, sec) = LinkCrypto.parse(code)!!
            val posts = ArrayList<String>()
            val net = object : LinkNet {
                override fun post(url: String, body: String, asFile: Boolean): Int { posts.add(body); return 200 }
                override fun get(url: String): String = posts.mapIndexed { i, b -> MiniJson.write(mapOf("event" to "message", "id" to "m$i", "time" to 1.8e9 + i, "message" to b)) }.joinToString("\n")
            }
            val wa = World(); val wb = World()
            var aBuys = true
            val la = Link(wa.store, net, { mapOf("amz" to aBuys, "av" to 10L) }) { wa.clock.t }
            val lb = Link(wb.store, net, { mapOf("amz" to true, "av" to 10L) }) { wb.clock.t }
            la.connect(code); lb.connect(code)
            la.cycle(); lb.cycle(); la.cycle(); lb.cycle()
            val one = la.otherPhoneBuys() != lb.otherPhoneBuys()
            val noDesk = !la.desktopBuys() && !lb.desktopBuys() && la.state().second.contains("waiting for the desktop") && la.otherPhones() == 1
            // the desktop buys: both stand by
            val o = mapOf("v" to 1, "d" to "d-x", "r" to "desktop", "at" to wa.clock.t / 1000.0, "amz" to true, "items" to emptyList<Any>())
            posts.add(LinkCrypto.seal(sec, MiniJson.write(o)))
            la.cycle(); lb.cycle()
            val bothStand = la.othersBuy() && lb.othersBuy()
            // the first phone does not buy (Buy off there): the other one is the buyer when the desktop stops
            val bye = mapOf("v" to 1, "d" to "d-x", "r" to "desktop", "at" to wa.clock.t / 1000.0, "amz" to true, "bye" to true, "items" to emptyList<Any>())
            posts.add(LinkCrypto.seal(sec, MiniJson.write(bye)))
            aBuys = false; la.touched(); la.cycle(); lb.cycle(); la.cycle()
            one && noDesk && bothStand && !lb.othersBuy() }
        t("106_every_product_field_kept_over_a_restart") {
            // (a regression guard: 1.8 / 1.9 lost Buy 1/2/3 at every start - a line of the reading code had become a comment)
            val p = Product(id = "x1", asin = "B0TEST0106", name = "N", cap = 12.5, kind = "box", watch = false, buy = true, tokens = listOf("a", "b"),
                status = "s", price = 9.5, lastCheck = 11, buyable = true, episodeStart = 12, alertedEpisode = 13, inProgress = true, attempted = true,
                purchased = true, submittedAt = 14, orderNumber = "111-1", fails = 2, failEpisode = 15, lockedUntil = 16, practiceOkAt = 17, year = "2026",
                set = "S", image = "https://m.media-amazon.com/images/I/81Xk2abcL.jpg", qty = 3, qtySince = 18, overCap = true, dSt = "over", dPr = 7.5,
                dAt = 19, msrp = 20.0, tcg = 21.0, others = listOf(mapOf("s" to "Target", "st" to "in", "pr" to 1.5, "at" to 22L)), picTriedAt = 23,
                dImg = "https://tcgplayer-cdn.tcgplayer.com/product/1_200w.jpg")
            val q = Product.fromMap(MiniJson.obj(MiniJson.write(p.toMap()))!!)!!
            q.copy(others = emptyList()) == p.copy(others = emptyList()) && q.others.size == 1 && q.qty == 3 && q.qtySince == 18L &&
                Settings.fromMap(MiniJson.obj(MiniJson.write(Settings(watching = true, buying = true, practice = false, intervalSec = 120, wifiOnly = true, autoReport = false,
                    runIcon = false, autoUpdate = false).toMap()))) == Settings(watching = true, buying = true, practice = false, intervalSec = 120, wifiOnly = true,
                    autoReport = false, runIcon = false, autoUpdate = false) }
        // ---- 2.2 (owner, 2026-11-17): who buys on Amazon (pc / phone / both), the PC's fast signal, the $5 cap steps, all products
        t("107_both_pc_failed_then_the_phone_orders") {
            val w = World(desk = true); w.mode = "both"; w.add("B0TEST0301", 120.0, true)
            w.verdicts["B0TEST0301"] = "failed" to w.clock.t
            w.check("B0TEST0301")
            w.amz.places == 1 && w.p("B0TEST0301").purchased }
        t("108_both_pc_bought_the_phone_does_not_order") {
            val w = World(desk = true); w.mode = "both"; w.add("B0TEST0302", 120.0, true)
            w.verdicts["B0TEST0302"] = "bought" to w.clock.t
            w.check("B0TEST0302")
            val p = w.p("B0TEST0302")
            w.amz.begins == 1 && w.amz.places == 0 && w.amz.replyOk == false && !p.buy && !p.attempted && !p.purchased && p.status.contains("PC BOUGHT") &&
                w.said.problems.isEmpty() }
        t("109_both_pc_silent_about_it_the_phone_orders_after_10s") {
            val w = World(desk = true); w.mode = "both"; w.add("B0TEST0303", 120.0, true)
            val t0 = w.clock.t
            w.check("B0TEST0303")
            w.amz.places == 1 && w.clock.t - t0 >= Engine.HOLD_NONE_MS }
        t("110_both_pc_started_and_never_said_how_it_ended_no_order") {
            val w = World(desk = true); w.mode = "both"; w.add("B0TEST0304", 120.0, true)
            w.verdicts["B0TEST0304"] = "started" to w.clock.t
            w.check("B0TEST0304")
            val p = w.p("B0TEST0304")
            w.amz.places == 0 && !p.attempted && p.status.contains("never two orders") }
        t("111_both_the_pc_word_arrives_while_waiting") {
            val w = World(desk = true); w.mode = "both"; w.add("B0TEST0305", 120.0, true)
            val t0 = w.clock.t
            w.verdictFn = { _ -> if (w.clock.t - t0 < 30_000) "started" to t0 else "failed" to w.clock.t }
            w.check("B0TEST0305")
            w.amz.places == 1 && w.clock.t - t0 >= 30_000 && w.clock.t - t0 < Engine.HOLD_MS }
        t("112_pc_mode_linked_pc_buys_the_phone_stands_by_and_status_says_why") {
            val w = World(desk = true); w.add("B0TEST0306", 120.0, true)
            w.check("B0TEST0306"); w.engine.step()
            w.amz.begins == 0 && w.said.last.contains("stands by") && w.said.last.contains("Who buys") }
        t("113_both_another_phone_first_in_line_this_one_does_not_buy") {
            val w = World(desk = true); w.mode = "both"; w.other = true; w.add("B0TEST0307", 120.0, true)
            w.check("B0TEST0307")
            w.amz.begins == 0 && w.amz.places == 0 }
        t("114_phone_mode_the_pc_signal_looks_at_once_and_buys") {
            val w = World(desk = false); w.mode = "phone"; w.add("B0TEST0308", 120.0, true)
            w.store.update("idB0TEST0308") { it.copy(lastCheck = w.clock.t) }          // (just looked: not due for a while)
            w.engine.send(Engine.Cmd.Go("B0TEST0308")); w.engine.step()
            w.amz.places == 1 && w.p("B0TEST0308").purchased }
        t("115_the_pc_signal_never_buys_by_itself") {
            // pc mode: ignored; phone mode: out of stock on this phone's own look -> nothing; over the cap -> nothing; not set to Buy -> nothing
            val w = World(desk = true); w.add("B0TEST0309", 120.0, true)
            w.engine.send(Engine.Cmd.Go("B0TEST0309")); w.engine.step()
            val pcIgnored = w.amz.begins == 0
            val w2 = World(desk = false); w2.mode = "phone"
            w2.add("B0TEST0310", 120.0, true, stock = false); w2.add("B0TEST0311", 120.0, true, price = 199.0); w2.add("B0TEST0312", 120.0, false)
            for (a in listOf("B0TEST0310", "B0TEST0311", "B0TEST0312")) { w2.engine.send(Engine.Cmd.Go(a)); w2.engine.step() }
            pcIgnored && w2.amz.begins == 0 && w2.amz.places == 0 }
        t("116_cap_steps_of_5") {
            CapStep.up(449.99) == 450.0 && CapStep.up(450.0) == 455.0 && CapStep.down(450.01) == 450.0 && CapStep.down(450.0) == 445.0 &&
                CapStep.down(7.0) == 5.0 && CapStep.down(5.0) == 5.0 && CapStep.up(0.0) == 5.0 && CapStep.snap(452.4) == 450.0 && CapStep.snap(452.6) == 455.0 &&
                CapStep.snap(0.3) == 5.0 && CapStep.scale(455.88, 550.0, 455.88, null) == 605.0 && CapStep.scale(null, 0.0, null, null) == 50.0 }
        t("117_price_bar_fixed_scale_and_no_cap_mark_without_a_cap") {
            val a = PriceMarks.of(500.0, 550.0, 455.88, null, 880.0)
            val b = PriceMarks.of(500.0, 0.0, 455.88, null)
            Math.abs(a.frac(440.0) - 0.5f) < 0.001f && b.items.none { it.kind == PriceMarks.CAP } && a.items.any { it.kind == PriceMarks.CAP } }
        t("118_all_products_come_without_a_cap_not_watched_never_bought") {
            val w = World(); w.add("B0TEST0313", 120.0, true)
            val json = MiniJson.write(mapOf("v" to 1, "products" to listOf(
                mapOf("a" to "B0TEST0313", "n" to "Already here", "k" to "box"),
                mapOf("a" to "B0TEST0314", "n" to "Magic: The Gathering Test Collector Booster Box", "k" to "box", "gy" to "2026", "gs" to "Test", "ms" to 455.88,
                    "sx" to listOf(mapOf("s" to "Target", "u" to "https://www.target.com/p/x/-/A-1"), mapOf("s" to "Bad", "u" to "http://x"))),
                mapOf("a" to "bad code", "n" to "x"))))
            val r = Catalog.import(json, w.store)!!
            val p = w.p("B0TEST0314")
            w.amz.items["B0TEST0314"] = FakeAmazon.Item(true, 99.0, "Magic: The Gathering Test Product B0TEST0314 Collector Booster Box")
            w.check("B0TEST0314")
            r.added == 1 && r.already == 1 && r.bad == 1 && p.cap == 0.0 && !p.watch && !p.buy && p.year == "2026" && p.msrp == 455.88 &&
                p.others.size == 1 && p.others[0]["u"] == "https://www.target.com/p/x/-/A-1" && w.amz.begins == 0 && w.p("B0TEST0313").cap == 120.0 &&
                Catalog.import("{}", w.store) == null && Catalog.import("not json", w.store) == null }
        t("119_new_install_buys_for_real_an_old_setting_is_kept") {
            val d = Settings()
            val old = Settings.fromMap(mapOf("watching" to true, "buying" to false, "practice" to true))
            val missing = Settings.fromMap(mapOf("watching" to true))
            d.buying && !d.practice && d.buyMode == "pc" && !old.buying && old.practice && missing.buying && !missing.practice &&
                Settings.fromMap(mapOf("buyMode" to "everyone")).buyMode == "pc" &&
                Settings.fromMap(MiniJson.obj(MiniJson.write(Settings(buyMode = "both", buyModeAt = 5).toMap()))).let { it.buyMode == "both" && it.buyModeAt == 5L } }
        // ---- 2.3 (owner, 2026-11-17): each device its own buying choice; Settings, Linked devices; the price bar's labels
        fun hub(): Pair<LinkNet, ArrayList<String>> {
            val posts = ArrayList<String>()
            return object : LinkNet {
                override fun post(url: String, body: String, asFile: Boolean): Int { posts.add(body); return 200 }
                override fun get(url: String): String = posts.mapIndexed { i, b -> MiniJson.write(mapOf("event" to "message", "id" to "m$i", "time" to 1.8e9 + i, "message" to b)) }.joinToString("\n")
            } to posts
        }
        t("120_a_choice_stays_on_its_own_device_the_pc_echo_changes_nothing") {
            val code = LinkCrypto.make(); val (_, sec) = LinkCrypto.parse(code)!!
            val (net, posts) = hub()
            val wa = World(); val wb = World()
            val la = Link(wa.store, net, { mapOf("amz" to true, "nm" to "Phone") }) { wa.clock.t }
            val lb = Link(wb.store, net, { mapOf("amz" to true, "nm" to "Tablet") }) { wb.clock.t }
            la.connect(code); lb.connect(code)
            wb.store.setSettings { it.copy(buyMode = "both", buyModeAt = wb.clock.t) }; lb.touched()
            la.cycle(); lb.cycle()
            wa.store.setSettings { it.copy(buyMode = "pc", buyModeAt = wa.clock.t + 5_000) }; la.touched(); la.cycle(); lb.cycle(); la.cycle()
            val sent = posts.mapNotNull { MiniJson.obj(LinkCrypto.unseal(sec, it)) }.last { it["nm"] == "Phone" }
            // the PC tells its own (newest) view - a "bm" from the PC is not taken by anyone
            posts.add(LinkCrypto.seal(sec, MiniJson.write(mapOf("v" to 1, "d" to "d-x", "r" to "desktop", "at" to wa.clock.t / 1000.0 + 100,
                "amz" to true, "items" to emptyList<Any>(), "bm" to "phone", "bmAt" to wa.clock.t / 1000.0 + 100))))
            la.cycle(); lb.cycle()
            sent["bm"] == "pc" && sent["nm"] == "Phone" && wa.store.settings().buyMode == "pc" && wb.store.settings().buyMode == "both" &&
                la.goTarget()!!.first.endsWith("-go") }
        t("121_pc_only_watches_and_buys_only_when_the_pc_stopped_and_it_takes_over") {
            val w = World(desk = true); w.add("B0TEST0321", 120.0, true)                 // PC buys -> watch only
            w.check("B0TEST0321"); val watched = w.amz.begins == 0
            val w2 = World(desk = false); w2.add("B0TEST0322", 120.0, true)               // the PC stopped, takes over on -> buys
            w2.check("B0TEST0322"); val tookOver = w2.amz.places == 1
            val w3 = World(desk = false); w3.fo = false; w3.add("B0TEST0323", 120.0, true)  // takes over off -> never buys
            w3.check("B0TEST0323"); val never = w3.amz.begins == 0
            val w4 = World(desk = false); w4.handed = true; w4.add("B0TEST0324", 120.0, true)   // the PC handed Amazon to another device -> not this one
            w4.check("B0TEST0324"); val handed = w4.amz.begins == 0
            val w5 = World(desk = true); w5.mode = "phone"; w5.add("B0TEST0325", 120.0, true)  // "Instead of PC" -> buys
            w5.check("B0TEST0325")
            watched && tookOver && never && handed && w5.amz.places == 1 }
        t("122_a_remote_change_reaches_only_that_device_and_an_older_one_is_not_taken") {
            val code = LinkCrypto.make()
            val (net, _) = hub()
            val wa = World(); val wb = World()
            val la = Link(wa.store, net, { mapOf("amz" to true, "nm" to "Phone") }) { wa.clock.t }
            val lb = Link(wb.store, net, { mapOf("amz" to true, "nm" to "Tablet") }) { wb.clock.t }
            la.connect(code); lb.connect(code)
            wa.store.setSettings { it.copy(buyMode = "both", buyModeAt = wa.clock.t) }; la.touched()
            la.cycle(); lb.cycle(); la.cycle()
            val devs = lb.devices("Tablet")
            val phone = devs.firstOrNull { !it.me && !it.pc }
            // the tablet sets the phone to "PC only", takes over off
            wb.clock.t += 10_000
            val ok = lb.setFor(phone!!.id, "pc", false)
            la.cycle()
            val took = wa.store.settings().buyMode == "pc" && !wa.store.settings().failover && wb.store.settings().buyMode == "pc" &&
                wa.store.log().any { it.contains("changed on Tablet") }
            // an older change (made before the phone's own newest one) is not taken
            wa.store.setSettings { it.copy(buyMode = "both", buyModeAt = wb.clock.t + 60_000) }; la.touched(); la.cycle()
            lb.setFor(phone.id, "phone", true); la.cycle()
            val kept = wa.store.settings().buyMode == "both"
            val shown = devs.any { it.me && it.name == "Tablet" } && phone.name == "Phone" && phone.mode == "both"
            ok && took && kept && shown }
        t("123_the_pc_hand_flag_and_the_devices_list") {
            val code = LinkCrypto.make(); val (_, sec) = LinkCrypto.parse(code)!!
            val (net, posts) = hub()
            val wa = World(); val la = Link(wa.store, net, { mapOf("amz" to true, "nm" to "Phone", "vn" to "2.3") }) { wa.clock.t }
            la.connect(code); la.cycle()
            posts.add(LinkCrypto.seal(sec, MiniJson.write(mapOf("v" to 1, "d" to "d-x", "r" to "desktop", "at" to wa.clock.t / 1000.0,
                "amz" to false, "hand" to true, "items" to emptyList<Any>()))))
            la.cycle()
            val handed = la.pcHanded() && !la.desktopBuys()
            posts.add(LinkCrypto.seal(sec, MiniJson.write(mapOf("v" to 1, "d" to "d-x", "r" to "desktop", "at" to wa.clock.t / 1000.0 + 1,
                "amz" to true, "items" to emptyList<Any>()))))
            la.cycle()
            val d = la.devices("Phone")
            handed && !la.pcHanded() && la.desktopBuys() && d.size == 2 && d[0].pc && d[1].me && d[1].mode == "pc" }
        t("124_price_bar_fixed_prices_above_the_cap_alone_below") {
            val all = listOf(PriceMarks.of(399.99, 380.0, 455.88, 410.0), PriceMarks.of(400.0, 405.0, 400.0, 401.0), PriceMarks.of(5.0, 600.0, 4.0, 6.0),
                PriceMarks.of(1299.0, 1200.0, 323.88, 1250.0, 2000.0))
            all.all { pm ->
                val below = pm.items.filter { !it.above }
                val noOverlap = clear(pm)
                below.map { it.kind } == listOf("cap") && noOverlap && pm.rows in 1..PriceMarks.MAX_ROWS &&
                    pm.items.filter { it.above }.map { it.kind }.toSet() == setOf("zero", "msrp", "tcg", "now") }
        }
        // ---- 2.4 (owner, 2026-11-17 5:55 PM): the update question not in a loop; Bought with every device's purchases; labels by real width
        t("125_labels_of_any_width_never_overlap") {
            // big text: every label as wide as 30-60 % of the bar - rows are added so that none touch (four at most, one per label)
            val cases = listOf(PriceMarks.of(399.99, 380.0, 455.88, 410.0, null, mapOf("zero" to 0.3, "msrp" to 0.45, "tcg" to 0.4, "now" to 0.5, "cap" to 0.4)),
                PriceMarks.of(400.0, 405.0, 400.0, 401.0, null, mapOf("zero" to 0.6, "msrp" to 0.6, "tcg" to 0.6, "now" to 0.6)),
                PriceMarks.of(5.0, 600.0, 4.0, 6.0, null, mapOf("zero" to 0.1, "msrp" to 0.12, "tcg" to 0.11, "now" to 0.13)),
                PriceMarks.of(1299.0, 1200.0, 323.88, 1250.0, 2000.0, mapOf("zero" to 0.08, "msrp" to 0.16, "tcg" to 0.16, "now" to 0.18)))
            val small = cases[3]                                        // (narrow labels far apart: one row)
            cases.all { clear(it) && it.items.filter { m -> m.above }.all { m -> it.centre(m) - it.width(m) / 2 >= -1e-9 && it.centre(m) + it.width(m) / 2 <= 1 + 1e-9 } } &&
                cases[1].rows == 4 && small.rows <= 2 }
        t("126_bought_shows_every_device_once_newest_first") {
            val now = 1_800_000_000_000L
            val own = listOf(Purchase(now - 60_000, "B0TEST0401", "Hobbit Collector Booster Box", 455.88, "111-1", true))
            val pc = Buys.fromLink(Buys.clean(listOf(
                mapOf("t" to (now - 30_000) / 1000.0, "s" to "Target", "n" to "Hobbit Collector Booster Box", "p" to 539.99, "by" to "Firefox", "a" to "target_hobbit"),
                mapOf("t" to (now - 61_000) / 1000.0, "s" to "Amazon", "n" to "Hobbit Collector Booster Box", "p" to 455.88, "by" to "the buying browser", "a" to "B0TEST0401"),
                mapOf("t" to "bad", "s" to "Walmart"), mapOf("t" to 5.0, "s" to "Walmart"))), "PC")
            val all = Buys.all(listOf(Buys.mine(own, "Pixel"), pc))
            val sent = Buys.post(own)
            all.size == 2 && all[0].store == "Target" && all[0].device == "PC" && all[0].by == "Firefox" && all[1].mine && all[1].device == "Pixel" &&
                pc.size == 2 && sent.single()["by"] == "the app" && sent.single()["s"] == "Amazon" &&
                Buys.of(all, "B0TEST0401", "Hobbit Collector Booster Box").size == 2 && Buys.of(all, "B0XXXXXXXX", "Other").isEmpty() }
        t("127_purchases_travel_over_the_link_pc_and_phones") {
            val code = LinkCrypto.make(); val (_, sec) = LinkCrypto.parse(code)!!
            val (net, posts) = hub()
            val wa = World(); val wb = World()
            val la = Link(wa.store, net, { mapOf("amz" to true, "nm" to "Phone") }) { wa.clock.t }
            val lb = Link(wb.store, net, { mapOf("amz" to true, "nm" to "Tablet") }) { wb.clock.t }
            la.connect(code); lb.connect(code)
            wb.store.addPurchase(Purchase(wb.clock.t, "B0TEST0402", "Star Trek Bundle", 59.99, null, false))
            lb.touched(); lb.cycle()
            posts.add(LinkCrypto.seal(sec, MiniJson.write(mapOf("v" to 1, "d" to "d-x", "r" to "desktop", "at" to wa.clock.t / 1000.0, "amz" to true, "items" to emptyList<Any>(),
                "buys" to listOf(mapOf("t" to wa.clock.t / 1000.0 - 3600, "s" to "Walmart", "n" to "Edge of Eternities Bundle", "p" to 49.97, "by" to "Edge", "a" to "walmart_eoe"))))))
            la.cycle()
            val b = la.buys("Phone")
            b.size == 2 && b.any { it.device == "PC" && it.store == "Walmart" && it.by == "Edge" } && b.any { it.device == "Tablet" && it.name == "Star Trek Bundle" && it.by == "the app" } }
        t("128_tcg_link_the_pcs_page_or_a_search") {
            val p = Product(id = "x", asin = "B0TEST0403", name = "Magic: The Gathering | The Hobbit Collector Booster Box | 12 Packs", cap = 1.0)
            Tcg.link(p) == "https://www.tcgplayer.com/search/magic/product?q=The+Hobbit+Collector+Booster+Box" &&
                Tcg.link(p.copy(tcgUrl = "https://www.tcgplayer.com/product/601234")) == "https://www.tcgplayer.com/product/601234" &&
                Tcg.link(p.copy(tcgUrl = "https://evil.com/product/1")).startsWith("https://www.tcgplayer.com/search/") &&
                !Tcg.okUrl("https://www.tcgplayer.com.evil.com/x") && Tcg.okUrl("https://tcgplayer.com/product/1")
        }
        // ---- 2.5 (owner, 2026-11-17 6:50 PM): the A+ colors, the gradient bar, Best price
        t("129_gradient_green_to_msrp_red_past_tcg_always_increasing") {
            val (a, b, c) = PriceMarks.gradient(600.0, 520.0, 455.88, 542.98)
            val noRef = PriceMarks.gradient(100.0, 80.0, null, null)
            val odd = PriceMarks.gradient(100.0, 0.0, 99.0, 20.0)          // (TCG under the MSRP: still increasing, inside the bar)
            // (2.8: green and red at least SPREAD apart - the light green is at or before the MSRP, the light red at or after the TCG price)
            a <= 455.88 / 600 + 1e-9 && c >= 542.98 / 600 - 1e-9 && c - a >= PriceMarks.SPREAD - 1e-9 && b > a && b < c &&
                listOf(noRef, odd).all { (x, y, z) -> x >= 0.1 && x < y && y < z && z <= 0.95 } }
        t("130_best_price_cheapest_in_stock_anywhere") {
            val p = Product(id = "x", asin = "B0TEST0501", cap = 520.0, lastCheck = 1, buyable = true, price = 499.99, others = listOf(
                mapOf("s" to "Target", "st" to "in", "pr" to 489.99, "u" to "https://www.target.com/p/x"),
                mapOf("s" to "Walmart", "st" to "out", "pr" to 400.0, "u" to "https://www.walmart.com/ip/x"),
                mapOf("s" to "GameStop", "st" to "in", "pr" to 450.0)))                                // (no page: not offered)
            val b = BestPrice.of(p)!!
            val amazonOnly = BestPrice.of(p.copy(others = emptyList()))!!
            b.store == "Target" && b.price == 489.99 && b.url == "https://www.target.com/p/x" && amazonOnly.store == "Amazon" &&
                amazonOnly.url == "https://www.amazon.com/dp/B0TEST0501" && BestPrice.of(p.copy(buyable = false, others = emptyList())) == null }
        // ---- 2.6 (owner, 2026-11-17 6:50 PM: real crowd sharing): what is sent and taken, the count, the look limits
        t("131_crowd_sends_only_product_store_price_time") {
            val m = Crowd.sighting("0123456789ab", "B0GXC89N66", 455.881, 1.8e9)
            m.keys == setOf("v", "c", "k", "a", "s", "st", "t", "p") && m["p"] == 455.88 && m["s"] == "Amazon" && m["st"] == "in" &&
                Crowd.hello("0123456789ab") == mapOf("v" to 1, "c" to "0123456789ab", "k" to "h") }
        t("132_crowd_takes_fresh_clean_sightings_of_others_once") {
            val w = World()
            var t = 1_800_000_000_000L
            val cs = CrowdState(w.store) { t }
            val nowS = t / 1000.0
            fun line(m: Map<String, Any?>, i: Int) = MiniJson.write(mapOf("id" to "m$i", "event" to "message", "message" to MiniJson.write(m)))
            val msgs = listOf(
                mapOf("v" to 1, "c" to "aaaaaaaaaaaa", "k" to "s", "a" to "B0GXC89N66", "s" to "Amazon", "st" to "in", "t" to nowS - 5, "p" to 455.88),
                mapOf("v" to 1, "c" to "aaaaaaaaaaaa", "k" to "s", "a" to "B0GXC89N66", "s" to "Amazon", "st" to "in", "t" to nowS - 4),      // again: 2 min gap
                mapOf("v" to 1, "c" to cs.myId(), "k" to "s", "a" to "B0HNTNV94J", "s" to "Amazon", "st" to "in", "t" to nowS),            // my own
                mapOf("v" to 1, "c" to "bbbbbbbbbbbb", "k" to "s", "a" to "B0HNTNV94J", "s" to "Target", "st" to "in", "t" to nowS),       // not Amazon
                mapOf("v" to 1, "c" to "cccccccccccc", "k" to "s", "a" to "B0HNTNV94J", "s" to "Amazon", "st" to "in", "t" to nowS - 7200), // old
                mapOf("v" to 1, "c" to "dddddddddddd", "k" to "s", "a" to "B0NOTMINE0", "s" to "Amazon", "st" to "in", "t" to nowS),       // not mine
                mapOf("v" to 1, "c" to "eeeeeeeeeeee", "k" to "h"), mapOf("v" to 2, "c" to "ffffffffffff", "k" to "h"),
                mapOf("v" to 1, "c" to "BAD", "k" to "h"))
            val looks = ArrayList<String>()
            val got = cs.take(msgs.mapIndexed { i, m -> line(m, i) } + "junk", setOf("B0GXC89N66", "B0HNTNV94J")) { looks += it; true }
            val counted = cs.count()                      // me, aaaa, bbbb, dddd, eeee (cccc's sighting is old: dropped, not counted)
            t += 30 * 60_000L
            val many = (0 until 60).map { line(mapOf("v" to 1, "c" to "aaaaaaaaaaaa", "k" to "s", "a" to "B0TEST${"%04d".format(it)}", "s" to "Amazon", "st" to "in", "t" to t / 1000.0), 100 + it) }
            val cs2 = CrowdState(w.store) { t }            // (read back from the file: the same contributor number and count)
            val lim = cs2.take(many, (0 until 60).map { "B0TEST${"%04d".format(it)}" }.toSet()) { true }
            got == listOf("B0GXC89N66") && looks == listOf("B0GXC89N66") && counted == 5 && cs2.myId() == cs.myId() && cs2.count() == 5 &&
                lim.size == Crowd.LOOKS_PER_HOUR && cs2.since == "m159" }
        t("133_crowd_switch_kept_and_on_by_default") {
            Settings().crowd && !Settings.fromMap(MiniJson.obj(MiniJson.write(Settings(crowd = false).toMap()))).crowd && Settings.fromMap(mapOf("watching" to true)).crowd }
        // ---- 2.7 (owner, 2026-11-17 8:35 PM): at most 3 purchases of a product ever, the owner excepted; the TCG price history
        t("134_limit_three_purchases_master_excepted") {
            val w = World(); w.add("B0TEST0801", 120.0, true)
            var bought = 3; var master = false
            val e = Engine(w.store, w.amz, w.said, w.clock, { "wifi" }, master = { master }, boughtOf = { bought })
            e.check(w.p("B0TEST0801")); val blocked = w.amz.begins == 0
            master = true
            w.store.update("idB0TEST0801") { it.copy(buyable = false) }
            e.check(w.p("B0TEST0801")); val masterBuys = w.amz.places == 1
            blocked && masterBuys && Limit.mayBuy(2, false) && !Limit.mayBuy(3, false) && Limit.mayBuy(9, true) }
        t("135_master_by_fingerprint_only") {
            // (no real code is written here - this file is public; only fingerprints are in the app)
            Limit.fp("abc") == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" && !Limit.codeOk("RKM-AAAA-BBBB-CCCC") && !Limit.codeOk("") &&
                !Limit.isMaster(Settings(masterCode = "RKM-AAAA-BBBB-CCCC", reportTopic = "rkr-aaaaaaaaaaaaaaaaaaaa"), false) && Limit.isMaster(Settings(), true) }
        t("136_tcg_history_and_master_flag_over_the_link") {
            val code = LinkCrypto.make(); val (_, sec) = LinkCrypto.parse(code)!!
            val (net, posts) = hub()
            val wa = World(); val la = Link(wa.store, net, { mapOf("amz" to true) }) { wa.clock.t }
            la.connect(code); la.cycle()
            fun desk(extra: Map<String, Any?>) = posts.add(LinkCrypto.seal(sec, MiniJson.write(mapOf("v" to 1, "d" to "d-x", "r" to "desktop",
                "at" to wa.clock.t / 1000.0, "amz" to true, "items" to emptyList<Any>()) + extra)))
            desk(mapOf("mst" to true, "th" to mapOf("B0GXC89N66" to listOf(listOf(20400, 530.0), listOf(20401, 535.0), listOf(99, 1.0)), "bad" to listOf(listOf(20400, 1.0)))))
            la.cycle()
            val h1 = la.tcgHistory("B0GXC89N66")
            desk(emptyMap()); la.cycle()                      // (a post without the history keeps the last one; the master flag comes with every post)
            val h2 = la.tcgHistory("B0GXC89N66")
            h1 == listOf(20400L to 530.0, 20401L to 535.0) && h2 == h1 && !la.pcIsMaster() && la.tcgHistory("bad").isEmpty() }
        // ---- 2.8 (owner, 2026-11-17 11 PM): the newest phone (not linked to a PC) had no pictures; the device count
        t("137_central_list_pictures_for_new_and_existing_products") {
            val w = World(); w.add("B0TEST0901", 120.0, false)                     // (already on the phone - e.g. added by hand)
            val T1 = "https://tcgplayer-cdn.tcgplayer.com/product/692963_in_200x200.jpg"
            val T2 = "https://tcgplayer-cdn.tcgplayer.com/product/692971_in_200x200.jpg"
            val json = MiniJson.write(mapOf("v" to 1, "products" to listOf(
                mapOf("a" to "B0TEST0901", "n" to "Here already", "k" to "box", "i" to T1),
                mapOf("a" to "B0TEST0902", "n" to "New one", "k" to "bundle", "i" to T2),
                mapOf("a" to "B0TEST0903", "n" to "Bad picture host", "k" to "box", "i" to "https://evil.example/x.jpg"),
                mapOf("a" to "B0TEST0904", "n" to "No picture", "k" to "box"))))
            Catalog.import(json, w.store)
            val newOk = w.p("B0TEST0902").cImg == T2 && w.p("B0TEST0903").cImg == "" && w.p("B0TEST0904").cImg == "" && w.p("B0TEST0901").cImg == ""
            val n1 = Catalog.pictures(json, w.store); val n2 = Catalog.pictures(json, w.store)
            val kept = Product.fromMap(MiniJson.obj(MiniJson.write(w.p("B0TEST0901").toMap()))!!)!!.cImg == T1
            newOk && n1 == 1 && n2 == 0 && w.p("B0TEST0901").cImg == T1 && kept && Catalog.pictures("not json", w.store) == 0 &&
                Product.fromMap(Product("a", "B0TEST0905", cImg = "https://evil.example/x.jpg").toMap())!!.cImg == "" }
        t("138_picture_order_pc_then_central_list_then_own_and_fallback") {
            val D = "https://target.scene7.com/is/image/Target/GUEST_1?wid=200"; val C = "https://tcgplayer-cdn.tcgplayer.com/product/1_in_200x200.jpg"
            val all = Pics.shown(Product("a", "B0TEST0906", image = I, dImg = D, cImg = C))
            val noPc = Pics.shown(Product("a", "B0TEST0906", image = I, cImg = C))
            val needNot = !Pics.needsOwn(Product("a", "B0TEST0906", cImg = C))
            Pics.broken = { it == C }
            val needBroken = Pics.needsOwn(Product("a", "B0TEST0906", cImg = C))
            Pics.broken = { false }
            all == listOf(D, C, I) && noPc == listOf(C, I) && needNot && needBroken && Pics.needsOwn(Product("a", "B0TEST0906")) &&
                !Pics.needsOwn(Product("a", "B0TEST0906", image = I)) }
        t("139_pictures_looked_for_while_nothing_is_watched_not_for_central_list_ones") {
            val w = World()
            w.add("B0TEST0907", 0.0, false); w.store.update("idB0TEST0907") { it.copy(watch = false) }
            w.add("B0TEST0908", 0.0, false); w.store.update("idB0TEST0908") { it.copy(watch = false, cImg = "https://tcgplayer-cdn.tcgplayer.com/product/2_in_200x200.jpg") }
            repeat(3) { w.engine.step() }
            Pics.valid(w.p("B0TEST0907").image) && w.p("B0TEST0907").picTriedAt > 0 && w.p("B0TEST0908").image == "" && w.p("B0TEST0908").picTriedAt == 0L &&
                w.amz.begins == 0 }
        t("140_first_read_of_the_crowd_goes_back_12_hours") {
            val w = World(); Crowd.FIRST_SINCE == "12h" && CrowdState(w.store).since == "" }
        t("141_tcg_history_from_the_central_file_for_every_phone") {
            val w = World(); w.add("B0TEST0911", 120.0, false); w.add("B0TEST0912", 120.0, false)
            val json = MiniJson.write(mapOf("v" to 1, "h" to mapOf(
                "B0TEST0911" to listOf(listOf(20410, 530.0), listOf(20400, 520.5), listOf(20410, 999.0), listOf(5, 1.0), listOf(20420, -3.0)),
                "B0TEST0912" to listOf(listOf(20400, 99.0)), "bad code" to listOf(listOf(20400, 1.0), listOf(20401, 2.0)))))
            val m = TcgHist.parse(json)
            w.store.writeFile(TcgHist.FILE, json)
            val of = TcgHist.of(w.store, "B0TEST0911")
            val unlinked = TcgHist.apply(w.store, m, false); val linked = TcgHist.apply(w.store, mapOf("B0TEST0912" to listOf(1L to 1.0, 2L to 7.0)), true)
            val (pts, fromTcg) = TcgHist.pick(of, listOf(20000L to 1.0, 20001L to 2.0))
            val (pts2, fromTcg2) = TcgHist.pick(emptyList(), listOf(20000L to 1.0, 20001L to 2.0))
            m.keys == setOf("B0TEST0911") && of == listOf(20400L to 520.5, 20410L to 530.0) && unlinked == 1 && linked == 0 &&
                w.p("B0TEST0911").tcg == 530.0 && w.p("B0TEST0912").tcg == null && fromTcg && pts == of && !fromTcg2 && pts2.size == 2 &&
                !TcgHist.ok("{}") && !TcgHist.ok("not json") && TcgHist.ok(json) && TcgHist.of(w.store, "B0NOTHERE1").isEmpty() }
        t("141b_tcg_series_sold_bars_one_year_and_change") {
            val w = World()
            val json = MiniJson.write(mapOf("v" to 2, "h" to mapOf("B0TEST0931" to listOf(listOf(20700, 800.0, 40), listOf(20703, 520.0, -5), listOf(20706, 516.0, 12))),
                "y" to mapOf("B0TEST0931" to listOf(listOf(20400, 900.0, 300), listOf(20407, 516.0, 20)))))
            w.store.writeFile(TcgHist.FILE, json)
            val q = TcgHist.series(w.store, "B0TEST0931")!!; val y = TcgHist.series(w.store, "B0TEST0931", year = true)!!
            val v1 = TcgHist.parseSeries(MiniJson.write(mapOf("h" to mapOf("B0TEST0932" to listOf(listOf(20700, 1.0), listOf(20701, 2.0))))))["B0TEST0932"]!!
            q.sold == listOf(20700L to 40, 20703L to 0, 20706L to 12) && y.pts.first() == (20400L to 900.0) && Math.abs(q.changePct!! - (-35.5)) < 1e-9 &&
                v1.sold.all { it.second == 0 } && v1.pts.size == 2 && TcgHist.of(w.store, "B0TEST0931").size == 3 && TcgHist.series(w.store, "B0NONE0000") == null }
        t("142_tcg_page_link_from_the_central_list_the_pcs_link_stays") {
            val w = World(); w.add("B0TEST0913", 120.0, false); w.add("B0TEST0914", 120.0, false)
            w.store.update("idB0TEST0914") { it.copy(tcgUrl = "https://www.tcgplayer.com/product/1/the-pcs-own") }
            val json = MiniJson.write(mapOf("v" to 1, "products" to listOf(
                mapOf("a" to "B0TEST0913", "n" to "x", "tp" to "692963"), mapOf("a" to "B0TEST0914", "n" to "y", "tp" to "692971"),
                mapOf("a" to "B0TEST0915", "n" to "z", "tp" to "12; evil"))))
            Catalog.pictures(json, w.store)
            Catalog.import(MiniJson.write(mapOf("v" to 1, "products" to listOf(mapOf("a" to "B0TEST0916", "n" to "new", "tp" to "706142")))), w.store)
            w.p("B0TEST0913").tcgUrl == "https://www.tcgplayer.com/product/692963" && w.p("B0TEST0914").tcgUrl == "https://www.tcgplayer.com/product/1/the-pcs-own" &&
                w.p("B0TEST0916").tcgUrl == "https://www.tcgplayer.com/product/706142" && Tcg.link(w.p("B0TEST0913")) == "https://www.tcgplayer.com/product/692963" }
        t("143_bar_ends_at_the_highest_price_seen_blend_is_gradual") {
            val p = Product(id = "x", asin = "B0TEST0921", cap = 520.0, others = listOf(mapOf("s" to "Target", "pr" to 589.99), mapOf("s" to "Walmart", "pr" to null)))
            val hist = listOf(20700L to 610.0, 20730L to 575.0)
            val high = CapStep.high(p, hist)
            val sc = CapStep.scale(540.0, 520.0, 455.88, 560.0, high)            // highest seen 610 (TCG 3 months) -> 671 -> $675
            val noHigh = CapStep.scale(540.0, 520.0, 455.88, 560.0, CapStep.high(Product(id = "y", asin = "B0TEST0922"), emptyList()))
            val (a, _, c) = PriceMarks.gradient(sc, 520.0, 455.88, 470.0)        // (MSRP and TCG close together: still a gradual blend)
            val (a2, _, c2) = PriceMarks.gradient(1000.0, 0.0, 100.0, 900.0)     // (far apart: kept where they are)
            high == 610.0 && sc == 675.0 && noHigh == 620.0 && CapStep.top(540.0, 520.0, 455.88, 560.0, high) == 610.0 &&
                c - a >= PriceMarks.SPREAD - 1e-9 && a >= 0.08 && c <= 0.95 && Math.abs(a2 - 0.1) < 1e-9 && Math.abs(c2 - 0.9) < 1e-9 }
        return results
    }

    @JvmStatic fun main(args: Array<String>) {
        val r = run()
        val bad = r.filterValues { !it }.keys
        println(if (bad.isEmpty()) "ALL PASS (${r.size})" else "SOME FAILED: $bad")
        if (bad.isNotEmpty()) kotlin.system.exitProcess(1)
    }
}
