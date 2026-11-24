package com.restalker.mobile

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/** The buttons on the notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: ""
        val store = RkApp.store()
        when (intent.action) {
            BUY_NOW -> {
                NotificationManagerCompat.from(ctx).cancel(Notifier.idFor("r", id))
                store.log("Buy now pressed on a restock message.")
                WatchService.cmd(ctx, "buynow", id)
            }
            SNOOZE -> {
                store.setSettings { it.copy(snoozeUntil = System.currentTimeMillis() + 3 * 3600_000L) }
                store.log("Restock messages snoozed for 3 hours (purchases and problems are still sent).")
                NotificationManagerCompat.from(ctx).cancel(Notifier.idFor("r", id))
            }
            HIDDEN -> {
                if (!Notifier.hidden) store.log("The watching notice was swiped away - watching goes on, and the notice stays away.", quiet = true)
                Notifier.hidden = true
            }
            STOP -> {
                store.setSettings { it.copy(watching = false) }
                store.log("Watching switched off from the notification.")
                WatchService.stop(ctx)
            }
        }
    }

    companion object {
        const val BUY_NOW = "com.restalker.mobile.BUY_NOW"
        const val SNOOZE = "com.restalker.mobile.SNOOZE"
        const val STOP = "com.restalker.mobile.STOP"
        const val HIDDEN = "com.restalker.mobile.HIDDEN"
        fun pending(ctx: Context, action: String, id: String): PendingIntent =
            PendingIntent.getBroadcast(ctx, (action + id).hashCode(), Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** After the phone restarts (or the app is updated): watching starts again if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val s = RkApp.store().settings()
        if (s.watching) try { WatchService.start(ctx) } catch (e: Exception) { RkApp.store().log("! Could not start watching after the restart: ${e.message}") }
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) AfterUpdate.run(ctx)
    }
}

/** 1.1.3 (owner, 2026-11-24: "When the app is updated, it does not launch automatically. This should be a default setting and changeable
 *  in the app settings"): an update that was installed while the app's screen was open (the updater notes that moment) opens the screen
 *  again. From Android 10 an app may open its own screen from the background only when "Display over other apps" is allowed for it;
 *  without that a notification says the update is in - one tap opens the app. Watching starts again by itself either way. */
object AfterUpdate {
    const val PREFS = "rk_update_open"; const val NOTE_ID = 4712
    /** The updater, just before Android installs: was the app's screen open? */
    fun note(ctx: Context, screenOpen: Boolean) {
        try { ctx.getSharedPreferences(PREFS, 0).edit().putLong("at", if (screenOpen) System.currentTimeMillis() else 0L).commit() } catch (e: Exception) { }
    }
    fun run(ctx: Context) {
        try {
            val sp = ctx.getSharedPreferences(PREFS, 0); val at = sp.getLong("at", 0L); sp.edit().remove("at").apply()
            val overlays = android.os.Build.VERSION.SDK_INT >= 23 && android.provider.Settings.canDrawOverlays(ctx)
            when (com.restalker.mobile.core.UpdatePlan.afterUpdate(at, System.currentTimeMillis(), RkApp.store().settings().reopenAfterUpdate, android.os.Build.VERSION.SDK_INT, overlays)) {
                "open" -> ctx.startActivity(Intent(ctx, com.restalker.mobile.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "notify" -> {
                    Notifier.channels(ctx)
                    val n = androidx.core.app.NotificationCompat.Builder(ctx, Notifier.CH_NEED).setSmallIcon(R.drawable.ic_note)
                        .setContentTitle("Restalker was updated to v${BuildConfig.VERSION_NAME}").setContentText("Tap to open it again. Watching already continues.")
                        .setAutoCancel(true).setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH).setContentIntent(Notifier.openMain(ctx)).build()
                    try { androidx.core.app.NotificationManagerCompat.from(ctx).notify(NOTE_ID, n) } catch (e: SecurityException) { }
                }
            }
        } catch (e: Exception) { }
    }
}
