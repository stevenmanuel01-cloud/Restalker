package com.restalker.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/** 2.1: while the PC (or another phone) buys, this phone may sleep (no wake lock - battery); this alarm wakes it every 15 minutes for a
 *  moment: the link is read, the engine looks if a look is due, and if the PC has stopped the phone becomes the buyer at once. */
class StandbyAlarm : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val svc = WatchService.instance ?: return
        val wl = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "restalker:standby")
        wl.acquire(90_000)                                // (released by itself after 90 s at the latest)
        DeskLink.cycleAsync { try { svc.standbyCheck() } catch (e: Exception) { } }
        try { svc.engine.send(com.restalker.mobile.core.Engine.Cmd.Wake) } catch (e: Exception) { }
        schedule(ctx)
    }

    companion object {
        const val EVERY_MS = 15 * 60_000L
        private fun pi(ctx: Context) = PendingIntent.getBroadcast(ctx, 4242, Intent(ctx, StandbyAlarm::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + EVERY_MS, pi(ctx)) } catch (e: Exception) { }
        }

        fun cancel(ctx: Context) {
            try { val p = pi(ctx); (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(p); p.cancel() } catch (e: Exception) { }
        }

        /** (tests) is the alarm set? */
        fun isSet(ctx: Context): Boolean = PendingIntent.getBroadcast(ctx, 4242, Intent(ctx, StandbyAlarm::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null
    }
}
