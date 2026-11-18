package com.restalker.mobile

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.restalker.mobile.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The app's screen: the version in the title, adding a product from an Amazon link. */
@RunWith(AndroidJUnit4::class)
class ScreenTests {
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun p(asin: String) = RkApp.store().products().first { it.asin == asin }
    private val tag: String get() = InstrumentationRegistry.getArguments().getString("shotTag") ?: ""
    private fun shot(name0: String) {
        val name = name0 + tag
        try {
            val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
            val d = File(ctx.filesDir, "shots").also { it.mkdirs() }
            File(d, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        } catch (e: Exception) { }
    }

    /** 2.7: every screen test starts with all product groups open - groups stay folded on purpose (folded.json), and the random taps
     *  run before the big-text pass fold some, which hid the products those tests look for */
    @get:Rule(order = 0) val openGroups = object : org.junit.rules.ExternalResource() {
        override fun before() { try { RkApp.store().writeFile("folded.json", "[]") } catch (e: Exception) { } }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    /** 2.7: scroll the home list to [m]; if it is not there, look once more after a moment (a slow test phone may still be drawing),
     *  and if it is still not there say what the list DID hold (what is in the app's list of products, and the list's rows) */
    private fun toList(m: androidx.compose.ui.test.SemanticsMatcher) {
        try { compose.onNodeWithTag("list").performScrollToNode(m); return } catch (e: AssertionError) { }
        compose.waitForIdle(); Thread.sleep(1_500); compose.waitForIdle()
        try { compose.onNodeWithTag("list").performScrollToNode(m) } catch (e: AssertionError) {
            shot("fail_list")
            val rows = try { compose.onNodeWithTag("list").printToString(maxDepth = 2) } catch (x: Throwable) { "(not readable: $x)" }
            val ps = RkApp.store().products().joinToString { it.asin + (if (it.watch) "" else "/nowatch") }
            throw AssertionError("not in the list: ${m.description} | products in the app: [$ps] | folded: ${RkApp.store().readFile("folded.json")} | the list:\n" +
                rows.lines().filter { "Tag:" in it || "Text =" in it }.joinToString("\n").take(4000), e)
        }
    }

    @Test fun t5_screen_titleAndAddProduct() {
        RkApp.store().products().forEach { RkApp.store().remove(it.id) }
        compose.onNodeWithTag("title").assertIsDisplayed()
        // the whole title, version included, is shown (not cut off) - with normal and with big text
        val lay = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("title").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(lay) }
        val l0 = lay.first(); val full = l0.layoutInput.text.text
        assertEquals("the whole title is visible: $full", full.length, l0.getLineEnd(l0.lineCount - 1, visibleEnd = true))
        assertEquals("no line is cut with ...", false, (0 until l0.lineCount).any { l0.isLineEllipsized(it) })
        compose.onNodeWithText("Restalker Mobile v${com.restalker.mobile.core.APP_VERSION}").assertIsDisplayed()
        compose.onNodeWithTag("add").performClick()
        compose.onNodeWithTag("f-link").performTextInput("https://www.amazon.com/Magic-Hobbit/dp/B0TEST0001/ref=sr_1_1")
        compose.onNodeWithTag("f-cap").performTextInput("120")
        for (k in 0 until 3) {           // (2.1: once missed with big text on API 35 - the keyboard took the tap; as for a person, tap again)
            if (RkApp.store().products().any { it.asin == "B0TEST0001" }) break
            compose.waitForIdle(); compose.onNodeWithTag("f-save").performClick()
            try { compose.waitUntil(5_000) { RkApp.store().products().any { it.asin == "B0TEST0001" } }; break } catch (e: Throwable) { if (k == 2) throw e }
        }
        compose.waitForIdle()
        shot("t5_screen")
        assertEquals(120.0, p("B0TEST0001").cap, 0.001)
        toList(hasTestTag("product-B0TEST0001"))
        compose.onNodeWithTag("product-B0TEST0001").assertIsDisplayed()
    }
    @Test fun t6_firstRun_setupCard_andRealPurchasesNeedYes() {
        RkApp.store().products().forEach { RkApp.store().remove(it.id) }
        RkApp.store().setSettings { com.restalker.mobile.core.Settings() }
        compose.waitForIdle()
        compose.onNodeWithTag("setup").assertIsDisplayed()
        shot("t6_first_run")
        // 2.2 (owner: "load up with the option to be buying"): a new install buys for real (only products set to Buy, under the cap)
        assertEquals("a new install buys for real", false, RkApp.store().settings().practice)
        assertEquals("a new install has buying on", true, RkApp.store().settings().buying)
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("practice").performScrollTo().performClick()     // practice on: at once
        compose.waitUntil(3_000) { RkApp.store().settings().practice }
        compose.onNodeWithTag("practice").performScrollTo().performClick()     // switching Real purchases on again asks first
        compose.onNodeWithTag("confirm-real").assertIsDisplayed()
        assertEquals("still practicing until Yes", true, RkApp.store().settings().practice)
        shot("t6_confirm_real")
        compose.onNodeWithTag("confirm-real").performClick()
        compose.waitUntil(3_000) { !RkApp.store().settings().practice }
    }

    @Test fun t7_addProduct_userMistakes() {
        compose.onNodeWithTag("add").performClick()
        compose.onNodeWithTag("f-link").performTextInput("https://www.ebay.com/itm/12345")
        compose.onNodeWithTag("f-cap").performTextInput("120")
        // (the keyboard is still sliding in: a tap during that can miss - as for a person, tap again)
        for (k in 0 until 3) {
            compose.waitForIdle(); compose.onNodeWithTag("f-save").performClick()
            try { compose.waitUntil(4_000) { compose.onAllNodesWithTag("f-err").fetchSemanticsNodes().isNotEmpty() }; break } catch (e: Throwable) { if (k == 2) throw e }
        }
        shot("t7_bad_link")
        compose.onNodeWithTag("f-link").performTextClearance()
        compose.onNodeWithTag("f-link").performTextInput("B0TEST0009")
        compose.onNodeWithTag("f-cap").performTextClearance()
        compose.onNodeWithTag("f-cap").performTextInput("4500")
        for (k in 0 until 3) {                                          // a big cap is asked about once (tap again if the keyboard took the tap)
            compose.waitForIdle(); compose.onNodeWithTag("f-save").performClick()
            try { compose.waitUntil(4_000) { compose.onAllNodesWithText("is that right?", substring = true).fetchSemanticsNodes().isNotEmpty() }; break } catch (e: Throwable) { if (k == 2) throw e }
        }
        assertEquals(false, RkApp.store().products().any { it.asin == "B0TEST0009" })
        for (k in 0 until 3) {
            compose.waitForIdle(); compose.onNodeWithTag("f-save").performClick()
            try { compose.waitUntil(5_000) { RkApp.store().products().any { it.asin == "B0TEST0009" } }; break } catch (e: Throwable) { if (k == 2) throw e }
        }
        assertEquals(4500.0, p("B0TEST0009").cap, 0.001)
    }

    @Test fun t9_reportButton_showsCodeToCopy() {
        // (the screen may still be drawing on a slow test phone: wait for it, and scroll a second time if the first try is too early)
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("tab3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        shot("t9_settings")
        compose.onNodeWithTag("report").performScrollTo().performClick()
        compose.onNodeWithTag("report-note").performTextInput("test note: pictures missing")      // 1.6: your words go with it
        compose.onNodeWithTag("report-send").performClick()
        compose.waitUntil(90_000) { compose.onAllNodesWithTag("report-msg").fetchSemanticsNodes().isNotEmpty() &&
            RkApp.store().log().any { it.contains("Report sent") || it.contains("could not be sent") } }
        compose.waitForIdle(); Thread.sleep(1_000)
        shot("t9_report")
        val code = com.restalker.mobile.core.Report.code(RkApp.store())
        compose.onNodeWithText(code, substring = true).assertIsDisplayed()
    }

    private fun prod(asin: String, name: String, year: String = "", set: String = "", buyable: Boolean = false, buy: Boolean = false, price: Double? = null) =
        com.restalker.mobile.core.Product(id = "id$asin", asin = asin, name = name, cap = 400.0, kind = "box", watch = true, buy = buy, year = year, set = set,
            buyable = buyable, price = price, lastCheck = if (price != null) System.currentTimeMillis() - 30_000 else 0,
            status = if (buyable) "BUYABLE at \$${price}" else if (price != null) "out of stock" else "not checked yet")

    /** 1.4 (owner: "The desktop version had several collapsible groups"): years, then sets, each folds and stays folded; In stock now on top;
     *  the filters. */
    @Test fun t10_home_groups_fold_and_filters() {
        val s = RkApp.store()
        s.products().forEach { s.remove(it.id) }; s.writeFile("folded.json", "[]")
        s.add(prod("B0TEST0101", "MTG Star Trek Collector Booster Box", "2025", "Star Trek", price = 455.0))
        s.add(prod("B0TEST0102", "MTG Star Trek Bundle", "2025", "Star Trek", price = 99.0))
        s.add(prod("B0TEST0103", "The Hobbit Collector Booster Box", "2026", "The Hobbit", buyable = true, buy = true, price = 350.0))
        s.add(prod("B0TEST0104", "Magic: The Gathering Nauctis: The Sunken Realm Collector Booster Display"))
        compose.waitForIdle(); Thread.sleep(500)
        shot("t10_groups")
        toList(hasTestTag("grp-hot")); compose.onNodeWithTag("grp-hot").assertIsDisplayed()
        toList(hasTestTag("top-B0TEST0103")); compose.onNodeWithTag("top-B0TEST0103").assertIsDisplayed()
        toList(hasTestTag("product-B0TEST0101"))
        toList(hasTestTag("grp-2025"))
        compose.onNodeWithTag("grp-2025").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("product-B0TEST0101").assertDoesNotExist()                          // folded
        assertEquals(true, s.readFile("folded.json")!!.contains("y:2025"))                         // remembered
        toList(hasTestTag("grp-Your products"))
        toList(hasTestTag("set-Nauctis: The Sunken Realm"))
        compose.onNodeWithTag("set-Nauctis: The Sunken Realm").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("product-B0TEST0104").assertDoesNotExist()
        compose.onNodeWithTag("set-Nauctis: The Sunken Realm").performClick(); compose.waitForIdle()
        toList(hasTestTag("product-B0TEST0104"))
        toList(hasTestTag("grp-2025"))
        compose.onNodeWithTag("grp-2025").performClick(); compose.waitForIdle()                    // open again
        toList(hasTestTag("product-B0TEST0102"))
        toList(hasTestTag("filter1"))
        compose.onNodeWithTag("filter1").performClick(); compose.waitForIdle()                     // In stock
        shot("t10_in_stock")
        compose.onNodeWithTag("product-B0TEST0101").assertDoesNotExist()
        toList(hasTestTag("product-B0TEST0103"))
        toList(hasTestTag("filter2"))
        compose.onNodeWithTag("filter2").performClick(); compose.waitForIdle()                     // Set to buy
        compose.onNodeWithTag("product-B0TEST0104").assertDoesNotExist()
        toList(hasTestTag("filter0"))
        compose.onNodeWithTag("filter0").performClick(); compose.waitForIdle()
        // 1.5: In stock = for sale at ANY price (also over the cap); Watched = watched, set to buy or not
        s.add(prod("B0TEST0105", "MTG Star Trek Collector Booster Box (over)", "2025", "Star Trek", price = 499.0).copy(overCap = true, cap = 460.0, status = "for sale over your cap"))
        s.update("idB0TEST0102") { it.copy(watch = false) }
        compose.waitForIdle()
        toList(hasTestTag("filter1"))
        compose.onNodeWithTag("filter1").performClick(); compose.waitForIdle()
        toList(hasTestTag("product-B0TEST0105"))         // over the cap: in stock
        toList(hasTestTag("filter3"))
        compose.onNodeWithTag("filter3").performClick(); compose.waitForIdle()                     // Watched
        compose.onNodeWithTag("product-B0TEST0102").assertDoesNotExist()
        toList(hasTestTag("product-B0TEST0104"))
        toList(hasTestTag("filter0"))
        compose.onNodeWithTag("filter0").performClick(); compose.waitForIdle()
    }

    /** 1.5: Buy 1 / 2 / 3 on the product screen; the desktop's view (other stores, MSRP, TCG) shown; a picture shown. */
    @Test fun t13_productScreen_quantity_desktopView_picture() {
        val s = RkApp.store()
        s.products().forEach { s.remove(it.id) }
        val f = File(ctx.cacheDir, "pic.png"); val bm = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888); bm.eraseColor(android.graphics.Color.rgb(200, 30, 30))
        f.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 1.7: the app downloads the picture itself (here: given by the test instead of Amazon's picture server)
        PicCache.clear(); val png = f.readBytes(); PicCache.fetch = { u -> if (u.contains("/images/I/T13pic")) png else throw java.io.IOException("unexpected $u") }
        s.add(prod("B0TEST0301", "The Hobbit Collector Booster Box", "2026", "The Hobbit", price = 520.0).copy(image = "https://m.media-amazon.com/images/I/T13pic.jpg",
            dSt = "over", dPr = 529.99, dAt = System.currentTimeMillis(), msrp = 455.88, tcg = 610.25,
            others = listOf(mapOf("s" to "Best Buy", "st" to "in", "pr" to 449.99, "at" to System.currentTimeMillis(), "u" to "https://www.bestbuy.com/product/x/JJ1"),
                mapOf("s" to "Target", "st" to "out", "pr" to null, "at" to 0L, "u" to "https://www.target.com/p/x/-/A-1"))))
        compose.waitForIdle()
        toList(hasTestTag("product-B0TEST0301"))
        compose.onAllNodesWithText("Over cap · \$529.99", substring = true).onFirst().assertIsDisplayed()   // the desktop's newer look (also on top: in stock)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("pic-B0TEST0301", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }   // the picture is shown
        compose.onNodeWithTag("product-B0TEST0301").performClick(); compose.waitForIdle(); Thread.sleep(800)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("bigpic-img-B0TEST0301", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        shot("t13_product_desktop_view")
        compose.onNodeWithTag("bigpic-img-B0TEST0301", useUnmergedTree = true).assertIsDisplayed()      // 2.0: the large picture, the title under it
        compose.onNodeWithTag("ptitle-B0TEST0301").assertIsDisplayed()
        // 2.0: the price bar - $529.99 (the desktop's) is over the $400 cap: red; every tick there
        compose.onNodeWithTag("pricebar-B0TEST0301").assertIsDisplayed()
        compose.onNodeWithTag("pricebar-red", useUnmergedTree = true).assertExists()
        for (k in listOf("zero", "msrp", "tcg", "cap", "now")) compose.onNodeWithTag("tick-$k", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ref-B0TEST0301").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("others-B0TEST0301").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Best Buy").assertIsDisplayed()
        // 2.2: in stock at a store -> Open (that store's own page); out of stock -> no Open
        compose.onNodeWithTag("open-Best Buy").performScrollTo().assertIsDisplayed()
        assertTrue("no Open for a store without stock", compose.onAllNodesWithTag("open-Target").fetchSemanticsNodes().isEmpty())
        shot("t13_other_stores_bottom")
        compose.onNodeWithTag("qty-B0TEST0301-2").performScrollTo().performClick()
        compose.waitUntil(3_000) { p("B0TEST0301").qty == 2 && p("B0TEST0301").qtySince > 0 }
        compose.onNodeWithTag("qty-B0TEST0301-1").performClick()
        compose.waitUntil(3_000) { p("B0TEST0301").qty == 1 }
        PicCache.fetch = PicCache::realFetch
    }

    /** 1.7: a picture that does not load - the initials stay (no blank box, no crash); the reason is in the diagnostics and the self-check
     *  finds it (it sends a report by itself). A grey placeholder pixel is never shown as the picture. The desktop's picture is used when
     *  this phone has none. */
    @Test fun t14_picture_failure_selfcheck_desktop_picture() {
        val s = RkApp.store()
        s.products().forEach { s.remove(it.id) }
        s.setSettings { it.copy(autoReport = false) }
        PicCache.clear(); PicCache.retryMs = 0
        try {
            PicCache.fetch = { throw java.io.IOException("the picture server answered 403") }
            s.add(prod("B0TEST0401", "Star Trek Collector Booster Box", "2026", "Star Trek", price = 399.99).copy(image = "https://m.media-amazon.com/images/I/T14bad.jpg"))
            compose.waitForIdle()
            toList(hasTestTag("product-B0TEST0401"))
            compose.waitUntil(5_000) { PicCache.failures("https://m.media-amazon.com/images/I/T14bad.jpg") >= 1 }
            compose.onNodeWithText("ST", useUnmergedTree = true).assertIsDisplayed()                 // the initials
            PicCache.get("https://m.media-amazon.com/images/I/T14bad.jpg")              // (a second failure)
            val hp = com.restalker.mobile.core.Health.check(HealthRunner.input(ctx))
            assertTrue("self-check finds it: $hp", hp.any { it.first == "pics-load" && it.second.contains("403") })
            val d = Diag.text(ctx)
            assertTrue("diagnostics tell why", d.contains("T14bad") && d.contains("failed: the picture server answered 403") && d.contains("pics-load"))
            // a 1x1 grey pixel served as the picture is refused (it was the blank box of 1.5/1.6)
            val px = java.io.ByteArrayOutputStream().also { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            PicCache.fetch = { px }
            assertEquals(null, PicCache.get("https://m.media-amazon.com/images/I/T14px.jpg"))
            assertTrue(PicCache.status["https://m.media-amazon.com/images/I/T14px.jpg"]!!.contains("placeholder"))
            // a web page instead of a picture
            PicCache.fetch = { "<html>Robot check</html>".toByteArray() }
            assertEquals(null, PicCache.get("https://m.media-amazon.com/images/I/T14html.jpg"))
            assertTrue(PicCache.status["https://m.media-amazon.com/images/I/T14html.jpg"]!!.contains("web page"))
            // the desktop's picture when this phone has none
            val good = java.io.ByteArrayOutputStream().also { Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
                .compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            PicCache.fetch = { u -> if (u.contains("bbystatic")) good else throw java.io.IOException("no") }
            s.add(prod("B0TEST0402", "Star Trek Beam Me Up Bundle", "2026", "Star Trek", price = 99.99).copy(dImg = "https://pisces.bbystatic.com/image2/BestBuy_US/images/products/6686/6686924_sd.jpg"))
            compose.waitForIdle()
            toList(hasTestTag("product-B0TEST0402"))
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("pic-B0TEST0402", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            shot("t14_pictures")
            // 1.9: one picture kept for two different products is no product picture: dropped for both, never taken again
            val shared = "https://m.media-amazon.com/images/I/31uI7WnC0LL.jpg"
            s.add(prod("B0TEST0403", "Nauctis Collector Booster Box", "2026", "Nauctis").copy(image = shared))
            s.add(prod("B0TEST0404", "Nauctis Secret Lair Bundle", "2026", "Nauctis").copy(image = shared))
            HealthRunner.repairPictures()
            assertEquals("", p("B0TEST0403").image); assertEquals("", p("B0TEST0404").image)
            assertTrue((s.readFile(com.restalker.mobile.core.Engine.BAD_PICS) ?: "").contains("31uI7WnC0LL"))
        } finally { PicCache.retryMs = 10 * 60_000L; PicCache.fetch = PicCache::realFetch }
    }

    /** 2.0: under the cap the price bar is green; Settings has the practice with any Amazon item and the share code (download page). */
    @Test fun t15_greenBar_practiceAny_share() {
        val s = RkApp.store()
        s.products().forEach { s.remove(it.id) }
        s.add(prod("B0TEST0501", "Star Trek Collector Booster Box", "2026", "Star Trek", buyable = true, price = 369.99).copy(msrp = 455.88, tcg = 410.0))
        compose.waitForIdle()
        toList(hasTestTag("product-B0TEST0501"))
        compose.onNodeWithTag("product-B0TEST0501").performClick(); compose.waitForIdle(); Thread.sleep(600)
        compose.onNodeWithTag("pricebar-green", useUnmergedTree = true).assertExists()
        shot("t15_green_bar")
        compose.onNodeWithTag("back").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Settings").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("practice-any").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pa-link").performTextInput("https://www.ebay.com/itm/1")
        for (k in 0 until 3) {                                    // not an Amazon link: said, nothing runs (tap again if the keyboard took the tap)
            compose.waitForIdle(); compose.onNodeWithTag("pa-go").performScrollTo().performClick()
            try { compose.waitUntil(5_000) { compose.onAllNodesWithTag("pa-err").fetchSemanticsNodes().isNotEmpty() }; break } catch (e: Throwable) { if (k == 2) throw e }
        }
        compose.onNodeWithTag("share-qr").performScrollTo().assertIsDisplayed()
        shot("t15_settings_share")
    }

    /** 2.2: the cap right at the price bar - - / + in $5 steps, dragging the cap mark (only from the mark itself); a product without a
     *  cap asks for one before Buy goes on; Get all products brings the central list in without caps. */
    @Test fun t16_cap_at_the_bar() {
        val s = RkApp.store()
        s.products().forEach { s.remove(it.id) }
        s.add(prod("B0TEST0601", "Reality Fracture Collector Booster Box", "2026", "Reality Fracture", price = 420.0).copy(msrp = 323.88))
        compose.waitForIdle()
        toList(hasTestTag("product-B0TEST0601"))
        compose.onNodeWithTag("product-B0TEST0601").performClick(); compose.waitForIdle(); Thread.sleep(600)
        compose.onNodeWithTag("capup-B0TEST0601").performScrollTo().performClick()
        compose.waitUntil(3_000) { p("B0TEST0601").cap == 405.0 }
        compose.onNodeWithTag("capdown-B0TEST0601").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("capdown-B0TEST0601").performClick()
        compose.waitUntil(3_000) { p("B0TEST0601").cap == 395.0 }
        compose.onNodeWithTag("cap-B0TEST0601").assertIsDisplayed()
        shot("t16_cap_buttons")
        // 2.3: an exact cap typed in the box next to the buttons (saved with Done), then back to 395 with the box
        compose.onNodeWithTag("capfield-B0TEST0601").performTextReplacement("412.34")
        compose.onNodeWithTag("capfield-B0TEST0601").performImeAction()
        compose.waitUntil(3_000) { Math.abs(p("B0TEST0601").cap - 412.34) < 0.001 }
        compose.onNodeWithTag("capfield-B0TEST0601").performTextReplacement("395")
        compose.onNodeWithTag("capfield-B0TEST0601").performImeAction()
        compose.waitUntil(3_000) { p("B0TEST0601").cap == 395.0 }
        shot("t16_cap_typed")
        // a swipe that does not start on the cap mark changes nothing
        compose.onNodeWithTag("capdrag-B0TEST0601", useUnmergedTree = true).performTouchInput { swipe(Offset(width * 0.02f, height * 0.4f), Offset(width * 0.1f, height * 0.4f), 300) }
        compose.waitForIdle(); Thread.sleep(300)
        assertEquals(395.0, p("B0TEST0601").cap, 0.001)
        // dragging the cap mark to the right raises it (on a $5 mark)
        compose.onNodeWithTag("capknob-B0TEST0601", useUnmergedTree = true).performTouchInput { swipe(center, Offset(center.x + 120f, center.y), 500) }
        compose.waitUntil(3_000) { p("B0TEST0601").cap > 395.0 }
        val c = p("B0TEST0601").cap
        assertTrue("on a \$5 mark: $c", Math.abs(c / 5.0 - Math.round(c / 5.0)) < 1e-9)
        shot("t16_cap_dragged")
        // 2.3: pulling the product page down looks at it now (and nothing breaks)
        compose.onNodeWithTag("prefresh-B0TEST0601").performTouchInput { swipeDown(startY = height * 0.15f, endY = height * 0.7f) }
        compose.waitForIdle(); Thread.sleep(500)
        compose.onNodeWithTag("product-screen").assertExists()
        // (the pull started the watching service for that one look - stopped again, as the other screen tests run without it)
        Thread.sleep(1_500)
        InstrumentationRegistry.getInstrumentation().targetContext.let { it.stopService(android.content.Intent(it, WatchService::class.java)) }
        // no cap -> Buy asks for one first
        s.update("idB0TEST0601") { it.copy(cap = 0.0, buy = false) }; compose.waitForIdle(); Thread.sleep(400)
        compose.onNodeWithTag("buy-B0TEST0601").performScrollTo().performClick()
        compose.onNodeWithTag("e-cap").assertIsDisplayed()
        assertEquals("not set to Buy without a cap", false, p("B0TEST0601").buy)
        compose.onNodeWithTag("e-cap").performTextClearance(); compose.onNodeWithTag("e-cap").performTextInput("350")
        compose.onNodeWithTag("e-save").performClick()
        compose.waitUntil(3_000) { p("B0TEST0601").cap == 350.0 }
    }

    /** 2.2: Get all products (the central list on the release home): added without caps, nothing watched or set to Buy; a product of
     *  your own is kept as it is. */
    @Test fun t17_get_all_products() {
        val s = RkApp.store()
        s.products().forEach { s.remove(it.id) }
        s.add(prod("B0GXC89N66", "The Hobbit Collector Booster Box", "2026", "The Hobbit", price = 520.0))
        compose.waitForIdle()
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("catalog-go").performScrollTo().performClick()
        // (the products come in one by one: wait for the end of it - the message says how many)
        compose.waitUntil(45_000) { compose.onAllNodesWithText("products added", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val added = s.products().filter { it.asin != "B0GXC89N66" }
        shot("t17_all_products")
        assertTrue("products came in (${added.size})", added.size >= 10)
        assertTrue("no caps, not watched, not Buy", added.all { it.cap == 0.0 && !it.watch && !it.buy })
        assertEquals("the own product kept", 400.0, p("B0GXC89N66").cap, 0.001)
        assertTrue("the own product is not doubled", s.products().count { it.asin == "B0GXC89N66" } == 1)
    }

    /** 1.4: a product's own screen - Buy, Watch, the cap, Remove (asks once), Back. */
    @Test fun t11_productScreen() {
        val s = RkApp.store()
        s.products().forEach { s.remove(it.id) }
        s.add(prod("B0TEST0201", "The Hobbit Collector Booster Box", "2026", "The Hobbit", price = 420.0))
        compose.waitForIdle()
        toList(hasTestTag("product-B0TEST0201"))
        compose.onNodeWithTag("product-B0TEST0201").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("product-screen").assertIsDisplayed()
        compose.waitForIdle(); Thread.sleep(800)
        shot("t11_product")
        compose.onNodeWithTag("buy-B0TEST0201").performScrollTo().performClick()
        compose.waitUntil(3_000) { p("B0TEST0201").buy }
        compose.onNodeWithTag("edit-B0TEST0201").performScrollTo().performClick()
        compose.onNodeWithTag("e-cap").performTextClearance(); compose.onNodeWithTag("e-cap").performTextInput("399.5")
        compose.onNodeWithTag("e-save").performClick()
        compose.waitUntil(3_000) { p("B0TEST0201").cap == 399.5 }
        compose.onNodeWithTag("remove-B0TEST0201").performScrollTo().performClick()
        assertEquals("asks once before removing", true, RkApp.store().products().any { it.asin == "B0TEST0201" })
        compose.onNodeWithTag("remove-B0TEST0201").performClick()
        compose.waitUntil(3_000) { RkApp.store().products().none { it.asin == "B0TEST0201" } }
        compose.onNodeWithTag("list").assertIsDisplayed()                                          // back on the home screen
    }

    /** 1.4: linking by typing the code (the camera's fallback): a wrong code is explained; a right one links; Unlink asks first. */
    @Test fun t12_settings_linkByCode() {
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("pastecode").performScrollTo().performClick()
        compose.onNodeWithTag("link-code").performTextInput("hello")
        compose.onNodeWithTag("link-save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("link-msg").fetchSemanticsNodes().isNotEmpty() && !DeskLink.get().linked() }
        compose.onNodeWithText("That is not a Restalker link code", substring = true).assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithTag("pastecode").performScrollTo().performClick()
        compose.onNodeWithTag("link-code").performTextInput(com.restalker.mobile.core.LinkCrypto.make())
        compose.onNodeWithTag("link-save").performClick()
        compose.waitUntil(10_000) { DeskLink.get().linked() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Linked with the desktop", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("OK").performClick(); compose.waitForIdle()
        // (scroll the Settings list itself - with big text the nearest scroll could be the tab swiper)
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("link-title"))
        compose.onNodeWithTag("link-title").assertIsDisplayed()
        compose.onNodeWithTag("runicon").performScrollTo().performClick()
        compose.waitUntil(3_000) { !RkApp.store().settings().runIcon }
        compose.onNodeWithTag("runicon").performClick()
        compose.waitUntil(3_000) { RkApp.store().settings().runIcon }
        Thread.sleep(500); shot("t12_linked")
        compose.onNodeWithTag("unlink").performScrollTo().performClick()
        compose.onNodeWithTag("unlink-yes").performClick()
        compose.waitUntil(30_000) { !DeskLink.get().linked() }
    }

    /** 2.3: swiping left / right moves between Watch, Bought, Activity and Settings; pulling down refreshes; Settings, Linked devices:
     *  this device's own buying choice, "Buy if the PC stops" and its name. */
    @Test fun t18_swipe_pull_linked_devices() {
        compose.onNodeWithTag("list").assertExists()
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("bought").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }; compose.waitForIdle()
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pager").performTouchInput { swipeRight() }; compose.waitForIdle()
        compose.onNodeWithTag("pager").performTouchInput { swipeRight() }; compose.waitForIdle()
        compose.onNodeWithTag("pager").performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("list").fetchSemanticsNodes().isNotEmpty() }
        // pull down on the home screen: refreshes, no crash
        compose.onNodeWithTag("refresh-0").performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.7f) }
        compose.waitForIdle(); Thread.sleep(1_500)
        compose.onNodeWithTag("title").assertIsDisplayed()
        // linked: this device's own row in Linked devices
        assertEquals(null, DeskLink.get().connect(com.restalker.mobile.core.LinkCrypto.make()))
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("dev-me").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bm-both").performScrollTo().performClick()
        compose.waitUntil(3_000) { RkApp.store().settings().buyMode == "both" }
        compose.onNodeWithTag("bm-pc").performScrollTo().performClick()
        compose.waitUntil(3_000) { RkApp.store().settings().buyMode == "pc" }
        compose.onNodeWithTag("fo-me").performScrollTo().performClick()
        compose.waitUntil(3_000) { !RkApp.store().settings().failover }
        compose.onNodeWithText("Watches only, does not buy.", substring = true).assertExists()
        compose.onNodeWithTag("fo-me").performClick()
        compose.waitUntil(3_000) { RkApp.store().settings().failover }
        compose.onNodeWithTag("dev-rename").performScrollTo().performClick()
        compose.onNodeWithTag("dev-name").performTextReplacement("Test tablet")
        compose.onNodeWithTag("dev-name-ok").performClick()
        compose.waitUntil(3_000) { RkApp.store().settings().deviceName == "Test tablet" }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Test tablet (this device)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("devices").performScrollTo()
        Thread.sleep(500); shot("t18_linked_devices")
        DeskLink.get().unlink()
        RkApp.store().setSettings { it.copy(deviceName = "", buyMode = "pc", failover = true) }
    }

    /** 2.4: Bought lists this device's purchases (with price, store, device, browser); the product's page has its own Bought section above
     *  Latest; the TCG price is a link. */
    @Test fun t19_bought_and_tcg_link() {
        val s = RkApp.store()
        s.products().forEach { s.remove(it.id) }
        android.util.Log.i("RkTest", "t19 step 1")
        s.add(prod("B0TEST0701", "The Hobbit Collector Booster Box", "2026", "The Hobbit", price = 520.0).copy(msrp = 455.88, tcg = 542.98))
        android.util.Log.i("RkTest", "t19 step 2")
        s.addPurchase(com.restalker.mobile.core.Purchase(System.currentTimeMillis() - 60_000, "B0TEST0701", "The Hobbit Collector Booster Box", 455.88, null, true))
        // 2.8: TCGplayer's history from the central file (no PC linked) - the chart, and the bar's right end = the highest price seen
        val today = System.currentTimeMillis() / 86_400_000L
        s.writeFile(com.restalker.mobile.core.TcgHist.FILE, com.restalker.mobile.core.MiniJson.write(mapOf("v" to 2, "h" to mapOf("B0TEST0701" to
            ((0 until 30).map { listOf(today - 87 + it * 3, 520.0 + (it % 5) * 9.5, 20 + it * 3) } + listOf(listOf(today - 40, 612.0, 90)))),
            "y" to mapOf("B0TEST0701" to (0 until 52).map { listOf(today - 364 + it * 7, 700.0 - it * 3, 50) }))))
        android.util.Log.i("RkTest", "t19 step 3")
        compose.waitForIdle()
        android.util.Log.i("RkTest", "t19 step 4")
        compose.onNodeWithTag("tab1").performClick(); compose.waitForIdle()
        android.util.Log.i("RkTest", "t19 step 5")
        try {
            android.util.Log.i("RkTest", "t19 step 6")
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("buy-row").fetchSemanticsNodes().isNotEmpty() }
            android.util.Log.i("RkTest", "t19 step 7")
            compose.onAllNodesWithText("Amazon  ·  ", substring = true).onFirst().assertExists()      // (one per purchase - a second try adds one)
            android.util.Log.i("RkTest", "t19 step 8")
            compose.onNodeWithTag("bought-total").assertExists()
            shot("t19_bought")
        android.util.Log.i("RkTest", "t19 step 9")
        } finally { compose.onNodeWithTag("tab0").performClick(); compose.waitForIdle() }           // (back on Watch even when it failed)
        android.util.Log.i("RkTest", "t19 step 10")
        toList(hasTestTag("product-B0TEST0701"))
        android.util.Log.i("RkTest", "t19 step 11")
        compose.onNodeWithTag("boughtn-product-B0TEST0701", useUnmergedTree = true).assertExists()            // 2.9: "Bought 1x" by the picture
        // (the count includes earlier tests' purchases of the same product, matched by name - so: a count is shown, not which one)
        compose.onNodeWithTag("boughtn-product-B0TEST0701", useUnmergedTree = true).assert(androidx.compose.ui.test.hasText("Bought ", substring = true))
        compose.onNodeWithTag("product-B0TEST0701").performClick(); compose.waitForIdle(); Thread.sleep(500)
        compose.onNodeWithTag("pboughtn-B0TEST0701", useUnmergedTree = true).assertExists()
        android.util.Log.i("RkTest", "t19 step 12")
        compose.onNodeWithTag("tcglink-B0TEST0701").performScrollTo().assertIsDisplayed()
        android.util.Log.i("RkTest", "t19 step 13")
        compose.onNodeWithText("TCG market \$542.98", substring = true).assertExists()
        android.util.Log.i("RkTest", "t19 step 14")
        compose.onNodeWithTag("best-B0TEST0701").assertExists()                                      // 2.5: Best price line
        android.util.Log.i("RkTest", "t19 step 15")
        compose.onNodeWithTag("pricebar-gradient", useUnmergedTree = true).assertExists()             // 2.5: the gradient bar
        android.util.Log.i("RkTest", "t19 step 16")
        compose.onNodeWithTag("pricebar-end-B0TEST0701").assertExists()                             // 2.8
        compose.onNodeWithText("highest price seen, \$612.00", substring = true).assertExists()
        compose.onNodeWithTag("tcgchart-B0TEST0701").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("TCGplayer, last 3 months", substring = true).assertExists()
        compose.onNodeWithTag("tcgchange-B0TEST0701", useUnmergedTree = true).assertExists()
        shot("t19_tcg_chart")
        compose.onNodeWithTag("tcgrange-1Y").performScrollTo().performClick(); compose.waitForIdle()             // 1 year
        compose.onNodeWithText("TCGplayer, last year", substring = true).assertExists()
        shot("t19_tcg_chart_year")
        compose.onNodeWithTag("pbought-B0TEST0701").performScrollTo().assertIsDisplayed()
        shot("t19_product_bought")
        android.util.Log.i("RkTest", "t19 step 17")
        compose.onNodeWithTag("back").performClick()
    }

    /** 2.9: Settings - ntfy alerts: set up once, the code shown (the same on linked devices), switch kept. */
    @Test fun t21_ntfy_alerts_setup() {
        RkApp.store().setSettings { it.copy(alertTopic = "", alertsOn = false) }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("tab3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        try {
            compose.onNodeWithTag("alerts-setup").performScrollTo().performClick(); compose.waitForIdle(); Thread.sleep(600)
            val st = RkApp.store().settings()
            assertTrue("a channel was made and switched on", com.restalker.mobile.core.NtfyAlerts.ok(st.alertTopic) && st.alertsOn)
            compose.onNodeWithTag("alerts-code").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(st.alertTopic).assertExists()
            compose.onNodeWithTag("alerts-qr").assertExists()
            shot("t21_alerts")
            compose.onNodeWithTag("alerts-on").performScrollTo().performClick(); compose.waitForIdle()
            compose.waitUntil(3_000) { !RkApp.store().settings().alertsOn }
        } finally { RkApp.store().setSettings { it.copy(alertsOn = false) }; compose.onNodeWithTag("tab0").performClick(); compose.waitForIdle() }
    }

    /** 2.6: the contributors line under the title; Settings: the crowd switch and About at the bottom. */
    @Test fun t20_crowd_contributors_about() {
        android.util.Log.i("RkTest", "t19 step 18")
        compose.onNodeWithTag("contributors", useUnmergedTree = true).assertExists()
        android.util.Log.i("RkTest", "t19 step 19")
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        android.util.Log.i("RkTest", "t19 step 20")
        compose.onNodeWithTag("about-text").performScrollTo().assertIsDisplayed()
        android.util.Log.i("RkTest", "t19 step 21")
        compose.onNodeWithText("Restocks of sought-after Magic products", substring = true).assertExists()
        shot("t20_about")
        android.util.Log.i("RkTest", "t19 step 22")
        compose.onNodeWithTag("crowd-on").performScrollTo().performClick()
        android.util.Log.i("RkTest", "t19 step 23")
        compose.waitUntil(3_000) { !RkApp.store().settings().crowd }
        android.util.Log.i("RkTest", "t19 step 24")
        compose.onNodeWithTag("crowd-on").performClick()
        android.util.Log.i("RkTest", "t19 step 25")
        compose.waitUntil(3_000) { RkApp.store().settings().crowd }
        android.util.Log.i("RkTest", "t19 step 26")
        compose.onNodeWithTag("tab0").performClick(); compose.waitForIdle()
    }

    @Test fun t8_turnPhoneSideways_noCrash() {
        val dev = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        dev.setOrientationLeft(); Thread.sleep(2_000); compose.waitForIdle()
        shot("t8_sideways")
        compose.onNodeWithTag("title").assertIsDisplayed()
        dev.setOrientationNatural(); Thread.sleep(1_500)
        compose.onNodeWithTag("title").assertIsDisplayed()
    }
}
