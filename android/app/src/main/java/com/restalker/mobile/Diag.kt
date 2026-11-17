package com.restalker.mobile

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.webkit.WebViewCompat
import com.restalker.mobile.core.APP_VERSION
import com.restalker.mobile.core.ENGINE_FROM
import com.restalker.mobile.core.Engine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The diagnostics you can send (Send diagnostics): the app's state and log. No password, card or address is ever in it - the app
 *  never has them. Order numbers and product codes are included (they are needed to find a problem). */
object Diag {
    fun text(ctx: Context): String {
        val s = RkApp.store()
        val st = s.settings()
        val sb = StringBuilder()
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sb.append("Restalker Mobile v$APP_VERSION (Amazon checkout from desktop $ENGINE_FROM) diagnostics, ${t.format(Date())}\n")
        sb.append("Phone: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n")
        val wv = try { WebViewCompat.getCurrentWebViewPackage(ctx)?.versionName } catch (e: Exception) { null }
        sb.append("Browser part (WebView): ${wv ?: "?"}\n")
        sb.append("Notifications allowed: ${NotificationManagerCompat.from(ctx).areNotificationsEnabled()}\n")
        sb.append("Battery: watching all day allowed: ${(ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)}\n")
        sb.append("Network: ${WatchService.netKind(ctx) ?: "none"}\n")
        val svc = WatchService.instance
        sb.append("Watching service: ${if (svc != null) "running (${svc.engine.busyWith}; ${svc.notifier.lastStatus})" else "not running"}\n")
        svc?.dataText()?.takeIf { it.isNotEmpty() }?.let { sb.append("$it\n") }
        sb.append("Settings: watching=${st.watching} buying=${st.buying} practice=${st.practice} every=${st.intervalSec}s wifiOnly=${st.wifiOnly}" +
            " snoozed=${st.snoozeUntil > System.currentTimeMillis()} resting=${st.cooldownUntil > System.currentTimeMillis()} (streak ${st.cooldownStreak})" +
            " ordersRead=${if (st.ordersReadAt > 0) t.format(Date(st.ordersReadAt)) else "never"}\n")
        sb.append("Desktop link: ${DeskLink.get().state().second}; desktop buys now: ${DeskLink.get().desktopBuys()}; last round: ${DeskLink.lastResult.ifBlank { "-" }}\n")
        sb.append("\nProducts (${s.products().size}):\n")
        for (p in s.products()) sb.append("- ${p.asin} cap \$${Engine.money(p.cap)} ${p.kind} watch=${p.watch} buy=${p.buy} qty=${p.qty} pic=${p.image.isNotBlank()} desk=${p.dSt} bought=${p.purchased} attempted=${p.attempted}" +
            " fails=${p.fails} | ${p.status}" + (if (p.lastCheck > 0) " (${t.format(Date(p.lastCheck))})" else "") + " | ${p.label.take(80)}\n")
        sb.append("\nPurchases (${s.purchases().size}):\n")
        for (x in s.purchases().take(20)) sb.append("- ${t.format(Date(x.at))} ${x.asin} ${x.price?.let { "\$" + Engine.money(it) } ?: ""} order ${x.order ?: "-"} verified=${x.verified} ${x.note}\n")
        sb.append("\nLog (newest last):\n")
        s.log().takeLast(300).forEach { sb.append(it).append('\n') }
        return sb.toString()
    }
}

/** Sends reports to Claude (see core/Report.kt) from the phone: by the Report problem button, or by itself after a problem. */
object Reporter {
    fun put(url: String, headers: Map<String, String>, body: ByteArray): Int {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true; c.connectTimeout = 20_000; c.readTimeout = 60_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        c.disconnect()
        return code
    }

    /** In the background; [done] gets true/false on the main thread. */
    fun send(ctx: android.content.Context, why: String, done: ((Boolean) -> Unit)? = null) {
        val store = RkApp.store()
        Thread {
            // 1.6: what you wrote comes first in the report (owner 2026-11-16: "App issues sent" - the report had the log but not the issues)
            val ok = com.restalker.mobile.core.Report.send(store, "Restalker Mobile report", "REPORT: $why\n\n" + Diag.text(ctx), why, put = ::put)
            if (done != null) android.os.Handler(android.os.Looper.getMainLooper()).post { done(ok) }
        }.start()
    }

    fun auto(ctx: android.content.Context, why: String) {
        val s = RkApp.store().settings()
        if (!com.restalker.mobile.core.Report.autoDue(s, System.currentTimeMillis())) return
        RkApp.store().setSettings { it.copy(reportAt = System.currentTimeMillis()) }     // (one at a time)
        send(ctx, "automatic: $why")
    }
}
