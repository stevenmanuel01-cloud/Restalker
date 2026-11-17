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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollTo
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

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
        compose.onNodeWithTag("f-save").performClick()
        compose.waitUntil(5_000) { RkApp.store().products().any { it.asin == "B0TEST0001" } }
        compose.waitForIdle()
        shot("t5_screen")
        assertEquals(120.0, p("B0TEST0001").cap, 0.001)
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("product-B0TEST0001"))
        compose.onNodeWithTag("product-B0TEST0001").assertIsDisplayed()
    }
    @Test fun t6_firstRun_setupCard_andRealPurchasesNeedYes() {
        RkApp.store().products().forEach { RkApp.store().remove(it.id) }
        RkApp.store().setSettings { com.restalker.mobile.core.Settings() }
        compose.waitForIdle()
        compose.onNodeWithTag("setup").assertIsDisplayed()
        shot("t6_first_run")
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("practice").performScrollTo().performClick()     // switching Real purchases on asks first
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
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("grp-hot")); compose.onNodeWithTag("grp-hot").assertIsDisplayed()
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("top-B0TEST0103")); compose.onNodeWithTag("top-B0TEST0103").assertIsDisplayed()
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("grp-2025"))
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("product-B0TEST0101"))
        compose.onNodeWithTag("grp-2025").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("product-B0TEST0101").assertDoesNotExist()                          // folded
        assertEquals(true, s.readFile("folded.json")!!.contains("y:2025"))                         // remembered
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("grp-Your products"))
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("set-Nauctis: The Sunken Realm"))
        compose.onNodeWithTag("set-Nauctis: The Sunken Realm").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("product-B0TEST0104").assertDoesNotExist()
        compose.onNodeWithTag("set-Nauctis: The Sunken Realm").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("product-B0TEST0104"))
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("grp-2025"))
        compose.onNodeWithTag("grp-2025").performClick(); compose.waitForIdle()                    // open again
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("product-B0TEST0102"))
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("filter1"))
        compose.onNodeWithTag("filter1").performClick(); compose.waitForIdle()                     // In stock
        shot("t10_in_stock")
        compose.onNodeWithTag("product-B0TEST0101").assertDoesNotExist()
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("product-B0TEST0103"))
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("filter2"))
        compose.onNodeWithTag("filter2").performClick(); compose.waitForIdle()                     // Set to buy
        compose.onNodeWithTag("product-B0TEST0104").assertDoesNotExist()
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("filter0"))
        compose.onNodeWithTag("filter0").performClick(); compose.waitForIdle()
        // 1.5: In stock = for sale at ANY price (also over the cap); Watched = watched, set to buy or not
        s.add(prod("B0TEST0105", "MTG Star Trek Collector Booster Box (over)", "2025", "Star Trek", price = 499.0).copy(overCap = true, cap = 460.0, status = "for sale over your cap"))
        s.update("idB0TEST0102") { it.copy(watch = false) }
        compose.waitForIdle()
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("filter1"))
        compose.onNodeWithTag("filter1").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("product-B0TEST0105"))         // over the cap: in stock
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("filter3"))
        compose.onNodeWithTag("filter3").performClick(); compose.waitForIdle()                     // Watched
        compose.onNodeWithTag("product-B0TEST0102").assertDoesNotExist()
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("product-B0TEST0104"))
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("filter0"))
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
            others = listOf(mapOf("s" to "Best Buy", "st" to "in", "pr" to 449.99, "at" to System.currentTimeMillis()), mapOf("s" to "Target", "st" to "out", "pr" to null, "at" to 0L))))
        compose.waitForIdle()
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("product-B0TEST0301"))
        compose.onAllNodesWithText("Over cap · \$529.99", substring = true).onFirst().assertIsDisplayed()   // the desktop's newer look (also on top: in stock)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("pic-B0TEST0301", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }   // the picture is shown
        compose.onNodeWithTag("product-B0TEST0301").performClick(); compose.waitForIdle(); Thread.sleep(800)
        shot("t13_product_desktop_view")
        compose.onAllNodesWithTag("pic-B0TEST0301", useUnmergedTree = true).onFirst().assertIsDisplayed()
        compose.onNodeWithTag("ref-B0TEST0301").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("others-B0TEST0301").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Best Buy").assertIsDisplayed()
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
            compose.onNodeWithTag("list").performScrollToNode(hasTestTag("product-B0TEST0401"))
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
            compose.onNodeWithTag("list").performScrollToNode(hasTestTag("product-B0TEST0402"))
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

    /** 1.4: a product's own screen - Buy, Watch, the cap, Remove (asks once), Back. */
    @Test fun t11_productScreen() {
        val s = RkApp.store()
        s.products().forEach { s.remove(it.id) }
        s.add(prod("B0TEST0201", "The Hobbit Collector Booster Box", "2026", "The Hobbit", price = 420.0))
        compose.waitForIdle()
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("product-B0TEST0201"))
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
        compose.onNodeWithTag("link-title").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("runicon").performScrollTo().performClick()
        compose.waitUntil(3_000) { !RkApp.store().settings().runIcon }
        compose.onNodeWithTag("runicon").performClick()
        compose.waitUntil(3_000) { RkApp.store().settings().runIcon }
        Thread.sleep(500); shot("t12_linked")
        compose.onNodeWithTag("unlink").performScrollTo().performClick()
        compose.onNodeWithTag("unlink-yes").performClick()
        compose.waitUntil(30_000) { !DeskLink.get().linked() }
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
