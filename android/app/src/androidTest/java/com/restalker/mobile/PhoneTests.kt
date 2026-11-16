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

    private fun waitFor(ms: Long, f: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (f()) return true; Thread.sleep(250) }
        return f()
    }

    private fun shot(name: String) {
        try {
            val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
            val d = File(ctx.getExternalFilesDir(null), "shots").also { it.mkdirs() }
            File(d, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        } catch (e: Exception) { }
    }

    private fun dumpLog(name: String) {
        try {
            val d = File(ctx.getExternalFilesDir(null), "shots").also { it.mkdirs() }
            File(d, "$name.log.txt").writeText(RkApp.store().log().joinToString("\n") + "\n\n--- requests ---\n" + site.requests.joinToString("\n"))
        } catch (e: Exception) { }
    }

    private fun start(practice: Boolean, buying: Boolean = true, vararg ps: Product) {
        val s = RkApp.store()
        ps.forEach { s.add(it) }
        s.setSettings { it.copy(watching = true, buying = buying, practice = practice, intervalSec = 60) }
        device.pressHome()                      // (the app is in the background, as on your phone)
        WatchService.start(ctx)
        assertTrue("watching started", waitFor(15_000) { WatchService.instance != null })
    }

    private fun p(asin: String) = RkApp.store().products().first { it.asin == asin }

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
        File(File(ctx.getExternalFilesDir(null), "shots").also { it.mkdirs() }, "t4_timers.txt").writeText("40 x 25 ms timers took $ms ms in the hidden browser\n")
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
}
