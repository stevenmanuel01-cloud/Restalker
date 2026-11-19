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
    }
}
