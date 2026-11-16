package com.restalker.mobile

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
    private fun shot(name: String) {
        try {
            val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
            val d = File(ctx.getExternalFilesDir(null), "shots").also { it.mkdirs() }
            File(d, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        } catch (e: Exception) { }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun t5_screen_titleAndAddProduct() {
        RkApp.store().products().forEach { RkApp.store().remove(it.id) }
        compose.onNodeWithTag("title").assertIsDisplayed()
        compose.onNodeWithText("Restalker Mobile v1.0").assertIsDisplayed()
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
}
