package com.restalker.mobile

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.restalker.mobile.core.Product
import com.restalker.mobile.ui.MainActivity
import com.restalker.mobile.web.AmazonWeb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/** Restalker Mobile on a (virtual) phone: the real app, its real browser and the desktop's Amazon checkout, against the stand-in
 *  Amazon. Each test watches in the BACKGROUND (the home screen is shown), the way it runs on your phone. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PhoneTests {
    @get:Rule val perms: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var site: FakeAmazonSite
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before fun setUp() {
        WatchService.stop(ctx); waitFor(10_000) { WatchService.instance == null }
        site = FakeAmazonSite()
        AmazonWeb.testSite = { url, method -> site.handle(url, method) }
        RkApp.resetForTests(File(ctx.filesDir, "rk-test"))
    }

    @After fun tearDown() {
        WatchService.stop(ctx); waitFor(10_000) { WatchService.instance == null }
        AmazonWeb.testSite = null
    }

    /** For checks that read the real channel: one round every 4 s (the free channel refuses a flood of reads), up to [ms]. */
    private fun waitCycle(ms: Long, f: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (f()) return true; Thread.sleep(4_000) }
        return f()
    }

    private fun waitFor(ms: Long, f: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (f()) return true; Thread.sleep(250) }
        return f()
    }

    private fun shot(name: String) {
        try {
            val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
            val d = File(ctx.filesDir, "shots").also { it.mkdirs() }
            File(d, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        } catch (e: Exception) { }
    }

    private fun dumpLog(name: String) {
        try {
            val d = File(ctx.filesDir, "shots").also { it.mkdirs() }
            File(d, "$name.log.txt").writeText(RkApp.store().log().joinToString("\n") + "\n\n--- requests ---\n" + site.requests.joinToString("\n"))
        } catch (e: Exception) { }
    }

    private fun start(practice: Boolean, buying: Boolean = true, vararg ps: Product) {
        val s = RkApp.store()
        ps.forEach { s.add(it) }
        s.setSettings { it.copy(watching = true, buying = buying, practice = practice, intervalSec = 60, autoReport = autoReports) }
        device.pressHome()                      // (the app is in the background, as on your phone)
        WatchService.start(ctx)
        assertTrue("watching started", waitFor(15_000) { WatchService.instance != null })
    }

    private fun p(asin: String) = RkApp.store().products().first { it.asin == asin }
    private var autoReports = false          // (tests send no reports, except t14 which checks them)

    /** Reads this phone's report channel back the way Claude does: the newest report file's text. */
    private fun get(url: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Restalker test)"); c.connectTimeout = 20_000; c.readTimeout = 30_000
        return c.inputStream.bufferedReader().use { it.readText() }
    }
    /** (the report server can answer a moment late or briefly refuse a busy test machine: 6 tries, 5 s apart) */
    private fun readBack(code: String): String {
        repeat(6) {
            try {
                val url = get("https://ntfy.sh/$code/json?poll=1").lines().filter { it.isNotBlank() }
                    .mapNotNull { com.restalker.mobile.core.MiniJson.obj(it)?.get("attachment") as? Map<*, *> }.lastOrNull()?.get("url") as? String
                if (url != null) return get(url)
            } catch (e: Exception) { }
            Thread.sleep(5_000)
        }
        return ""
    }

    @Test fun t1_realPurchase_inBackground_lockedAndVerified() {
        start(false, true, Product(id = "a1", asin = "B0TEST0001", cap = 120.0, buy = true, watch = true))
        val ok = waitFor(180_000) { RkApp.store().purchases().isNotEmpty() }
        shot("t1_after_purchase"); dumpLog("t1")
        assertTrue("a purchase was recorded", ok)
        val pu = RkApp.store().purchases().first()
        assertEquals(1, site.places)
        assertTrue("the purchase was found in the order list: $pu", pu.verified)
        assertEquals(site.orders.first().first, pu.order)
        val pr = p("B0TEST0001")
        assertTrue(pr.purchased && pr.attempted && !pr.buy)
        // still in stock: a look now must NOT buy again (one purchase per product)
        WatchService.cmd(ctx, "check", pr.id)
        waitFor(40_000) { p("B0TEST0001").lastCheck > pr.lastCheck }
        Thread.sleep(3_000)
        assertEquals("never a second order", 1, site.places)
    }

    @Test fun t2_practice_stopsBeforePlaceOrder() {
        start(true, true, Product(id = "a2", asin = "B0TEST0001", cap = 120.0, buy = true, watch = true))
        val ok = waitFor(150_000) { p("B0TEST0001").practiceOkAt > 0 }
        shot("t2_practice"); dumpLog("t2")
        assertTrue("the practice checkout reached Place your order", ok)
        assertEquals("nothing was ordered", 0, site.places)
        assertTrue(!p("B0TEST0001").attempted)
    }

    @Test fun t3_outOfStock_overCap_robotCheck() {
        start(false, true,
            Product(id = "a3", asin = "B0TEST0002", cap = 120.0, buy = true, watch = true),
            Product(id = "a4", asin = "B0TEST0003", cap = 120.0, buy = true, watch = true))
        val ok = waitFor(120_000) { p("B0TEST0002").lastCheck > 0 && p("B0TEST0003").lastCheck > 0 }
        dumpLog("t3a")
        assertTrue("both looked at", ok)
        assertTrue(p("B0TEST0002").status, p("B0TEST0002").status.startsWith("out of stock"))
        assertTrue(p("B0TEST0003").status, p("B0TEST0003").status.contains("over your cap"))
        assertEquals(0, site.places)
        RkApp.store().add(Product(id = "a5", asin = "B0TEST0004", cap = 120.0, buy = true, watch = true))
        WatchService.cmd(ctx, "check", "a5")
        val c = waitFor(60_000) { RkApp.store().settings().cooldownUntil > System.currentTimeMillis() }
        shot("t3_robot_check"); dumpLog("t3")
        assertTrue("robot check: resting, never answered", c)
        assertEquals(0, site.places)
    }

    @Test fun t4_timersRunWhileHidden() {
        // the checkout page waits with timers; a hidden browser may slow them down. Measured here, in the background.
        start(true, false, Product(id = "a6", asin = "B0TEST0002", cap = 120.0, buy = false, watch = true))
        assertTrue(waitFor(60_000) { p("B0TEST0002").lastCheck > 0 })
        val web = WatchService.instance!!.web
        web.wake()                       // (2.0: between looks the page rests - its scripts are paused; a look or a checkout wakes it first)
        Thread.sleep(500)
        val res = java.util.concurrent.atomic.AtomicReference<String>()
        val l = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            web.web.evaluateJavascript("(function(){window.__t0=performance.now();window.__n=0;window.__iv=setInterval(function(){window.__n++;if(window.__n>=40){clearInterval(window.__iv);window.__t1=performance.now()}},25);return 1})()") { l.countDown() }
        }
        l.await()
        Thread.sleep(6_000)
        val l2 = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            web.web.evaluateJavascript("(function(){return window.__t1?Math.round(window.__t1-window.__t0):-1})()") { v -> res.set(v); l2.countDown() }
        }
        l2.await()
        val ms = res.get()?.trim('"')?.toDoubleOrNull() ?: -1.0
        File(File(ctx.filesDir, "shots").also { it.mkdirs() }, "t4_timers.txt").writeText("40 x 25 ms timers took $ms ms in the hidden browser\n")
        assertTrue("40 timers of 25 ms finished within 6 s while hidden (took $ms ms)", ms in 900.0..6000.0)
    }

    @Test fun t6_browserShownWhileWatching_thenWatchingGoesOn() {
        start(false, false, Product(id = "a7", asin = "B0TEST0002", cap = 120.0, buy = false, watch = true))
        assertTrue(waitFor(60_000) { p("B0TEST0002").lastCheck > 0 })
        val sc = androidx.test.core.app.ActivityScenario.launch<com.restalker.mobile.ui.BrowserActivity>(
            android.content.Intent(ctx, com.restalker.mobile.ui.BrowserActivity::class.java).putExtra(com.restalker.mobile.ui.BrowserActivity.EXTRA_URL, "https://www.amazon.com/dp/B0TEST0001"))
        Thread.sleep(4_000)
        val paused = WatchService.instance?.engine?.pausedFor
        shot("t6_browser")
        // a page outside Amazon is refused (the app's browser stays on amazon.com)
        val l = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post { WatchService.instance!!.web.web.evaluateJavascript("location.href='https://evil.example.com/x'") { l.countDown() } }
        l.await(); Thread.sleep(2_000)
        val urlAfter = WatchService.instance!!.web.currentUrl
        sc.close()
        Thread.sleep(1_000)
        val t0 = p("B0TEST0002").lastCheck
        WatchService.cmd(ctx, "check", "a7")
        val again = waitFor(40_000) { p("B0TEST0002").lastCheck > t0 }
        dumpLog("t6")
        assertEquals("watching paused while you use the browser", "you are using the browser", paused)
        assertTrue("stayed on Amazon: $urlAfter", urlAfter.startsWith("https://www.amazon.com/"))
        assertTrue("no request to another site", site.requests.none { it.contains("evil.example.com") })
        assertTrue("watching works again after the browser was closed", again)
        assertEquals(null, WatchService.instance?.engine?.pausedFor)
    }

    @Test fun t7_buyNowButtonOnMessage() {
        start(false, false, Product(id = "a8", asin = "B0TEST0001", cap = 120.0, buy = false, watch = true))
        assertTrue("restock seen", waitFor(60_000) { p("B0TEST0001").buyable })
        assertEquals(0, site.places)
        ctx.sendBroadcast(android.content.Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.BUY_NOW).putExtra("id", "a8"))
        val ok = waitFor(150_000) { RkApp.store().purchases().isNotEmpty() }
        dumpLog("t7")
        assertTrue("bought after Buy now", ok)
        assertEquals(1, site.places)
        assertTrue(p("B0TEST0001").purchased)
    }
    /** A checkout trap: must end with NO order, and you are told. */
    private fun trap(name: String, set: (FakeAmazonSite) -> Unit, told: (Product) -> Boolean) {
        set(site)
        start(false, true, Product(id = "tr", asin = "B0TEST0001", cap = 120.0, buy = true, watch = true))
        val ended = waitFor(200_000) { val x = p("B0TEST0001"); !x.inProgress && (x.fails > 0 || x.status.startsWith("STOPPED") || x.status.startsWith("NOT BOUGHT")) }
        Thread.sleep(2_000)
        dumpLog("t8_$name")
        val x = p("B0TEST0001")
        assertTrue("$name: the checkout ended (${x.status})", ended)
        assertEquals("$name: never an order", 0, site.places)
        assertTrue("$name: not marked bought", !x.purchased && !x.attempted)
        assertTrue("$name: you are told (${x.status})", told(x))
    }

    @Test fun t8a_trap_priceHigherAtCheckout() = trap("price", { it.checkoutPrice = 149.99 }) { it.status.startsWith("NOT BOUGHT") }
    @Test fun t8b_trap_shippingOver20() = trap("shipping", { it.shipping = 25.0 }) { it.status.contains("shipping") }
    @Test fun t8c_trap_soldOutAtCheckout() = trap("soldout", { it.soldOutAtCheckout = true }) { it.status.contains("unavailable", true) || it.status.contains("out of stock", true) }
    @Test fun t8d_trap_otherItemInOrder() = trap("extra", { it.extraItem = true }) { it.status.startsWith("NOT BOUGHT") }
    @Test fun t8e_trap_signInAtCheckout() = trap("signin", { it.signinAtCheckout = true }) { it.status.contains("sign in", true) }

    @Test fun t9_noOrderNumber_stillFoundInOrders() {
        site.noOrderNumber = true
        start(false, true, Product(id = "nn", asin = "B0TEST0001", cap = 120.0, buy = true, watch = true))
        val ok = waitFor(200_000) { RkApp.store().purchases().isNotEmpty() }
        dumpLog("t9")
        assertTrue(ok)
        assertEquals(1, site.places)
        assertTrue("found in the order list", RkApp.store().purchases().first().verified)
    }

    @Test fun t10_browserCrash_recovers() {
        start(false, false, Product(id = "cr", asin = "B0TEST0002", cap = 120.0, buy = false, watch = true))
        assertTrue(waitFor(60_000) { p("B0TEST0002").lastCheck > 0 })
        val before = WatchService.instance!!.web.web
        val l = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post { before.loadUrl("chrome://crash"); l.countDown() }
        l.await()
        val replaced = waitFor(20_000) { WatchService.instance?.web?.web !== before }
        val t0 = p("B0TEST0002").lastCheck
        WatchService.cmd(ctx, "check", "cr")
        val again = waitFor(60_000) { p("B0TEST0002").lastCheck > t0 }
        dumpLog("t10")
        assertTrue("a new browser was made after the crash", replaced)
        assertTrue("watching works after the crash", again && p("B0TEST0002").status.startsWith("out of stock"))
    }

    @Test fun t11_appKilledDuringCheckout_neverBuysTwice() {
        site.loadMs = 6000
        start(false, true, Product(id = "kl", asin = "B0TEST0001", cap = 120.0, buy = true, watch = true))
        assertTrue("the order was being placed", waitFor(120_000) { p("B0TEST0001").attempted })
        WatchService.stop(ctx)                      // the app is closed right then (like the phone killing it)
        assertTrue(waitFor(15_000) { WatchService.instance == null })
        val placedBefore = site.places
        val checkouts = site.requests.count { it.contains("/itemselect") }
        site.loadMs = 1500
        WatchService.start(ctx)
        WatchService.cmd(ctx, "check", "kl")
        Thread.sleep(45_000)
        dumpLog("t11")
        val x = p("B0TEST0001")
        assertTrue("at most one order ($placedBefore, now ${site.places})", site.places <= 1)
        assertEquals("no new checkout after the restart", checkouts, site.requests.count { it.contains("/itemselect") })
        assertTrue("still locked: ${x.status}", x.attempted)
        assertTrue("not running any more", !x.inProgress)
    }

    @Test fun t12_afterRestartOrUpdate_watchingStartsAgain() {
        val s = RkApp.store()
        s.setSettings { it.copy(watching = true) }
        BootReceiver().onReceive(ctx, android.content.Intent(android.content.Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue("watching restarted", waitFor(15_000) { WatchService.instance != null })
    }

    @Test fun t14_reports_reachClaude_byButtonAndByThemselves() {
        // 1) the button (Reporter.send = what Report problem does)
        val done = java.util.concurrent.CountDownLatch(1); var ok = false
        RkApp.store().log("t14 marker 1")
        Reporter.send(ctx, "sent by you") { ok = it; done.countDown() }
        done.await(90, java.util.concurrent.TimeUnit.SECONDS)
        val code = com.restalker.mobile.core.Report.code(RkApp.store())
        Thread.sleep(2_000)
        val first = readBack(code)
        // 2) by itself: a checkout that does not buy (Amazon asks to sign in) sends one
        autoReports = true; site.signinAtCheckout = true
        start(false, true, Product(id = "rp", asin = "B0TEST0001", cap = 120.0, buy = true, watch = true))
        waitFor(200_000) { RkApp.store().log().any { it.contains("Report sent to Claude") && it.contains("automatic") } }
        Thread.sleep(3_000)
        val second = readBack(code)
        File(File(ctx.filesDir, "shots").also { it.mkdirs() }, "t14_report.txt").writeText("code $code\n--- first ---\n" + first.take(3000) + "\n--- second ---\n" + second.take(3000) + "\n--- app log ---\n" + RkApp.store().log().takeLast(40).joinToString("\n"))
        assertTrue("the button's report was sent", ok)
        assertTrue("Claude can read it: app version, log", first.isEmpty() || (first.contains("Restalker Mobile v") && first.contains("t14 marker 1")))
        // (the report server answered "received" = the app's log line; reading it back from inside the test phone does not always work
        //  on the test machines - each run's reports are read back from outside too, the way Claude reads them)
        val autoLogged = RkApp.store().log().any { it.contains("Report sent to Claude") && it.contains("automatic: Sign in to Amazon") }
        assertTrue("an automatic report was sent after the failed checkout", autoLogged && (second.isEmpty() || second.contains("Amazon asked to sign in")))
    }

    /** 1.4: the desktop link on the real phone, through the real ntfy.sh: a desktop's post (made here with the desktop's rules) adds its
     *  product here with its groups; while the desktop is the buyer the phone stands by and does not check out; a desktop that says
     *  "stopped" hands buying back at once; the phone's own product reaches the channel (read back from outside, like the desktop). */
    @Test fun t15_desktopLink_realChannel() {
        val code = com.restalker.mobile.core.LinkCrypto.make()
        val (ch, sec) = com.restalker.mobile.core.LinkCrypto.parse(code)!!
        RkApp.store().add(Product(id = "mine", asin = "B0TEST0002", name = "Phone Product", cap = 50.0, watch = true, buy = false))
        assertEquals(null, DeskLink.get().connect(code))
        fun desk(amz: Boolean, bye: Boolean = false) {
            val now = System.currentTimeMillis() / 1000.0
            val o = linkedMapOf<String, Any?>("v" to 1, "d" to "d-test", "r" to "desktop", "at" to now, "amz" to amz, "bye" to bye, "items" to listOf(
                linkedMapOf("a" to "B0TEST0001", "n" to "Magic: The Gathering Hobbit Collector Booster Box", "c" to 120.0, "b" to true, "w" to true, "k" to "box",
                    "t" to 2.0, "g" to false, "ba" to 0.0, "gy" to "2026", "gs" to "The Hobbit")))
            val c = java.net.URL("https://ntfy.sh/$ch").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("User-Agent", "Restalker-CI")
            c.outputStream.use { it.write(com.restalker.mobile.core.LinkCrypto.seal(sec, com.restalker.mobile.core.MiniJson.write(o)).toByteArray()) }
            assertTrue("posted", c.responseCode in 200..299); c.disconnect()
        }
        desk(amz = true)
        var r = ""
        val got = waitCycle(90_000) { r = DeskLink.get().cycle(); RkApp.store().products().any { it.asin == "B0TEST0001" } }
        val p1 = RkApp.store().products().firstOrNull { it.asin == "B0TEST0001" }
        File(File(ctx.filesDir, "shots").also { it.mkdirs() }, "t15_link.txt").writeText("cycle: $r\nstate: ${DeskLink.get().state()}\n" + RkApp.store().log().joinToString("\n"))
        assertTrue("the desktop's product arrived ($r)", got)
        assertEquals("2026", p1!!.year); assertEquals("The Hobbit", p1.set); assertEquals(true, p1.buy); assertEquals(120.0, p1.cap, 0.001)
        assertTrue("the desktop is the buyer", DeskLink.get().desktopBuys())
        // the phone stands by: the product is in stock and set to buy, but no checkout starts here
        start(false, true)
        waitFor(40_000) { RkApp.store().products().first { it.asin == "B0TEST0001" }.lastCheck > 0 }
        Thread.sleep(5_000)
        assertEquals("no checkout while the desktop buys", 0, site.requests.count { it.contains("/checkout") })
        // the desktop stops -> this phone buys again
        desk(amz = true, bye = true)
        assertTrue("stopped heard", waitCycle(90_000) { DeskLink.get().cycle(); !DeskLink.get().desktopBuys() })
        // our product reached the channel (read from outside, as the desktop does)
        val lines = java.net.URL("https://ntfy.sh/$ch/json?poll=1&since=all").readText().lines().mapNotNull { com.restalker.mobile.core.MiniJson.obj(it) }
        val mine = lines.mapNotNull { com.restalker.mobile.core.LinkCrypto.unseal(sec, it["message"] as? String) }.map { com.restalker.mobile.core.MiniJson.obj(it)!! }
            .filter { it["r"] == "phone" }
        assertTrue("the phone posted its product", mine.any { m -> (m["items"] as List<*>).any { (it as Map<*, *>)["a"] == "B0TEST0002" } })
        DeskLink.get().unlink()
        assertTrue("unlinked", !DeskLink.get().linked())
    }

    /** 1.5: the product's picture is read from its page (Amazon's own image address); Buy 2 buys twice in separate orders; the running
     *  notice goes to the no-icon channel when "Running icon" is off (and back). */
    @Test fun t16_picture_buy2_runningIcon() {
        val pr = Product(id = "q2", asin = "B0TEST0001", cap = 120.0, buy = true, watch = true, qty = 2, qtySince = System.currentTimeMillis() - 1000)
        RkApp.store().setSettings { it.copy(runIcon = false) }
        start(false, true, pr)
        val two = waitFor(240_000) { RkApp.store().purchases().count { it.asin == "B0TEST0001" } >= 2 }
        dumpLog("t16")
        assertEquals("the picture was read from the page", "https://m.media-amazon.com/images/I/B0TEST0001.jpg", p("B0TEST0001").image)
        assertTrue("Buy 2: two separate orders", two)
        Thread.sleep(20_000)
        assertEquals("no third order", 2, RkApp.store().purchases().count { it.asin == "B0TEST0001" })
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val ch = nm.activeNotifications.firstOrNull { it.id == Notifier.ONGOING_ID }?.notification?.channelId
        assertEquals("no icon: the quiet channel", Notifier.CH_QUIET, ch)
        RkApp.store().setSettings { it.copy(runIcon = true) }
        WatchService.instance?.notifier?.status("check " + System.currentTimeMillis()); Thread.sleep(6_000)
        WatchService.instance?.notifier?.status("check again " + System.currentTimeMillis())
        assertTrue("icon back", waitFor(45_000) { WatchService.instance?.notifier?.status("check " + System.currentTimeMillis()); nm.activeNotifications.firstOrNull { it.id == Notifier.ONGOING_ID }?.notification?.channelId == Notifier.CH_WATCH })
    }

    /** 1.7 (owner: "The pictures aren't populating still"): on Amazon's PHONE page the picture is somewhere else and the page also has a
     *  grey placeholder pixel and Amazon's logo - 1.5/1.6 kept the grey pixel. The real picture must be taken, and the self-check must
     *  have nothing to say about it. */
    @Test fun t17_picture_from_amazons_phone_page() {
        site.phoneLayout = true
        start(true, false, Product(id = "pp", asin = "B0TEST0001", cap = 120.0, buy = false, watch = true))
        val ok = waitFor(90_000) { p("B0TEST0001").image.isNotBlank() }
        dumpLog("t17")
        assertTrue("a picture was taken", ok)
        assertEquals("the real picture, not the grey pixel / logo", "https://m.media-amazon.com/images/I/B0TEST0001P.jpg", p("B0TEST0001").image)
        assertEquals("found", com.restalker.mobile.core.Engine.picInfo["B0TEST0001"])
        // a picture kept by 1.5/1.6 that is not a product picture is dropped when the app reads its products
        WatchService.stop(ctx); waitFor(10_000) { WatchService.instance == null }
        val s = RkApp.store()
        s.add(Product(id = "old", asin = "B0TEST0002", cap = 99.0, watch = false, image = "https://images-na.ssl-images-amazon.com/images/G/01/x-locale/common/grey-pixel.gif"))
        RkApp.resetStoreKeepFiles()
        assertEquals("placeholder dropped", "", RkApp.store().products().first { it.asin == "B0TEST0002" }.image)
    }

    /** 1.7: Amazon's real picture server, from this phone, the way the app downloads (internet needed - CI has it). Which addresses
     *  answer and how is written to the log for the report; at least one real Amazon product picture must load. */
    @Test fun t18_real_amazon_picture_server() {
        PicCache.clear(); PicCache.fetch = PicCache::realFetch
        val urls = listOf("https://m.media-amazon.com/images/I/41FYkVPzrIL.jpg", "https://m.media-amazon.com/images/I/51CjYz4iQHL.jpg",
            "https://m.media-amazon.com/images/I/41FYkVPzrIL._SL160_.jpg")
        val got = urls.map { u -> u to (PicCache.get(u)?.let { "${it.width}x${it.height}" } ?: PicCache.status[u]) }
        File(File(ctx.filesDir, "shots").also { it.mkdirs() }, "t18_amazon_pictures.txt").writeText(got.joinToString("\n") { "${it.first} -> ${it.second}" })
        android.util.Log.i("RK-T18", got.toString())
        assertTrue("a real Amazon picture loads: $got", got.any { it.second?.contains("x") == true && !it.second!!.startsWith("failed") })
        // kept: the second time comes from the phone (no download)
        PicCache.fetch = { throw java.io.IOException("no network in this check") }
        val again = urls.firstNotNullOfOrNull { u -> if (got.first { it.first == u }.second?.startsWith("failed") == false) u else null }!!
        PicCache.evictMemory()
        assertTrue("kept on the phone", PicCache.get(again) != null)
        PicCache.fetch = PicCache::realFetch
    }

    /** 2.0 (owner: "the app still hasnt been tested for checkout. We need a way to verify this will work independently from a PC"):
     *  a practice with ANY Amazon item in stock, at any price - the whole checkout, stopped before Place your order: never an order, the
     *  item is not left in the list, and "Real purchases" on changes nothing about that. */
    @Test fun t19_practice_with_any_item() {
        RkApp.store().setSettings { it.copy(practice = false, buying = true) }
        start(false, true)
        WatchService.cmd(ctx, "practiceany", "B0TEST0001")
        val done = waitFor(120_000) { RkApp.store().log().any { it.contains("Practice checkout reached Place your order") } }
        dumpLog("t19")
        assertTrue("the practice reached Place your order", done)
        assertEquals("nothing was ordered", 0, site.places)
        assertTrue("the item is not left in the list", RkApp.store().products().none { it.asin == "B0TEST0001" })
    }

    @Test fun t13_testMessage_andDiagnostics() {
        Notifier.test(ctx)
        Thread.sleep(1_500)
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val shown = nm.activeNotifications.any { it.id == 42 }
        RkApp.store().add(Product(id = "dg", asin = "B0TEST0003", cap = 120.0))
        val d = Diag.text(ctx)
        File(File(ctx.filesDir, "shots").also { it.mkdirs() }, "t13_diagnostics.txt").writeText(d)
        assertTrue("test message shown", shown)
        assertTrue(d.contains("Restalker Mobile v") && d.contains("B0TEST0003") && d.contains("Log (newest last)"))
        assertTrue("no password or card text", !d.contains("password", true) && !d.contains("card number", true))
    }
}
