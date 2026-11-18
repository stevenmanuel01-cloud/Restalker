package com.restalker.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.restalker.mobile.core.Alerts
import com.restalker.mobile.core.Engine
import com.restalker.mobile.core.Product
import com.restalker.mobile.ui.BrowserActivity
import com.restalker.mobile.ui.MainActivity

/** The engine's messages to you, as phone notifications with buttons (Buy now / Snooze 3 h / Open). */
class Notifier(private val ctx: Context) : Alerts {
    @Volatile var lastStatus = ""; private set
    private var lastStatusPost = 0L
    var onStatus: ((String) -> Unit)? = null

    override fun restock(p: Product, price: Double?) {
        val practice = RkApp.store().settings().practice
        val b = base(CH_RESTOCK, "IN STOCK: ${p.label}",
            (price?.let { "\$${Engine.money(it)}" } ?: "Price not shown") + " (your cap \$${Engine.money(p.cap)}). " +
                (if (practice) "Practice mode is on: Buy now runs a practice checkout (nothing is bought)." else "Tap Buy now to buy it."))
            .addAction(0, "Buy now", ActionReceiver.pending(ctx, ActionReceiver.BUY_NOW, p.id))
            .addAction(0, "Open", openBrowser(p.url, p.id.hashCode()))
            .addAction(0, "Snooze 3 h", ActionReceiver.pending(ctx, ActionReceiver.SNOOZE, p.id))
        post(idFor("r", p.id), b.build())
        NtfyOut.send("restock", "IN STOCK: ${p.label}", (price?.let { "\$${Engine.money(it)}" } ?: "Price not shown") + " (cap \$${Engine.money(p.cap)}) - seen by ${DeskLink.name()}", p.url)
    }

    override fun bought(p: Product, price: Double?, order: String?, verified: Boolean) {
        val title = if (verified) "BOUGHT: ${p.label}" else "BOUGHT? Check Amazon: ${p.label}"
        val text = (price?.let { "\$${Engine.money(it)}" } ?: "") + (order?.let { " - order $it" } ?: "") +
            if (verified) " - found in your Amazon orders." else " - Amazon showed the order but it is not in your order list yet. Check Amazon."
        post(idFor("b", p.id), base(CH_BUY, title, text.trim()).addAction(0, "Your orders", openBrowser(Engine.ORDERS_URL, 7)).build())
        NtfyOut.send("bought", title, text.trim() + " (${DeskLink.name()})", Engine.ORDERS_URL)
    }

    override fun needYou(title: String, text: String, p: Product?) {
        val b = base(CH_NEED, title, text)
        if (title.contains("check it's you") || title.contains("Sign in")) b.addAction(0, if (title.contains("Sign in")) "Sign in" else "Show browser",
            if (title.contains("Sign in")) openBrowser(BrowserActivity.SIGNIN_URL, 9) else openBrowser(null, 8))
        post(idFor("n", title + (p?.id ?: "")), b.build())
        NtfyOut.send("need", title, "$text (${DeskLink.name()})")
        Reporter.auto(ctx, "$title - $text")
    }

    override fun problem(what: String) { Reporter.auto(ctx, what) }

    override fun practice(p: Product, text: String) = post(idFor("p", p.id), base(CH_BUY, "PRACTICE: ${p.label}", text).build())

    override fun status(text: String) {
        lastStatus = text
        onStatus?.invoke(text)
        val now = System.currentTimeMillis()
        if (now - lastStatusPost < 30_000 || hidden) return      // (2.0: every 30 s at most; never after you swiped it away)
        lastStatusPost = now
        if (WatchService.instance != null) post(ONGOING_ID, ongoing(ctx, text))
    }

    private fun base(ch: String, title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(ctx, ch).setSmallIcon(R.drawable.ic_note).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openMain(ctx))

    private fun openBrowser(url: String?, req: Int): PendingIntent {
        val i = Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (url != null) i.putExtra(BrowserActivity.EXTRA_URL, url)
        return PendingIntent.getActivity(ctx, 1000 + req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun post(id: Int, n: Notification) {
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (e: SecurityException) { /* notifications not allowed - the app's screen shows everything */ }
    }

    companion object {
        const val CH_WATCH = "watching"; const val CH_QUIET = "watching_quiet"; const val CH_RESTOCK = "restocks"; const val CH_BUY = "purchases"; const val CH_NEED = "needyou"
        const val ONGOING_ID = 1
        fun idFor(kind: String, key: String) = (kind + key).hashCode() and 0x7fffffff or 0x100

        fun channels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_WATCH, "Watching (always shown while watching)", NotificationManager.IMPORTANCE_LOW))
            // 1.5 (owner: "We can keep a single icon at the top to indicate that its running, but it should be clearable by user option"):
            // the same notice without an icon in the status bar (Android requires a notice while an app watches all day; this one is tucked
            // away at the bottom of the notifications - and you may switch this channel off in the phone's settings: watching goes on)
            nm.createNotificationChannel(NotificationChannel(CH_QUIET, "Watching - no icon", NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel(CH_RESTOCK, "Restocks", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CH_BUY, "Purchases and practice runs", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CH_NEED, "Restalker needs you", NotificationManager.IMPORTANCE_HIGH))
        }

        /** "Test message" button: shows that messages reach you (sound and all). */
        fun test(ctx: Context) {
            val n = NotificationCompat.Builder(ctx, CH_BUY).setSmallIcon(R.drawable.ic_note).setContentTitle("Restalker test message")
                .setContentText("Messages work. Restocks and purchases will look and sound like this.").setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(openMain(ctx)).build()
            try { NotificationManagerCompat.from(ctx).notify(42, n) } catch (e: SecurityException) { }
        }

        fun openMain(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        /** 2.0 (owner: "The notification bar on my phone is still constant and cannot be silenced"): the notice can always be swiped away
         *  (Android 13+; watching goes on) and is not shown again until watching is switched on again - 1.5-1.9 put it back every few
         *  seconds. While the PC buys (this phone stands by) it has no icon either. */
        @Volatile var hidden = false
        @Volatile var standby = false

        fun ongoing(ctx: Context, text: String): Notification {
            val icon = RkApp.store().settings().runIcon && !standby
            return NotificationCompat.Builder(ctx, if (icon) CH_WATCH else CH_QUIET).setSmallIcon(R.drawable.ic_note)
                .setContentTitle(if (standby) "Restalker - standby (the PC buys)" else "Restalker is watching")
                .setPriority(if (icon) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
                .setContentText(text + " · swipe to hide").setOngoing(false).setOnlyAlertOnce(true).setSilent(true).setContentIntent(openMain(ctx))
                .setDeleteIntent(ActionReceiver.pending(ctx, ActionReceiver.HIDDEN, ""))
                .addAction(0, "Stop watching", ActionReceiver.pending(ctx, ActionReceiver.STOP, ""))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
        }
    }
}
