package com.restalker.mobile

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.restalker.mobile.core.UpdatePlan
import java.io.File
import java.security.MessageDigest

/** 1.7: the app updates itself (core/UpdatePlan.kt says when and what). It downloads the new version, checks its fingerprint, and hands
 *  it to Android's installer. The first time Android asks you once (tap Install); once this app has installed itself, Android 12 and
 *  newer let it update without asking. Never during a purchase. Every step is written in the app's log; a failure goes into the self-check. */
object Updater {
    const val CH = Notifier.CH_NEED
    @Volatile var state: String = ""; private set
    @Volatile var available: UpdatePlan.Offer? = null; private set
    /** A failed update (for the self-check / report), null when the last try went well. */
    @Volatile var failed: String? = null
    @Volatile private var running = false
    @Volatile private var nextCheck = 0L

    fun allowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** 2.4 (owner: "The notification alert pops up several times and wont clear fully. It continues to pop in a loop if i accept or
     *  cancel the update"): when Android wants a tap for an update (the first self-update, or Play Protect's scan), it is asked at most
     *  once per ASK_GAP for that version - unless YOU tap Update now. 2.0-2.3 asked again every time the app came back to the front
     *  (which it does right after that question closes), so the question came back in a loop. */
    const val ASK_GAP_MS = 6 * 3_600_000L
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
    /** Android's question for [code] was shown at this time (0 = never). */
    fun askedAt(ctx: Context, code: Int): Long = prefs(ctx).let { if (it.getInt("askedCode", -1) == code) it.getLong("askedAt", 0L) else 0L }
    fun markAsked(ctx: Context, code: Int, at: Long = System.currentTimeMillis()) { prefs(ctx).edit().putInt("askedCode", code).putLong("askedAt", at).apply() }
    /** May Android's question be shown for [code] now? [explicit] = you tapped Update now / allowed installs just now. */
    fun mayAsk(ctx: Context, code: Int, explicit: Boolean, now: Long = System.currentTimeMillis()): Boolean =
        explicit || now - askedAt(ctx, code) >= ASK_GAP_MS

    /** At most every 3 hours (sooner while waiting for a purchase to finish). Any thread. [explicit]: you asked for it (Update now). */
    fun tick(ctx: Context, force: Boolean = false, explicit: Boolean = false) {
        if (running || (!force && System.currentTimeMillis() < nextCheck)) return
        running = true
        Thread({ try { run(ctx.applicationContext, explicit) } catch (e: Exception) { fail("update: ${e.message?.take(100)}") } finally { running = false } }, "updater").start()
    }

    private fun log(s: String) = RkApp.store().log(s)
    private fun fail(s: String) { failed = s; state = s; log("! $s"); nextCheck = System.currentTimeMillis() + UpdatePlan.CHECK_EVERY_MS }

    private fun run(ctx: Context, explicit: Boolean) {
        nextCheck = System.currentTimeMillis() + UpdatePlan.CHECK_EVERY_MS
        val note = try { get(UpdatePlan.NOTE_URL) } catch (e: Exception) { state = "could not check for updates (no internet?)"; nextCheck = System.currentTimeMillis() + 30 * 60_000L; return }
        val offer = UpdatePlan.parse(note, BuildConfig.VERSION_CODE)
        available = offer
        if (offer == null) { state = "up to date (v${BuildConfig.VERSION_NAME})"; failed = null; return }
        if (!RkApp.store().settings().autoUpdate) { state = "v${offer.version} is available (updating by itself is off)"; return }
        if (!allowed(ctx)) { state = "v${offer.version} is ready - allow Restalker to install its updates"; return }
        // (Android asked for a tap for this version a short while ago and it was not given: not asked again by itself for 6 hours)
        if (!mayAsk(ctx, offer.code, explicit)) { state = "v${offer.version} is ready - tap Update now to install it"; return }
        val f = File(ctx.cacheDir, "update-${offer.code}.apk")
        if (!(f.isFile && sha256(f) == offer.sha256)) {
            state = "downloading v${offer.version}"
            ctx.cacheDir.listFiles()?.filter { it.name.startsWith("update-") }?.forEach { it.delete() }
            val tmp = File(f.path + ".part")
            try { download(offer.apk, tmp) } catch (e: Exception) { tmp.delete(); fail("update download failed: ${e.message?.take(100)}"); return }
            if (!fingerprintOk(tmp, offer.sha256)) { tmp.delete(); return }
            tmp.renameTo(f)
        }
        install(ctx, f, offer.version, offer.code)
    }

    /** The file must be exactly the released one. */
    fun fingerprintOk(f: File, sha: String): Boolean {
        val got = try { sha256(f) } catch (e: Exception) { "" }
        if (got == sha.lowercase()) return true
        fail("update not installed: the downloaded file is not the released one (fingerprint ${got.take(12)})")
        return false
    }

    /** Hands the checked file to Android's installer (also used by the tests with a local file). */
    fun install(ctx: Context, f: File, version: String, code: Int = -1) {
        val svc = WatchService.instance
        if (!UpdatePlan.canInstallNow(RkApp.store().products(), svc?.engine?.busyWith)) {
            state = "v$version waits until the purchase is over"; nextCheck = System.currentTimeMillis() + UpdatePlan.RETRY_SOON_MS; return
        }
        val pi = ctx.packageManager.packageInstaller
        val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(p)
        pi.openSession(id).use { s ->
            s.openWrite("restalker.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val i = Intent(ctx, UpdateReceiver::class.java).putExtra("version", version).putExtra("code", code)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            state = "installing v$version"
            log("Installing the update to v$version (the app restarts by itself; watching continues).")
            s.commit(PendingIntent.getBroadcast(ctx, 77, i, flags).intentSender)
        }
    }

    fun get(url: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000; c.useCaches = false; c.setRequestProperty("Cache-Control", "no-cache")
        try { if (c.responseCode != 200) throw java.io.IOException("answer ${c.responseCode}"); return c.inputStream.bufferedReader().use { it.readText().take(10_000) } } finally { c.disconnect() }
    }

    private fun download(url: String, to: File) {
        var u = url
        repeat(5) {                                      // (GitHub sends the file from its download server: https only)
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 20_000; c.readTimeout = 60_000
            try {
                val code = c.responseCode
                if (code in 300..399) { u = c.getHeaderField("Location")?.takeIf { it.startsWith("https://") } ?: throw java.io.IOException("bad redirect"); return@repeat }
                if (code != 200) throw java.io.IOException("answer $code")
                c.inputStream.use { ins -> to.outputStream().use { out ->
                    val buf = ByteArray(65_536); var n = 0L
                    while (true) { val r = ins.read(buf); if (r < 0) break; out.write(buf, 0, r); n += r; if (n > 80_000_000) throw java.io.IOException("too big") }
                } }
                return
            } finally { c.disconnect() }
        }
        throw java.io.IOException("too many redirects")
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val b = ByteArray(65_536); while (true) { val n = ins.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Android's answer to an install. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val v = intent.getStringExtra("version") ?: "?"
        val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val store = RkApp.store()
        when (st) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Updater.markAsked(ctx, intent.getIntExtra("code", -1))          // 2.4: asked now - not again by itself for 6 hours
                store.log("Update to v$v: Android asks you once - tap Install (after this the app updates by itself).")
                val n = NotificationCompat.Builder(ctx, Updater.CH).setSmallIcon(R.drawable.ic_note)
                    .setContentTitle("Restalker update v$v is ready").setContentText("Tap, then Install. (Only this once - next time it updates by itself.)")
                    .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(PendingIntent.getActivity(ctx, 78, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
                try { NotificationManagerCompat.from(ctx).notify(UPDATE_NOTE_ID, n) } catch (e: SecurityException) { }
                if (com.restalker.mobile.ui.MainActivity.shown) try { ctx.startActivity(confirm) } catch (e: Exception) { }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Updater.failed = null; store.log("Updated to v$v.")
                try { NotificationManagerCompat.from(ctx).cancel(UPDATE_NOTE_ID) } catch (e: Exception) { }
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(120) ?: "status $st"
                try { NotificationManagerCompat.from(ctx).cancel(UPDATE_NOTE_ID) } catch (e: Exception) { }     // (2.4: the question is gone)
                if (st == PackageInstaller.STATUS_FAILURE_ABORTED) store.log("Update to v$v not installed now (Install was not tapped) - it is offered again in 6 hours, or tap Update now.")
                else { Updater.failed = "update to v$v failed: $msg"; store.log("! Update to v$v failed: $msg") }
            }
        }
    }
    companion object { const val UPDATE_NOTE_ID = 4711 }
}
