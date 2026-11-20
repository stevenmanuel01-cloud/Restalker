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
    var onStatus: ((String) -> Unit)? = null

    /** 2.12: the kinds of message you chose in Settings (NotifyPrefs). */
    private fun wants(kind: String) = com.restalker.mobile.core.NotifyPrefs.allowed(RkApp.store().settings(), kind)

    override fun restock(p: Product, price: Double?) {
        if (!wants("restock")) return                    // (2.12: restocks switched off in Settings - neither here nor on ntfy)
        val practice = RkApp.store().settings().practice
        // 2.15: a product watched without a cap - no Buy now (a cap is needed to buy)
        val capWords = if (p.cap > 0) "your cap \$${Engine.money(p.cap)}" else "watching only - no cap set"
        val b = base(CH_RESTOCK, "IN STOCK: ${p.label}",
            (price?.let { "\$${Engine.money(it)}" } ?: "Price not shown") + " ($capWords). " +
                (if (p.cap <= 0) "To buy it, open it in the app and set the most you'll pay." else if (practice) "Practice mode is on: Buy now runs a practice checkout (nothing is bought)." else "Tap Buy now to buy it."))
        if (p.cap > 0) b.addAction(0, "Buy now", ActionReceiver.pending(ctx, ActionReceiver.BUY_NOW, p.id))
        b.addAction(0, "Open", openBrowser(p.url, p.id.hashCode()))
            .addAction(0, "Snooze 3 h", ActionReceiver.pending(ctx, ActionReceiver.SNOOZE, p.id))
        post(idFor("r", p.id), b.build())
        NtfyOut.send("restock", "IN STOCK: ${p.label}", (price?.let { "\$${Engine.money(it)}" } ?: "Price not shown") + (if (p.cap > 0) " (cap \$${Engine.money(p.cap)})" else " (no cap)") + " - seen by ${DeskLink.name()}", p.url)
    }

    override fun bought(p: Product, price: Double?, order: String?, verified: Boolean) {
        val title = if (verified) "BOUGHT: ${p.label}" else "BOUGHT? Check Amazon: ${p.label}"
        val text = (price?.let { "\$${Engine.money(it)}" } ?: "") + (order?.let { " - order $it" } ?: "") +
            if (verified) " - found in your Amazon orders." else " - Amazon showed the order but it is not in your order list yet. Check Amazon."
        if (wants("bought")) post(idFor("b", p.id), base(CH_BUY, title, text.trim()).addAction(0, "Your orders", openBrowser(Engine.ORDERS_URL, 7)).build())
        NtfyOut.send("bought", title, text.trim() + " (${DeskLink.name()})", Engine.ORDERS_URL)
    }

    override fun needYou(title: String, text: String, p: Product?) {
        val b = base(CH_NEED, title, text)
        if (title.contains("check it's you") || title.contains("Sign in")) b.addAction(0, if (title.contains("Sign in")) "Sign in" else "Show browser",
            if (title.contains("Sign in")) openBrowser(BrowserActivity.SIGNIN_URL, 9) else openBrowser(null, 8))
        if (wants("need")) post(idFor("n", title + (p?.id ?: "")), b.build())
        NtfyOut.send("need", title, "$text (${DeskLink.name()})")
        Reporter.auto(ctx, "$title - $text")
    }

    override fun problem(what: String) { Reporter.auto(ctx, what) }

    override fun practice(p: Product, text: String) { if (wants("practice")) post(idFor("p", p.id), base(CH_BUY, "PRACTICE: ${p.label}", text).build()) }

    /** 2.12: a price drop - clearly cheaper than before (PriceDrop). 1.0.4: also at or under your cap while the product is not set to
     *  Buy (owner: "watched but not armed and the user might want to know there is a price drop so they can manually go in and
     *  purchase") - then with a Buy now button. */
    fun priceDrop(p: Product, was: Double, now: Double) {
        val under = p.stock().first == "in"
        val title = "PRICE DROP: ${p.label}"
        val text = "\$${Engine.money(now)} (was \$${Engine.money(was)}) - " + when {
            !under -> "still over your cap of \$${Engine.money(p.cap)}."
            p.cap > 0 -> "under your cap of \$${Engine.money(p.cap)}. It is not set to Buy: tap Buy now to buy it."
            else -> "watching only - no cap set."
        }
        RkApp.store().log("${p.label}: price drop to \$${Engine.money(now)} (was \$${Engine.money(was)}).")
        if (wants("drop")) {
            val b = base(CH_DROP, title, text)
            if (under && p.cap > 0 && !p.purchased && !p.attempted) b.addAction(0, "Buy now", ActionReceiver.pending(ctx, ActionReceiver.BUY_NOW, p.id))
            post(idFor("d", p.id), b.addAction(0, "Open", openBrowser(p.url, p.id.hashCode() + 3)).build())
        }
        NtfyOut.send("drop", title, "$text (${DeskLink.name()})", p.url)
    }

    // 2.12 (owner: "Notifications should only pop up when the user needs to see something. We do not need a constant notification that
    // the app is running and watching"): the engine's running status ("Looking at ...") is shown on the app's screen only - 2.0-2.11 also
    // rewrote the watching notice with it every 30 s. The notice (Android requires one while an app watches all day) now never changes:
    // no icon, no sound, not on the lock screen, at the very bottom of the notifications - and once swiped away it stays away.
    override fun status(text: String) {
        lastStatus = text
        onStatus?.invoke(text)
    }

    /** The watching notice again (only when its look changes: the icon switch, standby). Never after you swiped it away. */
    fun refreshNotice() {
        if (hidden || WatchService.instance == null) return
        post(ONGOING_ID, ongoing(ctx))
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
        const val CH_WATCH = "watching"; const val CH_QUIET = "watching_quiet"; const val CH_RESTOCK = "restocks"; const val CH_BUY = "purchases"; const val CH_NEED = "needyou"; const val CH_DROP = "pricedrops"
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
            nm.createNotificationChannel(NotificationChannel(CH_DROP, "Price drops", NotificationManager.IMPORTANCE_DEFAULT))
        }

        /** 1.0.6 (owner, 2026-11-20: "The ntfy messages arent really needed anymore since the app can handle notifications"): a message
         *  of the linked PC (a purchase there, something that needs you there) - it comes sealed over the relay with this device's
         *  round and is shown as this app's own notification. Not shown when ntfy already shows it on this phone (the PC has ntfy
         *  alerts set up AND the ntfy app is installed here) - nothing arrives twice. The kinds you switched off in Settings stay off. */
        fun fromPc(ctx: Context, title: String, text: String, kind: String, url: String?) {
            val st = RkApp.store().settings()
            val k = com.restalker.mobile.core.NotifyPrefs.pcKind(kind, title)
            val twice = try { DeskLink.pcHasChannel() && ntfyInstalled(ctx) } catch (e: Exception) { false }
            RkApp.store().log("From your PC: $title" + (if (twice) " (ntfy shows it)" else ""), quiet = twice)
            if (twice || !com.restalker.mobile.core.NotifyPrefs.allowed(st, k)) return
            val ch = when (k) { "bought" -> CH_BUY; "restock" -> CH_RESTOCK; else -> CH_NEED }
            val b = NotificationCompat.Builder(ctx, ch).setSmallIcon(R.drawable.ic_note).setContentTitle("PC: $title").setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE).setContentIntent(openMain(ctx))
            if (url != null && url.startsWith("https://")) b.addAction(0, "Open", PendingIntent.getActivity(ctx, 1500 + (url.hashCode() and 0xff),
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            try { NotificationManagerCompat.from(ctx).notify(idFor("pc", title + text.take(40)), b.build()) } catch (e: SecurityException) { }
        }
        fun ntfyInstalled(ctx: Context): Boolean = try { ctx.packageManager.getPackageInfo("io.heckel.ntfy", 0); true } catch (e: Exception) { false }

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
        /** 2.12: kept in Settings (2.0-2.11: forgotten at every restart, so the notice came back). */
        var hidden: Boolean
            get() = try { RkApp.store().settings().noticeHidden } catch (e: Exception) { false }
            set(v) { try { if (RkApp.store().settings().noticeHidden != v) RkApp.store().setSettings { it.copy(noticeHidden = v) } } catch (e: Exception) { } }
        @Volatile var standby = false
        /** 1.0.4: outside the watch hours you set (the notice says so). */
        @Volatile var outside = false

        /** 1.0.4 (owner, 2026-11-20: "I still have this constant message that pops up all the time in my notifications bar. I know the
         *  program is running so I don't think I need this to continue to pop up"): Android itself puts this notice up every time watching
         *  starts - after each update, each restart of the phone, each time Android restarted the app - and an app may not take its own
         *  watching notice down (a swipe hides it only until the next start; 2.12's "stays away" could not hold for that reason). The
         *  one way to make it stay away is the phone's own switch for this kind of notice: [noticeSettings] opens exactly that page (the
         *  notice's Hide button and Settings, Notifications lead there), and this says whether it is switched off. Watching goes on. */
        fun noticeOff(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < 26) return false
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (!nm.areNotificationsEnabled()) return false           // (everything off is not "this notice hidden" - restocks would be lost too)
            return nm.getNotificationChannel(noticeChannel())?.importance == NotificationManager.IMPORTANCE_NONE
        }
        fun noticeSettings(ctx: Context): Intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, noticeChannel()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The channel the watching notice uses now (Settings, "Turn it off completely" opens this one in the phone's settings). */
        fun noticeChannel(): String = if (RkApp.store().settings().runIcon && !standby) CH_WATCH else CH_QUIET

        fun ongoing(ctx: Context): Notification {
            val icon = RkApp.store().settings().runIcon && !standby
            return NotificationCompat.Builder(ctx, if (icon) CH_WATCH else CH_QUIET).setSmallIcon(R.drawable.ic_note)
                .setContentTitle(if (outside) "Restalker - waiting for your watch hours" else if (standby) "Restalker - standby (the PC buys)" else "Restalker is watching")
                .setPriority(if (icon) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET).setShowWhen(false)
                .setContentText("Hide = switch this notice off for good (watching goes on)").setOngoing(false).setOnlyAlertOnce(true).setSilent(true).setContentIntent(openMain(ctx))
                .setDeleteIntent(ActionReceiver.pending(ctx, ActionReceiver.HIDDEN, ""))
                // 1.0.4: "Hide" opens the phone's own switch for this notice - switched off there it never comes back (see noticeOff)
                .addAction(0, "Hide", PendingIntent.getActivity(ctx, 77, noticeSettings(ctx), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .addAction(0, "Stop watching", ActionReceiver.pending(ctx, ActionReceiver.STOP, ""))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
        }
    }
}
