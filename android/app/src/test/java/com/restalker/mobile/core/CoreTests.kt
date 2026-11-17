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
        val engine = Engine(store, amz, said, clock, { net }, { desk })
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
                LinkCrypto.unseal(sec, w.dropLast(2) + "AA") == null && LinkCrypto.unseal(sec, "hello") == null && !w.contains("B0TEST0001")
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
            val ok1 = w.p("B0TEST0001").image == "https://m.media-amazon.com/images/I/B0TEST0001._AC_SL1500_.jpg"
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
        return results
    }

    @JvmStatic fun main(args: Array<String>) {
        val r = run()
        val bad = r.filterValues { !it }.keys
        println(if (bad.isEmpty()) "ALL PASS (${r.size})" else "SOME FAILED: $bad")
        if (bad.isNotEmpty()) kotlin.system.exitProcess(1)
    }
}
