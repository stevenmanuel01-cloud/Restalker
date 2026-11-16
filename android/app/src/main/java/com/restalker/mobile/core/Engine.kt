package com.restalker.mobile.core

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** The app's browser, as the engine sees it. The Android side runs it on the main thread; the engine waits for the answers. */
interface Page {
    /** Opens the address and waits until the page has loaded and the Amazon part is in it. */
    fun goto(url: String, timeoutMs: Long = 35_000): Boolean
    /** Asks the Amazon part (amz_part.js) a question; keeps trying while a page is changing. null = no answer in time. */
    fun ask(msg: Map<String, Any?>, timeoutMs: Long = 8_000): Map<String, Any?>?
    fun images(on: Boolean)
    /** The pages opened since the last call (their paths), then forgotten. Used to see that Amazon showed its sign-in page. */
    fun visited(): List<String> = emptyList()
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
             private val desktopBuys: () -> Boolean = { false }) {
    sealed class Cmd {
        data class CheckNow(val id: String) : Cmd()
        data class BuyNow(val id: String) : Cmd()          // the "Buy now" button on a restock message: set to buy, then look at once
        data class Practice(val id: String) : Cmd()        // a practice checkout of this product now (stops before Place your order)
        object Wake : Cmd()
        object Quit : Cmd()
    }

    private val q = LinkedBlockingDeque<Cmd>()
    @Volatile var pausedFor: String? = null               // e.g. "you are using the browser" - nothing is opened meanwhile
    @Volatile var busyWith: String = "idle"; private set
    @Volatile private var quit = false
    @Volatile private var overrideDesk: String? = null

    fun send(c: Cmd) { q.offerLast(c) }
    /** Nothing to do and nothing running (the service may close when watching is off). */
    fun idle(): Boolean = q.isEmpty() && busyWith == "idle"

    private fun log(s: String) = store.log(s)
    private fun s() = store.settings()
    private fun now() = clock.now()

    /** The engine's own thread runs this until Quit. */
    fun run() {
        log("Restalker Mobile $APP_VERSION started (Amazon checkout from desktop $ENGINE_FROM).")
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
        pausedFor?.let { status("Paused - $it"); waitFor(2_000); return }
        if (now() < st.cooldownUntil) { status("Amazon asked us to slow down - next look ${secs(st.cooldownUntil - now())}"); waitFor(minOf(st.cooldownUntil - now(), 30_000)); return }
        val net = network()
        if (net == null) { offline(); status("No internet - waiting for it"); waitFor(15_000); return }
        if (st.wifiOnly && net != "wifi") { status("Wi-Fi only is on - waiting for Wi-Fi"); waitFor(30_000); return }
        val watched = store.products().filter { it.watch }
        if (watched.isEmpty()) { status("Watching, but no product is switched on."); waitFor(30_000); return }
        val desk = desktopBuys()
        if (watched.any { it.buy } && !desk && now() - st.ordersReadAt > ORDERS_EVERY) { readOrders(); return }   // (the desktop reads them while it buys)
        // 1.4: while the linked desktop watches and buys, this phone looks only every 10 minutes (a backup; it saves battery and data)
        val interval = if (desk) maxOf(STANDBY_MS, st.intervalSec * 1000L) else st.intervalSec.coerceAtLeast(Settings.MIN_INTERVAL) * 1000L
        val due = watched.minByOrNull { it.lastCheck } ?: return
        val wait = due.lastCheck + interval - now()
        offlineSince = 0
        if (wait > 0) { status(if (desk) "Desktop is buying · this phone stands by (next look ${secs(wait)})" else "Watching ${watched.size} product(s) · next look ${secs(wait)}"); waitFor(minOf(wait, 30_000)); return }
        check(due)
    }

    private fun handle(c: Cmd) {
        when (c) {
            is Cmd.Quit -> quit = true
            is Cmd.Wake -> {}
            is Cmd.CheckNow -> store.product(c.id)?.let { check(it, manual = true) }
            is Cmd.BuyNow -> {
                val p = store.product(c.id) ?: return
                if (p.purchased || p.attempted) { alerts.needYou("Not bought", "${p.label} was already ordered once - press Re-arm in the app to buy it again.", p); return }
                store.update(p.id) { it.copy(buy = true, watch = true) }
                if (!s().buying) store.setSettings { it.copy(buying = true) }
                log("${p.label}: Buy now pressed on the message - set to buy, looking at it now.")
                overrideDesk = p.id                  // (your Buy now: this phone buys it now, even while a linked desktop is the buyer)
                try { store.product(p.id)?.let { check(it, manual = true) } } finally { overrideDesk = null }
            }
            is Cmd.Practice -> {
                val p = store.product(c.id) ?: return
                log("${p.label}: practice checkout started by you (it stops before Place your order).")
                if (!openAndRead(p)) return
                val p2 = store.product(p.id) ?: return
                if (!p2.buyable) { alerts.practice(p2, "Practice not possible right now: ${p2.status}. A practice needs the product to be buyable at or under its cap."); return }
                buy(p2, store.product(p2.id)?.price, forcePractice = true)
            }
        }
    }

    private fun waitFor(ms: Long) {
        if (ms <= 0) return
        if (clock !== RealClock) { clock.sleep(ms); return }       // (tests: their clock jumps ahead)
        val c = q.pollFirst(ms, TimeUnit.MILLISECONDS) ?: return
        q.offerFirst(c)        // (a command arrived: step() takes it next)
    }

    // ------------------------------------------------------------------ looking at a product
    private var lastReply: Map<String, Any?>? = null

    /** Opens the product's page and reads it (the desktop's REVALIDATE_QUEUED check). Updates the product. false = could not read it. */
    private fun openAndRead(p: Product): Boolean {
        busyWith = "looking at ${p.label}"
        page.images(false)
        if (!page.goto(p.url)) { setSeen(p, "the page did not load", null, false); loadFailed(); return false }
        loadFails = 0
        page.ask(mapOf("bb" to "reset"), 10_000)
        val r = page.ask(mapOf("bb" to "eligible", "product" to p.forPage()), 12_000)
        lastReply = r
        if (r == null) { setSeen(p, "the page did not answer", null, false); return false }
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
            reason == "price above cap" -> "for sale over your cap" + (price?.let { " (\$${money(it)})" } ?: "")
            Regex("identity|not the product", RegexOption.IGNORE_CASE).containsMatchIn(reason) -> "NOT THIS PRODUCT - the page shows something else ($reason)"
            reason.contains("purchase control") -> "out of stock" + (price?.let { " (\$${money(it)} shown)" } ?: "")
            else -> reason.ifBlank { "not buyable" }
        }
        setSeen(store.product(p.id) ?: p, text, price, eligible)
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

    private fun setSeen(p: Product, text: String, price: Double?, buyable: Boolean) {
        val t = now()
        store.update(p.id) {
            val newEp = buyable && !it.buyable
            it.copy(status = text, price = price ?: if (buyable) it.price else price, lastCheck = t, buyable = buyable,
                episodeStart = if (newEp) t else it.episodeStart)
        }
        if (buyable && !p.buyable) log("${p.label}: $text")
    }

    fun check(p0: Product, manual: Boolean = false) {
        if (!openAndRead(p0)) { busyWith = "idle"; return }
        val p = store.product(p0.id) ?: return
        if (manual) log("${p.label}: looked at by you - ${p.status}")
        if (p.buyable) {
            val practicedThisRestock = s().practice && p.episodeStart > 0 && p.practiceOkAt >= p.episodeStart
            if (canAutoBuy(p) && !practicedThisRestock) buy(p, p.price)
            else if (p.buy && s().buying && desktopBuys() && overrideDesk != p.id) {
                if (p.alertedEpisode != p.episodeStart) { store.update(p.id) { it.copy(alertedEpisode = p.episodeStart) }; log("${p.label}: buyable - the linked desktop buys it (this phone stands by).") }
            }
            else if (!canAutoBuy(p) && !p.purchased && !p.attempted && p.alertedEpisode != p.episodeStart && now() >= s().snoozeUntil) {
                store.update(p.id) { it.copy(alertedEpisode = p.episodeStart) }
                alerts.restock(p, p.price)
            }
        }
        busyWith = "idle"
    }

    private fun canAutoBuy(p: Product): Boolean {
        val st = s()
        return st.buying && p.buy && !p.attempted && !p.purchased && p.cap > 0 && now() >= p.lockedUntil && !signinHold() && (overrideDesk == p.id || !desktopBuys())
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
    private enum class End { BOUGHT, PRACTICE_OK, UNCLEAR, FAILED, STOPPED }

    fun buy(p: Product, price: Double?, forcePractice: Boolean = false) {
        val practice = forcePractice || s().practice
        busyWith = "checking out ${p.label}" + if (practice) " (practice)" else ""
        if (!practice && !canAutoBuy(p)) { busyWith = "idle"; return }
        store.update(p.id) { it.copy(inProgress = true, status = if (practice) "PRACTICE CHECKOUT - starting" else "CHECKOUT - starting") }
        log("${p.label}: ${if (practice) "PRACTICE checkout" else "CHECKOUT"} started" + (price?.let { " (\$${money(it)}, cap \$${money(p.cap)})" } ?: ""))
        page.images(true)
        page.visited()                                  // (forget the pages before this checkout)
        var end = End.STOPPED; var why = ""; var order: String? = null; var paid: Double? = price
        var practiceFees: Map<String, Any?>? = null
        var submitted = false
        try {
            val b = page.ask(mapOf("bb" to "begin", "product" to p.forPage(), "price" to price, "hit" to now()), 10_000)
            if (b != null && b["ok"] == false && b["reason"] is String) { why = b["reason"] as String; return }
            var seen = 0L
            val until = now() + CHECKOUT_MS
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
            }
            End.PRACTICE_OK -> {
                val f = fees
                val txt = "Practice checkout reached Place your order and stopped (nothing bought)" +
                    (f?.let { " - item \$${money(it["base"].asDouble() ?: 0.0)}, tax \$${money(it["tax"].asDouble() ?: 0.0)}, shipping \$${money(it["shipping"].asDouble() ?: 0.0)}, total \$${money(it["total"].asDouble() ?: 0.0)}" } ?: "")
                store.update(p.id) { it.copy(inProgress = false, practiceOkAt = now(), status = "PRACTICE OK - reached Place your order") }
                log("${p.label}: $txt")
                alerts.practice(store.product(p.id) ?: p, txt)
            }
            End.UNCLEAR -> {
                store.update(p.id) { it.copy(inProgress = false, attempted = true, buy = false, status = "ORDER MAY HAVE BEEN PLACED - check Amazon") }
                log("! ${p.label}: order unclear - $why. Not bought again (press Re-arm after checking Amazon).")
                alerts.problem("${p.label}: order unclear - $why")
                store.addPurchase(Purchase(now(), p.asin, p.label, paid, null, false, "unclear - check Amazon"))
                alerts.needYou("Check your Amazon orders", "${p.label}: $why. Restalker will not buy it again until you press Re-arm.", p)
            }
            End.FAILED, End.STOPPED -> {
                val ep = p.episodeStart
                val fails = if (p.failEpisode == ep) p.fails + 1 else 1
                val lock = fails >= LOCK_AFTER
                store.update(p.id) {
                    it.copy(inProgress = false, attempted = it.attempted && !why.startsWith("ORDER NOT PLACED"),     // (Amazon said no order: may be tried again)
                        fails = fails, failEpisode = ep, lockedUntil = if (lock) now() + LOCK_MS else it.lockedUntil,
                        status = (if (end == End.FAILED) "NOT BOUGHT - " else "STOPPED - ") + why.take(160))
                }
                log("${p.label}: ${if (end == End.FAILED) "not bought" else "stopped"} - $why" + if (lock) " (stopped trying for ${LOCK_MS / 60_000} min after $fails tries)" else "")
                alerts.problem("${p.label}: ${if (end == End.FAILED) "not bought" else "stopped"} - $why")
                if (lock) alerts.needYou("Stopped trying", "${p.label}: $fails checkouts in a row did not finish (last: ${why.take(120)}). Trying again in ${LOCK_MS / 60_000} min.", p)
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
        const val ORDERS_URL = "https://www.amazon.com/gp/css/order-history"
        const val ORDERS_EVERY = 30 * 60_000L
        const val CHECKOUT_MS = 150_000L
        const val LOCK_AFTER = 3
        const val LOCK_MS = 10 * 60_000L
        val VERIFY_LOOKS = longArrayOf(5_000, 15_000, 40_000, 120_000)
        const val STANDBY_MS = 10 * 60_000L
        fun money(d: Double) = String.format(java.util.Locale.US, "%.2f", d)
        fun secs(ms: Long) = if (ms < 90_000) "in ${(ms + 999) / 1000} s" else "in ${(ms + 59_999) / 60_000} min"
    }
}
