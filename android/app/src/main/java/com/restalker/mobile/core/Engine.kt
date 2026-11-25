package com.restalker.mobile.core

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** The app's browser, as the engine sees it. The Android side runs it on the main thread; the engine waits for the answers. */
interface Page {
    /** Opens the address and waits until the page has loaded and the Amazon part is in it. */
    fun goto(url: String, timeoutMs: Long = 35_000): Boolean
    /** 1.1.5: opens the address and comes back as soon as the NEW page exists and the Amazon part is in it - while the page is still
     *  arriving. The answers of [ask] then say how far the page is ("ready"). A browser that cannot do this waits for the whole page. */
    fun open(url: String, timeoutMs: Long = 35_000): Boolean = goto(url, timeoutMs)
    /** Asks the Amazon part (amz_part.js) a question; keeps trying while a page is changing. null = no answer in time. */
    fun ask(msg: Map<String, Any?>, timeoutMs: Long = 8_000): Map<String, Any?>?
    fun images(on: Boolean)
    /** 1.1.8: the look has its answer - whatever the page would still download is not needed (see Engine.openAndRead). */
    fun stop() {}
    /** The pages opened since the last call (their paths), then forgotten. Used to see that Amazon showed its sign-in page. */
    fun visited(): List<String> = emptyList()
    /** 2.0 (owner: "The app does seem to use the battery fairly quickly"): nothing to look at for a while - the page is closed and its
     *  scripts stop (an Amazon page keeps running its own scripts while it is open). The next goto wakes it. */
    fun rest() {}
}

/** Messages to you (the Android side shows them as notifications with buttons). */
interface Alerts {
    fun restock(p: Product, price: Double?)
    fun bought(p: Product, price: Double?, order: String?, verified: Boolean)
    fun needYou(title: String, text: String, p: Product? = null)
    fun practice(p: Product, text: String)
    fun status(text: String)
    /** Something went wrong (a checkout that did not finish, a message that needs you): the Android side may send a report. */
    fun problem(what: String) {}
    /** 1.1.3: null = a message of this kind can be shown on this device; else why not, in plain words (the Activity then says so
     *  instead of "you were sent a message"). */
    fun cannotShow(kind: String): String? = null
}

interface Clock { fun now(): Long; fun sleep(ms: Long) }
object RealClock : Clock { override fun now() = System.currentTimeMillis(); override fun sleep(ms: Long) = Thread.sleep(ms) }

/** The phone's Restalker: looks at each product in turn and buys it when it is in stock at or under its cap.
 *  The page work and every money check are the desktop's own Amazon checkout (content.js, unchanged); this is a port of the desktop's
 *  driver for it (multibuy.py _try_amazon / _amz_order_check), with the desktop's purchase rules: one purchase per product (the lock is
 *  written BEFORE "Place your order" is allowed), never a robot check answered, practice mode stops before Place your order, and a
 *  bought message only after the order was looked up in your Amazon orders. */
class Engine(private val store: Store, private val page: Page, private val alerts: Alerts, private val clock: Clock = RealClock,
             /** "Wi-Fi" / "mobile" / null (no internet) - the Android side tells; tests give their own. */
             private val network: () -> String? = { "wifi" },
             /** 1.4: linked with the desktop and the desktop is the buyer right now (this phone only stands by). */
             private val desktopBuys: () -> Boolean = { false },
             /** 2.2: another linked phone / tablet is first in line to buy (this one stands by whatever Who buys says). */
             private val otherPhoneBuys: () -> Boolean = { false },
             /** 2.2: who buys on Amazon while linked - "pc" | "phone" | "both" (Settings, Who buys). */
             private val buyerMode: () -> String = { "pc" },
             /** 2.2: the PC's latest checkout news for a product on the fast channel: (started | bought | failed | busy, when ms) or null. */
             private val pcVerdict: (String) -> Pair<String, Long>? = { null },
             /** 2.3 ("pc"): this device takes over when the PC stops. */
             private val failover: () -> Boolean = { true },
             /** 2.3: the PC does not buy on Amazon because another phone / tablet buys there instead. */
             private val pcHanded: () -> Boolean = { false },
             /** 2.6: this phone's own look saw a product come IN STOCK (under or over the cap) - the crowd hears it (asin, price). */
             private val onSighting: (String, Double?) -> Unit = { _, _ -> },
             /** 2.7: the owner's device (no purchase limit) and a product's purchases on every linked device. */
             private val master: () -> Boolean = { true },
             private val boughtOf: (Product) -> Int = { 0 },
             /** 2.14: the bytes this app has received so far (-1 = not known) - for "Data by activity" in the diagnostics. */
             rxBytes: () -> Long = { -1L },
             /** 2.15: every linked device takes part in the shared checkout lock (then they race; the first at Place your order wins). */
             private val raceOn: () -> Boolean = { false },
             /** 2.15: claim the product right before Place your order: true = this device has it, false = another of your devices has it,
              *  null = the lock could not be asked (then the 2.2 rules decide - never two orders). */
             private val raceLock: (String) -> Boolean? = { null },
             /** 2.15: how this device's claimed checkout ended ("bought" keeps the others out for a day; "failed" frees it). */
             private val raceRelease: (String, String) -> Unit = { _, _ -> },
             /** 1.0.4: this device's time zone (the watch hours are times of day on its own clock; the tests give their own). */
             private val zone: () -> java.util.TimeZone = { java.util.TimeZone.getDefault() },
             /** 1.0.9: the linked PC is watching right now (Link.pcWatchesNow). */
             private val pcWatches: () -> Boolean = { false },
             /** 1.1.0: this phone's battery -> (percent or null when unknown, charging). */
             private val battery: () -> Pair<Int?, Boolean> = { null to false }) {
    val meter = DataMeter(rxBytes)
    sealed class Cmd {
        data class CheckNow(val id: String) : Cmd()
        data class BuyNow(val id: String) : Cmd()          // the "Buy now" button on a restock message: set to buy, then look at once
        data class Practice(val id: String) : Cmd()        // a practice checkout of this product now (stops before Place your order)
        data class PracticeAny(val asin: String) : Cmd()   // 2.0: a practice checkout of ANY Amazon item that is in stock (nothing is bought)
        data class Crowd(val asin: String) : Cmd()          // 2.6: another Restalker saw it in stock on Amazon - look now
        data class Go(val asin: String) : Cmd()             // 2.2: the PC saw it in stock under the cap (Who buys: phone / both) - look now
        data class Baseline(val id: String) : Cmd()         // 2.15: the first look when the app starts (prices to set a cap by)
        object Wake : Cmd()
        object Quit : Cmd()
    }

    private val q = LinkedBlockingDeque<Cmd>()
    @Volatile var pausedFor: String? = null               // e.g. "you are using the browser" - nothing is opened meanwhile
    @Volatile var busyWith: String = "idle"; private set
    /** 1.1.0: the time between two looks at one product right now (the self-check allows for it). */
    @Volatile var paceMs: Long = 0; private set
    @Volatile private var quietLook = false              // 2.15: a start-up look at a product that is not watched (nothing said or shared)
    @Volatile private var quit = false
    @Volatile private var overrideDesk: String? = null

    fun send(c: Cmd) { q.offerLast(c) }
    /** Nothing to do and nothing running (the service may close when watching is off). */
    fun idle(): Boolean = q.isEmpty() && busyWith == "idle"

    private fun log(s: String) = store.log(s)
    /** 2.13: a detail for the problem reports only (not shown in Activity). */
    private fun detail(s: String) = store.log(s, quiet = true)
    // 1.1.3 (owner: "every time i get an update, there are several error message indicators on specific products. When i click the
    // product, there is no error visible"): right after the app starts again a page may fail to load once - that is not something that
    // needs you. How many looks in a row could not read each product's page (its red mark shows from the third).
    private val failRun = java.util.concurrent.ConcurrentHashMap<String, Int>()
    fun failedInARow(id: String): Int = failRun[id] ?: 0
    // 2.13 (owner: the crowd's "saw it - looking now", "BUYABLE at" and "the linked desktop buys it" lines "seem redundant and could
    // maybe be consolidated"): who tipped this phone off (the crowd / the PC) and whether the look found it newly in stock - so ONE line
    // says it all: "in stock at $89.99 (another Restalker spotted it first) - the linked desktop buys it (this phone stands by)."
    @Volatile private var tipFor: Pair<String, String>? = null
    @Volatile private var freshId: String? = null
    private fun s() = store.settings()
    private fun now() = clock.now()
    /** 1.0.4: inside the watch hours now (always, when none are set). */
    fun inHours(): Boolean = WatchHours.inside(s(), now(), zone())

    /** The engine's own thread runs this until Quit. */
    fun run() {
        detail("Restalker Mobile $APP_VERSION started (Amazon checkout from desktop $ENGINE_FROM).")
        // a checkout that was running when watching last stopped is not running any more (its lock fields stay as they were)
        store.products().filter { it.inProgress }.forEach { p -> store.update(p.id) { it.copy(inProgress = false) } }
        while (!quit) {
            try { step() } catch (e: InterruptedException) { break } catch (e: Exception) { log("! engine problem: ${e.message?.take(160)}"); waitFor(5_000) }
        }
        store.flushLog()
    }

    /** One step: a command, or the next product that is due, or a short wait. Public for the tests. */
    fun step() {
        val c = q.pollFirst()
        if (c != null) { handle(c); return }
        val st = s()
        if (!st.watching) { status("Not watching. Switch Watching on to start."); waitFor(30_000); return }
        // 1.0.4 (owner: "select times of the day to start and stop the watcher automatically"): outside your watch hours nothing is looked
        // at (the page is closed - battery and data); what you start yourself above (Check now, Buy now, a practice) still runs
        if (!inHours()) { page.rest(); status("Outside your watch hours - watching starts again at ${WatchHours.label(st.hoursFrom)}"); waitFor(30_000); return }
        pausedFor?.let { status("Paused - $it"); waitFor(2_000); return }
        if (now() < st.cooldownUntil) { status("Amazon asked us to slow down - next look ${secs(st.cooldownUntil - now())}"); waitFor(minOf(st.cooldownUntil - now(), 30_000)); return }
        val net = network()
        if (net == null) { offline(); status("No internet - waiting for it"); waitFor(15_000); return }
        if (st.wifiOnly && net != "wifi") { status("Wi-Fi only is on - waiting for Wi-Fi"); waitFor(30_000); return }
        val watched = store.products().filter { it.watch }
        // 2.8: pictures are looked for also while no product is switched on (a new phone with the whole central list, nothing watched yet)
        if (watched.isEmpty()) { if (meter.run("pictures") { pictureOnce() }) return; status("Watching, but no product is switched on."); waitFor(30_000); return }
        // 2.2: standing by = the linked PC buys and Who buys is "pc" (with "both" this phone watches at its own pace too)
        val desk = standby()
        if (watched.any { it.buy } && !desk && now() - st.ordersReadAt > ORDERS_EVERY) { meter.run("orders") { readOrders() }; return }   // (the desktop reads them while it buys)
        // 1.4: while the linked desktop watches and buys, this phone looks only every 10 minutes (a backup; it saves battery and data)
        // 1.0.9 (owner, 2026-11-21: "I think the app may be making my phone slower"): with "Both buy" this phone opened a full Amazon page
        // about every 16 s all day (9 products every 90 s, ~1.5 MB each) while the PC watched the same products. While the PC is watching
        // NOW, this phone looks by itself only every WITH_PC_MS: the PC's in-stock signal (Cmd.Go - at or under the cap, a product set to
        // Buy) and another Restalker's (Cmd.Crowd) still make it look AT ONCE and race the PC to the checkout. PC not heard lately -> the
        // usual pace again
        val withPc = !desk && buyerMode() == "both" && pcWatches()
        val usual = if (desk) maxOf(STANDBY_MS, st.intervalSec * 1000L)
            else if (withPc) maxOf(WITH_PC_MS, st.intervalSec * 1000L)
            else st.intervalSec.coerceAtLeast(Settings.MIN_INTERVAL) * 1000L
        // 1.1.0 (owner, 2026-11-22: "throttle the system based on battery life. Use 50% and 25% battery life as the steps ... even when
        // the PC is not running"): battery at 50% or less -> at most a look every 5 min per product, 25% or less -> every 15 min (not while
        // charging; Settings, "Save battery when low"). Go / Crowd above still look at once
        val bat = battery()
        val batMs = BatteryPace.minMs(bat.first, bat.second, st.batterySave)
        val interval = maxOf(usual, batMs)
        paceMs = interval
        val due = watched.minByOrNull { it.lastCheck } ?: return
        val wait = due.lastCheck + interval - now()
        offlineSince = 0
        if (wait > 0 && meter.run("pictures") { pictureOnce() }) return
        // 2.0: the page closed and quiet until the next look (battery). 2.14: between EVERY two looks (was: only for a wait over 20 s) -
        // with 8 products every 90 s the last page stayed open, its scripts and ads still working, between all the looks
        if (wait > 3_000) page.rest()
        if (wait > 0) { status(if (desk) (when { otherPhoneBuys() -> "Another phone / tablet buys"; desktopBuys() -> "The PC buys"; pcHanded() -> "Another device buys"
            else -> "Not buying here" }) + " · this device stands by (a backup look every 30 min - Settings, Linked devices) · next look ${secs(wait)}"
            else if (batMs > usual) "${BatteryPace.words(bat.first, bat.second, st.batterySave)} (it looks at once when the PC or the crowd sees a restock; Settings) · ${watched.size} product(s) · next look ${secs(wait)}"
            else if (withPc) "The PC is watching - this phone looks every ${WITH_PC_MS / 60_000} min, and at once when the PC sees a restock · ${watched.size} product(s) · next look ${secs(wait)}"
            else "Watching ${watched.size} product(s) · next look ${secs(wait)}"); waitFor(minOf(wait, 30_000)); return }
        check(due)
    }

    private fun handle(c: Cmd) {
        when (c) {
            is Cmd.Quit -> quit = true
            is Cmd.Wake -> {}
            is Cmd.CheckNow -> store.product(c.id)?.let { check(it, manual = true) }
            is Cmd.Baseline -> {
                // 2.15 (owner: "When the app starts up, it should do an automatic 1 time check of all products so that the data is included as
                // a base. It is difficult for a user to assess a cap if they have no data showing what the prices are"): a watched product
                // gets its usual look; one that is not watched only gets its price and stock written down - no message, no sharing, no buying
                val p = store.product(c.id) ?: return
                if (now() < s().cooldownUntil || network() == null || (s().wifiOnly && network() != "wifi")) return
                if (now() - p.lastCheck < BASELINE_FRESH) return
                if (p.watch) check(p) else { quietLook = true; try { meter.run("looks") { openAndRead(p) } } finally { quietLook = false; busyWith = "idle" } }
            }
            is Cmd.Go -> {
                // 2.2 (owner: "buy only on app (so the desktop is doing the searching work but pushing purchasing to the app)"): the PC's
                // signal - this phone looks at the product at once and buys it if IT sees it in stock under the cap (the PC's word alone
                // never buys anything)
                val p = store.products().firstOrNull { it.asin == c.asin && it.buy && it.watch } ?: return
                if (!s().watching || !inHours() || !s().buying || buyerMode() == "pc" || otherPhoneBuys() || now() < s().cooldownUntil) return
                detail("${p.label}: the PC saw it in stock - looking now (Who buys: ${buyerMode()}).")
                tipFor = p.id to "your PC spotted it first"
                check(p, manual = false)
            }
            is Cmd.Crowd -> {
                // 2.6: another Restalker saw it in stock on Amazon - this phone looks itself at once (it buys only what IT sees in stock
                // under your cap; the crowd's word alone never buys anything)
                val p = store.products().firstOrNull { it.asin == c.asin && it.watch } ?: return
                if (!s().watching || !inHours() || now() < s().cooldownUntil) return
                detail("${p.label}: another Restalker saw it in stock - looking now.")
                tipFor = p.id to "another Restalker spotted it first"
                check(p, manual = false)
            }
            is Cmd.BuyNow -> {
                val p = store.product(c.id) ?: return
                if (p.purchased || p.attempted) { alerts.needYou("Not bought", "${p.label} was already ordered once - press Re-arm in the app to buy it again.", p); return }
                if (p.cap <= 0) { alerts.needYou("Not bought - no cap", "${p.label} is watched without a price cap. Open it in the app and set the most you'll pay, then Buy.", p); return }
                store.update(p.id) { it.copy(buy = true, watch = true) }
                if (!s().buying) store.setSettings { it.copy(buying = true) }
                log("${p.label}: Buy now pressed on the message - set to buy, looking at it now.")
                overrideDesk = p.id                  // (your Buy now: this phone buys it now, even while a linked desktop is the buyer)
                try { store.product(p.id)?.let { check(it, manual = true) } } finally { overrideDesk = null }
            }
            is Cmd.Practice -> {
                val p = store.product(c.id) ?: return
                log("${p.label}: practice checkout started by you (it stops before Place your order).")
                practice(p)
            }
            is Cmd.PracticeAny -> {
                // 2.0 (owner: "the app still hasnt been tested for checkout ... I get an error message stating that the product must be in stock
                // and under my cap. If this were the case, then the PC would have already bought it"): any Amazon item in stock, at any
                // price - the practice stops before "Place your order", so nothing is ever bought. The item is not kept in your list.
                store.products().firstOrNull { it.asin == c.asin }?.let { practice(it); return }
                val tmp = Product(id = PRACTICE_ID + c.asin, asin = c.asin, cap = 1.0, kind = "other", watch = false, buy = false)
                store.add(tmp)
                try { log("Practice checkout with Amazon item ${c.asin} (not added to your list; it stops before Place your order)."); practice(tmp) }
                finally { store.remove(tmp.id) }
            }
        }
    }

    private fun waitFor(ms: Long) {
        if (ms <= 0) return
        if (clock !== RealClock) { clock.sleep(ms); return }       // (tests: their clock jumps ahead)
        val c = q.pollFirst(ms, TimeUnit.MILLISECONDS) ?: return
        q.offerFirst(c)        // (a command arrived: step() takes it next)
    }

    /** A practice checkout: the product must be in stock - at ANY price (2.0: over the cap too - a practice never orders). */
    private fun practice(p: Product) {
        if (!openAndRead(p)) { alerts.practice(p, "Practice not possible: Amazon's page did not open. Try again in a minute."); return }
        val p2 = store.product(p.id) ?: return
        val pr = p2.price
        val inStock = p2.buyable || (p2.overCap && pr != null) || (p.id.startsWith(PRACTICE_ID) && pr != null && lastReply?.get("reason").asStr() == "price above cap")
        if (!inStock) {
            alerts.practice(p2, "Practice not possible with this one right now: ${p2.status}. A practice needs an item that is in stock (at any price) - " +
                "use Settings -> Practice checkout with any Amazon item that is in stock (nothing is bought).")
            return
        }
        // (over the cap: for this practice only, the cap is set just above the price - the real cap is not changed)
        val run = if (p2.buyable) p2 else p2.copy(cap = (pr ?: p2.cap) + 1.0)
        buy(run, pr, forcePractice = true)
    }

    // ------------------------------------------------------------------ looking at a product
    private var lastReply: Map<String, Any?>? = null
    /** 1.1.7: when Buy Now is pressed - the fastest way first, a slower way only on proof, and back by itself (see PressLadder). */
    private val ladder = PressLadder({ now() }, try { store.readFile("press_ladder.json") } catch (e: Exception) { null })
    private var lastEarly = false
    private fun ladderSave() { try { store.writeFile("press_ladder.json", ladder.save()) } catch (e: Exception) { } }
    fun pressLadderText(): String = ladder.text()

    /** Opens the product's page and reads it (the desktop's REVALIDATE_QUEUED check). Updates the product. false = could not read it. */
    private fun openAndRead(p: Product): Boolean {
        busyWith = "looking at ${p.label}"
        page.images(false)
        if (!page.open(p.url)) { failRun.merge(p.id, 1, Int::plus); setSeen(p, "the page did not load", null, false); loadFailed(); return false }
        loadFails = 0
        page.ask(mapOf("bb" to "reset"), 10_000)
        // 1.1.5 (owner, 2026-11-24: "as soon as the program detects enough information ... it should move on to the next step" - "a dynamic
        // number that is based off of when the actual page is read, not an arbitrary number"): until 1.1.4 the app waited for the WHOLE
        // page (all its pictures and scripts), then a fixed quarter second, then asked once. Now the page is asked while it arrives, again
        // and again: "buyable" ends the look the moment the buy box is complete (the desktop's own check says so); "not buyable" and "over
        // the cap" count only once the page has been read to its end (two more looks after that, for a part that fills in late).
        var r: Map<String, Any?>? = null
        val lookEnd = now() + LOOK_MS; var after = 0
        while (true) {
            r = page.ask(mapOf("bb" to "eligible", "product" to p.forPage()), 12_000)
            val ready = r?.get("ready").asStr()
            // 1.1.7 (owner, 2026-11-25: the fastest option is the default; a slower one only after being PROVEN, and back by itself):
            // "buyable" ends the look the moment the buy box is complete, while the page is still arriving. 1.1.6 waited for the page
            // to be read on a suspicion (pages without the product on the PC after early presses); now that is measured and decided
            // by the numbers - see PressLadder. It never waits for pictures and scripts, or a fixed pause, as it did until 1.1.4.
            if (r == null || r["limited"] == true || (r["eligible"] == true && (ready != "loading" || ladder.early()))) { lastEarly = r?.get("eligible") == true && ready == "loading"; break }
            if (ready == null) break                                   // (a page part that does not say how far the page is: one look, as before)
            val read = ready == "interactive" || ready == "complete"
            if (read && (r["reason"].asStr() ?: "") == "price above cap") break
            if (read && ++after >= 3) break
            if (now() >= lookEnd) break
            clock.sleep(60)
        }
        lastReply = r
        if (r == null) { failRun.merge(p.id, 1, Int::plus); setSeen(p, "the page did not answer", null, false); return false }
        failRun.remove(p.id)
        val reason = r["reason"].asStr() ?: ""
        val title = r["title"].asStr() ?: ""
        if (Regex("challenge|throttle", RegexOption.IGNORE_CASE).containsMatchIn(reason)) { amazonCheck(p); return false }
        if (store.settings().cooldownStreak > 0) store.setSettings { it.copy(cooldownStreak = 0) }
        if (p.tokens.isEmpty() || p.name.isBlank()) {
            val tk = Tokens.fromTitle(title)
            if (title.isNotBlank() && tk.size >= 2 && !Regex("^amazon\\.com", RegexOption.IGNORE_CASE).containsMatchIn(title))
                store.update(p.id) { it.copy(tokens = if (it.tokens.isEmpty()) tk else it.tokens, name = it.name.ifBlank { title.take(160) }) }
        }
        val price = r["price"].asDouble()
        val eligible = r["eligible"] == true
        val text = when {
            eligible -> "BUYABLE" + (price?.let { " at \$${money(it)}" } ?: "")
            // 1.1.5: the seller in the buy box is one Amazon's purchase limit was reached for - looked at, not bought from this seller
            r["limited"] == true -> "seller limit - Amazon's purchase limit holds for " + (r["limitSellerName"].asStr()?.ifBlank { null } ?: "this seller") +
                (price?.let { " (\$${money(it)})" } ?: "") + "; an offer of another seller is bought"
            reason == "price above cap" -> "for sale over your cap" + (price?.let { " (\$${money(it)})" } ?: "")
            // 1.1.6: Amazon answered with a page that has no product at all (not this listing's fault, not "sold out"): looked at again
            reason.contains("page without the product") -> { ladder.junk(); ladderSave(); reason }
            Regex("identity|not the product", RegexOption.IGNORE_CASE).containsMatchIn(reason) -> "NOT THIS PRODUCT - the page shows something else ($reason)"
            reason.contains("purchase control") -> "out of stock" + (price?.let { " (\$${money(it)} shown)" } ?: "")
            else -> reason.ifBlank { "not buyable" }
        }
        val before = store.product(p.id) ?: p
        setSeen(before, text, price, eligible)
        val over = reason == "price above cap"
        if ((eligible || over) && !(before.buyable || before.overCap) && !quietLook) try { onSighting(p.asin, price) } catch (e: Exception) { }
        if (over != (store.product(p.id)?.overCap ?: false)) store.update(p.id) { it.copy(overCap = over) }
        // 1.5: the product's picture (Amazon's own, from this page) - once
        if ((store.product(p.id)?.image ?: "").isBlank()) takePicture(p)
        // 1.1.8 (a report from an unlinked phone, 2026-11-25: "Data used while watching: 11166 MB (about 634 MB an hour)" - 15,769 looks at
        // about 723 KB each): a look needs the page only until it has its answer - "buyable" the moment the buy box is in, "not
        // buyable" when the text has been read. Until 1.1.7 the page then went on downloading everything else Amazon puts on a
        // product page (scripts, styles, late requests, video) until the NEXT look opened. Now the page is stopped right here - unless
        // this product is set to Buy and is buyable (then the page is needed whole for the checkout that follows).
        val cur = store.product(p.id)
        if (!(eligible && cur != null && cur.buy)) { try { page.stop() } catch (e: Exception) { } }
        return true
    }

    // ---- pages that don't load / no internet: after 10 failures in a row (or 10 minutes without internet) you are told once
    private var loadFails = 0
    private var offlineSince = 0L
    private var offlineTold = false
    private fun loadFailed() {
        loadFails++
        if (loadFails == 10) alerts.needYou("Amazon is not loading", "The last 10 Amazon pages did not load. Restalker keeps trying; check the phone's internet.")
    }
    private fun offline() {
        if (offlineSince == 0L) { offlineSince = now(); offlineTold = false }
        if (!offlineTold && now() - offlineSince > 10 * 60_000L && s().watching) {
            offlineTold = true
            alerts.needYou("No internet", "The phone has had no internet for 10 minutes - Restalker can't watch until it is back.")
        }
    }

    private var restEnded: String? = null
    private fun setSeen(p: Product, text: String, price: Double?, buyable: Boolean) {
        val t = now()
        store.update(p.id) {
            val newEp = buyable && !it.buyable
            // 1.1.7: new evidence ends the wait after failed checkouts at once - a new restock, or another price on a standing offer
            val newPrice = buyable && it.buyable && price != null && it.price != null && Math.abs(price - (it.price ?: price)) >= 0.01
            val endRest = (newEp || newPrice) && it.lockedUntil > t
            if (endRest) restEnded = if (newEp) "a new restock" else "another price"
            it.copy(status = text, price = price ?: if (buyable) it.price else price, lastCheck = t, buyable = buyable,
                episodeStart = if (newEp) t else it.episodeStart, lockedUntil = if (endRest) 0 else it.lockedUntil)
        }
        restEnded?.let { log("${p.label}: $it - the wait after failed checkouts ends now."); restEnded = null }
        if (buyable && !p.buyable && !quietLook) { detail("${p.label}: $text"); freshId = p.id }
    }

    fun check(p0: Product, manual: Boolean = false) {
        freshId = null
        val tip = tipFor?.takeIf { it.first == p0.id }?.second; tipFor = null
        if (!meter.run("looks") { openAndRead(p0) }) { busyWith = "idle"; return }
        val p = store.product(p0.id) ?: return
        if (manual) log("${p.label}: looked at by you - ${p.status}")
        val fresh = freshId == p.id; freshId = null
        // (2.13: one line for a restock, said once - what was seen, who spotted it, and what happens now)
        val seen = "${p.label}: in stock" + (p.price?.let { " at \$${money(it)}" } ?: "") + (tip?.let { " ($it)" } ?: "")
        if (p.buyable) {
            val practicedThisRestock = s().practice && p.episodeStart > 0 && p.practiceOkAt >= p.episodeStart
            if (canAutoBuy(p) && !practicedThisRestock) { if (fresh) log("$seen - buying it now."); buy(p, p.price) }
            else if (p.buy && s().buying && standby() && overrideDesk != p.id) {
                if (p.alertedEpisode != p.episodeStart) { store.update(p.id) { it.copy(alertedEpisode = p.episodeStart) }; log("$seen - " + standbyWhy()) }
            }
            else if (!canAutoBuy(p) && !p.purchased && !p.attempted && p.alertedEpisode != p.episodeStart && now() >= s().snoozeUntil) {
                store.update(p.id) { it.copy(alertedEpisode = p.episodeStart) }
                // 1.1.3 (owner, 2026-11-24: "lots of activity claiming restocks and delivered messages, but ... the app is not pushing
                // these messages as notifications"): the line says what really happened on this device
                val no = try { alerts.cannotShow("restock") } catch (e: Exception) { null }
                log(if (no == null) "$seen - you were sent a message." else "$seen - NO message could be shown: $no")
                alerts.restock(p, p.price)
            }
            else if (fresh) log("$seen.")
        } else if (tip != null) detail("${p.label}: not in stock when this phone looked (${p.status}).")
        busyWith = "idle"
    }

    private fun canAutoBuy(p: Product): Boolean {
        val st = s()
        if (!Limit.mayBuy(boughtOf(p), master())) return false          // 2.7: at most Limit.MAX purchases of a product (not the owner's)
        return st.buying && p.buy && !p.attempted && !p.purchased && p.cap > 0 && now() >= p.lockedUntil && !signinHold() &&
            (overrideDesk == p.id || mayBuyHere())
    }

    /** 2.3 (each device its own choice): may THIS device buy now? "both" / "phone": yes; "pc": only when the PC has stopped (not when it
     *  handed Amazon to another device) and "takes over" is on; never while another phone / tablet is first in line (one order). */
    private fun mayBuyHere(): Boolean = (!otherPhoneBuys() || (raceOn() && buyerMode() != "pc")) && when (buyerMode()) {
        "pc" -> failover() && !desktopBuys() && !pcHanded()
        else -> true
    }

    /** 2.14: why this device only stands by, for the Activity line (2.13 always said "the linked desktop buys it" - also when the PC
     *  had stopped and another phone / tablet was first in line). */
    private fun standbyWhy(): String = when {
        otherPhoneBuys() -> "another phone / tablet buys it (this one stands by)."
        desktopBuys() -> "the linked desktop buys it (this phone stands by)."
        pcHanded() -> "another device buys it (this one stands by)."
        else -> "this device does not buy it (Settings, Linked devices)."
    }

    /** This device only stands by (a backup look every 30 minutes). */
    private fun standby(): Boolean = !mayBuyHere()

    /** 2.2 (Who buys: both): right before Place your order this phone waits for the PC's OWN try at the same product - it orders only if
     *  that try FAILED (or the PC is not trying it at all); the PC bought it / the PC's word does not come in time -> not ordered here.
     *  One order per product, whichever is faster. -> null = go ahead, else why not. */
    private fun holdForPc(p: Product, startedAt: Long): String? {
        if (buyerMode() != "both" || !desktopBuys() || overrideDesk == p.id) return null
        val t0 = now()
        log("${p.label}: at Place your order - waiting for the PC's own try first (Who buys: both; one order only).")
        var saw = false
        while (now() - t0 < HOLD_MS) {
            val v = pcVerdict(p.asin)?.takeIf { it.second >= startedAt - 180_000 }
            if (v != null) {
                saw = true
                when (v.first) {
                    "bought" -> return "PC: the PC bought it (or its order is being checked) - not ordered here"
                    "failed" -> { log("${p.label}: the PC's try did not buy it - this phone orders now."); return null }
                }
            } else if (!saw && now() - t0 >= HOLD_NONE_MS) {
                log("${p.label}: the PC is not checking this one out - this phone orders now."); return null
            }
            clock.sleep(400)
        }
        return "the PC's result did not come in ${HOLD_MS / 1000} s - not ordered here (never two orders)"
    }

    /** Amazon asked to sign in: checkouts wait until you opened the sign-in (the app clears it) or for 1 hour. */
    private fun signinHold() = s().signinNeededAt > 0 && now() - s().signinNeededAt < 60 * 60_000L

    private fun amazonCheck(p: Product) {
        val st = s()
        val streak = st.cooldownStreak + 1
        val rest = minOf(60L * 60_000, 10L * 60_000 * streak)
        store.setSettings { it.copy(cooldownUntil = now() + rest, cooldownStreak = streak) }
        setSeen(p, "Amazon shows a robot check / asks us to slow down - resting ${rest / 60_000} min", null, false)
        log("! ${p.label}: Amazon showed a robot check or slowed us down. Resting ${rest / 60_000} min (it is never answered by the app).")
        if (now() - st.needYouAt > 60 * 60_000L) {
            store.setSettings { it.copy(needYouAt = now()) }
            alerts.needYou("Amazon wants to check it's you", "Open the app, press Show browser and finish Amazon's check yourself. Restalker never answers it; it rests ${rest / 60_000} min meanwhile.", p)
        }
    }

    // ------------------------------------------------------------------ buying (the desktop's _try_amazon)
    private enum class End { BOUGHT, PRACTICE_OK, UNCLEAR, FAILED, STOPPED, PC_BOUGHT, RACE_LOST, LIMIT }
    private var limitSeller = "*"; private var limitName = ""

    fun buy(p: Product, price: Double?, forcePractice: Boolean = false) = meter.run("checkouts") { buyNow(p, price, forcePractice) }

    private fun buyNow(p: Product, price: Double?, forcePractice: Boolean) {
        var lockHeld = false                     // 2.15: this device holds the shared checkout lock for it
        val practice = forcePractice || s().practice
        busyWith = "checking out ${p.label}" + if (practice) " (practice)" else ""
        if (!practice && !canAutoBuy(p)) { busyWith = "idle"; return }
        store.update(p.id) { it.copy(inProgress = true, status = if (practice) "PRACTICE CHECKOUT - starting" else "CHECKOUT - starting") }
        log("${p.label}: ${if (practice) "PRACTICE checkout" else "CHECKOUT"} started" + (price?.let { " (\$${money(it)}, cap \$${money(p.cap)})" } ?: ""))
        page.images(true)
        page.visited()                                  // (forget the pages before this checkout)
        var end = End.STOPPED; var why = ""; var order: String? = null; var paid: Double? = price
        val startedAt = now()
        var practiceFees: Map<String, Any?>? = null
        var submitted = false
        try {
            ladder.press(lastEarly); ladderSave()
            val b = page.ask(mapOf("bb" to "begin", "product" to p.forPage(), "price" to price, "hit" to now()), 10_000)
            if (b != null && b["ok"] == false && b["reason"] is String) { why = b["reason"] as String; return }
            var seen = 0L
            var until = now() + CHECKOUT_MS
            loop@ while (now() < until) {
                val m = page.ask(mapOf("bb" to "msgs", "since" to seen), 4_000)
                if (m == null || m["ok"] != true) { clock.sleep(150); continue }
                for (x in m["msgs"].asList().mapNotNull { it.asMap() }) {
                    seen = maxOf(seen, x["n"].asLong() ?: 0)
                    val t = x["type"].asStr() ?: ""; val reason = x["reason"].asStr() ?: ""
                    if (t != "CHECKOUT_PRICE_EVIDENCE") log("  checkout step: $t" + (if (reason.isNotBlank()) " - $reason" else "") +
                        listOf("page", "step", "label").mapNotNull { k -> x[k]?.let { v -> " $k=$v" } }.joinToString("") +
                        (x["headings"]?.let { " headings=$it" } ?: "") + (x["notes"]?.let { " notes=$it" } ?: "") +
                        (x["fees"]?.let { " fees=" + MiniJson.write(it) } ?: ""))
                    when (t) {
                        "CHECKOUT_PRICE_EVIDENCE" -> store.update(p.id) { it.copy(status = "checkout: " + reason.lowercase().replace('_', ' ')) }
                        "SUBMITTING" -> {
                            if (practice) {
                                practiceFees = x["fees"].asMap()
                                page.ask(mapOf("bb" to "reply", "n" to x["n"], "value" to mapOf("ok" to false, "reason" to "practice run - stopped before Place your order")), 4_000)
                                end = End.PRACTICE_OK
                                continue@loop
                            }
                            // 2.15: the race - the first of your devices at Place your order claims the product; the others stop
                            val claim = if (raceOn()) try { raceLock(p.asin) } catch (e: Exception) { null } else null
                            if (claim == false) {
                                page.ask(mapOf("bb" to "reply", "n" to x["n"], "value" to mapOf("ok" to false, "reason" to "another of your devices is ordering it")), 4_000)
                                end = End.RACE_LOST; why = "another of your devices got to Place your order first - not ordered here"; return
                            }
                            if (claim == true) { lockHeld = true; detail("${p.label}: this device won the race to Place your order (the shared lock).") }
                            // 2.2: Who buys = both - the PC's own try first (when the shared lock could not be asked)
                            val h0 = now()
                            val held = if (claim == true) null else holdForPc(p, startedAt)
                            until += now() - h0                  // (the wait does not count against the checkout's own time)
                            if (held != null) {
                                page.ask(mapOf("bb" to "reply", "n" to x["n"], "value" to mapOf("ok" to false, "reason" to held)), 4_000)
                                if (held.startsWith("PC: ")) { end = End.PC_BOUGHT; why = held.removePrefix("PC: ") } else { end = End.STOPPED; why = held }
                                return
                            }
                            // the one-per-product lock: written down BEFORE the page may press Place your order
                            val cur = store.product(p.id)
                            val ok = cur != null && canAutoBuy(cur)
                            if (ok) { store.update(p.id) { it.copy(attempted = true, submittedAt = now(), status = "PLACING THE ORDER") }; submitted = true }
                            page.ask(mapOf("bb" to "reply", "n" to x["n"], "value" to mapOf("ok" to ok, "reason" to if (ok) "" else "switched off or already ordered")), 4_000)
                            if (!ok) { why = "switched off or already ordered meanwhile"; return }
                            log("${p.label}: Place your order allowed (item \$${money(x["fees"].asMap()?.get("base").asDouble() ?: price ?: 0.0)}) - waiting for Amazon's confirmation")
                        }
                        "PURCHASE_SUCCESS" -> { end = End.BOUGHT; order = x["orderNumber"].asStr(); paid = x["price"].asDouble() ?: paid; return }
                        "SUBMISSION_UNCLEAR" -> {
                            val found = orderCheck(p)
                            if (found != null) { end = End.BOUGHT; order = found; return }
                            end = End.UNCLEAR; why = "Amazon did not confirm the order and it is not (yet) in your Amazon orders" + if (reason.isNotBlank()) ": $reason" else ""; return
                        }
                        "DEFINITE_SUBMISSION_FAILURE" -> { end = End.FAILED; why = "ORDER NOT PLACED - $reason"; return }
                        "ACTION_REQUIRED" -> { end = End.STOPPED; why = reason; actionRequired(p, reason); return }
                        "SHIPPING_APPROVAL_REQUIRED" -> {
                            val f = x["fees"].asMap()
                            end = End.STOPPED; why = "shipping \$${money(f?.get("shipping").asDouble() ?: 0.0)} is over \$20"
                            alerts.needYou("Shipping costs need your OK", "${p.label}: shipping is over \$20 (\$${money(f?.get("shipping").asDouble() ?: 0.0)}) - not bought. Open Amazon to buy it yourself if you still want it.", p)
                            return
                        }
                        "FLOW_BLOCKED" -> {
                            if (practice && end == End.PRACTICE_OK) return
                            if ((x["page"].asStr() ?: "").startsWith("/ap/")) {        // (Amazon's sign-in / verification pages)
                                end = End.STOPPED; why = "Amazon asked to sign in"; actionRequired(p, "sign in required"); return
                            }
                            if (Regex("purchase limit", RegexOption.IGNORE_CASE).containsMatchIn(reason)) {
                                // 1.1.5: Amazon's own limit for this account - for the seller whose offer was in the buy box
                                limitSeller = x["limitSeller"].asStr()?.ifBlank { null } ?: "*"; limitName = x["limitSellerName"].asStr() ?: ""
                                end = End.LIMIT; why = reason; return
                            }
                            end = End.FAILED; why = "Amazon checkout: $reason"; return
                        }
                    }
                }
                clock.sleep(120)
            }
            if (end == End.PRACTICE_OK) return
            if (submitted) {
                val found = orderCheck(p)
                if (found != null) { end = End.BOUGHT; order = found } else { end = End.UNCLEAR; why = "Amazon did not answer within ${CHECKOUT_MS / 1000} s after Place your order" }
            } else { end = End.FAILED; why = "the checkout did not finish within ${CHECKOUT_MS / 1000} s" }
        } finally {
            if ((end == End.FAILED || end == End.STOPPED) && !why.contains("sign in", true) &&
                (try { page.visited() } catch (e: Exception) { emptyList() }).any { it.startsWith("/ap/") }) {
                // Amazon showed its sign-in / verification page during this checkout: that is why it did not finish
                why = "Amazon asked to sign in"; end = End.STOPPED; actionRequired(p, "sign in required")
            }
            if (end != End.BOUGHT) try { page.ask(mapOf("bb" to "reset"), 4_000) } catch (e: Exception) { }    // (no unfinished checkout is left in the browser)
            // 2.15: the shared lock - bought (or maybe bought) keeps your other devices out; anything else frees it at once
            if (lockHeld) try { raceRelease(p.asin, if (end == End.BOUGHT || end == End.UNCLEAR) "bought" else "failed") } catch (e: Exception) { }
            finish(p, end, why, order, paid, practiceFees, submitted)
            try { page.images(false) } catch (e: Exception) { }
            busyWith = "idle"
        }
    }

    private fun finish(p0: Product, end: End, why: String, order: String?, paid: Double?, fees: Map<String, Any?>?, submitted: Boolean) {
        val p = store.product(p0.id) ?: p0
        when (end) {
            End.BOUGHT -> {
                store.update(p.id) { it.copy(inProgress = false, purchased = true, attempted = true, buy = false, orderNumber = order, status = "BOUGHT - checking your Amazon orders") }
                log("${p.label}: BOUGHT at \$${money(paid ?: 0.0)}" + (order?.let { " - order $it" } ?: "") + ". Looking it up in your Amazon orders before telling you.")
                val ok = verifyBought(p, order)
                store.update(p.id) { it.copy(status = if (ok != null) "BOUGHT - order ${ok} (in your Amazon orders)" else "BOUGHT - order ${order ?: "?"} (not in your order list yet - check Amazon)", orderNumber = ok ?: order) }
                store.addPurchase(Purchase(now(), p.asin, p.label, paid, ok ?: order, ok != null))
                alerts.bought(store.product(p.id) ?: p, paid, ok ?: order, ok != null)
                // 1.5: Buy 2 / 3 - separate orders: until that many were bought (counted from when you chose it), it is bought again
                val q = store.product(p.id)
                if (q != null && q.qty > 1) {
                    val n = store.purchases().count { it.asin == q.asin && it.at >= q.qtySince && it.order != null }
                    if (n < q.qty) {
                        store.update(p.id) { it.copy(purchased = false, attempted = false, buy = true, orderNumber = null, status = "BOUGHT $n of ${q.qty} - the next one is bought too") }
                        log("${p.label}: $n of ${q.qty} bought - the next order follows (Buy ${q.qty}).")
                    } else log("${p.label}: all ${q.qty} bought.")
                }
            }
            End.PRACTICE_OK -> {
                val f = fees
                val txt = "Practice checkout reached Place your order and stopped (nothing bought)" +
                    (f?.let { " - item \$${money(it["base"].asDouble() ?: 0.0)}, tax \$${money(it["tax"].asDouble() ?: 0.0)}, shipping \$${money(it["shipping"].asDouble() ?: 0.0)}, total \$${money(it["total"].asDouble() ?: 0.0)}" } ?: "")
                store.update(p.id) { it.copy(inProgress = false, practiceOkAt = now(), status = "PRACTICE OK - reached Place your order") }
                log("${p.label}: $txt")
                alerts.practice(store.product(p.id) ?: p, txt)
            }
            End.RACE_LOST -> {
                // (nothing counts against this device; the winner's purchase comes over the link and turns Buy off as usual)
                store.update(p.id) { it.copy(inProgress = false, status = "ANOTHER OF YOUR DEVICES IS ORDERING IT - not ordered here") }
                log("${p.label}: $why.")
            }
            End.PC_BOUGHT -> {
                // (one order per product: Buy goes off here; the PC's purchase comes over the link as usual)
                store.update(p.id) { it.copy(inProgress = false, buy = false, status = "THE PC BOUGHT IT - not ordered here") }
                log("${p.label}: $why (Who buys: both).")
            }
            End.UNCLEAR -> {
                store.update(p.id) { it.copy(inProgress = false, attempted = true, buy = false, status = "ORDER MAY HAVE BEEN PLACED - check Amazon") }
                log("! ${p.label}: order unclear - $why. Not bought again (press Re-arm after checking Amazon).")
                alerts.problem("${p.label}: order unclear - $why")
                store.addPurchase(Purchase(now(), p.asin, p.label, paid, null, false, "unclear - check Amazon"))
                alerts.needYou("Check your Amazon orders", "${p.label}: $why. Restalker will not buy it again until you press Re-arm.", p)
            }
            End.LIMIT -> {
                // 1.1.5 (owner, 2026-11-24): kept for THAT seller only, 6 hours; no failed try is counted, nothing is locked, Buy stays on
                val who = when { limitSeller == "*" -> "a seller that could not be read"; limitName.isNotBlank() -> limitName; limitSeller == "ATVPDKIKX0DER" -> "Amazon.com"; else -> "seller $limitSeller" }
                val already = (p.limitsAt(now())[limitSeller]?.get("until").asLong() ?: 0) > now() + LIMIT_MS - 15 * 60_000L
                store.update(p.id) { it.withLimit(limitSeller, limitName, now() + LIMIT_MS, now()).copy(inProgress = false, buyable = false,
                    status = "SELLER LIMIT - Amazon's purchase limit reached for $who; left alone for 6 hours, another seller's offer is still bought") }
                log("${p.label}: Amazon's purchase limit reached for $who - that seller's offer is left alone for 6 hours" +
                    (if (limitSeller == "*") " (the seller could not be read: the whole listing rests)" else "; an offer of another seller at or under your cap is still bought") + ". Nothing is locked.")
                if (!already) alerts.needYou("Amazon's purchase limit reached", "${p.label}: Amazon's checkout says you have reached its purchase limit for this item" +
                    (if (limitSeller == "*") ". Restalker does not try this listing for 6 hours, then tries again by itself."
                     else " from $who. Restalker leaves $who's offer alone for 6 hours; an offer from another seller at or under your cap is still bought.") +
                    " Buy stays on (nothing is locked) - switch Buy off and on to try sooner.", p)
            }
            End.FAILED, End.STOPPED -> {
                val ep = p.episodeStart
                val fails = if (p.failEpisode == ep) p.fails + 1 else 1
                val lock = fails >= LOCK_AFTER
                // 1.1.7 (owner, 2026-11-25: a 10-minute wait "seems too late"; "what am I sacrificing ... who is being harmed"): the wait
                // spares Amazon the same failing checkout again and again - but while it lasts this device does not buy, so it is as
                // short as it can be: 30 s, then 1, 2 and at most 5 minutes while the failures go on, and a NEW restock or another
                // price ends it at once (setSeen). Until 1.1.6: 10 minutes after 3 tries, whatever happened meanwhile.
                val restMs = if (lock) REST_STEPS[minOf(fails - LOCK_AFTER, REST_STEPS.size - 1)] else 0L
                val restWord = if (restMs < 120_000L) "${restMs / 1000} s" else "${restMs / 60_000} min"
                store.update(p.id) {
                    it.copy(inProgress = false, attempted = it.attempted && !why.startsWith("ORDER NOT PLACED"),     // (Amazon said no order: may be tried again)
                        fails = fails, failEpisode = ep, lockedUntil = if (lock) now() + restMs else it.lockedUntil,
                        status = (if (end == End.FAILED) "NOT BOUGHT - " else "STOPPED - ") + why.take(160))
                }
                log("${p.label}: ${if (end == End.FAILED) "not bought" else "stopped"} - $why" + if (lock) " (the same offer waits $restWord after $fails tries; a new restock or another price ends the wait at once)" else "")
                alerts.problem("${p.label}: ${if (end == End.FAILED) "not bought" else "stopped"} - $why")
                if (fails == LOCK_AFTER) alerts.needYou("Checkout is not finishing", "${p.label}: $fails checkouts in a row did not finish (last: ${why.take(120)}). The same offer is tried again in $restWord (then at longer waits, at most 5 min); a new restock or another price is tried at once.", p)
            }
        }
    }

    private fun actionRequired(p: Product, reason: String) {
        val signin = Regex("sign ?in|password|login", RegexOption.IGNORE_CASE).containsMatchIn(reason)
        val check = Regex("challenge|verification|captcha", RegexOption.IGNORE_CASE).containsMatchIn(reason)
        if (signin) {
            val already = signinHold()
            store.setSettings { it.copy(signinNeededAt = now()) }
            log("! Amazon asked to sign in - no checkout until you sign in (tap Amazon sign-in in the app).")
            if (already) return                         // (told once - not again at every look)
        }
        if (check) {                                    // a robot check during a checkout: rest like on a product page
            val streak = s().cooldownStreak + 1; val rest = minOf(60L * 60_000, 10L * 60_000 * streak)
            store.setSettings { it.copy(cooldownUntil = now() + rest, cooldownStreak = streak) }
        }
        val title = when { signin -> "Sign in to Amazon in the app"; check -> "Amazon wants to check it's you"; else -> "Amazon needs you" }
        val text = when {
            signin -> "${p.label} was buyable but Amazon wants you to sign in. Open the app and press Amazon sign-in."
            check -> "${p.label} was buyable but Amazon showed a security check. Open the app, press Show browser and finish it yourself."
            else -> "${p.label}: $reason"
        }
        alerts.needYou(title, text, p)
    }

    // ------------------------------------------------------------------ your Amazon orders (the desktop's order check)
    /** Reads your Amazon order list for every product set to buy: the orders already there (so a new one can be told apart). */
    /** 1.6 (owner: pictures): a product without its picture (one that is not watched is never looked at) gets ONE look for it when there
     *  is time - Wi-Fi only, at most once a day per product, nothing else is done on that page. -> true when a page was opened. */
    private fun pictureOnce(): Boolean {
        // (1.7) also for a product whose picture was dropped as not a product picture
        // (2.8: not for a product that has the PC's or the central list's picture - no Amazon page is opened for those)
        val p = store.products().firstOrNull { Pics.needsOwn(it) && now() - it.picTriedAt > PIC_RETRY_MS && Asin.parse(it.asin) != null } ?: return false
        if (network() != "wifi") return false
        store.update(p.id) { it.copy(picTriedAt = now()) }
        status("Getting the picture of ${p.label}")
        page.images(false)
        if (!page.goto(p.url)) return true
        takePicture(p)
        return true
    }

    /** 1.7: reads the product's picture from the open page (only a real product picture - Pics.valid). What the page had is kept in
     *  [picInfo] for the diagnostics and the self-check (when none was found). */
    private fun takePicture(p: Product) {
        val r = page.ask(mapOf("bb" to "picture"), 4_000)
        val cands = (r?.get("pictures").asList().map { it.asStr() } + listOf(r?.get("picture").asStr())).filterNotNull()
        picInfo[p.asin] = (r?.get("pinfo").asStr() ?: if (r == null) "no answer from the page" else "-").take(300)
        val bad = (store.readFile(BAD_PICS) ?: "").split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        val c = Pics.choose(cands, p.asin, store.products(), bad)
        if (c != null) { store.update(p.id) { it.copy(image = c) }; picInfo[p.asin] = "found" }
        else if (cands.isNotEmpty()) picInfo[p.asin] = "only pictures of other products / known bad: " + cands.mapNotNull { Pics.id(it) }.distinct().take(4).joinToString(",")
    }

    fun readOrders() {
        busyWith = "reading your Amazon orders"
        val armed = store.products().filter { it.buy && it.watch }.map { it.asin }.distinct()
        store.setSettings { it.copy(ordersReadAt = now()) }
        page.images(false)
        if (!page.goto(ORDERS_URL)) { busyWith = "idle"; return }
        val known = HashMap(s().ordersKnown)
        for (a in armed) {
            val r = page.ask(mapOf("bb" to "orders", "asin" to a), 15_000) ?: continue
            if (r["signin"] == true) {
                store.setSettings { it.copy(signinNeededAt = now()) }
                log("! Your Amazon order list needs a sign-in - press Amazon sign-in in the app.")
                if (now() - s().needYouAt > 60 * 60_000L) { store.setSettings { it.copy(needYouAt = now()) }; alerts.needYou("Sign in to Amazon in the app", "Restalker can't read your Amazon orders (and can't buy) until you sign in: open the app and press Amazon sign-in.") }
                break
            }
            known[a] = r["ids"].asList().mapNotNull { it.asStr() }
        }
        store.setSettings { it.copy(ordersKnown = known) }
        busyWith = "idle"
    }

    /** Looks at your orders for an order of this product that was not there before. order number / null (not found or not readable). */
    private fun lookForNewOrder(p: Product, hint: String?): String? {
        if (!page.goto(ORDERS_URL)) return null
        val r = page.ask(mapOf("bb" to "orders", "asin" to p.asin), 15_000) ?: return null
        if (r["signin"] == true) return null
        val ids = r["ids"].asList().mapNotNull { it.asStr() }
        if (hint != null && hint in ids) return hint
        val before = s().ordersKnown[p.asin] ?: emptyList()
        return ids.firstOrNull { it !in before }
    }

    private fun orderCheck(p: Product): String? {
        for (wait in longArrayOf(12_000, 45_000)) { clock.sleep(wait); lookForNewOrder(p, null)?.let { return it } }
        return null
    }

    private fun verifyBought(p: Product, order: String?): String? {
        for (wait in VERIFY_LOOKS) { clock.sleep(wait); lookForNewOrder(p, order)?.let { return it } }
        return null
    }

    private fun status(t: String) = alerts.status(t)

    companion object {
        /** 1.7: per product code - what its page showed when its picture was looked for ("found", or which picture fields it had). */
        val picInfo = java.util.concurrent.ConcurrentHashMap<String, String>()
        /** 1.8.1: picture ids that are no product picture (one per line). */
        const val BAD_PICS = "badpics.txt"
        const val ORDERS_URL = "https://www.amazon.com/gp/css/order-history"
        const val ORDERS_EVERY = 30 * 60_000L
        const val CHECKOUT_MS = 150_000L
        const val LOCK_AFTER = 3
        const val LOCK_MS = 300_000L             // (1.1.7: the longest wait after failed checkouts - see REST_STEPS)
        const val LIMIT_MS = 6 * 3_600_000L       // 1.1.5: how long a seller Amazon's purchase limit was reached for is left alone (as on the desktop)
        /** 1.1.7: the waits after checkouts in a row that did not finish - short first, longer only while they go on (see buy's end). */
        val REST_STEPS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)
        const val LOOK_MS = 15_000L               // 1.1.5: the longest one look at a page that is still arriving may take
        val VERIFY_LOOKS = longArrayOf(5_000, 15_000, 40_000, 120_000)
        /** 2.0: the id of a practice item (Settings -> Practice checkout with any item): never shared, removed after the practice. */
        const val PRACTICE_ID = "practice-"
        const val HOLD_MS = 85_000L             // 2.2: Who buys = both - the longest wait at Place your order for the PC's result
        const val HOLD_NONE_MS = 10_000L        // 2.2: ... and when the PC says nothing at all about this product in that time, it is not trying it
        const val WITH_PC_MS = 10 * 60_000L     // 1.0.9: "Both buy" while the PC is watching - this phone's own looks (Go / Crowd still at once)
        const val STANDBY_MS = 30 * 60_000L     // 1.6: was 10 min (about 80 MB an hour on the owner's phone); the desktop's view shows meanwhile
        const val PIC_RETRY_MS = 24 * 3600_000L
        const val BASELINE_FRESH = 6 * 3_600_000L          // 2.15: a product looked at in the last 6 hours is not looked at again on start-up
        fun money(d: Double) = String.format(java.util.Locale.US, "%.2f", d)
        fun secs(ms: Long) = if (ms < 90_000) "in ${(ms + 999) / 1000} s" else "in ${(ms + 59_999) / 60_000} min"
    }
}
