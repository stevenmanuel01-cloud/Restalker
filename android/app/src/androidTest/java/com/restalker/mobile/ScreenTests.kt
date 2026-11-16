package com.restalker.mobile

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.onAllNodesWithTag
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
        compose.onNodeWithText("Restalker Mobile v${com.restalker.mobile.core.APP_VERSION}").assertIsDisplayed()
        compose.onNodeWithTag("add").performClick()
        compose.onNodeWithTag("f-link").performTextInput("https://www.amazon.com/Magic-Hobbit/dp/B0TEST0001/ref=sr_1_1")
        compose.onNodeWithTag("f-cap").performTextInput("120")
        compose.onNodeWithTag("f-save").performClick()
        compose.waitUntil(5_000) { RkApp.store().products().any { it.asin == "B0TEST0001" } }
        compose.waitForIdle()
        shot("t5_screen")
        assertEquals(120.0, p("B0TEST0001").cap, 0.001)
        compose.onNodeWithTag("product-B0TEST0001").assertIsDisplayed()
    }
    @Test fun t6_firstRun_setupCard_andRealPurchasesNeedYes() {
        RkApp.store().products().forEach { RkApp.store().remove(it.id) }
        RkApp.store().setSettings { com.restalker.mobile.core.Settings() }
        compose.waitForIdle()
        compose.onNodeWithTag("setup").assertIsDisplayed()
        shot("t6_first_run")
        compose.onNodeWithTag("practice").performClick()             // switching Practice off asks first
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

    @Test fun t8_turnPhoneSideways_noCrash() {
        val dev = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        dev.setOrientationLeft(); Thread.sleep(2_000); compose.waitForIdle()
        shot("t8_sideways")
        compose.onNodeWithTag("title").assertIsDisplayed()
        dev.setOrientationNatural(); Thread.sleep(1_500)
        compose.onNodeWithTag("title").assertIsDisplayed()
    }
}
