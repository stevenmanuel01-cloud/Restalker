package com.restalker.mobile

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.compose.ui.semantics.getOrNull
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
        compose.onNodeWithTag("logo").assertIsDisplayed()                                          // 2.11: the logo in the header
        compose.waitForIdle(); toList(hasTestTag("mascot-note"))                                   // 2.11: the mascot, no products yet
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("title"))
        // the whole title, version included, is shown (not cut off) - with normal and with big text
        val lay = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("title").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(lay) }
        val l0 = lay.first(); val full = l0.layoutInput.text.text
        assertEquals("the whole title is visible: $full", full.length, l0.getLineEnd(l0.lineCount - 1, visibleEnd = true))
        assertEquals("no line is cut with ...", false, (0 until l0.lineCount).any { l0.isLineEllipsized(it) })
        assertTrue("the title starts with the name and the version: $full", full.startsWith("Restalker Mobile v${com.restalker.mobile.core.APP_VERSION}"))
        compose.onAllNodesWithText("Restalker Mobile v${com.restalker.mobile.core.APP_VERSION}", substring = true).onFirst().assertIsDisplayed()
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
    /** 2.12: Settings parts are folded - open one (its line is tapped). */
    /** 2.15: the keyboard away, only if it is showing (the Back key then closes only the keyboard, not the sheet). */
    private fun hideKeyboard() {
        val dev = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val shown = { dev.executeShellCommand("dumpsys input_method").contains("mInputShown=true") }
        if (shown()) { dev.pressBack(); Thread.sleep(600) }
        compose.waitForIdle()
    }

    /** Opens a part of Settings (left open when it already is). 1.0.2: 13 parts became 6 - an old part's name opens the part it is in now. */
    private fun sec(key0: String) {
        val key = mapOf("devices" to "desk", "phone" to "amazon", "practice" to "amazon", "ntfy" to "notes", "discord" to "community",
            "crowd" to "community", "share" to "community", "problems" to "about")[key0] ?: key0
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("sech-$key").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag("secb-$key").fetchSemanticsNodes().isNotEmpty()) return
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("sech-$key"))
        compose.onNodeWithTag("sech-$key").performClick(); compose.waitForIdle()
    }
    private fun secClose(key: String) {
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("sech-$key"))
        compose.onNodeWithTag("sech-$key").performClick(); compose.waitForIdle()
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
        sec("amazon")
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
        sec("problems")
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
        // 1.0 (owner: '"Instock now" should be a collapsible field like the others'): it folds, is remembered, and opens again
        compose.onNodeWithTag("grp-hot").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("top-B0TEST0103").assertDoesNotExist()
        compose.onNodeWithTag("grp-hot").assertIsDisplayed()                                        // (the bar and its count stay)
        assertEquals(true, s.readFile("folded.json")!!.contains("\"hot\""))
        Thread.sleep(300); shot("t10_hot_folded")
        toList(hasTestTag("product-B0TEST0103"))                                                    // (still in its year, with the green chip on the year's bar)
        toList(hasTestTag("grp-hot"))
        compose.onNodeWithTag("grp-hot").performClick(); compose.waitForIdle()
        assertEquals(false, s.readFile("folded.json")!!.contains("\"hot\""))
        toList(hasTestTag("top-B0TEST0103")); compose.onNodeWithTag("top-B0TEST0103").assertIsDisplayed()
        toList(hasTestTag("product-B0TEST0101"))
        toList(hasTestTag("grp-2025"))
        shot("t10_groups_amber")                                                                    // 2.11: amber fold buttons in view
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
        compose.onNodeWithTag("pricebar-B0TEST0301").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pricebar-red", useUnmergedTree = true).assertExists()
        for (k in listOf("zero", "msrp", "tcg", "cap", "now")) compose.onNodeWithTag("tick-$k", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ref-B0TEST0301").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("others-B0TEST0301").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Best Buy").assertIsDisplayed()
        // 2.11: every store with a page -> Open (in stock or not)
        compose.onNodeWithTag("open-Best Buy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("open-Target").performScrollTo().assertIsDisplayed()
        // 2.11: the lowest price anywhere is the headline - Best Buy $449.99* (Amazon $529.99), with the note to buy it there yourself
        compose.onNodeWithTag("lowest-note-B0TEST0301").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("capfield-B0TEST0301").performScrollTo(); compose.waitForIdle(); shot("t13_lowest_and_cap")
        compose.onNodeWithText("\$449.99*", substring = true).assertExists()
        compose.onNodeWithTag("latest-B0TEST0301").performScrollTo().assertIsDisplayed()        // 2.11: Latest, a fixed-size box
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
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        sec("practice")
        compose.onNodeWithTag("practice-any").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pa-link").performTextInput("https://www.ebay.com/itm/1")
        for (k in 0 until 3) {                                    // not an Amazon link: said, nothing runs (tap again if the keyboard took the tap)
            compose.waitForIdle(); compose.onNodeWithTag("pa-go").performScrollTo().performClick()
            try { compose.waitUntil(5_000) { compose.onAllNodesWithTag("pa-err").fetchSemanticsNodes().isNotEmpty() }; break } catch (e: Throwable) { if (k == 2) throw e }
        }
        sec("share")
        compose.onNodeWithTag("share-qr").performScrollTo().assertIsDisplayed()
        // 1.0.1: what to do when the other phone (a newer Samsung) blocks the install stands under the code
        compose.onNodeWithTag("share-blocked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("share-blocked").assert(androidx.compose.ui.test.hasText("Auto Blocker", substring = true))
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
        // (1.0.2: - / + and the bar are held 30 s from the first change - "Save now" saves at once)
        compose.onNodeWithTag("capup-B0TEST0601").performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag("capsave-B0TEST0601").performScrollTo().performClick()
        compose.waitUntil(3_000) { p("B0TEST0601").cap == 405.0 }
        compose.onNodeWithTag("capdown-B0TEST0601").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("capdown-B0TEST0601").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("capsave-B0TEST0601").performScrollTo().performClick()
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
        assertTrue("nothing held", compose.onAllNodesWithTag("capheld-B0TEST0601", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // dragging the cap mark to the right raises it (on a $5 mark)
        compose.onNodeWithTag("capknob-B0TEST0601", useUnmergedTree = true).performTouchInput { swipe(center, Offset(center.x + 120f, center.y), 500) }
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("capsave-B0TEST0601").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("capsave-B0TEST0601").performScrollTo().performClick()
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
        sec("catalog")
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
        // 1.0.2: no Change cap / Practice checkout buttons; the cap is typed in its box (saved at once with Done) ...
        assertTrue("no Change cap / Practice buttons", compose.onAllNodesWithTag("edit-B0TEST0201").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithTag("practice-B0TEST0201").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("capfield-B0TEST0201").performScrollTo().performTextReplacement("399.5")
        compose.onNodeWithTag("capfield-B0TEST0201").performImeAction()
        compose.waitUntil(3_000) { p("B0TEST0201").cap == 399.5 }
        // ... and - / + are held 30 s from the first tap, then saved and noted once (not one line per \$5)
        RkApp.store().log("t11 cap marker")
        repeat(3) { compose.onNodeWithTag("capup-B0TEST0201").performClick(); compose.waitForIdle() }
        // (1.0.4: the "held" line is drawn a moment after the third tap - within 2 seconds; it needed a second try on slow virtual phones)
        compose.waitUntil(2_000) { compose.onAllNodesWithTag("capheld-B0TEST0201", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("not saved yet", 399.5, p("B0TEST0201").cap, 0.001)
        Thread.sleep(300); shot("t11_cap_held")
        compose.onNodeWithTag("capsave-B0TEST0201").performScrollTo().performClick(); compose.waitForIdle()
        compose.waitUntil(3_000) { p("B0TEST0201").cap == 410.0 }                    // (399.50 -> 400 -> 405 -> 410)
        val capLines = RkApp.store().log().let { l -> l.drop(l.indexOfLast { it.endsWith("t11 cap marker") } + 1) }.filter { it.contains("The Hobbit Collector Booster Box: cap") }      // (this try's lines only)
        assertTrue("one line for the change: ${capLines.takeLast(3)}", capLines.last().contains("\$399.50 → \$410.00") &&
            capLines.none { it.endsWith("\$400.00.") || it.endsWith("\$405.00.") })      // (no line for the steps in between)
        compose.onNodeWithTag("remove-B0TEST0201").performScrollTo().performClick()
        assertEquals("asks once before removing", true, RkApp.store().products().any { it.asin == "B0TEST0201" })
        compose.onNodeWithTag("remove-B0TEST0201").performClick()
        compose.waitUntil(3_000) { RkApp.store().products().none { it.asin == "B0TEST0201" } }
        compose.onNodeWithTag("list").assertIsDisplayed()                                          // back on the home screen
    }

    /** 1.4: linking by typing the code (the camera's fallback): a wrong code is explained; a right one links; Unlink asks first. */
    @Test fun t12_settings_linkByCode() {
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        sec("desk")
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
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("sech-desk"))
        compose.onNodeWithTag("link-title", useUnmergedTree = true).assertIsDisplayed()
        // 2.12: the watching icon is off by default (it is switched on and off again here)
        sec("notes")
        val icon0 = RkApp.store().settings().runIcon
        compose.onNodeWithTag("runicon").performScrollTo().performClick()
        compose.waitUntil(3_000) { RkApp.store().settings().runIcon != icon0 }
        compose.onNodeWithTag("runicon").performClick()
        compose.waitUntil(3_000) { RkApp.store().settings().runIcon == icon0 }
        Thread.sleep(500); shot("t12_linked")
        sec("desk")                                                       // (2.15: one part open at a time - notes closed it)
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
        // 2.12 (owner: the arrow froze and stayed until another tab): the refresh is seen running, then ends by itself
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("busy-refresh-0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("busy-refresh-0", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle(); Thread.sleep(700)
        shot("t18_after_refresh")
        compose.onNodeWithTag("title").assertIsDisplayed()
        // linked: this device's own row in Linked devices
        assertEquals(null, DeskLink.get().connect(com.restalker.mobile.core.LinkCrypto.make()))
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        sec("devices")
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
            // 1.0 (owner: '"Bought" should be sortable by all, product, store, etc.'): All = a navy bar per month; Product / Store / Device
            compose.onNodeWithTag("bgrp-0").assertIsDisplayed()
            compose.onNodeWithTag("bgrp-0").assert(androidx.compose.ui.test.hasText(java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.US).format(java.util.Date())))
            compose.onNodeWithTag("bsort2").performClick(); compose.waitForIdle()                 // Store
            compose.onNodeWithTag("bgrp-0").assert(androidx.compose.ui.test.hasText("Amazon"))
            Thread.sleep(300); shot("t19_bought_by_store")
            compose.onNodeWithTag("bsort3").performClick(); compose.waitForIdle()                 // Device
            compose.onNodeWithTag("bgrp-0").assert(androidx.compose.ui.test.hasText("(this device)", substring = true))
            Thread.sleep(300); shot("t19_bought_by_device")
            compose.onNodeWithTag("bsort1").performClick(); compose.waitForIdle()                 // Product
            // (earlier tests' purchases of other products come first, A to Z: scroll to this one's bar - in this view only the bar has the name)
            val name = androidx.compose.ui.test.hasText("The Hobbit Collector Booster Box")
            val hobbit = name and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("bought"))
            compose.onNodeWithTag("bought").performScrollToNode(name)
            Thread.sleep(300); shot("t19_bought_by_product")
            // a group folds with a tap on its bar (remembered) and opens again
            compose.onNode(hobbit).performClick(); compose.waitForIdle()
            assertEquals(true, s.readFile("folded.json")!!.contains("\"b:p:the hobbit collector booster box\""))
            Thread.sleep(300); shot("t19_bought_folded")
            compose.onNode(hobbit).performClick(); compose.waitForIdle()
            assertEquals(false, s.readFile("folded.json")!!.contains("\"b:p:"))
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("buy-row").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("bought").performScrollToNode(hasTestTag("bsort0"))
            compose.onNodeWithTag("bsort0").performClick(); compose.waitForIdle()                 // All again
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
        compose.onNodeWithText("(a bit under the lowest to a quarter over the highest price)", substring = true).assertExists()    // 1.0.2
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
            sec("ntfy")
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
        sec("about")
        compose.onNodeWithTag("about-text").performScrollTo().assertIsDisplayed()
        android.util.Log.i("RkTest", "t19 step 21")
        compose.onNodeWithText("Restocks of sought-after Magic products", substring = true).assertExists()
        shot("t20_about")
        android.util.Log.i("RkTest", "t19 step 22")
        sec("crowd")
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

    /** 2.12: Settings is folded (one line per part); Notifications: the quick choices and the switches, kept; the summary line follows. */
    @Test fun t22_settings_folded_notification_choices() {
        RkApp.store().setSettings { it.copy(nRestock = true, nDrop = false, nBuy = true, nNeed = true) }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("tab3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        assertTrue("folded: no switch shown before a part is opened", compose.onAllNodesWithTag("buying").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithTag("n-drop").fetchSemanticsNodes().isEmpty())
        Thread.sleep(500); shot("t22_settings_folded")
        try {
            sec("notes")
            compose.onNodeWithTag("np-restocks").performScrollTo().performClick()
            compose.waitUntil(3_000) { RkApp.store().settings().let { it.nRestock && !it.nBuy && it.nNeed && !it.nDrop } }
            compose.onNodeWithText("Restocks only (and when it needs you)").assertExists()
            compose.onNodeWithTag("np-all").performScrollTo().performClick()
            compose.waitUntil(3_000) { RkApp.store().settings().let { it.nRestock && it.nBuy && it.nNeed && it.nDrop } }
            compose.onNodeWithTag("n-drop").performScrollTo().performClick()
            compose.waitUntil(3_000) { !RkApp.store().settings().nDrop }
            compose.onNodeWithTag("np-usual").assertExists()                 // (all but price drops: the Recommended choice)
            compose.onNodeWithTag("notice-off").assertExists()
            compose.onNodeWithTag("n-restock").performScrollTo(); Thread.sleep(500); shot("t22_notifications")
            secClose("notes")                                              // closes again
            compose.waitUntil(3_000) { compose.onAllNodesWithTag("n-drop").fetchSemanticsNodes().isEmpty() }
        } finally { RkApp.store().setSettings { it.copy(nRestock = true, nDrop = false, nBuy = true, nNeed = true) }; compose.onNodeWithTag("tab0").performClick(); compose.waitForIdle() }
    }

    /** 2.15: Settings - navy parts, one open at a time; red marks on what needs you (go away when tapped); Activity in days, only
     *  restocks / checkouts / problems; Check now shows a turning circle. */
    @Test fun t23_navy_settings_marks_activity_days_spinner() {
        val s = RkApp.store()
        s.setSettings { it.copy(attnSeen = emptyMap(), attnDaily = true) }
        s.products().forEach { s.remove(it.id) }
        s.add(prod("B0TEST0701", "The Hobbit Collector Booster Box", "2026", "The Hobbit", price = 520.0).copy(status = "NOT BOUGHT - Amazon checkout: sold out"))
        s.log("Watching switched ON.")
        s.log("The Hobbit Collector Booster Box: cap \$485.00.")
        s.log("The Hobbit Collector Booster Box: in stock at \$350.00 - buying it now.")
        s.log("The Hobbit Collector Booster Box: CHECKOUT started (\$350.00, cap \$400.00)")
        compose.waitForIdle()
        // the product that needs you: a red mark on the card and on the Watch tab; opening it takes the card's mark away
        toList(hasTestTag("product-B0TEST0701"))
        compose.onNodeWithTag("attn-B0TEST0701", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("tabattn0", useUnmergedTree = true).assertExists()
        shot("t23_watch_marks")
        compose.onNodeWithTag("product-B0TEST0701").performClick(); compose.waitForIdle()
        // Check now: a turning circle until the look is done
        compose.onNodeWithTag("check-B0TEST0701").performScrollTo().performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("busy-check-B0TEST0701", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        shot("t23_check_spinner")
        compose.onNodeWithTag("back").performScrollTo().performClick(); compose.waitForIdle()
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("attn-B0TEST0701", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        // Settings: navy parts; not signed in to Amazon on the test phone -> the red mark; opening the part takes it away
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("attn-amazon", useUnmergedTree = true).assertExists()
        Thread.sleep(400); shot("t23_settings_navy_marks")
        sec("amazon")
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("attn-amazon", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("buying").assertExists()
        sec("notes")                                                      // one part open at a time
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("buying").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("attn-daily").performScrollTo()
        sec("discord")
        compose.onNodeWithTag("discord-join").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("discord-sharing", useUnmergedTree = true).assertExists()
        Thread.sleep(400); shot("t23_discord")
        // Activity: a navy bar per day; restocks and checkouts shown, settings changes not
        compose.onNodeWithTag("tab2").performClick(); compose.waitForIdle()
        // 1.0.2: one entry for the restock - what was seen, what was done (and how it ended, once it has)
        // (the big-text pass runs after the normal one on the same day: its entry is there too - so: the joined entry, no separate lines)
        assertTrue("one entry: restock + checkout", compose.onAllNodesWithText("In stock \$350.00 → checkout", substring = true).fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithText("CHECKOUT started", substring = true).fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithText("buying it now", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue("settings changes are not in Activity", compose.onAllNodesWithText("Watching switched ON", substring = true).fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithText("cap \$485.00.", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Today").assertExists()
        Thread.sleep(400); shot("t23_activity_days")
        // 1.0 (owner: "Activities ... should also be collapsible"): a day folds with a tap on its bar (remembered) and opens again
        compose.onNodeWithText("Today").performClick(); compose.waitForIdle()
        assertTrue("today is folded", compose.onAllNodesWithText("→ checkout", substring = true).fetchSemanticsNodes().isEmpty())
        assertEquals(true, s.readFile("folded.json")!!.contains("\"day:"))
        compose.onNodeWithText("Today").assertExists()                                             // (the bar stays)
        Thread.sleep(300); shot("t23_activity_folded")
        compose.onNodeWithText("Today").performClick(); compose.waitForIdle()
        assertTrue("today is open again", compose.onAllNodesWithText("→ checkout", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals(false, s.readFile("folded.json")!!.contains("\"day:"))
        compose.onNodeWithTag("tab0").performClick(); compose.waitForIdle()
    }

    /** 2.15 (owner: "the CAP should not be required to watch, but is required for buying"). */
    @Test fun t24_watch_without_a_cap_buy_asks_for_one() {
        val s = RkApp.store()
        s.products().forEach { s.remove(it.id) }
        s.add(prod("B0TEST0801", "Lorwyn Eclipsed Collector Booster Box", "2026", "Lorwyn Eclipsed").copy(cap = 0.0, watch = false))
        compose.waitForIdle()
        toList(hasTestTag("product-B0TEST0801"))
        compose.onNodeWithTag("product-B0TEST0801").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("watch-B0TEST0801").performScrollTo().performClick(); compose.waitForIdle()
        compose.waitUntil(3_000) { s.products().first { it.asin == "B0TEST0801" }.watch }
        assertEquals("no cap asked to watch", 0.0, s.products().first { it.asin == "B0TEST0801" }.cap, 0.001)
        Thread.sleep(300); shot("t24_watch_no_cap")
        compose.onNodeWithTag("back").performScrollTo().performClick(); compose.waitForIdle()
        // the add form: no cap and Buy off -> added, watched only; Buy on without a cap -> asks for the cap
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("title"))
        compose.onNodeWithTag("add").performClick()
        // (Buy switched on first, the keyboard put away after typing: with big text on a small screen it covers the switch and Add)
        compose.onNodeWithTag("f-buy").performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag("f-buy").assertIsOn()
        compose.onNodeWithTag("f-link").performTextInput("B0TEST0802")
        hideKeyboard(); compose.waitForIdle()
        compose.onNodeWithTag("f-buy").assertIsOn()
        for (k in 0 until 3) {           // (as in t5: the keyboard can take the first tap - tap again, as a person would)
            compose.waitForIdle(); compose.onNodeWithTag("f-save").performClick()
            try { compose.waitUntil(5_000) { compose.onAllNodesWithText("To buy it, enter the price cap", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }; break }
            catch (e: Throwable) { if (k == 2) throw e }
        }
        assertTrue("not added without a cap while Buy is on", s.products().none { it.asin == "B0TEST0802" })
        hideKeyboard(); compose.waitForIdle()
        compose.onNodeWithTag("f-buy").performScrollTo().performClick(); compose.waitForIdle()      // Buy off: just watch
        compose.onNodeWithTag("f-buy").assertIsOff()
        for (k in 0 until 3) {
            if (s.products().any { it.asin == "B0TEST0802" }) break
            compose.waitForIdle(); compose.onNodeWithTag("f-save").performClick()
            try { compose.waitUntil(5_000) { s.products().any { it.asin == "B0TEST0802" } }; break } catch (e: Throwable) { if (k == 2) throw e }
        }
        val q = s.products().first { it.asin == "B0TEST0802" }
        assertTrue("watched, no cap, not set to buy", q.watch && q.cap == 0.0 && !q.buy)
        compose.waitForIdle()
        toList(hasTestTag("product-B0TEST0802"))
        Thread.sleep(300); shot("t24_added_no_cap")
    }

    /** 1.0.2 (owner: "For beta versions, let's use a "b" at the end of the version number"): a copy newer than the one everyone has
     *  shows "v1.0.2b" (title and Help and about); the standard copy shows "v1.0.2". */
    @Test fun t25_beta_mark_only_on_a_beta() {
        val v = com.restalker.mobile.core.APP_VERSION
        try {
            MainActivity.betaForTests = true; RkApp.store().log("t25 marker a", quiet = true)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Restalker Mobile v${v}b").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("list").performScrollToNode(hasTestTag("title"))
            val lay = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("title").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(lay) }
            val l0 = lay.first(); val full = l0.layoutInput.text.text
            assertEquals("Restalker Mobile v${v}b", full)
            assertEquals("the whole title is visible: $full", full.length, l0.getLineEnd(l0.lineCount - 1, visibleEnd = true))
            Thread.sleep(300); shot("t25_beta")
            compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
            compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("sech-about"))
            compose.onAllNodesWithText("Restalker Mobile v${v}b", substring = true, useUnmergedTree = true).onFirst().assertExists()
            Thread.sleep(300); shot("t25_beta_about")
            compose.onNodeWithTag("tab0").performClick(); compose.waitForIdle()
            // the standard copy (the one everyone has): only the version, no b
            MainActivity.betaForTests = false; RkApp.store().log("t25 marker b", quiet = true)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Restalker Mobile v$v").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(compose.onAllNodesWithText("Restalker Mobile v${v}b").fetchSemanticsNodes().isEmpty())
            Thread.sleep(300); shot("t25_standard")
        } finally { MainActivity.betaForTests = null }
    }

    /** 1.0.2 (owner, 2026-11-19 11:40 PM): Watch / Buy right on the card; what each year holds in words; Settings in 6 parts (with the
     *  words used); Activity without the report button. */
    @Test fun t26_card_buttons_year_words_six_settings() {
        val s = RkApp.store()
        s.products().forEach { s.remove(it.id) }
        s.add(prod("B0TEST0901", "Foundations Collector Booster Box", "2024", "Foundations").copy(watch = false, buy = false))
        s.add(prod("B0TEST0902", "Duskmourn Collector Booster Box", "2024", "Duskmourn").copy(cap = 0.0, watch = false, buy = false))
        compose.waitForIdle()
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("grp-2024"))
        compose.onNodeWithTag("grp-2024-sub", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("2 products · none watched", useUnmergedTree = true).assertExists()
        toList(hasTestTag("product-B0TEST0901"))
        compose.onNodeWithTag("qwatch-B0TEST0901").performClick(); compose.waitUntil(3_000) { p("B0TEST0901").watch }
        compose.onNodeWithTag("qbuy-B0TEST0901").performClick(); compose.waitUntil(3_000) { p("B0TEST0901").buy }
        // (1.0.4: within 2 seconds, and if not: what the bar says instead - on a slow small phone the bar is redrawn a moment after the tap)
        val want = "2 products · 1 watched · 1 set to buy"
        // (the first Watch and Buy put "Check all now" and the sign-in note above the list, which pushes the year's bar down - off a
        // small screen with big text since 1.0.4's taller top card)
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("grp-2024"))
        try { compose.waitUntil(2_000) { compose.onAllNodesWithText(want, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
        catch (e: Throwable) {
            shot("fail_t26_bar")
            val says = try { compose.onAllNodesWithTag("grp-2024-sub", useUnmergedTree = true).fetchSemanticsNodes().map { n ->
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text } } } catch (x: Throwable) { listOf("(not readable: $x)") }
            val rows = try { compose.onNodeWithTag("list").printToString(maxDepth = 2).lines().filter { "Tag:" in it || "Text =" in it }.joinToString("\n").take(3000) } catch (x: Throwable) { "(list not readable: $x)" }
            throw AssertionError("the 2024 bar does not say '$want' within 2 s - it says $says | in the app: ${s.products().joinToString { it.asin + "/w=" + it.watch + "/b=" + it.buy }} | the list:\n$rows")
        }
        Thread.sleep(300); shot("t26_card_buttons")
        toList(hasTestTag("product-B0TEST0901"))
        compose.onNodeWithTag("qwatch-B0TEST0901").performClick()                         // Watch off: Buy goes off with it
        compose.waitUntil(3_000) { !p("B0TEST0901").watch && !p("B0TEST0901").buy }
        // Buy on a product without a cap: its page opens (the cap is set there first)
        toList(hasTestTag("product-B0TEST0902"))
        compose.onNodeWithTag("qbuy-B0TEST0902").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("product-screen").assertExists()
        assertTrue("not set to buy without a cap", !p("B0TEST0902").buy)
        compose.onNodeWithTag("back").performScrollTo().performClick(); compose.waitForIdle()
        // Settings: 6 parts
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        val parts = listOf("desk", "amazon", "notes", "catalog", "community", "about")
        for (k in parts) { compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("sech-$k")) }
        val old = listOf("devices", "phone", "practice", "ntfy", "discord", "crowd", "share", "problems")
        assertTrue("6 parts, the old ones joined in", old.all { compose.onAllNodesWithTag("sech-$it").fetchSemanticsNodes().isEmpty() })
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("sech-desk")); Thread.sleep(300); shot("t26_settings")
        sec("about")
        compose.onNodeWithTag("glossary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("report").performScrollTo().assertIsDisplayed()
        Thread.sleep(300); shot("t26_help")
        sec("community")
        compose.onNodeWithTag("discord-join").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("crowd-on").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("share-qr").performScrollTo().assertIsDisplayed()
        sec("amazon")
        compose.onNodeWithTag("practice-any").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("autoupdate").performScrollTo().assertIsDisplayed()
        // Activity: no report button there any more
        compose.onNodeWithTag("tab2").performClick(); compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("report-log").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("tab0").performClick(); compose.waitForIdle()
    }

    /** 1.0.4 (owner, 2026-11-20, notes 7 and 8: "Make the restalker icon slightly bigger, maybe 30%-50% ... It should also occur on all
     *  tabs" / "an option to start/stop the app from running on the watch page. Right now it's unclear if I swipe the app away if it
     *  continues to watch in the background"): the bigger logo and the version on every tab; a Stop / Start button with its words. */
    @Test fun t40_logo_on_every_tab_and_a_stop_start_button() {
        val s = RkApp.store()
        s.products().forEach { s.remove(it.id) }                        // (nothing to look at: watching may be switched on for a moment)
        s.setSettings { it.copy(watching = false) }
        compose.waitForIdle()
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("title"))
        compose.onNodeWithTag("logo").assertIsDisplayed()
        val sz = compose.onNodeWithTag("logo").fetchSemanticsNode().size
        val dp = sz.width / compose.density.density
        assertTrue("the logo is bigger than 1.0.3's 48 dp and square: $dp dp", dp >= 55.5f && sz.width == sz.height)
        // the whole title beside the bigger logo is still shown, nothing cut
        val lay = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("title").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(lay) }
        assertEquals("no line of the title is cut", false, (0 until lay.first().lineCount).any { lay.first().isLineEllipsized(it) })
        // ... no word is broken in two, and on an ordinary phone with ordinary text it is at most the name and the version under it
        val tl = lay.first(); val words = tl.layoutInput.text.text
        assertTrue("no word of the title is split over two lines: '$words' in ${tl.lineCount} lines",
            (0 until tl.lineCount - 1).all { val e = tl.getLineEnd(it); e >= words.length || words[e - 1].isWhitespace() || words[e].isWhitespace() })
        val cfg = ctx.resources.configuration
        if (cfg.screenWidthDp >= 400 && cfg.fontScale <= 1.01f) assertTrue("the title takes at most 2 lines beside the logo: ${tl.lineCount}", tl.lineCount <= 2)
        // Stop / Start says what it does, and what happens when the app is closed
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("watching"))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Start").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Stopped: nothing is watched or bought until you press Start.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("watching").performClick()
        compose.waitUntil(5_000) { s.settings().watching }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Stop").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Runs in the background", substring = true).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(400); shot("t40_watch")
        compose.onNodeWithTag("watching").performClick()
        compose.waitUntil(5_000) { !s.settings().watching }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Start").fetchSemanticsNodes().isNotEmpty() }
        // the same bar - logo, the tab's name, the version - on Bought, Activity and Settings
        for ((i, name) in listOf(1 to "Bought", 2 to "Activity", 3 to "Settings")) {
            compose.onNodeWithTag("tab$i").performClick(); compose.waitForIdle()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("logo-$name").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("logo-$name").assertIsDisplayed()
            compose.onNodeWithTag("brand-$name").assertIsDisplayed()
            assertTrue("the version on $name", compose.onAllNodesWithText("Restalker Mobile v${com.restalker.mobile.core.APP_VERSION}", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            Thread.sleep(300); shot("t40_brand_$name")
        }
        compose.onNodeWithTag("tab0").performClick(); compose.waitForIdle()
    }

    /** 1.0.4 (owner, note 1: "it shows items in stock though they aren't watched ... there should be an indicator of how old the data
     *  is. "6 hours ago" or something might be fine"): "In stock now" holds only what was seen for sale in the last 6 hours; every card
     *  of a product that is not watched says how old its data is. */
    @Test fun t41_in_stock_now_is_fresh_and_cards_say_how_old() {
        val s = RkApp.store()
        s.products().forEach { s.remove(it.id) }
        val h = 3_600_000L; val now = System.currentTimeMillis()
        // watched, seen over the cap half a minute ago: in stock now
        s.add(prod("B0TEST0801", "Fresh Collector Booster Box", "2026", "Fresh", price = 499.0).copy(overCap = true, status = "for sale over your cap"))
        // NOT watched, seen over the cap 7 hours ago (the one look when the app was opened): not "now" - its age is on the card
        s.add(prod("B0TEST0802", "Old Collector Booster", "2026", "Old", price = 72.10).copy(watch = false, overCap = true, cap = 45.0, lastCheck = now - 7 * h,
            status = "for sale over your cap"))
        // NOT watched, the linked PC saw it 20 minutes ago: in stock now
        s.add(prod("B0TEST0803", "Pc Collector Booster Box", "2026", "Pc").copy(watch = false, dSt = "over", dPr = 399.99, dAt = now - 20 * 60_000L))
        // NOT watched, never looked at
        s.add(prod("B0TEST0804", "New Collector Booster Box", "2026", "New").copy(watch = false))
        compose.waitForIdle()
        toList(hasTestTag("grp-hot"))
        toList(hasTestTag("top-B0TEST0801")); toList(hasTestTag("top-B0TEST0803"))
        Thread.sleep(300); shot("t41_in_stock_now")
        toList(hasTestTag("product-B0TEST0802"))
        assertTrue("seen 7 hours ago is not in stock NOW", compose.onAllNodesWithTag("top-B0TEST0802").fetchSemanticsNodes().isEmpty())
        assertTrue("said as the past", compose.onAllNodesWithText("Was over cap · \$72.10", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("its age", compose.onAllNodesWithText("Off (not watched) · checked 7 h ago", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        Thread.sleep(300); shot("t41_old_card")
        toList(hasTestTag("product-B0TEST0803"))
        assertTrue("the PC's look and its age", compose.onAllNodesWithText("Off (not watched) · desktop saw it 2", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        toList(hasTestTag("product-B0TEST0804"))
        assertTrue("never looked at", compose.onAllNodesWithText("Off (not watched) · not looked at yet", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // the In stock filter goes by the same rule
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("filter1"))
        compose.onNodeWithTag("filter1").performClick(); compose.waitForIdle()
        toList(hasTestTag("product-B0TEST0803"))
        assertTrue(compose.onAllNodesWithTag("product-B0TEST0802").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("filter0"))
        compose.onNodeWithTag("filter0").performClick(); compose.waitForIdle()
        s.products().forEach { s.remove(it.id) }
    }

    /** 1.0.4 (owner, note 3: "Is it possible to allow the user to select times of the day to start and stop the watcher
     *  automatically?"): Settings, Amazon, buying and this phone - Watch hours: All day / Set hours, each time half an hour earlier / later. */
    @Test fun t42_watch_hours() {
        val s = RkApp.store()
        s.setSettings { it.copy(hoursOn = false, hoursFrom = 22 * 60, hoursTo = 7 * 60) }
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        sec("amazon")
        compose.onNodeWithTag("hours-set").performScrollTo().performClick()
        compose.waitUntil(5_000) { s.settings().hoursOn }
        compose.onNodeWithTag("hours-from-plus").performScrollTo().performClick()
        compose.waitUntil(5_000) { s.settings().hoursFrom == 22 * 60 + 30 }
        compose.onNodeWithTag("hours-to-minus").performScrollTo().performClick()
        compose.waitUntil(5_000) { s.settings().hoursTo == 6 * 60 + 30 }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("10:30 PM - 6:30 AM", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("hours").performScrollTo()
        Thread.sleep(300); shot("t42_watch_hours")
        // the battery line says what it is, in plain words
        assertTrue(compose.onAllNodesWithText("keep running", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("hours-all").performScrollTo().performClick()
        compose.waitUntil(5_000) { !s.settings().hoursOn }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("hours-from-plus").fetchSemanticsNodes().isEmpty() }
        s.setSettings { it.copy(hoursOn = false, hoursFrom = 22 * 60, hoursTo = 7 * 60) }
    }

    /** 1.0.4 (owner, notes 2, 5, 6 and 10): not linked - how to get the PC program, with a link to send yourself; linked - a PC and a
     *  tablet the relay heard show as running although nothing came over the link channel, and a used-up message limit is said in words;
     *  the watching notice has its own row with the way to switch it off for good; the report words name no one. */
    @Test fun t43_devices_heard_pc_steps_notice_row_and_plain_report_words() {
        val s = RkApp.store()
        if (DeskLink.get().linked()) DeskLink.get().unlink()
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        sec("devices")
        compose.onNodeWithTag("getpc-send").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("getpc-url", useUnmergedTree = true).assertExists()
        Thread.sleep(300); shot("t43_get_the_pc")
        val code = com.restalker.mobile.core.LinkCrypto.make(); val sec0 = com.restalker.mobile.core.LinkCrypto.parse(code)!!.second
        assertEquals(null, DeskLink.get().connect(code))
        val t = System.currentTimeMillis() / 1000.0
        fun w(d: String, r: String, nm: String, vn: String) = com.restalker.mobile.core.LinkCrypto.seal(sec0,
            com.restalker.mobile.core.MiniJson.write(mapOf("d" to d, "r" to r, "nm" to nm, "vn" to vn)))
        assertEquals(2, DeskLink.get().takePresence(listOf(w("d-pc1", "desktop", "PC", "v145") to t - 180, w("p-tab1", "phone", "My tablet", "1.0.4") to t - 50 * 60)))
        RkApp.changed(); compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("dev-pc").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dev-pc").performScrollTo()
        assertTrue("the PC runs: heard by the relay 3 minutes ago", compose.onAllNodesWithText("Version v145 · running · heard 3 min ago", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("dev-p-tab1").performScrollTo()
        assertTrue("the tablet: its name, version and age", compose.onAllNodesWithText("My tablet", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithText("Version 1.0.4 · heard 50 min ago", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // the day's free messages used up: said in words, with the time they start again
        val now = System.currentTimeMillis()
        Quota.set(com.restalker.mobile.core.NtfyQuota.Q(250, 0, now + 3 * 3_600_000L, now)); RkApp.changed(); compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("link-health").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("link-health").performScrollTo()
        assertTrue(compose.onAllNodesWithText("used up (250 of 250)", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        Thread.sleep(300); shot("t43_devices")
        Quota.set(null); RkApp.changed(); compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("link-health").fetchSemanticsNodes().isEmpty() }
        DeskLink.get().unlink()
        // the watching notice: its own row, with the way to switch it off for good
        sec("notes")
        compose.onNodeWithTag("notice-off").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("notice-state", useUnmergedTree = true).assertExists()
        Thread.sleep(300); shot("t43_notice_row")
        // reports: plain words, no names
        sec("problems")
        compose.onNodeWithTag("autoreport").performScrollTo()
        assertTrue(compose.onAllNodesWithText("Send problem reports automatically", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("report").performScrollTo()
        assertTrue(compose.onAllNodesWithText("Send a report now", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("no name of a helper in the report words", listOf("Send problems to", "Send a report to", "Send to ").all { compose.onAllNodesWithText(it, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() })
        Thread.sleep(300); shot("t43_help")
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
