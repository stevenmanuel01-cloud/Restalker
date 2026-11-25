package com.restalker.mobile

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.restalker.mobile.core.Engine
import com.restalker.mobile.core.MiniJson
import com.restalker.mobile.core.TrackerMatch

/** 1.1.8: hears the notifications of the tracker apps the user chose (Android asks the user once: Settings, Notification access).
 *  Everything it does is in Trackers.heard. */
class TrackerListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            val s = sbn ?: return
            if (s.packageName == packageName) return
            val ex = s.notification?.extras ?: return
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") ?: ""
            val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(s.packageName, 0)).toString() } catch (e: Exception) { s.packageName }
            Trackers.heard(applicationContext, s.packageName, label, "$title\n$text\n$lines")
        } catch (e: Exception) { }
    }
}

/** The tracker apps on this phone: which ones the user chose, and what happens when one of them alerts. Nothing of an alert's words is
 *  kept or sent anywhere: they are looked at once, in memory, for a watched product, and dropped. */
object Trackers {
    private const val FILE = "tracker_apps.json"
    private val lock = Any()
    private var chosen: LinkedHashMap<String, String>? = null          // package -> the app's name
    private val seenApps = LinkedHashMap<String, String>()             // apps that have shown a notification since the start (to choose from)
    private val lastLook = HashMap<String, Long>()
    @Volatile var heardN = 0; private set
    @Volatile var matchedN = 0; private set
    @Volatile var last = ""; private set
    const val LOOK_GAP_MS = 20_000L

    fun allowed(ctx: Context): Boolean = try { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) } catch (e: Exception) { false }
    fun askRebind(ctx: Context) { try { NotificationListenerService.requestRebind(ComponentName(ctx, TrackerListener::class.java)) } catch (e: Exception) { } }

    private fun load(): LinkedHashMap<String, String> = synchronized(lock) {
        chosen ?: LinkedHashMap<String, String>().also { m ->
            try { MiniJson.obj(RkApp.store().readFile(FILE))?.forEach { (k, v) -> if (v is String) m[k] = v } } catch (e: Exception) { }
            chosen = m
        }
    }
    fun chosenApps(): Map<String, String> = synchronized(lock) { LinkedHashMap(load()) }
    /** The apps to choose from: the chosen ones, then every app that has shown a notification since Restalker started. */
    fun apps(): List<Triple<String, String, Boolean>> = synchronized(lock) {
        val c = load()
        (c.map { Triple(it.key, it.value, true) } + seenApps.filter { it.key !in c }.map { Triple(it.key, it.value, false) }).sortedBy { it.second.lowercase() }
    }
    fun choose(pkg: String, label: String, on: Boolean) {
        synchronized(lock) {
            val c = load()
            if (on) c[pkg] = label.take(60) else c.remove(pkg)
            try { RkApp.store().writeFile(FILE, MiniJson.write(c)) } catch (e: Exception) { }
        }
        try { RkApp.store().log("Trackers: ${label.take(60)} ${if (on) "is read for restock alerts from now on" else "is no longer read"}.") } catch (e: Exception) { }
        try { RkApp.changed() } catch (e: Exception) { }
    }

    /** One notification of another app. -> the watched products it was about (for the tests). */
    fun heard(ctx: Context, pkg: String, label: String, text: String, now: Long = System.currentTimeMillis()): List<String> {
        val isNew = synchronized(lock) { val n = pkg !in seenApps; if (seenApps.size < 80) seenApps[pkg] = label.take(60); n }
        if (isNew) try { RkApp.changed() } catch (e: Exception) { }
        if (pkg !in chosenApps()) return emptyList()                   // (not a tracker the user chose: its words are not looked at)
        heardN++
        val store = RkApp.store()
        val hits = TrackerMatch.match(text, store.products().filter { it.watch || it.buy })
        if (hits.isEmpty()) return emptyList()
        val name = TrackerMatch.cleanName(label)
        val out = ArrayList<String>()
        for (h in hits) {
            val p = h.product
            val due = synchronized(lock) { val l = lastLook[p.asin] ?: 0L; if (now - l >= LOOK_GAP_MS) { lastLook[p.asin] = now; true } else false }
            if (!due) continue
            matchedN++; out += p.asin
            last = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(now))} $name: ${p.label}"
            store.log("Tracker alert ($name): ${p.label} (told by ${h.how}) - looking now.")
            try { WatchService.instance?.engine?.send(Engine.Cmd.Go(p.asin)) ?: store.log("Watching is off - switch it on so a tracker's alert is looked at.", quiet = true) } catch (e: Exception) { }
            // the data: which tracker alerted for which product, when - only when the user shares with the crowd, and only for Amazon alerts
            if (h.amazon && name.length >= 2) try { if (store.settings().crowd) Relay.tracker(p.asin, name) } catch (e: Exception) { }
        }
        return out
    }

    fun status(ctx: Context): String = "Trackers on this phone (1.1.8): reading other apps' alerts " + (if (allowed(ctx)) "allowed" else "not allowed") +
        "; chosen: " + (chosenApps().values.joinToString(", ").ifBlank { "none" }) + "; alerts of chosen apps heard $heardN, about a watched product $matchedN" +
        (if (last.isNotEmpty()) "; last: $last" else "")
}
