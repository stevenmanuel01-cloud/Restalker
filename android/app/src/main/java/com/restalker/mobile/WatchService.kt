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
        val n = Notifier.ongoing(this, "Starting...")
        ServiceCompat.startForeground(this, Notifier.ONGOING_ID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        instance = this
        web = AmazonWeb(this); web.create()
        val store = RkApp.store()
        startRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()); startAt = System.currentTimeMillis()
        engine = Engine(store, web, notifier, network = { netKind(this) }, desktopBuys = { DeskLink.get().desktopBuys() })
        if (!web.available) store.log("! This phone's browser part (Android System WebView) is too old - update it in the Play Store.")
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "restalker:watch").also { it.acquire() }
        thread = Thread({ engine.run() }, "restalker-engine").also { it.isDaemon = true; it.start() }
        tick.postDelayed(object : Runnable {        // 1.4: the desktop link - a round every minute (when linked)
            override fun run() {
                if (DeskLink.get().linked()) DeskLink.cycleAsync()
                tick.postDelayed(this, 60_000)
            }
        }, 5_000)
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
                    "buynow" -> engine.send(Engine.Cmd.BuyNow(id))
                    "practice" -> engine.send(Engine.Cmd.Practice(id))
                    else -> engine.send(Engine.Cmd.Wake)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        tick.removeCallbacksAndMessages(null)
        engine.send(Engine.Cmd.Quit)
        thread?.interrupt()
        web.destroy()
        try { wake?.release() } catch (e: Exception) {}
        super.onDestroy()
    }

    private var startRx = 0L; private var startAt = 0L
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
