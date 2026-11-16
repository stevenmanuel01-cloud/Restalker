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
        sb.append("\nProducts (${s.products().size}):\n")
        for (p in s.products()) sb.append("- ${p.asin} cap \$${Engine.money(p.cap)} ${p.kind} watch=${p.watch} buy=${p.buy} bought=${p.purchased} attempted=${p.attempted}" +
            " fails=${p.fails} | ${p.status}" + (if (p.lastCheck > 0) " (${t.format(Date(p.lastCheck))})" else "") + " | ${p.label.take(80)}\n")
        sb.append("\nPurchases (${s.purchases().size}):\n")
        for (x in s.purchases().take(20)) sb.append("- ${t.format(Date(x.at))} ${x.asin} ${x.price?.let { "\$" + Engine.money(it) } ?: ""} order ${x.order ?: "-"} verified=${x.verified} ${x.note}\n")
        sb.append("\nLog (newest last):\n")
        s.log().takeLast(300).forEach { sb.append(it).append('\n') }
        return sb.toString()
    }
}
