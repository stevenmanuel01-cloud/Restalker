package com.restalker.mobile

import android.content.Context
import com.restalker.mobile.core.UpdatePlan

/** 1.0.7, the Google Play edition: this app never updates itself - Google Play does (its rule: an app from the store may be updated by
 *  the store alone). This stand-in has the same shape as the GitHub edition's updater (src/github) so the rest of the app is the same
 *  code; it looks for nothing, downloads nothing and installs nothing, and the app holds no permission to. */
object Updater {
    val state: String = ""
    val available: UpdatePlan.Offer? = null
    /** (never set here: there is no update of its own that could fail) */
    @Volatile var failed: String? = null
    val running = false
    fun allowed(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean = true
    fun mode(@Suppress("UNUSED_PARAMETER") ctx: Context): String = "play"
    fun modeText(@Suppress("UNUSED_PARAMETER") m: String): String = "New versions come from Google Play - your phone installs them by itself."
    /** A version from Google Play is the version everyone has: never marked beta. */
    fun beta(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean = false
    fun tick(@Suppress("UNUSED_PARAMETER") ctx: Context, @Suppress("UNUSED_PARAMETER") force: Boolean = false, @Suppress("UNUSED_PARAMETER") explicit: Boolean = false) { }
}
