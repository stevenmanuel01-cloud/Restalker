package com.restalker.mobile

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
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
        compose.onNodeWithTag("f-save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("f-err").fetchSemanticsNodes().isNotEmpty() }
        shot("t7_bad_link")
        compose.onNodeWithTag("f-link").performTextClearance()
        compose.onNodeWithTag("f-link").performTextInput("B0TEST0009")
        compose.onNodeWithTag("f-cap").performTextClearance()
        compose.onNodeWithTag("f-cap").performTextInput("4500")
        compose.onNodeWithTag("f-save").performClick()                 // a big cap is asked about once
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("f-err").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(false, RkApp.store().products().any { it.asin == "B0TEST0009" })
        compose.onNodeWithTag("f-save").performClick()
        compose.waitUntil(10_000) { RkApp.store().products().any { it.asin == "B0TEST0009" } }
        assertEquals(4500.0, p("B0TEST0009").cap, 0.001)
    }

    @Test fun t9_reportButton_showsCodeToCopy() {
        // (the screen may still be drawing on a slow test phone: wait for it, and scroll a second time if the first try is too early)
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("tab3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab3").performClick(); compose.waitForIdle()
        shot("t9_settings")
        compose.onNodeWithTag("report").performScrollTo().performClick()
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
        compose.onNodeWithTag("grp-hot").assertIsDisplayed()
        compose.onNodeWithTag("top-B0TEST0103").assertIsDisplayed()
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
        compose.onNodeWithTag("filter2").performClick(); compose.waitForIdle()                     // Set to buy
        compose.onNodeWithTag("product-B0TEST0104").assertDoesNotExist()
        compose.onNodeWithTag("filter0").performClick(); compose.waitForIdle()
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
        shot("t12_linked")
        compose.onNodeWithTag("link-title").assertIsDisplayed()
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
