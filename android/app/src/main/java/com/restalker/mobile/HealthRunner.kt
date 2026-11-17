package com.restalker.mobile

import android.content.Context
import com.restalker.mobile.core.Engine
import com.restalker.mobile.core.Health
import com.restalker.mobile.core.HealthIn
import com.restalker.mobile.core.MiniJson
import com.restalker.mobile.core.asLong
import java.io.File

/** 1.7: runs the self-check (core/Health.kt) every few minutes - from the watching service and when the app is opened - and sends a
 *  report by itself for what it finds (Reporter.auto). Also keeps the last crash (RkApp) until it was reported. */
object HealthRunner {
    private const val FILE = "health.json"
    private const val EVERY_MS = 5 * 60_000L
    private val firstSeen = HashMap<String, Long>()
    @Volatile var last: List<Pair<String, String>> = emptyList(); private set
    @Volatile private var lastRun = 0L

    fun crashFile(ctx: Context) = File(ctx.filesDir, "crash.txt")
    fun crashSentFile(ctx: Context) = File(ctx.filesDir, "crash-sent.txt")

    fun input(ctx: Context, now: Long = System.currentTimeMillis()): HealthIn {
        val s = RkApp.store()
        val svc = WatchService.instance
        val link = DeskLink.get()
        return HealthIn(now = now, products = s.products(), purchases = s.purchases(), settings = s.settings(),
            picFails = { PicCache.failures(it) }, picStatus = { PicCache.status[it] }, picInfo = { Engine.picInfo[it] },
            serviceRunning = svc != null, serviceSince = svc?.since ?: 0L, net = WatchService.netKind(ctx), deskBuys = DeskLink.standsBy(),
            linked = link.linked(), linkFailsInRow = DeskLink.failsInRow, linkLast = DeskLink.lastResult,
            mbPerHour = svc?.mbPerHour(), hoursWatching = svc?.hours() ?: 0.0, busy = svc?.engine?.busyWith?.startsWith("checking out") ?: false,
            updateFail = Updater.failed,
            crash = try { crashFile(ctx).takeIf { it.isFile }?.readText()?.take(4000) } catch (e: Exception) { null })
    }

    /** At most every 5 minutes (any thread). */
    fun tick(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRun < EVERY_MS) return
        lastRun = now
        Thread({ try { run(ctx, now) } catch (e: Exception) { } }, "self-check").start()
    }

    /** 1.8.1: pictures that are no product picture are dropped (and looked for again on the product's page). */
    fun repairPictures() {
        val store = RkApp.store()
        val (bad, ids) = com.restalker.mobile.core.Pics.repair(store.products(), { PicCache.status[it] }, { PicCache.failures(it) })
        if (bad.isEmpty()) return
        val old = (store.readFile(Engine.BAD_PICS) ?: "").split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        if (!old.containsAll(bad)) store.writeFile(Engine.BAD_PICS, (old + bad).toList().takeLast(500).joinToString("\n"))
        for (id in ids) store.update(id) { it.copy(image = "", picTriedAt = 0) }
        if (ids.isNotEmpty()) store.log("Pictures: ${ids.size} product(s) had a picture that is not theirs (${bad.joinToString(",")}) - looked for again.")
    }

    @Synchronized fun run(ctx: Context, now: Long = System.currentTimeMillis()): List<Pair<String, String>> {
        val store = RkApp.store()
        try { repairPictures() } catch (e: Exception) { }
        val probs = Health.check(input(ctx, now))
        last = probs
        val reported = HashMap<String, Long>()
        MiniJson.obj(store.readFile(FILE))?.forEach { (k, v) -> reported[k] = v.asLong() ?: 0L }
        val due = Health.due(probs, firstSeen, reported, now)
        if (due.isEmpty()) return due
        if (!Reporter.auto(ctx, "self-check: " + due.joinToString(" | ") { it.second })) return emptyList()
        due.forEach { reported[it.first] = now }
        store.writeFile(FILE, MiniJson.write(reported.filterValues { now - it < 7 * Health.AGAIN_MS }))
        if (due.any { it.first == "crash" }) try { crashFile(ctx).renameTo(crashSentFile(ctx).also { it.delete() }) } catch (e: Exception) { }
        return due
    }
}
