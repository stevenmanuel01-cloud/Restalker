package com.restalker.mobile

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.restalker.mobile.core.Engine
import com.restalker.mobile.web.AmazonWeb

/** Watching runs here, with the phone's "always shown" notification (Android lets such an app keep working all day). It owns the app's
 *  Amazon browser and the engine's thread. */
class WatchService : Service() {
    lateinit var web: AmazonWeb; private set
    lateinit var engine: Engine; private set
    lateinit var notifier: Notifier; private set
    private var thread: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private val tick = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this)
        // (2.12: a notice you swiped away stays away - 2.0-2.11 showed it again whenever watching started)
        val n = Notifier.ongoing(this)
        ServiceCompat.startForeground(this, Notifier.ONGOING_ID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        instance = this
        web = AmazonWeb(this); web.create()
        val store = RkApp.store()
        startRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()); startAt = System.currentTimeMillis()
        // 2.2: the PC and the other phones apart (Who buys: pc / phone / both decides what the PC's buying means for this phone)
        engine = Engine(store, web, notifier, network = { netKind(this) }, desktopBuys = { DeskLink.get().desktopBuys() },
            otherPhoneBuys = { DeskLink.get().otherPhoneBuys() },
            buyerMode = { if (DeskLink.get().linked()) RkApp.store().settings().buyMode else "solo" },     // (not linked: this phone buys)
            pcVerdict = { a -> GoListener.verdict(a) }, failover = { RkApp.store().settings().failover }, pcHanded = { DeskLink.get().pcHanded() },
            onSighting = { a, pr -> CrowdNet.share(a, pr) },                    // 2.6: the Restalker crowd hears this phone's sightings
            master = { DeskLink.master() }, boughtOf = { p -> DeskLink.boughtOf(p) },
            rxBytes = { android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()) },      // 2.14: Data by activity (diagnostics)
            // 2.15: the race to checkout between your devices (the shared lock on the relay; only when every linked device knows it)
            raceOn = { Relay.enabled && DeskLink.get().raceReady() }, raceLock = { a -> Relay.lock(a) }, raceRelease = { a, r -> Relay.release(a, r) })        // 2.7: at most 3 purchases of a product
        if (!web.available) store.log("! This phone's browser part (Android System WebView) is too old - update it in the Play Store.")
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "restalker:watch").also { it.acquire() }
        thread = Thread({ engine.run() }, "restalker-engine").also { it.isDaemon = true; it.start() }
        tick.postDelayed(object : Runnable {        // 1.4: the desktop link - a round every minute (when linked)
            override fun run() {
                if (DeskLink.get().linked()) DeskLink.cycleAsync()
                tick.postDelayed(this, 60_000)
            }
        }, 5_000)
        tick.postDelayed(object : Runnable {        // 1.7: the self-check (it sends a report by itself when something is wrong)
            override fun run() {
                HealthRunner.tick(this@WatchService)
                standbyCheck()
                GoListener.check()                          // 2.2: the fast channel with the PC (Who buys: phone / both)
                Updater.tick(this@WatchService)            // 1.7: the app updates itself (checks every 3 hours)
                CrowdNet.tick()                             // 2.6: the Restalker crowd (read every 2 minutes while watching)
                Relay.heartbeat()                           // 2.15: "active now" (every 15 minutes)
                // 2.12: price drop alerts - watched products for sale over the cap that got clearly cheaper (this phone's look or the PC's)
                try { com.restalker.mobile.core.PriceDrop.scan(RkApp.store(), System.currentTimeMillis()) { p, was, pr -> notifier.priceDrop(p, was, pr) } } catch (e: Exception) { }
                tick.postDelayed(this, 60_000)
            }
        }, 30_000)
        tick.postDelayed(object : Runnable {        // watching off and nothing left to do (a Check now / practice was done): close
            override fun run() {
                if (!RkApp.store().settings().watching && engine.idle() && !BrowserShown.on) { stopSelf(); return }
                tick.postDelayed(this, 20_000)
            }
        }, 20_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_CMD -> {
                val id = intent.getStringExtra("id") ?: ""
                when (intent.getStringExtra("cmd")) {
                    "check" -> engine.send(Engine.Cmd.CheckNow(id))
                    // 2.3 (owner: "a button to [refresh all products at once] would be good"): one after another, watched products only
                    "checkall" -> RkApp.store().products().filter { it.watch }.forEach { engine.send(Engine.Cmd.CheckNow(it.id)) }
                    // 2.15: the start-up look at every product not looked at in the last 6 hours (watched ones first)
                    "baseline" -> RkApp.store().products().filter { System.currentTimeMillis() - it.lastCheck >= Engine.BASELINE_FRESH }
                        .sortedBy { if (it.watch) 0 else 1 }.forEach { engine.send(Engine.Cmd.Baseline(it.id)) }
                    "buynow" -> engine.send(Engine.Cmd.BuyNow(id))
                    "practice" -> engine.send(Engine.Cmd.Practice(id))
                    "practiceany" -> engine.send(Engine.Cmd.PracticeAny(id))
                    else -> engine.send(Engine.Cmd.Wake)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        try { StandbyAlarm.cancel(this) } catch (e: Exception) { }
        tick.removeCallbacksAndMessages(null)
        GoListener.stop()
        engine.send(Engine.Cmd.Quit)
        thread?.interrupt()
        web.destroy()
        try { wake?.release() } catch (e: Exception) {}
        super.onDestroy()
    }

    /** 2.0 (owner: "The app does seem to use the battery fairly quickly"): while the PC (or another phone) buys, this phone looks only
     *  every 30 minutes - then the phone may sleep between looks (no wake lock) and the notice has no icon. When this phone is the buyer:
     *  the wake lock (looks every minute or so even with the screen off). */
    fun standbyCheck() {
        val sb = try { standsBy() } catch (e: Exception) { false }
        if (sb != Notifier.standby) {
            Notifier.standby = sb
            notifier.refreshNotice()
        }
        try {
            val w = wake ?: return
            if (sb && w.isHeld) w.release() else if (!sb && !w.isHeld) w.acquire()
        } catch (e: Exception) { }
        // 2.1: asleep in standby, the phone is still woken every 15 minutes (an alarm): the link is read and, when the PC has stopped,
        // this phone takes over within 15 minutes - without keeping the phone awake all the time
        if (sb) StandbyAlarm.schedule(this) else StandbyAlarm.cancel(this)
    }

    /** 2.2: this phone only stands by - another phone / tablet is first in line, or the PC buys and Who buys is "pc". */
    private fun standsBy(): Boolean = DeskLink.standsBy()

    private var startRx = 0L; private var startAt = 0L
    val since: Long get() = startAt
    /** 2.14: what this app has received since watching began (-1 = not known). */
    fun rxSinceStart(): Long { val rx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()); return if (rx < 0 || startRx < 0) -1 else rx - startRx }
    fun hours(): Double = (System.currentTimeMillis() - startAt) / 3_600_000.0
    fun mbPerHour(): Double? {
        val rx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
        if (rx < 0 || startRx < 0 || hours() < 0.25) return null
        return (rx - startRx) / 1_048_576.0 / hours()
    }
    /** Data this app has downloaded since watching started, and per hour (shown on the screen). */
    fun dataText(): String {
        val rx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
        if (rx < 0 || startRx < 0) return ""
        val mb = (rx - startRx) / 1_048_576.0
        val h = (System.currentTimeMillis() - startAt) / 3_600_000.0
        return String.format(java.util.Locale.US, "Data used while watching: %.0f MB", mb) + if (h > 0.25) String.format(java.util.Locale.US, " (about %.0f MB an hour)", mb / h) else ""
    }

    companion object {
        @Volatile var instance: WatchService? = null

        /** "wifi", "mobile", or null (no internet). */
        fun netKind(ctx: Context): String? {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return null) ?: return null
            if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
            return if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) "wifi" else "mobile"
        }
        const val ACT_CMD = "com.restalker.mobile.CMD"

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, WatchService::class.java))
        /** A command for the engine (starts the service first if needed). */
        fun cmd(ctx: Context, cmd: String, id: String = "") =
            ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java).setAction(ACT_CMD).putExtra("cmd", cmd).putExtra("id", id))
    }
}

/** Is the app's browser on screen (BrowserActivity)? */
object BrowserShown { @Volatile var on = false }
