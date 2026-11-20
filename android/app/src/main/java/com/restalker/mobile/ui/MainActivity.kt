package com.restalker.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.text.drawText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.restalker.mobile.BuildConfig
import com.restalker.mobile.DeskLink
import com.restalker.mobile.Diag
import com.restalker.mobile.Notifier
import com.restalker.mobile.Reporter
import com.restalker.mobile.RkApp
import com.restalker.mobile.WatchService
import com.restalker.mobile.CrowdNet
import com.restalker.mobile.Updater
import com.restalker.mobile.NtfyOut
import com.restalker.mobile.Relay
import com.restalker.mobile.core.APP_VERSION
import com.restalker.mobile.core.Asin
import com.restalker.mobile.core.CapStep
import com.restalker.mobile.core.Engine
import com.restalker.mobile.core.Groups
import com.restalker.mobile.core.MiniJson
import com.restalker.mobile.core.Product
import com.restalker.mobile.core.Pics
import com.restalker.mobile.core.PriceMarks
import com.restalker.mobile.core.RefreshTiming
import com.restalker.mobile.core.Settings
import com.restalker.mobile.core.Buys
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import com.restalker.mobile.core.Tcg
import com.restalker.mobile.core.asLong
import com.restalker.mobile.core.asStr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 1.4: the new look - four tabs at the bottom (Watch, Bought, Activity, Settings), a product screen, an "Add a product" sheet, and the
 *  desktop link (scan the desktop's QR code) in Settings. Everything the app did before is still here, only arranged for a phone. */
class MainActivity : ComponentActivity() {
    private val rev = mutableIntStateOf(0)
    private val bump: () -> Unit = { rev.intValue++ }
    private var shared = mutableStateOf<String?>(null)
    private var update = mutableStateOf<Pair<String, String>?>(null)
    private var reportResult = mutableStateOf<String?>(null)
    private var linkMsg = mutableStateOf<String?>(null)
    /** 1.6: "Report a problem" first asks what went wrong (optional) - your words go with the report. */
    private var asking = mutableStateOf(false)
    private fun report() { asking.value = true }
    private fun sendReport(note: String) {
        reportResult.value = "Sending..."
        val n = note.replace(Regex("\\s+"), " ").trim().take(1500)
        Reporter.send(this, if (n.isEmpty()) "sent by you" else "sent by you - your note: $n", n) { ok ->
            reportResult.value = if (!ok) "Could not send it (no internet?). Try again."
                // 1.0.4 (owner: "it should be a generic message to allow automatic reporting, and sending a manual report"): the same plain words for everyone;
                // the owner's devices also show their report code (it is how their reports are found)
                else if (Reporter.direct()) "Report sent.\nYour report code:\n${com.restalker.mobile.core.Report.code(RkApp.store())}"
                else "Report sent to the Restalker team. Problems are looked at once a day - for help sooner, ask in the Restalker Discord (Settings, Community)."
        }
    }
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { bump(); ticker.postDelayed(this, 5_000) } }

    // the desktop's QR code (the camera opens in a scanner screen; nothing is kept but the code)
    private val scanner = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { r -> r?.contents?.let { linkWith(it) } }
    private fun scan() {
        try {
            scanner.launch(com.journeyapps.barcodescanner.ScanOptions().setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                .setPrompt("Point at the QR code on the desktop (Link phone)").setBeepEnabled(false).setOrientationLocked(false))
        } catch (e: Exception) { linkMsg.value = "The camera could not be opened. Use \"Type the code\" instead." }
    }
    private fun linkWith(code: String) {
        linkMsg.value = "Linking..."
        Thread {                                  // (never on the screen's thread: a round may be talking to the network)
            val why = DeskLink.get().connect(code)
            runOnUiThread {
                linkMsg.value = why ?: "Linked with the desktop. Your products are shared within a minute."
                if (why == null) DeskLink.cycleAsync { bump() }
                bump()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RkApp.listeners.add(bump)
        sharedText(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (RkApp.store().settings().watching && WatchService.instance == null) try { WatchService.start(this) } catch (e: Exception) { }
        checkUpdate(explicit = false)                // (2.4: opening the app does not ask again within 6 hours)
        startLook()
        setContent { RkTheme { Screen() } }
    }

    /** 2.15 (owner: "When the app starts up, it should do an automatic 1 time check of all products so that the data is included as a
     *  base"): once each time the app starts, every product not looked at in the last 6 hours gets one look (its price and stock) - also
     *  the ones not watched (nothing is said, shared or bought for those). (The test build only when a test asks for it.) */
    private fun startLook() {
        if (lookedAtStart || (BuildConfig.DEBUG && !startLookInTests)) return
        lookedAtStart = true
        val now = System.currentTimeMillis()
        if (RkApp.store().products().none { now - it.lastCheck >= Engine.BASELINE_FRESH }) return
        try { WatchService.cmd(this, "baseline") } catch (e: Exception) { }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); sharedText(intent) }
    override fun onResume() {
        super.onResume(); shown = true; bump(); ticker.post(tick)
        // (back from "allow installs": go on at once - 2.4: only then, NOT after Android's install question closes, which also brings
        // the app back to the front: that asked again in a loop)
        if (update.value != null && Updater.state.contains("allow Restalker to install") && Updater.allowed(this)) checkUpdate(explicit = true)
        if (DeskLink.get().linked()) DeskLink.cycleAsync { bump() }          // (opening the app: the desktop's latest at once)
        Relay.heartbeat()                                                     // 2.15: the live "active now" count
        com.restalker.mobile.HealthRunner.tick(this)                          // 1.7: the self-check (a report by itself when something is wrong)
        try { CrowdNet.tick(force = true) } catch (e: Exception) { }          // 2.8: the contributor count and the central list's pictures, also when not watching
    }
    override fun onPause() { shown = false; ticker.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { RkApp.listeners.remove(bump); super.onDestroy() }

    private fun sharedText(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.let { t -> shared.value = Asin.firstLink(t) ?: t }
    }

    /** 1.7: the app updates itself (Updater); the screen shows what it is doing. */
    private fun checkUpdate(explicit: Boolean) = Thread {
        Updater.tick(this, force = true, explicit = explicit)
        for (k in 0 until 40) { Thread.sleep(500); if (Updater.available != null || Updater.state.isNotEmpty() && !Updater.state.startsWith("downloading")) break }
        runOnUiThread { update.value = Updater.available?.let { it.version to it.apk } }
    }.start()

    /** 1.0: this copy is a beta (newer than the version everyone has) - see Updater.beta. (The screen tests set [betaForTests].) */
    private fun isBeta(): Boolean = betaForTests ?: Updater.beta(this)
    /** 1.0.2: "1.0.2b" on a beta (newer than the version everyone has), else "1.0.2". */
    private fun shownVersion(): String = APP_VERSION + if (isBeta()) "b" else ""
    private fun signedIn() = CookieManager.getInstance().getCookie("https://www.amazon.com")?.let { it.contains("at-main=") || it.contains("x-main=") || it.contains("sess-at-main=") } == true
    private fun battOk() = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    private fun notesOk() = NotificationManagerCompat.from(this).areNotificationsEnabled()
    private fun openBrowser(url: String?) = startActivity(Intent(this, BrowserActivity::class.java).also { if (url != null) it.putExtra(BrowserActivity.EXTRA_URL, url) })
    private fun openNotifSettings() = startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName))
    // 1.0.7: the Google Play edition does not ask Android directly (the store restricts that request) - it opens Android's own list,
    // where you choose it yourself (BATTERY_HOW says how)
    private fun openBattery() = try {
        if (BuildConfig.PLAY) startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        else startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    } catch (e: Exception) {
        try { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        catch (e2: Exception) { startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }
    private fun setWatching(on: Boolean) {
        val store = RkApp.store()
        store.setSettings { it.copy(watching = on) }
        store.log("Watching switched ${if (on) "ON" else "OFF"}.")
        try { if (on) WatchService.start(this) else WatchService.stop(this) } catch (e: Exception) { store.log("! Could not start watching: ${e.message}") }
        Thread { try { DeskLink.get().touched() } catch (e: Exception) { } }.start()     // (2.0: the other phones / tablets learn whether this one may buy)
    }

    // =================================================================== the screen
    @Composable
    fun Screen() {
        val r = rev.intValue
        val store = RkApp.store()
        val st = remember(r) { store.settings() }
        val products = remember(r) { store.products() }
        // 2.3 (owner: "swipe left and right to change between watch, bought, activity, and settings")
        val pager = androidx.compose.foundation.pager.rememberPagerState(pageCount = { 4 })
        val scope = rememberCoroutineScope()
        val goTab: (Int) -> Unit = { i -> scope.launch { pager.animateScrollToPage(i) } }
        var adding by remember { mutableStateOf(false) }
        var openId by remember { mutableStateOf<String?>(null) }
        var editing by remember { mutableStateOf<Product?>(null) }
        var confirmReal by remember { mutableStateOf(false) }
        if (shared.value != null && !adding) adding = true
        val open = openId?.let { id -> products.firstOrNull { it.id == id } }
        BackHandler(enabled = open != null) { openId = null }

        Scaffold(containerColor = Look.Ground, bottomBar = {
            if (open == null) NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                listOf(Triple("Watch", Icons.Filled.Home, 0), Triple("Bought", Icons.Filled.ShoppingCart, 1), Triple("Activity", Icons.Filled.List, 2),
                    Triple("Settings", Icons.Filled.Settings, 3)).forEach { (label, icon, i) ->
                    // 2.15: a red mark on the tab when something there needs you
                    val mark = (i == 0 && attnOn("watch")) || (i == 3 && (attnOn("amazon") || attnOn("notes") || attnOn("phone")))
                    NavigationBarItem(selected = pager.currentPage == i, onClick = { goTab(i) }, icon = {
                        if (mark) BadgedBox(badge = { Badge(containerColor = Look.AttnRed, modifier = Modifier.testTag("tabattn$i")) }) { Icon(icon, contentDescription = null) }
                        else Icon(icon, contentDescription = null) },
                        label = { Text(label, maxLines = 1, fontSize = 12.sp) }, modifier = Modifier.testTag("tab$i"),
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Look.Navy, selectedTextColor = Look.Navy, indicatorColor = Look.AccentSoft,
                            unselectedIconColor = Look.Muted, unselectedTextColor = Look.Muted))
                }
            }
        }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (open != null) ProductRefresh(open, r) { ProductScreen(open, r, onBack = { openId = null }, onEdit = { editing = it }) }
                else androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize().testTag("pager")) { page ->
                    // 2.3 (owner: "swipe down and refresh the page ... This should not be a way to refresh all products at once"): pulling
                    // down reads the PC link and looks for an update - products are looked at with "Check all now" (Watch) instead
                    Refreshable("refresh-$page") {
                        when (page) {
                            0 -> WatchTab(st, products, r, onOpen = { openId = it.id }, onAdd = { adding = true }, onSettings = { goTab(3) })
                            1 -> BoughtTab(r)
                            2 -> ActivityTab(r)
                            else -> SettingsTab(st, r, onRealAsk = { confirmReal = true })
                        }
                    }
                }
            }
        }
        if (adding) AddSheet(shared.value, onDone = { adding = false; shared.value = null })
        editing?.let { p -> CapDialog(p) { editing = null } }
        if (asking.value) {
            var note by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { asking.value = false }, title = { Text("Report a problem") }, text = {
                Column {
                    Text("What went wrong? (optional - the app's log goes with it)", fontSize = 13.sp)
                    OutlinedTextField(note, { note = it }, minLines = 3, maxLines = 6, placeholder = { Text("For example: no pictures on the Hobbit box") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("report-note"))
                }
            }, confirmButton = { Button(colors = Look.btn(), onClick = { asking.value = false; sendReport(note) }, modifier = Modifier.testTag("report-send")) { Text("Send") } },
                dismissButton = { TextButton(onClick = { asking.value = false }) { Text("Cancel") } })
        }
        reportResult.value?.let { msg ->
            AlertDialog(onDismissRequest = { reportResult.value = null }, title = { Text("Report problem") },
                text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(msg, modifier = Modifier.testTag("report-msg")) } },
                confirmButton = {
                    Row {
                        if (msg.contains("rkr-")) TextButton(onClick = {
                            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Restalker report code", com.restalker.mobile.core.Report.code(RkApp.store())))
                        }) { Text("Copy code") }
                        TextButton(onClick = { reportResult.value = null }) { Text("OK") }
                    }
                })
        }
        linkMsg.value?.let { m ->
            AlertDialog(onDismissRequest = { linkMsg.value = null }, title = { Text("Desktop link") }, text = { Text(m, modifier = Modifier.testTag("link-msg")) },
                confirmButton = { TextButton(onClick = { linkMsg.value = null }) { Text("OK") } })
        }
        if (confirmReal) AlertDialog(onDismissRequest = { confirmReal = false }, title = { Text("Make real purchases?") },
            text = { Text("Products set to Buy are then really ordered on Amazon when they are in stock at or under their cap. Practice (off) stops every checkout right before \"Place your order\".") },
            confirmButton = { Button(colors = Look.btn(), onClick = {
                store.setSettings { it.copy(practice = false) }; store.log("Practice mode switched OFF (real purchases)."); confirmReal = false
            }, modifier = Modifier.testTag("confirm-real")) { Text("Yes, real purchases") } },
            dismissButton = { TextButton(onClick = { confirmReal = false }) { Text("Keep practicing") } })
    }

    /** 2.3: pull down to refresh: the PC link is read, an update is looked for, the screen is redrawn.
     *  2.12 (owner: "the little arrow should be animated and continue rotating. When it stops currently, it gives the impression that the
     *  app is frozen. It should also disappear when the refresh is complete (it currently doesn't go away until I switch to a different
     *  tab)"): 2.3-2.11 only STARTED the link round and the update check (both run on their own threads) and said "done" a moment later -
     *  so quickly that the screen never saw the refresh begin: the arrow stayed where the finger left it, frozen, until the page was left.
     *  Now the turning circle stays until the link round and the update check have really finished (at least 0.8 s so it is seen turning,
     *  at most 15 s), then goes away by itself. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Refreshable(tag: String, content: @Composable () -> Unit) {
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        PullToRefreshBox(isRefreshing = busy, onRefresh = {
            if (!busy) { busy = true; scope.launch {
                val t0 = System.currentTimeMillis()
                var linkDone = true
                try { if (DeskLink.get().linked()) { linkDone = false; DeskLink.cycleAsync { linkDone = true } } } catch (e: Exception) { linkDone = true }
                try { Updater.tick(this@MainActivity, force = true) } catch (e: Exception) { }
                while (true) {
                    val el = System.currentTimeMillis() - t0
                    val working = (!linkDone && DeskLink.running) || Updater.running
                    if (el >= RefreshTiming.MAX_MS || (el >= RefreshTiming.MIN_MS && !working)) break
                    kotlinx.coroutines.delay(100)
                }
                busy = false; bump()
            } }
        }, modifier = Modifier.fillMaxSize().testTag(tag)) {
            content()
            if (busy) Box(Modifier.size(1.dp).testTag("busy-$tag"))          // (for the screen test: the refresh is running)
        }
    }

    /** 2.3 (owner: "For each individual product page, there should be an option to swipe down and refresh all pages for that item,
     *  essentially forcing a new check"): pulling down looks at this product's Amazon page now (like Check now) and reads the PC link
     *  (the other stores' latest). The circle stays until that look is done (at most a minute). */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ProductRefresh(p: Product, r: Int, content: @Composable () -> Unit) {
        var since by remember(p.id) { mutableLongStateOf(0L) }
        // (2.12: shown turning for at least 0.8 s - a quick look never leaves a frozen arrow behind)
        var minUntil by remember(p.id) { mutableLongStateOf(0L) }
        LaunchedEffect(minUntil) { if (minUntil > 0) { kotlinx.coroutines.delay(RefreshTiming.MIN_MS); minUntil = 0L } }
        val busy = minUntil > 0 || (since > 0 && p.lastCheck < since && System.currentTimeMillis() - since < 60_000)
        LaunchedEffect(since) { if (since > 0) { kotlinx.coroutines.delay(60_000); bump() } }
        PullToRefreshBox(isRefreshing = busy, onRefresh = {
            since = System.currentTimeMillis(); minUntil = since
            WatchService.cmd(this@MainActivity, "check", p.id)
            try { if (DeskLink.get().linked()) DeskLink.cycleAsync { bump() } } catch (e: Exception) { }
        }, modifier = Modifier.fillMaxSize().testTag("prefresh-${p.asin}")) { content() }
    }

    /** 2.3: the words for a device's buying choice (chips and lines). */
    private fun modeChip(m: String) = when (m) { "phone" -> "Instead of PC"; "both" -> "Both buy"; else -> "PC only" }
    private fun modeLine(m: String, fo: Boolean) = when (m) {
        "phone" -> "Buys on Amazon instead of the PC (the PC keeps watching and tells it at once)."
        "both" -> "Buys too, alongside the PC - never two orders."
        else -> "Watches only, does not buy" + if (fo) " - unless the PC stops." else "."
    }
    private fun agoS(sec: Long) = when {
        sec < 90 -> "just now"; sec < 3600 -> "${sec / 60} min ago"; sec < 2 * 86400 -> "${sec / 3600} h ago"; else -> "${sec / 86400} days ago" }

    /** 2.3: Settings - the PC, this device and every other linked phone / tablet; each one's buying choice can be changed here. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun LinkedDevices(st: com.restalker.mobile.core.Settings, r: Int, inSection: Boolean = false) {
        val store = RkApp.store()
        val list = remember(r, st) { try { DeskLink.get().devices(DeskLink.name()) } catch (e: Exception) { emptyList() } }
        var renaming by remember { mutableStateOf(false) }
        // 1.0.4 (owner, 2026-11-20: "it's showing old data for my pc and tablet. Both of these devices are currently active, so there may
        // be a communication problem"): "heard" is now the newest sign of life (a post on the link, or the device's beat on the Restalker
        // relay every 15 minutes), and when the link itself is behind this says so - and why, when the message service told us
        val nowMs = System.currentTimeMillis()
        val behind = remember(r) { try { DeskLink.get().pcSyncBehindS() } catch (e: Exception) { -1L } }
        val quota = remember(r) { com.restalker.mobile.core.NtfyQuota.current(com.restalker.mobile.Quota.q, nowMs) }
        // 1.0.6: the link runs on the Restalker relay - the day's free messages matter only while the relay is not reached, or for a
        // linked device that still runs a version from before (it reads only the message service)
        val viaRelay = remember(r) { try { DeskLink.get().relayOk } catch (e: Exception) { false } }
        val oldOnes = remember(r) { try { DeskLink.get().onlyNtfy } catch (e: Exception) { false } }
        val usedUp = quota != null && quota.left <= 0 && (!viaRelay || oldOnes)
        Column(Modifier.fillMaxWidth().then(if (inSection) Modifier else Modifier.background(Color.White, Look.card)).padding(16.dp).testTag("devices")) {
            if (!inSection) Text("Linked devices", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("Each phone or tablet has its own buying choice. Changing one here changes only that device.",
                fontSize = 12.sp, color = Look.Muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
            if (behind >= 0 || usedUp) Text(
                (if (usedUp) "Today's free messages for this internet connection are used up (${quota!!.limit} of ${quota.limit})" +
                    (if (quota.resetAt > nowMs) " - they start again at ${hm(quota.resetAt)}" else "") +
                    (if (viaRelay) ". Until then product changes do not reach a linked device that still runs an older version (once it has updated itself it no longer needs them); your other devices are not affected. "
                     else ". Until then your devices cannot send each other product changes. ")
                else "The PC is running, but its product updates are not arriving here (last ${com.restalker.mobile.core.Ago.words(behind * 1000)}). ") +
                    "Watching and buying go on: each device looks for itself, and the shared checkout lock still makes sure only one of them orders.",
                fontSize = 12.sp, lineHeight = 16.sp, color = Look.AmberText,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().background(Look.AmberSoft, RoundedCornerShape(10.dp)).padding(10.dp).testTag("link-health"))
            for (d in list) {
                HorizontalDivider(color = Look.Line, modifier = Modifier.padding(vertical = 10.dp))
                Column(Modifier.testTag(if (d.me) "dev-me" else if (d.pc) "dev-pc" else "dev-${d.id}")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name + if (d.me) " (this device)" else "", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (d.me) TextButton(onClick = { renaming = true }, modifier = Modifier.testTag("dev-rename")) { Text("Rename") }
                    }
                    // (1.0.4: running = heard within 35 minutes - a device says "I am here" every 15)
                    val alive = d.agoS < com.restalker.mobile.core.Link.PRES_FRESH.toLong()
                    Text(when {
                        d.me -> "Version ${shownVersion()}"
                        else -> listOfNotNull(d.vn.takeIf { it.isNotBlank() }?.let { "Version $it" }, (if (alive) "running · " else "") + "heard ${agoS(d.agoS)}").joinToString(" · ")
                    }, fontSize = 12.sp, color = if (d.me || alive) Look.Muted else Look.AmberText, modifier = Modifier.testTag(if (d.me) "dev-heard-me" else if (d.pc) "dev-heard-pc" else "dev-heard-${d.id}"))
                    if (d.pc) {
                        val handed = try { DeskLink.get().pcHanded() } catch (e: Exception) { false }
                        Text(if (handed) "Does not buy on Amazon now: a device below is set to Instead of PC. It keeps watching every store."
                            else "Buys on Amazon, and watches every store.", fontSize = 12.sp, color = Look.Ink, lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 2.dp).testTag("dev-pc-line"))
                    } else if (d.mode.isEmpty()) {
                        // (1.0.4: known by its beat only - its own first update has not come over the link yet)
                        Text("Linked. Its buying choice shows here once its first update has arrived.", fontSize = 12.sp, color = Look.Ink, lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 2.dp).testTag("dev-line-${d.id}"))
                    } else {
                        Text(modeLine(d.mode, d.failover), fontSize = 12.sp, color = Look.Ink, lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 2.dp).testTag("dev-line-${if (d.me) "me" else d.id}"))
                        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("pc", "both", "phone").forEach { m ->
                                FilterChip(d.mode == m, { if (d.mode != m) setMode(d, m, d.failover) }, { Text(modeChip(m)) },
                                    Modifier.heightIn(min = 44.dp).testTag(if (d.me) "bm-$m" else "bm-${d.id}-$m"))
                            }
                        }
                        if (d.mode == "pc") Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Buy if the PC stops", fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Switch(d.failover, { v -> setMode(d, d.mode, v) }, Modifier.testTag(if (d.me) "fo-me" else "fo-${d.id}"), colors = Look.sw())
                        }
                        if (!d.me) Text("The change reaches it the next time it reads the link (a few minutes; at once if it is open).",
                            fontSize = 11.sp, color = Look.Muted)
                    }
                }
            }
        }
        if (renaming) {
            var t by remember { mutableStateOf(DeskLink.name()) }
            AlertDialog(onDismissRequest = { renaming = false }, title = { Text("This device's name") },
                text = { OutlinedTextField(t, { t = it.take(40) }, singleLine = true, modifier = Modifier.testTag("dev-name")) },
                confirmButton = { TextButton(onClick = {
                    renaming = false
                    store.setSettings { it.copy(deviceName = t.trim()) }
                    Thread { try { DeskLink.get().touched() } catch (e: Exception) { }; DeskLink.cycleAsync { bump() } }.start()
                }, modifier = Modifier.testTag("dev-name-ok")) { Text("Save") } },
                dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } })
        }
    }

    /** 2.3: a device's buying choice: this device - saved here; another - sent to it over the link (it applies it itself). */
    private fun setMode(d: com.restalker.mobile.core.Link.Device, m: String, fo: Boolean) {
        val store = RkApp.store()
        if (d.me) {
            store.setSettings { it.copy(buyMode = m, buyModeAt = System.currentTimeMillis(), failover = fo) }
            store.log("This device: ${modeChip(m)}" + (if (m == "pc") (if (fo) ", buys if the PC stops" else ", never buys") else "") + " (chosen here).")
            Thread { try { DeskLink.get().touched() } catch (e: Exception) { }; DeskLink.cycleAsync { bump() } }.start()
            com.restalker.mobile.GoListener.check()
            WatchService.instance?.standbyCheck()
            WatchService.instance?.engine?.send(Engine.Cmd.Wake)
        } else {
            store.log("${d.name}: set to ${modeChip(m)}" + (if (m == "pc") (if (fo) ", buys if the PC stops" else ", never buys") else "") + " (from this device).")
            Thread { val ok = try { DeskLink.get().setFor(d.id, m, fo) } catch (e: Exception) { false }
                runOnUiThread { if (!ok) store.log("${d.name}: the change could not be sent yet - it goes with the next link round.", quiet = true); bump() } }.start()
        }
        bump()
    }

    // =================================================================== Watch (home)
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    fun WatchTab(st: Settings, products: List<Product>, r: Int, onOpen: (Product) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit) {
        val store = RkApp.store()
        val signed = remember(r) { signedIn() }
        val notes = remember(r) { notesOk() }
        val svc = WatchService.instance
        val now = System.currentTimeMillis()
        val (linked, linkLine) = remember(r) { DeskLink.get().state() }
        val deskBuys = remember(r) { DeskLink.standsBy() }
        // 2.9 (owner: "purchased x times adjacent to each product picture for quick reference"): every linked device's purchases
        val allBuys = remember(r) { try { DeskLink.get().buys(DeskLink.name()) } catch (e: Exception) { emptyList() } }
        val boughtN = { p: Product -> try { Buys.of(allBuys, p.asin, p.label).size } catch (e: Exception) { 0 } }
        LazyColumn(Modifier.fillMaxSize().testTag("list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.fillMaxWidth().background(Look.Navy).padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 2.11 (owner: "I want to incorporate the logo into the app and not just as the icon to open")
                        // 1.0.4 (owner: "Make the restalker icon slightly bigger, maybe 30%-50%"): 72 instead of 48 where it fits (logoDp)
                        Logo(logoDp(), "logo")
                        Column(Modifier.weight(1f)) {
                            // 1.0 (owner: "My version of the app should clearly distinguish itself as "beta" whereas all other versions
                            // should be standard"): an amber BETA mark right after the version while this copy is newer than the one
                            // everyone has; a copy everyone has shows only its version
                            // 1.0.2 (owner: "For beta versions, let's use a "b" at the end of the version number ... This way when we actually
                            // release a full version, we aren't confusing versions"): a beta shows "v1.0.2b"; the same copy, once released to
                            // everyone, shows "v1.0.2" - nothing is renumbered
                            Text("Restalker Mobile v" + shownVersion(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 25.sp, maxLines = 3,
                                modifier = Modifier.testTag("title"))
                            // 2.6 (owner: "small text ... the total number of workers (individual devices contributing) ... XXX total
                            // contributors and counting")
                            // 2.15 (owner: "This number should be dynamic and tied directly to the total active number of devices working"):
                            // the relay's count of devices heard in the last 30 minutes (each working device checks in every 15 minutes)
                            val crowdN = remember(r) { CrowdNet.count() }
                            val activeN = remember(r) { Relay.active }
                            Text(if (!st.crowd) "Crowd sharing is off (Settings)" else if (activeN > 0) com.restalker.mobile.core.Crowd.activeWords(activeN)
                                else if (crowdN > 1) "$crowdN total contributors and counting" else "Joining the Restalker crowd",
                                color = Look.OnNavyDim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp).testTag("contributors"))
                        }
                        Button(onClick = onAdd, colors = Look.btn(),
                            contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.heightIn(min = 44.dp).testTag("add")) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                            Text("Add", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    // 1.0.4 (owner, 2026-11-20: "There should be an option to start/stop the app from running on the watch page. Right now
                    // it's unclear if I swipe the app away if it continues to watch in the background"): the little switch became a
                    // Stop / Start button that says what it does, and the line under it says what happens when the app is closed
                    Column(Modifier.fillMaxWidth().background(Look.NavyCard, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                        val inHours = remember(r) { com.restalker.mobile.core.WatchHours.inside(st, System.currentTimeMillis()) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                val watched = products.count { it.watch }
                                Text(if (!st.watching) "Not watching" else if (!inHours) "Waiting for your watch hours" else "Watching $watched product${if (watched == 1) "" else "s"}", color = Color.White,
                                    fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.testTag("watch-title"))
                                val line = if (!st.watching) "Press Start to watch" else svc?.notifier?.lastStatus?.ifBlank { null } ?: "starting..."
                                Text(line, color = Look.OnNavyDim, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 3, modifier = Modifier.testTag("status"))
                                if (linked) Text(if (deskBuys) "Desktop linked · this device watches (Settings, Linked devices)" else "Desktop linked · this device buys", color = Look.Mint, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp).testTag("linkchip"))
                            }
                            Button(onClick = { setWatching(!st.watching) },
                                colors = if (st.watching) ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Look.Ink) else Look.btn(),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                modifier = Modifier.padding(start = 8.dp).heightIn(min = 44.dp).semantics { stateDescription = if (st.watching) "on" else "off" }.testTag("watching")) {
                                Icon(if (st.watching) Icons.Filled.Close else Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                                Text(if (st.watching) "Stop" else "Start", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                        Text(if (st.watching) "Runs in the background - also after you close the app or swipe it away. Stop ends it."
                            else "Stopped: nothing is watched or bought until you press Start.",
                            color = Look.OnNavyDim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp).testTag("watch-hint"))
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    // 1.7: updates install by themselves; you are only asked for what Android needs from you (once)
                    update.value?.let { (v, apk) ->
                        when {
                            !st.autoUpdate -> Banner("Update available: Restalker Mobile v$v", "Get it", "update") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apk))) }
                            !Updater.allowed(this@MainActivity) -> Banner("Update v$v is ready. Let Restalker install its own updates (once) - then you never download again.", "Allow", "update-allow") {
                                try { startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                                catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apk))) } }
                            else -> Banner("Updating to v$v by itself: ${Updater.state.ifBlank { "starting" }}", "Update now", "update-now") { Updater.tick(this@MainActivity, force = true, explicit = true) }
                        }
                    }
                    if (st.signinNeededAt > 0 && now - st.signinNeededAt < 3_600_000L) Banner("Amazon wants you to sign in again. Nothing is bought until you do.", "Sign in", "signin-banner") { openBrowser(BrowserActivity.SIGNIN_URL) }
                    else if (products.any { it.buy } && st.buying && !signed) Banner("Products are set to Buy, but Amazon is not signed in.", "Sign in", "signin-banner") { openBrowser(BrowserActivity.SIGNIN_URL) }
                    // 1.0.7, the Google Play edition (the store's rule: say what is sent and ask first): the one question about problem
                    // reports. Until it is answered none is sent by itself; the answer can be changed in Settings, Help and about.
                    if (BuildConfig.PLAY && !st.reportAsked) Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color.White, Look.card).padding(14.dp).testTag("report-ask")) {
                        Text("Send problem reports automatically?", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("When something goes wrong, Restalker can send a report to its makers so it gets fixed. It holds $REPORT_WHAT. " +
                            "You can change this any time in Settings, Help and about.", fontSize = 13.sp, lineHeight = 18.sp, color = Look.Muted, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { RkApp.store().setSettings { it.copy(autoReport = true, reportAsked = true) }; RkApp.store().log("Automatic problem reports: you said yes.") },
                                modifier = Modifier.testTag("report-yes")) { Text("Yes, send them") }
                            OutlinedButton(onClick = { RkApp.store().setSettings { it.copy(autoReport = false, reportAsked = true) }; RkApp.store().log("Automatic problem reports: you said no.") },
                                modifier = Modifier.testTag("report-no")) { Text("No") }
                        }
                    }
                    if (!notes) Banner("Notifications are off - you won't hear about restocks or purchases.", "Fix", "notes-banner") { openNotifSettings() }
                    if (svc != null && !svc.web.available) Banner("This phone's \"Android System WebView\" is too old - update it in the Play Store.", "Update", "webview-banner") {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview"))) } catch (e: Exception) { } }
                    if (st.cooldownUntil > now) Banner("Resting: Amazon showed a robot check. Open the browser and finish it yourself; it looks again at ${hm(st.cooldownUntil)}.", "Browser", "rest-banner") { openBrowser(null) }
                    if (st.snoozeUntil > now) Banner("Restock messages snoozed until ${hm(st.snoozeUntil)}.", "Unsnooze", "snooze-banner") { store.setSettings { it.copy(snoozeUntil = 0) }; store.log("Snooze ended by you.") }
                    if (st.practice && products.any { it.buy } && st.buying) Text("Practice is on: checkouts stop before \"Place your order\" (Settings → Real purchases).",
                        color = Look.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                    SetupCard(products, r, onAdd, onSettings)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Text("Products", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Text("${products.size}" + (products.count { it.buy }.takeIf { it > 0 }?.let { " · $it set to buy" } ?: ""), color = Look.Muted, fontSize = 13.sp)
                }
                // 2.3 (owner: "a button to [refresh all products at once] would be good"): every watched product is looked at now, one after another
                if (products.any { it.watch }) {
                    var allAt by remember { mutableLongStateOf(0L) }
                    val all = working(allAt, 300_000) { RkApp.store().products().filter { it.watch }.all { it.lastCheck >= allAt } }
                    TextButton(onClick = {
                        if (!all) { allAt = System.currentTimeMillis(); WatchService.cmd(this@MainActivity, "checkall"); store.log("Check all now: every watched product is looked at now.", quiet = true) }
                    }, modifier = Modifier.padding(start = 8.dp).testTag("checkall")) {
                        if (all) Spinner("busy-checkall")
                        Text(if (all) "Checking ${products.count { it.watch }} products..." else "Check all now (${products.count { it.watch }})")
                    }
                }
                if (products.isEmpty()) MascotNote("No products yet. Tap Add, or open a product in the Amazon app, tap Share and choose Restalker." +
                    if (!linked) " Or link the desktop (Settings) to get its products." else "")
                else Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "In stock", "Set to buy", "Watched").forEachIndexed { i, l ->
                        FilterChip(filter.intValue == i, { filter.intValue = i }, { Text(l) }, modifier = Modifier.testTag("filter$i"),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Look.Accent, selectedLabelColor = Look.Ink))   // (2.11: amber)
                    }
                }
            }
            // 1.4 (owner: "The desktop version had several collapsible groups, and can easily be scrolled"): the desktop's groups -
            // release year, then set - each folds with a tap (remembered); what is in stock now stays on top
            // 1.5 (owner: "the app does not show anything in stock when sorted. There are several items in stock, just above the cap" -
            // and: "an additional filter in the app for watched (separate from armed)"): In stock = for sale at ANY price (the phone's own
            // look or the linked desktop's, whichever is newer); Watched = watched, whether set to buy or not
            // 1.0.4 (owner, 2026-11-20: "it shows items in stock though they aren't watched ... there should be an indicator of how old the
            // data is"): "in stock NOW" = seen for sale in the last 6 hours (Product.hot). A product that is not watched is looked at once
            // when the app starts, or by the linked PC - an older sighting stays on its card with its age, but is not "now"
            val shown = products.filter { when (filter.intValue) { 1 -> it.hot(now) && !it.purchased; 2 -> it.buy; 3 -> it.watch; else -> true } }
            val hot = shown.filter { it.hot(now) && !it.purchased }
            if (filter.intValue == 0 && hot.isNotEmpty()) {
                // 1.0 (owner: '"Instock now" should be a collapsible field like the others'): it folds with a tap and stays folded, like the
                // years (its count stays on the bar, and every year's bar still says how many of its products are in stock)
                val hotFolded = "hot" in folded
                stickyHeader(key = "hdr-hot") { GroupHeader("In stock now", hot.size, null, hotFolded, "grp-hot", accent = true) { fold("hot") } }
                if (!hotFolded) items(hot, key = { "hot-" + it.id }) { p -> ProductCard(p, deskBuys, "top-${p.asin}", boughtN(p)) { onOpen(p) } }
            }
            if (products.isNotEmpty() && shown.isEmpty()) item { MascotNote(when (filter.intValue) { 1 -> "Nothing is in stock right now - still watching."; 2 -> "No product is set to buy."; else -> "No product is watched." }) }
            val years = shown.groupBy { Groups.of(it).first }.toList().sortedBy { Groups.yearRank(it.first) }
            for ((year, inYear) in years) {
                val yKey = "y:$year"
                stickyHeader(key = yKey) { GroupHeader(year, inYear.size, inYear.count { it.hot(now) && !it.purchased }, yKey in folded, "grp-$year", sub = groupWords(inYear)) { fold(yKey) } }
                if (yKey in folded) continue
                for ((set, inSet) in inYear.groupBy { Groups.of(it).second }.toList().sortedBy { it.first.lowercase() }) {
                    val sKey = "s:$year|$set"
                    if (inYear.size > 1 || set != year) item(key = sKey) { SetHeader(set, inSet.size, sKey in folded, "set-$set") { fold(sKey) } }
                    if (sKey in folded) continue
                    items(inSet, key = { it.id }) { p -> ProductCard(p, deskBuys, bought = boughtN(p)) { onOpen(p) } }
                }
            }
        }
    }

    // the folded groups (kept in the app's folder, so they stay folded)
    private val folded = mutableStateListOf<String>().apply {
        try { MiniJson.read(RkApp.store().readFile("folded.json") ?: "[]").let { (it as? List<*>)?.mapNotNull { x -> x as? String }?.let { l -> addAll(l) } } } catch (e: Exception) { }
    }
    private val filter = mutableIntStateOf(0)
    /** [liveDays] (Activity): the days in the log now - folded days that are gone are forgotten (a date comes round again next year). */
    private fun fold(key: String, liveDays: List<String>? = null) {
        if (key in folded) folded.remove(key) else folded.add(key)
        if (liveDays != null) { val gone = folded.filter { it.startsWith("day:") && it !in liveDays }; if (gone.isNotEmpty()) folded.removeAll(gone) }
        try { RkApp.store().writeFile("folded.json", MiniJson.write(folded.toList())) } catch (e: Exception) { }
    }

    /** 2.15 (owner chose look B, 2026-11-19): every year (and "In stock now") is a navy bar across the screen with an amber button; it
     *  stays pinned at the top while its products scroll under it, so the brand colours never scroll away.
     *  1.0 (owner: "navy being primary features and amber being secondary. Match the other tabs and options"): the same bar on every tab -
     *  the years and "In stock now" (Watch), the groups of Bought, the days of Activity - and every one folds with a tap. [accent]: an
     *  amber title ("In stock now"); [hot]: how many of the group are in stock (a green chip); [extra]: a note on the right (a total). */
    @Composable
    fun GroupHeader(title: String, count: Int, hot: Int?, isFolded: Boolean, tag: String, accent: Boolean = false, extra: String? = null, sub: String? = null, onClick: () -> Unit) {
        Box(Modifier.fillMaxWidth().background(Look.Ground).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Look.Navy).clickable(onClick = onClick)
                .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp).heightIn(min = 30.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp, color = if (accent) Look.OnNavyAccent else Color.White,
                            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))   // (3: a product's name, also with big text)
                        if (sub == null) Text("  $count", color = Look.OnNavyDim, fontSize = 13.sp, maxLines = 1)
                        if (hot != null && hot > 0) Chip("$hot in stock", Look.GoodSoft, Look.GoodText, Modifier.padding(start = 8.dp))
                    }
                    // 1.0.2 (owner: "add a very small amount of text to explain to the user. Something like "2027 - 2 items watched - 1 item
                    // tracked""): what the group holds, in words
                    if (sub != null) Text(sub, color = Look.OnNavyDim, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, modifier = Modifier.padding(top = 1.dp).testTag("$tag-sub"))
                }
                if (extra != null) Text(extra, color = Look.OnNavyDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                Box(Modifier.padding(start = 8.dp).size(28.dp).background(Look.Accent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (isFolded) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, contentDescription = if (isFolded) "Show" else "Hide", tint = Look.Ink, modifier = Modifier.size(20.dp)) }
            }
        }
    }

    /** 1.0.2: "11 products · 4 watched · 2 set to buy" (the parts that are 0 left out, but the products always said). */
    private fun groupWords(ps: List<Product>): String {
        val w = ps.count { it.watch }; val b = ps.count { it.buy }
        return listOfNotNull("${ps.size} product${if (ps.size == 1) "" else "s"}", "$w watched".takeIf { w > 0 }, "$b set to buy".takeIf { b > 0 },
            "none watched".takeIf { w == 0 }).joinToString(" · ")
    }

    /** 2.15 (look B): every set is a white pill with an amber outline. */
    @Composable
    fun SetHeader(title: String, count: Int, isFolded: Boolean, tag: String, onClick: () -> Unit) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White)
            .border(2.dp, Look.Accent, RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(start = 14.dp, end = 10.dp, top = 5.dp, bottom = 5.dp)
            .heightIn(min = 26.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Look.GreyText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("$count", color = Look.Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
            Icon(if (isFolded) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, contentDescription = if (isFolded) "Show" else "Hide",
                tint = Look.Ink, modifier = Modifier.size(20.dp))
        }
    }

    /** 2.11 (owner: "The hat and eyes can become our mascot for branding throughout (I dont want to overdo it, but I want it to be
     *  recognizable)"): the mascot beside the "nothing here" notes (no products, nothing in stock, nothing bought) and in About. */
    @Composable
    fun MascotNote(text: String) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).testTag("mascot-note"), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.restalker.mobile.R.drawable.ic_mascot), contentDescription = null, modifier = Modifier.size(44.dp))
            Text(text, color = Look.Muted, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 12.dp))
        }
    }

    @Composable
    fun SetupCard(products: List<Product>, r: Int, onAdd: () -> Unit, onSettings: () -> Unit) {
        val notes = remember(r) { notesOk() }; val signed = remember(r) { signedIn() }; val batt = remember(r) { battOk() }
        val practiced = products.any { it.practiceOkAt > 0 } || DeskLink.get().linked()
        if (notes && signed && batt && products.isNotEmpty() && practiced) return
        Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(Color.White, Look.card).padding(16.dp).testTag("setup")) {
            Text("Getting started", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Step(notes, "Allow notifications", "Allow") { openNotifSettings() }
            Step(signed, "Sign in to Amazon in the app", "Sign in") { openBrowser(BrowserActivity.SIGNIN_URL) }
            Step(batt, "Let it keep running in the background (battery)", "Allow") { openBattery() }
            Step(products.isNotEmpty(), "Add a product, or link the desktop", "Add") { onAdd() }
            Step(practiced, "Run one Practice checkout (Settings, Amazon, buying and this phone)", null) {}
            if (!batt && BuildConfig.PLAY) Text(BATTERY_HOW, fontSize = 12.sp, color = Look.Muted, modifier = Modifier.testTag("battery-how"))
            if (!batt) Text("Samsung / Xiaomi / OnePlus: also set Restalker to \"Unrestricted\" in the phone's battery settings.", fontSize = 12.sp, color = Look.Muted)
        }
    }

    /** 1.0.4: one time of the watch hours - the time, and half an hour earlier / later. */
    @Composable
    fun HourRow(label: String, min: Int, tag: String, set: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 12.sp, color = Look.Muted)
                Text(com.restalker.mobile.core.WatchHours.label(min), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.testTag("$tag-now"))
            }
            OutlinedButton(onClick = { set(com.restalker.mobile.core.WatchHours.step(min, -30)) }, contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.heightIn(min = 44.dp).testTag("$tag-minus")) { Text("Earlier", fontSize = 13.sp, maxLines = 1) }
            OutlinedButton(onClick = { set(com.restalker.mobile.core.WatchHours.step(min, 30)) }, contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.padding(start = 6.dp).heightIn(min = 44.dp).testTag("$tag-plus")) { Text("Later", fontSize = 13.sp, maxLines = 1) }
        }
    }

    @Composable
    fun Step(done: Boolean, text: String, button: String?, onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
            Text(if (done) "✓" else "•", color = if (done) Look.Good else Look.Muted, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
            Text(text, fontSize = 14.sp, modifier = Modifier.weight(1f), color = if (done) Look.Muted else Look.Ink)
            if (!done && button != null) TextButton(onClick = onClick) { Text(button) }
        }
    }

    /** The product's state in one chip: (text, background, text color). */
    private fun chip(p: Product): Triple<String, Color, Color> {
        val (st, pr, _) = p.stock()
        val money = pr?.let { " · \$${Engine.money(it)}" } ?: ""
        // 1.0.4: seen for sale longer ago than 6 hours - said as the past, in grey (its age is on the line below)
        val old = p.inStock && !p.hot(System.currentTimeMillis())
        return when {
            p.purchased && p.qty <= 1 -> Triple("Bought", Look.TealSoft, Look.TealText)
            old -> Triple((if (st == "in") "Was in stock" else "Was over cap") + money, Look.GreySoft, Look.GreyText)
            st == "in" -> Triple("In stock$money", Look.TealSoft, Look.TealText)
            st == "over" -> Triple("Over cap$money", Look.OrangeSoft, Look.OrangeText)
            st == "out" -> Triple("Out of stock", Look.GreySoft, Look.GreyText)
            p.lastCheck == 0L -> Triple("Not checked yet", Look.GreySoft, Look.GreyText)
            else -> Triple(p.status.take(28), Look.GreySoft, Look.GreyText)
        }
    }
    /** "checked 30s ago" - by this phone, or "seen by the desktop 2 min ago" when the desktop's look is the newer one. */
    private fun seenText(p: Product): String {
        val (st, _, at) = p.stock()
        if (at == 0L || st.isEmpty()) return ""
        return if (at == p.dAt && p.dAt > p.lastCheck) "desktop saw it ${ago(at)}" else "checked ${ago(at)}"
    }
    private fun qtyText(p: Product): String = if (p.qty <= 1) "" else {
        val n = RkApp.store().purchases().count { it.asin == p.asin && it.at >= p.qtySince && it.order != null }
        "Buy ${p.qty} ($n bought)"
    }

    private fun buyLine(p: Product, deskBuys: Boolean): String = when {
        p.inProgress -> "Buying now…"
        p.purchased -> "Bought${p.orderNumber?.let { " · order $it" } ?: ""}"
        p.attempted -> "An order may have been placed - check Amazon"
        p.lockedUntil > System.currentTimeMillis() -> "Paused after failed tries until ${hm(p.lockedUntil)}"
        // 1.0.4 (owner: "there should be an indicator of how old the data is"): a product that is not watched says when it was last seen
        !p.watch -> "Off (not watched) · " + seenText(p).ifEmpty { "not looked at yet" }
        p.buy && deskBuys -> listOf(qtyText(p).ifEmpty { "Buy on" }, "the desktop buys it", seenText(p)).filter { it.isNotEmpty() }.joinToString(" · ")
        p.buy -> listOf(qtyText(p).ifEmpty { "Buy on" }, seenText(p)).filter { it.isNotEmpty() }.joinToString(" · ")
        else -> listOf("Alerts only", seenText(p)).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    @Composable
    fun Thumb(p: Product, size: Int) {
        // 1.5 (owner: "does not display any product pictures"): Amazon's own picture of the product; the initials while there is none.
        // 1.7 (owner: "The pictures aren't populating still"): only a real product picture (Pics), downloaded by the app itself (PicCache);
        // this phone's own picture first, then the linked desktop's
        val urls = Pics.shown(p)
        var bm by remember(urls) { mutableStateOf(urls.firstNotNullOfOrNull { com.restalker.mobile.PicCache.cached(it) }) }
        if (urls.isNotEmpty()) LaunchedEffect(urls) {
            while (bm == null) {                       // (no internet / the picture server refused: tried again later while on screen)
                bm = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { urls.firstNotNullOfOrNull { com.restalker.mobile.PicCache.get(it) } }
                if (bm == null) kotlinx.coroutines.delay(com.restalker.mobile.PicCache.retryMs.coerceAtLeast(5_000) + 1_000)
            }
        }
        Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(if (bm != null) Color.White else Look.GreySoft)
            .testTag("thumb-${p.asin}"), contentAlignment = Alignment.Center) {
            val b = bm
            if (b == null)
                Text(p.label.split(Regex("[^A-Za-z0-9]+")).filter { it.length > 2 && it.lowercase() !in setOf("the", "magic", "gathering") }.take(2)
                    .joinToString("") { it.take(1).uppercase() }.ifBlank { "?" }, color = Look.GreyText, fontWeight = FontWeight.Bold, fontSize = (size / 3).sp)
            else androidx.compose.foundation.Image(b.asImageBitmap(), contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp).testTag("pic-${p.asin}"))
        }
    }

    /** 2.0: the product's picture, large (the product screen). */
    @Composable
    fun BigPicture(p: Product) {
        val urls = Pics.shown(p)
        var bm by remember(urls) { mutableStateOf(urls.firstNotNullOfOrNull { com.restalker.mobile.PicCache.cached(it, 640) }) }
        if (urls.isNotEmpty()) LaunchedEffect(urls) {
            while (bm == null) {
                bm = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    urls.firstNotNullOfOrNull { com.restalker.mobile.PicCache.get(it, 640) } ?: urls.firstNotNullOfOrNull { com.restalker.mobile.PicCache.get(it) } }
                if (bm == null) kotlinx.coroutines.delay(com.restalker.mobile.PicCache.retryMs.coerceAtLeast(5_000) + 1_000)
            }
        }
        Box(Modifier.fillMaxWidth().height(260.dp).clip(Look.card).background(Color.White).testTag("bigpic-${p.asin}"), contentAlignment = Alignment.Center) {
            val b = bm
            if (b == null) Text(p.label.split(Regex("[^A-Za-z0-9]+")).filter { it.length > 2 && it.lowercase() !in setOf("the", "magic", "gathering") }.take(2)
                .joinToString("") { it.take(1).uppercase() }.ifBlank { "?" }, color = Look.GreyText, fontWeight = FontWeight.Bold, fontSize = 64.sp)
            else androidx.compose.foundation.Image(b.asImageBitmap(), contentDescription = p.label, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(12.dp).testTag("bigpic-img-${p.asin}"))
        }
    }

    /** 2.0 (owner: "tick marks on the line for $0, $MSRP, $TCG, $cap and current listing. The bar can remain red when its over the cap,
     *  but should be green when its under"): where the price stands among the others. */
    @Composable
    fun PriceBar(p: Product, nowPr: Double?, cap: Double = p.cap, onDrag: (Double) -> Unit = {}, onDrop: (Double) -> Unit = {}) {
        // 2.10: the bar runs from half to twice the MSRP (CapStep.range) - it stays put while the cap mark is dragged
        // 1.0.2: 75% of the lowest to 125% of the highest - the price now, the cap, MSRP, TCG and every store's current listing
        val (lo, scale) = remember(p.id, p.msrp, p.tcg, p.others) { CapStep.range(nowPr, p.cap, p.msrp, p.tcg, listings(p)) }
        val under = nowPr != null && nowPr <= cap
        val barColor = if (nowPr == null) Look.GreyText else if (under) Look.Good else Look.BadText
        var last by remember { mutableStateOf(cap) }
        var grabbed by remember { mutableStateOf(false) }
        // 2.4: every label is MEASURED (the phone's text size counts) - rows and row height come from the real text, so none overlap
        val tm = androidx.compose.ui.text.rememberTextMeasurer()
        val styleOf = { k: String -> androidx.compose.ui.text.TextStyle(fontSize = if (k == PriceMarks.NOW || k == PriceMarks.CAP) 12.sp else 11.sp,
            fontWeight = if (k == PriceMarks.NOW || k == PriceMarks.CAP) FontWeight.Bold else FontWeight.Normal) }
        val dens = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp).testTag("pricebar-${p.asin}")) {
            val w = maxWidth
            val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val texts = PriceMarks.labels(nowPr, cap, p.msrp, p.tcg, lo)
            val sizes = texts.mapValues { (k, t) -> tm.measure(t, styleOf(k), maxLines = 1).size }
            val padPx = with(dens) { 6.dp.toPx() }
            val marks = PriceMarks.of(nowPr, cap, p.msrp, p.tcg, scale, sizes.mapValues { (_, z) -> ((z.width + padPx) / wPx).toDouble() }, lo)
            val rowH = with(dens) { (sizes.values.maxOfOrNull { it.height } ?: 40).toDp() } + 3.dp
            val lineY = rowH * marks.rows + 10.dp
            Box(Modifier.fillMaxWidth().height(lineY + rowH + 26.dp)) {
                val valueAt = { x: Float -> CapStep.snap(lo + (scale - lo) * (x / wPx).coerceIn(0f, 1f)) }
                // (only a drag that starts ON the cap mark moves it - a tap or a swipe elsewhere on the bar changes nothing)
                val capPx = wPx * ((cap - lo) / (scale - lo)).toFloat().coerceIn(0f, 1f)
                val grabPx = with(dens) { 36.dp.toPx() }
                Box(Modifier.fillMaxSize().testTag("capdrag-${p.asin}").pointerInput(p.id, scale, p.cap) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> grabbed = Math.abs(o.x - capPx) <= grabPx; if (grabbed) { last = valueAt(o.x); onDrag(last) } },
                        onDragEnd = { if (grabbed) onDrop(last); grabbed = false }, onDragCancel = { if (grabbed) onDrop(last); grabbed = false },
                        onHorizontalDrag = { ch, _ -> if (grabbed) { ch.consume(); last = valueAt(ch.position.x); onDrag(last) } })
                })
                // 2.5: the whole bar is a gradient - dark green (cheap) to dark red (very expensive); a white marker shows the price now
                val (g1, g2, g3) = PriceMarks.gradient(marks.max, cap, p.msrp, p.tcg, lo)
                // (drawn as 96 thin strips of solid color - no gradient shader: the API 33 / 34 test phones' software graphics froze on one)
                // 2.8: a softer middle (a warm step between the light middle and red) and eased blends - green turns to red gradually
                val stops = listOf(0.0 to Look.BarGreen, g1 to Look.BarGreenLight, g2 to Look.BarMid, (g2 + g3) / 2 to Look.BarWarm, g3 to Look.BarRedLight, 1.0 to Look.BarRed)
                androidx.compose.foundation.Canvas(Modifier.offset(y = lineY - 1.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).testTag("pricebar-gradient")) {
                    val n = 96
                    val sw = size.width / n
                    for (i in 0 until n) {
                        val f = (i + 0.5) / n
                        val k = stops.indexOfLast { it.first <= f }.coerceIn(0, stops.size - 2)
                        val (f0, c0) = stops[k]; val (f1, c1) = stops[k + 1]
                        val t0 = if (f1 > f0) ((f - f0) / (f1 - f0)).toFloat().coerceIn(0f, 1f) else 0f
                        val t = t0 * t0 * (3f - 2f * t0)                      // (eased: no hard edge where two colors meet)
                        drawRect(androidx.compose.ui.graphics.lerp(c0, c1, t), topLeft = androidx.compose.ui.geometry.Offset(i * sw, 0f),
                            size = androidx.compose.ui.geometry.Size(sw + 1f, size.height))
                    }
                }
                if (nowPr != null) Box(Modifier.offset(x = (w * marks.frac(nowPr) - 6.dp).coerceIn(0.dp, w - 12.dp), y = lineY - 6.dp).size(12.dp, 20.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color.White).border(2.5.dp, Look.Ink, RoundedCornerShape(4.dp))
                    .testTag(if (under) "pricebar-green" else "pricebar-red"))
                // 2.7: every tick first, then the labels on a white backing - a tick from a higher row never crosses a lower label
                for (m in marks.items.filter { it.above }) {
                    val x = w * marks.frac(m.value)
                    val c = when (m.kind) { PriceMarks.NOW -> barColor; else -> Look.GreyText }
                    val top = lineY - rowH * m.row - 4.dp
                    Box(Modifier.offset(x = (x - 1.dp).coerceIn(0.dp, w - 2.dp), y = top).width(if (m.kind == PriceMarks.NOW) 2.dp else 1.dp).height(lineY + 8.dp - top).background(c))
                }
                for (m in marks.items) {
                    val x = w * marks.frac(m.value)
                    val c = when (m.kind) { PriceMarks.NOW -> barColor; PriceMarks.CAP -> Look.Ink; else -> Look.GreyText }
                    val lw = w * marks.width(m).toFloat()
                    val lx = w * marks.centre(m).toFloat() - lw / 2
                    if (m.above) {
                        val top = lineY - rowH * m.row - 4.dp
                        val tw = with(dens) { (sizes[m.kind]?.width ?: 0).toDp() } + 4.dp          // (the backing: just the text's width)
                        Box(Modifier.offset(x = lx, y = top - rowH + 1.dp).width(lw), contentAlignment = Alignment.Center) {
                            Text(m.label, style = styleOf(m.kind), color = c, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, softWrap = false,
                                modifier = Modifier.width(tw).background(Color.White).testTag("tick-${m.kind}"))
                        }
                    } else {
                        Box(Modifier.offset(x = (x - 1.dp).coerceIn(0.dp, w - 2.dp), y = lineY).width(2.dp).height(18.dp).background(c))
                        Box(Modifier.offset(x = (x - 10.dp).coerceIn(0.dp, w - 20.dp), y = lineY - 6.dp).size(20.dp).clip(RoundedCornerShape(10.dp))
                            .background(Look.Accent).border(3.dp, Color.White, RoundedCornerShape(10.dp)).testTag("capknob-${p.asin}"))
                        Text(m.label, style = styleOf(m.kind), color = c, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, softWrap = false,
                            modifier = Modifier.offset(x = lx, y = lineY + 18.dp).width(lw).testTag("tick-cap"))
                    }
                }
            }
        }
        // 2.10: what the bar covers
        Text("Bar: \$${Engine.money(lo).removeSuffix(".00")} to \$${Engine.money(scale).removeSuffix(".00")} (a bit under the lowest to a quarter over the highest price)",
            fontSize = 11.sp, color = Look.Muted, modifier = Modifier.padding(top = 2.dp).testTag("pricebar-end-${p.asin}"))
    }

    /** 2.7: the TCG market price over time: a navy line on a light area, the highest and lowest price, the first and last day, the price
     *  now; your cap as a dashed line when it is inside the range. 2.8 (owner: TCGplayer's own "Market Price History" chart): from
     *  TCGplayer itself - the items sold as light bars behind the line, the change over the period, 3 months / 1 year. */
    @Composable
    fun TcgChart(pts0: List<Pair<Long, Double>>, p: Product, fromTcg: Boolean = false) {
        var year by remember(p.id) { mutableStateOf(false) }
        val yr = remember(p.id, fromTcg) { if (fromTcg) try { com.restalker.mobile.core.TcgHist.series(RkApp.store(), p.asin, year = true) } catch (e: Exception) { null } else null }
        val qr = remember(p.id, fromTcg, pts0) { if (fromTcg) try { com.restalker.mobile.core.TcgHist.series(RkApp.store(), p.asin) } catch (e: Exception) { null } else null }
        val ser = if (year && yr != null) yr else qr
        val pts = ser?.pts ?: pts0
        val sold = ser?.sold ?: emptyList()
        val lo0 = pts.minOf { it.second }; val hi0 = pts.maxOf { it.second }
        // 2.9 (owner: "The price graph needs y-axis reference. Also consider adding another horizontal line to signify the user cap"):
        // the range always takes in your cap, so its line is always there; prices along the left side
        val withCap = listOf(lo0, hi0) + listOfNotNull(p.cap.takeIf { it > 0 })
        val loA = withCap.min(); val hiA = withCap.max()
        val pad = maxOf((hiA - loA) * 0.10, hiA * 0.02, 1.0)
        val lo = maxOf(0.0, loA - pad); val hi = hiA + pad
        val d0 = pts.first().first; val d1 = pts.last().first
        val fmt = SimpleDateFormat("MMM d", Locale.US)
        val day = { d: Long -> fmt.format(Date(d * 86_400_000L + 43_200_000L)) }
        val tm = androidx.compose.ui.text.rememberTextMeasurer()
        val axisStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = Look.Muted)
        val capStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = Look.AmberText, fontWeight = FontWeight.SemiBold)
        val maxSold = (sold.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
        val chg = if (pts.first().second > 0) (pts.last().second / pts.first().second - 1) * 100 else null
        Column(Modifier.fillMaxWidth().background(Color.White, Look.card).padding(16.dp).testTag("tcgchart-${p.asin}")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TCG market price", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("\$${Engine.money(pts.last().second)}", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Look.Navy)
                if (chg != null) Text(String.format(Locale.US, "  %+.1f%%", chg), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (chg < 0) Look.Good else if (chg > 0) Look.BadText else Look.Muted, modifier = Modifier.testTag("tcgchange-${p.asin}"))
            }
            Text("Low \$${"%,.0f".format(lo0)} · High \$${"%,.0f".format(hi0)} · " + (if (fromTcg) "TCGplayer, last " + (if (year && yr != null) "year" else "3 months") +
                (if (sold.any { it.second > 0 }) " · bars: items sold" else "") else "${pts.size} days recorded by the PC"), fontSize = 11.sp, color = Look.Muted,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(158.dp).testTag("tcgplot-${p.asin}")) {
                val lp = 44.dp.toPx()                                   // (the prices along the left side)
                val qh = 18.dp.toPx()                                   // (2.10: the quarters along the bottom)
                val top = 6.dp.toPx(); val w = size.width - lp; val h = size.height - top - qh
                // 2.10 (owner: "The price graph shows data points that are too specific. A year and quarter abbreviation is sufficient"):
                // the time axis says only the quarter and year ("Q3 '26") - a faint line where a quarter starts, its name under its part
                val qs = com.restalker.mobile.core.Quarters.of(d0, d1)
                for ((qStart, qEnd, name) in qs) {
                    val xa = lp + (if (d1 > d0) (qStart - d0).toFloat() / (d1 - d0) * w else 0f)
                    val xb = lp + (if (d1 > d0) (qEnd - d0).toFloat() / (d1 - d0) * w else w)
                    if (qStart > d0) drawLine(Look.Line, androidx.compose.ui.geometry.Offset(xa, top), androidx.compose.ui.geometry.Offset(xa, top + h), strokeWidth = 1f)
                    val t = tm.measure(name, axisStyle)
                    if (xb - xa >= t.size.width + 4) drawText(t, topLeft = androidx.compose.ui.geometry.Offset((xa + xb) / 2 - t.size.width / 2, top + h + 3.dp.toPx()))
                }
                val x = { d: Long -> lp + (if (d1 > d0) (d - d0).toFloat() / (d1 - d0) * w else w / 2) }
                val y = { v: Double -> top + (h - ((v - lo) / (hi - lo)).toFloat() * h) }
                for (k in 0..3) {
                    val v = hi - (hi - lo) * k / 3; val yy = y(v)
                    drawLine(Look.Line, androidx.compose.ui.geometry.Offset(lp, yy), androidx.compose.ui.geometry.Offset(lp + w, yy), strokeWidth = 1f)
                    val t = tm.measure("\$" + (if (v >= 100) String.format(Locale.US, "%,.0f", v) else Engine.money(v)), axisStyle)
                    drawText(t, topLeft = androidx.compose.ui.geometry.Offset(lp - t.size.width - 6.dp.toPx(), (yy - t.size.height / 2f).coerceIn(0f, size.height - t.size.height)))
                }
                // (the items sold: light bars from the bottom, up to 70% of the height - like TCGplayer's own chart)
                if (sold.size >= 2) {
                    val bw = (w / sold.size * 0.72f).coerceAtLeast(2f)
                    for ((d, q) in sold) if (q > 0) {
                        val bh = h * 0.70f * q / maxSold
                        drawRect(Look.BarSold, topLeft = androidx.compose.ui.geometry.Offset((x(d) - bw / 2).coerceIn(lp, lp + w - bw), top + h - bh), size = androidx.compose.ui.geometry.Size(bw, bh))
                    }
                }
                val line = androidx.compose.ui.graphics.Path(); val area = androidx.compose.ui.graphics.Path()
                pts.forEachIndexed { i, (d, v) ->
                    if (i == 0) { line.moveTo(x(d), y(v)); area.moveTo(x(d), top + h); area.lineTo(x(d), y(v)) } else { line.lineTo(x(d), y(v)); area.lineTo(x(d), y(v)) }
                }
                area.lineTo(x(d1), top + h); area.close()
                if (sold.size < 2) drawPath(area, Look.Navy.copy(alpha = 0.10f))
                drawPath(line, Look.Navy, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx()))
                if (p.cap > 0) {
                    drawLine(Look.Accent, androidx.compose.ui.geometry.Offset(lp, y(p.cap)), androidx.compose.ui.geometry.Offset(lp + w, y(p.cap)),
                        strokeWidth = 2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                    val t = tm.measure("your cap \$" + Engine.money(p.cap), capStyle)
                    val ty = y(p.cap) - t.size.height - 2.dp.toPx()
                    drawRect(Color.White.copy(alpha = 0.85f), topLeft = androidx.compose.ui.geometry.Offset(lp + 4.dp.toPx(), ty.coerceAtLeast(0f)), size = androidx.compose.ui.geometry.Size(t.size.width.toFloat(), t.size.height.toFloat()))
                    drawText(t, topLeft = androidx.compose.ui.geometry.Offset(lp + 4.dp.toPx(), ty.coerceAtLeast(0f)))
                }
                drawCircle(Look.Navy, 4.dp.toPx(), androidx.compose.ui.geometry.Offset(x(d1), y(pts.last().second)))
            }
            // 3 months / 1 year (when TCGplayer's year is there)
            if (yr != null && qr != null) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
                listOf(false to "3M", true to "1Y").forEach { (y1, l) ->
                    FilterChip(year == y1, { year = y1 }, { Text(l, fontSize = 12.sp) }, modifier = Modifier.padding(horizontal = 6.dp).testTag("tcgrange-$l"),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Look.Navy, selectedLabelColor = Color.White))
                }
            }
        }
    }

    /** 1.0.2: the stores' current listings (in stock or over the cap, seen in the last 2 hours) - for the price bar's range. */
    private fun listings(p: Product): List<Double> {
        val now = System.currentTimeMillis()
        return p.others.mapNotNull { o ->
            val st = o["st"] as? String ?: return@mapNotNull null
            val v = (o["pr"] as? Number)?.toDouble() ?: return@mapNotNull null
            val a0 = (o["at"] as? Number)?.toDouble() ?: 0.0
            val aMs = if (a0 < 1e11) (a0 * 1000).toLong() else a0.toLong()
            v.takeIf { (st == "in" || st == "over") && now - aMs <= com.restalker.mobile.core.BestPrice.FRESH_MS }
        }
    }

    /** 2.2: another store's own product page, in your browser (you buy it there yourself). */
    private fun openOutside(u: String) = try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { }

    @Composable
    fun ProductCard(p: Product, deskBuys: Boolean, tag: String = "product-${p.asin}", bought: Int = 0, onClick: () -> Unit) {
        val (c, bg, fg) = chip(p)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(Look.card).background(Color.White)
            .then(if (p.inProgress || (p.stock().first == "in" && p.buy)) Modifier.border(2.dp, Look.GoodBorder, Look.card) else Modifier)
            .clickable { if (attnSeenKey("product:" + p.asin)) attnTapKey("product:" + p.asin); onClick() }.padding(14.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Thumb(p, 56)
                BoughtBadge(bought, Modifier.padding(top = 4.dp).testTag("boughtn-$tag"))
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(p.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (attnSeenKey("product:" + p.asin)) Box(Modifier.padding(start = 6.dp)) { AttnMark("attn-${p.asin}", 18) }     // 2.15
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 5.dp)) {
                    Chip(c, bg, fg, Modifier.testTag("pstatus-${p.asin}"))
                    Text(if (p.cap > 0) "  cap \$${Engine.money(p.cap)}" else if (p.watch) "  no cap · watch only" else "  no cap yet", color = if (p.cap > 0) Look.Muted else Look.AmberText, fontSize = 12.sp, maxLines = 1)
                }
                Text(buyLine(p, deskBuys), color = if (p.inProgress) Look.Teal else if (p.attempted) Look.Red else Look.Muted, fontSize = 12.sp,
                    fontWeight = if (p.inProgress) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
            }
            // 1.0.2 (owner: "adding small buttons to watch and arm/buy ... so that the user can do this directly without having to dive into
            // the product page and scroll down"): Watch and Buy right on the card (amber = on). Buy without a cap opens the product to set one.
            if (tag.startsWith("product-") || tag.startsWith("top-")) Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                QuickToggle("Watch", p.watch, "qwatch-${p.asin}") { quickWatch(p, !p.watch) }
                QuickToggle("Buy", p.buy, "qbuy-${p.asin}") { if (!quickBuy(p, !p.buy)) onClick() }
            }
        }
    }

    /** 1.0.2: a small on / off button on a product card. */
    @Composable
    fun QuickToggle(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
        Box(Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Look.Accent else Color.White)
            .border(1.5.dp, if (on) Look.Accent else Color(0xFFB8C3D1), RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .semantics { stateDescription = if (on) "on" else "off" }
            .padding(horizontal = 10.dp, vertical = 5.dp).widthIn(min = 40.dp).testTag(tag), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) Look.Ink else Look.GreyText, maxLines = 1)
        }
    }
    private fun quickWatch(p: Product, on: Boolean) {
        val s = RkApp.store()
        s.update(p.id) { it.copy(watch = on, buy = if (on) it.buy else false) }
        s.log("${p.label}: Watch ${if (on) "ON" else "OFF"}" + if (!on && p.buy) " (and Buy OFF)." else ".")
    }
    /** -> false when it needs the product page first (no cap yet). */
    private fun quickBuy(p: Product, on: Boolean): Boolean {
        val s = RkApp.store()
        if (on && !com.restalker.mobile.core.Limit.mayBuy(DeskLink.boughtOf(p), DeskLink.master())) { s.log("${p.label}: ${com.restalker.mobile.core.Limit.WORDS}"); return true }
        if (on && p.cap <= 0) return false
        s.update(p.id) { it.copy(buy = on, watch = it.watch || on) }
        s.log("${p.label}: Buy ${if (on) "ON" else "OFF"}.")
        return true
    }

    /** 2.9: "Bought 2×" under / on a product's picture (every linked device counted); nothing when never bought. */
    @Composable
    fun BoughtBadge(n: Int, modifier: Modifier = Modifier, big: Boolean = false) {
        if (n <= 0) return
        Text("Bought ${n}×", color = Color.White, fontSize = if (big) 13.sp else 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = modifier.background(Look.Navy, RoundedCornerShape(8.dp)).padding(horizontal = if (big) 9.dp else 6.dp, vertical = 2.dp))
    }

    // =================================================================== one product
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun ProductScreen(p: Product, r: Int, onBack: () -> Unit, onEdit: (Product) -> Unit) {
        val store = RkApp.store()
        var confirmRemove by remember(p.id) { mutableStateOf(false) }
        val deskBuys = remember(r) { DeskLink.standsBy() }
        // 1.0.2 (owner: "the scroll bar infinitely shows all numbers in \$5 increments ... The price change should only be noted a single
        // time"): a run of cap lines shows only its newest (older copies kept these one per \$5 step)
        val lines = remember(r) { com.restalker.mobile.core.Store.shown(store.log()).filter { it.contains(p.label) }.takeLast(80).asReversed()
            .let { l -> l.filterIndexed { i, x -> !(CAP_LINE.containsMatchIn(x) && i > 0 && CAP_LINE.containsMatchIn(l[i - 1])) } }.take(60) }
        val buys = remember(r) { try { Buys.of(DeskLink.get().buys(DeskLink.name()), p.asin, p.label) } catch (e: Exception) { emptyList() } }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("product-screen")) {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("back")) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                Text("Product", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { openBrowser(p.url) }) { Text("Open on Amazon") }
            }
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 2.0 (owner: "the picture to be much larger (you can place the product title underneath it instead of on the side)")
                Box { BigPicture(p)
                    val n = remember(r) { DeskLink.boughtOf(p) }
                    BoughtBadge(n, Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("pboughtn-${p.asin}"), big = true) }
                Column {
                    Text(p.label, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 23.sp, modifier = Modifier.testTag("ptitle-${p.asin}"))
                    Text("Amazon · ${p.asin} · ${p.kind}", color = Look.Muted, fontSize = 12.sp)
                }
                Column(Modifier.fillMaxWidth().background(Color.White, Look.card).padding(16.dp)) {
                    val (c, bg, fg) = chip(p)
                    // 2.11 (owner: "It should still reflect the lowest price (walmart) but have an asterisk to note the user has to purchase
                    // manually (until we integrate other stores)"): the headline is the lowest price seen in stock anywhere lately; when that is
                    // not Amazon it carries * and a line under it says where (buy it there yourself) and what Amazon asks
                    val amz = p.stock().second
                    val best = remember(p) { com.restalker.mobile.core.BestPrice.of(p) }
                    val elsewhere = best != null && best.store != "Amazon" && (amz == null || best.price < amz)
                    val nowPr = if (elsewhere) best!!.price else amz
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(if (elsewhere) "Lowest now" else "Now", color = Look.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Chip(c, bg, fg) }
                    // 2.2 (owner: "the ability to change the cap higher on the page, closer to where the price indicator bar is ... a slider option
                    // for the bar to change the cap - limited to $5 increments"): drag the cap mark on the bar, or - / + $5
                    var drag by remember(p.id, p.cap) { mutableStateOf<Double?>(null) }
                    // 1.0.2 (owner: "Timer starts when the user starts to change price, then the update locks in after 30 seconds and
                    // displays it"): a cap being changed is held for 30 s (CapLater), then saved and noted once
                    val held = CapLater.pending[p.id]
                    val capNow = drag ?: held?.value ?: p.cap
                    Row(Modifier.padding(top = 8.dp).testTag("best-${p.asin}"), verticalAlignment = Alignment.Bottom) {
                        Text(nowPr?.let { "\$${Engine.money(it)}" + if (elsewhere) "*" else "" } ?: "—", fontWeight = FontWeight.Bold, fontSize = 30.sp)
                        if (elsewhere) Text("@ ${best!!.store} ↗", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Look.Link,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            modifier = Modifier.padding(start = 8.dp, bottom = 5.dp).clickable { openOutside(best.url) }.testTag("bestlink-${p.asin}"))
                    }
                    if (elsewhere) Text("* Buy it yourself at ${best!!.store} - Restalker buys on Amazon only (for now). Amazon: " +
                        (amz?.let { "\$${Engine.money(it)}" } ?: "not in stock"), color = Look.Muted, fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 2.dp).testTag("lowest-note-${p.asin}"))
                    PriceBar(p, nowPr, capNow, onDrag = { drag = it }, onDrop = { v -> drag = null; CapLater.change(p, v) })
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(if (capNow > 0) "Your cap" else "No cap yet", fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp, color = if (capNow > 0) Look.Ink else Look.AmberText, modifier = Modifier.weight(1f).testTag("cap-${p.asin}"))
                        // 2.3 (owner: "Next to the cap, we should have a field to manually input a cap if the user doesnt want to use the slider
                        // or buttons"): an exact amount (cents allowed), saved with the keyboard's Done
                        var capText by remember(p.id, capNow) { mutableStateOf(if (capNow > 0) Engine.money(capNow).removeSuffix(".00") else "") }
                        val focus = LocalFocusManager.current
                        val typed = capText.toDoubleOrNull()?.takeIf { it > 0 && it < 100000 }
                        // 2.11 (owner: "Leaving all text sizes the same, reduce the box sizes by approximately 50%"): a slim box and small round
                        // buttons - the same text sizes (the touch area of the buttons stays finger-sized)
                        val bad = capText.isNotEmpty() && typed == null
                        androidx.compose.foundation.text.BasicTextField(value = capText, onValueChange = { t -> capText = t.filter { it.isDigit() || it == '.' }.take(8) },
                            singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Look.Ink),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { typed?.let { CapLater.now(p, Math.round(it * 100) / 100.0) }; focus.clearFocus() }),
                            modifier = Modifier.width(84.dp).height(32.dp).testTag("capfield-${p.asin}"),
                            decorationBox = { inner -> Row(Modifier.fillMaxSize().border(if (bad) 2.dp else 1.dp, if (bad) Look.Red else Color(0xFFB8C3D1), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text("\$", fontSize = 15.sp, color = Look.Muted); Spacer(Modifier.width(2.dp)); inner() } })
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { CapLater.step(p, up = false) }, enabled = capNow > CapStep.STEP, contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(32.dp).testTag("capdown-${p.asin}")) { Text("−", fontSize = 20.sp) }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { CapLater.step(p, up = true) }, contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(32.dp).testTag("capup-${p.asin}")) { Text("+", fontSize = 20.sp) }
                    }
                    if (held != null) {
                        var left by remember(held.at) { mutableLongStateOf(CapLater.left(p.id)) }
                        LaunchedEffect(held.at) { while (left > 0) { kotlinx.coroutines.delay(1_000); left = CapLater.left(p.id) } }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("capheld-${p.asin}")) {
                            Text("New cap \$${Engine.money(held.value)} is saved in ${(left + 999) / 1000} s", color = Look.AmberText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { CapLater.commit(p.id) }, modifier = Modifier.testTag("capsave-${p.asin}")) { Text("Save now", fontSize = 12.sp) }
                        }
                    } else Text("Drag the cap knob under the bar, tap − / + (\$5 steps), or type an exact cap in the box and tap Done.", color = Look.Muted, fontSize = 11.sp)
                    Text(seenText(p).ifEmpty { "Not checked yet" }.replaceFirstChar { it.uppercase() } + if (p.lastCheck > 0 && p.lastCheck >= p.dAt) " · " + p.status else "",
                        color = Look.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    // 2.4 (owner: "include a hyperlink for the TCG price to the actual page so the use can view that"): the TCG price opens its
                    // TCGplayer page (the one the linked PC reads it from; else TCGplayer's search for this product)
                    FlowRow(Modifier.padding(top = 4.dp).testTag("ref-${p.asin}"), verticalArrangement = Arrangement.Center) {
                        p.msrp?.let { Text("MSRP \$${Engine.money(it)}  ·  ", color = Look.GreyText, fontSize = 13.sp) }
                        Text((p.tcg?.let { "TCG market \$${Engine.money(it)}" } ?: "TCG prices") + " ↗", color = Look.Link, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            modifier = Modifier.clickable { openOutside(Tcg.link(p)) }.testTag("tcglink-${p.asin}"))
                    }
                    // (2.11: the lowest price is the headline above - the separate "Best price" line of 2.5-2.10 is gone)
                }
                // 2.7 (owner: "a simple graph of the historic price from TCG on each individual product page"): what the linked PC recorded
                // 2.8 (owner: "include the tcg price graph for each product under each product page"): TCGplayer's own history
                // (the central list's, read once a day - every phone has it, linked or not); else what the linked PC recorded
                val (hist, fromTcg) = remember(r) { com.restalker.mobile.core.TcgHist.pick(
                    try { com.restalker.mobile.core.TcgHist.of(store, p.asin) } catch (e: Exception) { emptyList() },
                    try { DeskLink.get().tcgHistory(p.asin) } catch (e: Exception) { emptyList() }) }
                if (hist.size >= 2) TcgChart(hist, p, fromTcg)
                Column(Modifier.fillMaxWidth().background(Color.White, Look.card)) {
                    // 2.7: at most Limit.MAX purchases of a product, every linked device counted (not on the owner's devices)
                    val boughtN = remember(r) { DeskLink.boughtOf(p) }
                    val isMaster = remember(r) { DeskLink.master() }
                    val limitHit = !com.restalker.mobile.core.Limit.mayBuy(boughtN, isMaster)
                    SwitchRow("Buy when in stock", if (limitHit) com.restalker.mobile.core.Limit.WORDS
                        else if (deskBuys && p.buy) "The linked desktop buys it; this phone takes over if the desktop stops" else "One order, at or under your cap" +
                        (if (!isMaster && boughtN > 0) " · bought $boughtN of ${com.restalker.mobile.core.Limit.MAX}" else ""),
                        p.buy && !limitHit, "buy-${p.asin}") { v ->
                        if (v && limitHit) { store.log("${p.label}: ${com.restalker.mobile.core.Limit.WORDS}"); bump(); return@SwitchRow }
                        if (v && p.cap <= 0) { onEdit(p); return@SwitchRow }            // (2.2: a product without a cap gets one first)
                        store.update(p.id) { it.copy(buy = v, watch = it.watch || v) }; store.log("${p.label}: Buy ${if (v) "ON" else "OFF"}.") }
                    HorizontalDivider(color = Look.Line)
                    // 2.15 (owner: "the CAP should not be required to watch, but is required for buying"): Watch without a cap - messages
                    // and the crowd at any price; Buy still asks for a cap first
                    SwitchRow("Watch", if (p.cap > 0) "Looked at all day; a phone message when it comes back" else "Looked at all day; a phone message when it comes back, at any price (no cap needed to watch)",
                        p.watch, "watch-${p.asin}") { v ->
                        store.update(p.id) { it.copy(watch = v) } }
                    HorizontalDivider(color = Look.Line)
                    // 1.5 (owner: "there is no option to set the app to buy multiple instances of a product"): Buy 1 / 2 / 3 - separate
                    // orders (as on the desktop); the count starts when you choose it
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("How many", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(qtyText(p).ifEmpty { "One order" } + " · separate orders", fontSize = 12.sp, color = Look.Muted)
                        }
                        (1..3).forEach { n ->
                            FilterChip(p.qty == n, {
                                if (p.qty != n) { store.update(p.id) { it.copy(qty = n, qtySince = System.currentTimeMillis()) }; store.log("${p.label}: Buy $n.") }
                            }, { Text("$n") }, Modifier.padding(start = 6.dp).testTag("qty-${p.asin}-$n"))
                        }
                    }
                }
                if (p.purchased || p.attempted || p.lockedUntil > System.currentTimeMillis()) {
                    Column(Modifier.fillMaxWidth().background(Look.AmberSoft, Look.card).padding(14.dp)) {
                        Text(when { p.purchased -> "Bought${p.orderNumber?.let { " (order $it)" } ?: ""}. It is not bought again until you re-arm it."
                            p.attempted -> "An order may have been placed. Check your Amazon orders, then re-arm it if nothing was ordered."
                            else -> "Paused after failed checkouts until ${hm(p.lockedUntil)}." }, color = Look.AmberText, fontSize = 13.sp)
                        Button(onClick = {
                            store.update(p.id) { it.copy(purchased = false, attempted = false, inProgress = false, fails = 0, lockedUntil = 0, orderNumber = null) }
                            store.log("${p.label}: re-armed by you (it may be bought again).")
                        }, colors = ButtonDefaults.buttonColors(containerColor = Look.Amber), modifier = Modifier.padding(top = 8.dp).testTag("rearm-${p.asin}")) { Text("Re-arm (buy again)") }
                    }
                }
                var checkAt by remember(p.id) { mutableLongStateOf(0L) }
                val checking = working(checkAt, 60_000) { (RkApp.store().product(p.id)?.lastCheck ?: 0L) >= checkAt && !engineBusyWith(p.label) }
                // 1.0.2 (owner: "we can lose the buttons for changing the cap (this is already readily available above near the graph). We can
                // also lose the practice checkout button. This option can stay in the settings tab"): Check now and Remove
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Action("Check now", "check-${p.asin}", Modifier.weight(1f), busy = checking) { checkAt = System.currentTimeMillis(); WatchService.cmd(this@MainActivity, "check", p.id) }
                    Action(if (confirmRemove) "Tap again" else "Remove", "remove-${p.asin}", Modifier.weight(1f), danger = true) {
                        if (confirmRemove) { store.remove(p.id); store.log("${p.label}: removed."); onBack() } else confirmRemove = true
                    }
                }
                // 2.15 (owner: "Given that there is no limit to the number or type of product a user can add ... the app is useable for
                // everything, just not necessarily the discord"): a product that is not on the central (Magic) list is watched and bought
                // like any other, but only on your devices - it can be suggested for the list
                if (CrowdNet.onList(p.asin) == false) {
                    var sugg by remember(p.id) { mutableStateOf(RkApp.store().readFile("suggested.txt")?.contains(p.asin) == true) }
                    Row(Modifier.fillMaxWidth().background(Color.White, Look.card).padding(12.dp).testTag("suggest-${p.asin}"), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (sugg) "Suggested for the Restalker list - thank you." else "Not on the Restalker list: only your devices watch it (it is not shared or posted on Discord).",
                            fontSize = 12.sp, lineHeight = 16.sp, color = Look.Muted, modifier = Modifier.weight(1f))
                        if (!sugg) TextButton(onClick = {
                            Thread { val ok = Relay.suggest(p.asin, p.label); runOnUiThread { if (ok) { sugg = true; RkApp.store().writeFile("suggested.txt", (RkApp.store().readFile("suggested.txt") ?: "") + p.asin + "\n") } } }.start()
                        }, modifier = Modifier.testTag("suggest-go-${p.asin}")) { Text("Suggest it") }
                    }
                }
                // 2.4 (owner: "individual product pages should have a "bought" section above the "latest" section which shows the activity
                // as well"): this product's purchases on every linked device
                if (buys.isNotEmpty()) {
                    Text("Bought", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Look.GreyText)
                    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).padding(12.dp).testTag("pbought-${p.asin}"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) { buys.forEach { BuyLines(it, withName = false) } }
                }
                // 2.11 (owner: "Limit the size of the "latest" section to be no bigger than its current size (consider standardizing this across
                // all products regardless of the amount of information to display) and provide a scroll bar"): the same height on every
                // product, the newest first, older lines by scrolling (a thin amber bar shows where you are)
                Text("Latest", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Look.GreyText)
                val ls = rememberScrollState()
                Box(Modifier.fillMaxWidth().height(160.dp).background(Color.White, RoundedCornerShape(14.dp)).testTag("latest-${p.asin}")) {
                    Column(Modifier.fillMaxSize().verticalScroll(ls).padding(start = 12.dp, end = 18.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (lines.isEmpty()) Text("Nothing yet.", fontSize = 12.sp, color = Look.Muted)
                        lines.forEach { Text(it.replace(p.label + ": ", ""), fontSize = 12.sp, lineHeight = 16.sp) }
                    }
                    if (ls.maxValue > 0) androidx.compose.foundation.Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(10.dp).padding(vertical = 10.dp, horizontal = 3.dp).testTag("latest-bar-${p.asin}")) {
                        val total = size.height; val view = 160.dp.toPx()
                        val thumb = (total * view / (view + ls.maxValue)).coerceAtLeast(18.dp.toPx())
                        val y = (total - thumb) * ls.value / ls.maxValue
                        drawRoundRect(Look.Line, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2))
                        drawRoundRect(Look.Accent, topLeft = androidx.compose.ui.geometry.Offset(0f, y), size = androidx.compose.ui.geometry.Size(size.width, thumb),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2))
                    }
                }
                // 1.5: the product's other stores, as the linked desktop sees them. 2.2 (owner: "the information for "other stores" to be placed
                // towards the bottom of the page ... include external links to any product that is in stock so that the user can manually
                // purchase it"): at the bottom; a store that has it in stock gets Open (its own page, in your browser - you buy it there yourself)
                if (p.others.isNotEmpty()) Column(Modifier.fillMaxWidth().background(Color.White, Look.card).padding(16.dp).testTag("others-${p.asin}"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Other stores", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("What the linked PC sees there. Open takes you to that store's page - to buy it yourself, or to save it to a list there (this app buys on Amazon only).",
                        color = Look.Muted, fontSize = 11.sp, lineHeight = 15.sp)
                    p.others.forEach { o ->
                        val st = o["st"] as? String ?: ""; val pr = (o["pr"] as? Number)?.toDouble(); val at = (o["at"] as? Number)?.toLong() ?: 0L
                        val u = (o["u"] as? String)?.takeIf { it.startsWith("https://") }
                        val name = o["s"] as? String ?: "?"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, fontSize = 14.sp)
                                if (at > 0) Text("seen ${ago(at)}", color = Look.Muted, fontSize = 11.sp)
                            }
                            val (t, bg, fg) = when (st) { "in" -> Triple("In stock", Look.TealSoft, Look.TealText); "over" -> Triple("Over cap", Look.OrangeSoft, Look.OrangeText)
                                "out" -> Triple("Out of stock", Look.GreySoft, Look.GreyText); else -> Triple("Not read yet", Look.GreySoft, Look.GreyText) }
                            Chip(t + (pr?.let { " · \$${Engine.money(it)}" } ?: ""), bg, fg)
                            // 2.11 (owner: "Provide links to all stores even if out of stock (in case the user wants to watch it separately, add to
                            // favorites, wishlists, etc)")
                            if (u != null) TextButton(onClick = { openOutside(u) }, modifier = Modifier.testTag("open-$name")) { Text("Open") }
                        }
                    }
                }
            }
        }
    }

    /** 2.15 (owner: "anytime the program is doing something, it should display the rotating arrow. Example, if i click "check now" the
     *  program doesnt provide any feedback"): [busy] shows a turning circle in the button until the work is done. */
    @Composable
    fun Action(text: String, tag: String, modifier: Modifier, danger: Boolean = false, busy: Boolean = false, onClick: () -> Unit) {
        OutlinedButton(onClick = { if (!busy) onClick() }, modifier = modifier.heightIn(min = 48.dp).testTag(tag), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = if (danger) Look.Red else Look.Ink)) {
            if (busy) Spinner("busy-$tag")
            Text(text, fontWeight = FontWeight.SemiBold, maxLines = 2, fontSize = 14.sp)
        }
    }

    @Composable
    fun Spinner(tag: String) = CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp).testTag(tag), color = Look.Navy, strokeWidth = 2.dp)

    /** 2.15: true from [start] until [done] (at least 0.8 s, so it is seen; at most [maxMs]). */
    @Composable
    fun working(start: Long, maxMs: Long, done: () -> Boolean): Boolean {
        var on by remember(start) { mutableStateOf(start > 0) }
        LaunchedEffect(start) {
            if (start <= 0) { on = false; return@LaunchedEffect }
            on = true
            while (true) {
                val el = System.currentTimeMillis() - start
                if (el >= maxMs || (el >= RefreshTiming.MIN_MS && try { done() } catch (e: Exception) { true })) break
                kotlinx.coroutines.delay(300)
            }
            on = false; bump()
        }
        return on
    }
    private fun engineBusyWith(label: String): Boolean = WatchService.instance?.engine?.let { !it.idle() && it.busyWith.contains(label) } ?: false

    @Composable
    fun SwitchRow(label: String, help: String, on: Boolean, tag: String, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (help.isNotEmpty()) Text(help, fontSize = 12.sp, color = Look.Muted, lineHeight = 16.sp)
            }
            Switch(checked = on, onCheckedChange = set, modifier = Modifier.testTag(tag),
                colors = Look.sw())
        }
    }

    // =================================================================== add a product (a sheet from the bottom)
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    fun AddSheet(prefill: String?, onDone: () -> Unit) {
        var link by remember { mutableStateOf(prefill ?: "") }
        var cap by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf("box") }
        var buy by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var bigOk by remember { mutableStateOf(false) }
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val linked = remember { DeskLink.get().linked() }
        ModalBottomSheet(onDismissRequest = { if (!busy) onDone() }, sheetState = sheet, containerColor = Color.White) {
            // (the fields scroll; the error and the Add button stay on screen - a small phone with big text must still reach them)
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add a product", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(link, { link = it; err = "" }, label = { Text("Amazon link or product code") }, singleLine = true,
                        modifier = Modifier.weight(1f).testTag("f-link"))
                    OutlinedButton(onClick = {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()?.let { t -> link = Asin.firstLink(t) ?: t.trim(); err = "" }
                    }, modifier = Modifier.padding(start = 8.dp, top = 6.dp).heightIn(min = 48.dp)) { Text("Paste") }
                }
                Text("Tip: in the Amazon app, tap Share → Restalker.", color = Look.Muted, fontSize = 12.sp)
                Asin.parse(link)?.let { a -> Text("Amazon product $a", color = Look.TealText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                OutlinedTextField(cap, { cap = it; err = ""; bigOk = false }, label = { Text("Most you'll pay (price cap) \$" + if (buy) "" else " - only to buy") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("f-cap"))
                Text("Kind - the page must show the same kind (\"other\" = not checked)", fontSize = 12.sp, color = Look.Muted)
                FlowRow { listOf("box", "bundle", "pack", "other").forEach { k -> FilterChip(kind == k, { kind = k }, { Text(k) }, Modifier.padding(end = 6.dp)) } }
                SwitchRowPlain("Buy it when in stock", "Off = just tell me", buy, "f-buy") { buy = it }
            }
                if (busy) Text("Opening the short link...", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                if (err.isNotEmpty()) Text(err, color = Look.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).testTag("f-err"))
                Spacer(Modifier.height(10.dp))
                Button(colors = Look.btn(), enabled = !busy, onClick = {
                    val c = cap.replace("$", "").replace(",", "").trim().toDoubleOrNull()
                    // 2.15: a cap is needed only to buy - watching works without one
                    if (cap.isNotBlank() && (c == null || c <= 0)) { err = "The price cap must be a number above zero (for example 449.99) - or leave it empty to only watch."; return@Button }
                    if (buy && c == null) { err = "To buy it, enter the price cap (for example 449.99). To only watch it, switch Buy off."; return@Button }
                    if (c != null && c >= 1000 && !bigOk) { err = "A cap of \$${Engine.money(c)} - is that right? Tap Add again to keep it."; bigOk = true; return@Button }
                    busy = true
                    Thread {
                        val asin = Asin.resolve(link) { u -> followOnce(u) }
                        runOnUiThread {
                            busy = false
                            if (asin == null) { err = "That is not an Amazon product link or code. Open the product in the Amazon app, tap Share, and choose Restalker."; return@runOnUiThread }
                            val s = RkApp.store()
                            if (!s.add(Product(id = s.newId(), asin = asin, name = "", cap = c ?: 0.0, kind = kind, watch = true, buy = buy))) err = "That product is already in the list."
                            else { s.log("Added Amazon $asin" + (if (c != null) " (cap \$${Engine.money(c)})" else " (watch only - no cap)") + if (buy) ", set to Buy." else ". Set it to Buy when you want it bought."); onDone() }
                        }
                    }.start()
                }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("f-save")) { Text("Add product", fontSize = 16.sp) }
                if (linked) Text("It is also added to your desktop (linked).", color = Look.Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
            }
        }
    }

    @Composable
    fun SwitchRowPlain(label: String, help: String, on: Boolean, tag: String, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Text(help, fontSize = 12.sp, color = Look.Muted) }
            Switch(checked = on, onCheckedChange = set, modifier = Modifier.testTag(tag), colors = Look.sw())
        }
    }

    /** Opens a short link once and returns where it sends (Amazon's own short links only - see Asin.resolve). */
    private fun followOnce(u: String): String? = try {
        val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
        c.instanceFollowRedirects = false; c.connectTimeout = 10_000; c.readTimeout = 10_000; c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
        val code = c.responseCode
        val loc = c.getHeaderField("Location")
        c.disconnect()
        if (code in 300..399 && loc != null) java.net.URL(java.net.URL(u), loc).toString() else null
    } catch (e: Exception) { null }

    @Composable
    fun CapDialog(p: Product, onDone: () -> Unit) {
        var cap by remember { mutableStateOf(Engine.money(p.cap)) }
        var err by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = onDone, title = { Text("Price cap") }, text = {
            Column {
                Text(p.label, fontSize = 13.sp, color = Look.Muted, maxLines = 2)
                OutlinedTextField(cap, { cap = it; err = "" }, label = { Text("Most you'll pay \$") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.testTag("e-cap"))
                if (err.isNotEmpty()) Text(err, color = Look.Red, fontSize = 13.sp)
            }
        }, confirmButton = {
            Button(colors = Look.btn(), onClick = {
                val c = cap.replace("$", "").replace(",", "").trim().toDoubleOrNull()
                if (c == null || c <= 0) { err = "Enter the price cap (for example 449.99)."; return@Button }
                RkApp.store().update(p.id) { it.copy(cap = c) }
                RkApp.store().log("${p.label}: cap \$${Engine.money(c)}.")
                onDone()
            }, modifier = Modifier.testTag("e-save")) { Text("Save") }
        }, dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } })
    }

    // =================================================================== Bought / Activity
    /** 1.0.4 (owner, 2026-11-20: "Make the restalker icon slightly bigger, maybe 30%-50%, depending on what fits nicely on the screen size
     *  without causing wrapping text ... easily recognizable and defined. It should also occur on all tabs"): Bought, Activity and
     *  Settings start with the same navy bar as Watch - the logo, the tab's name and the version. */
    @Composable
    fun TabHeader(text: String) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().background(Look.Navy).padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp).testTag("brand-$text"),
                verticalAlignment = Alignment.CenterVertically) {
                Logo(logoDp(), "logo-$text")
                Column(Modifier.weight(1f)) {
                    Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp)
                    Text("Restalker Mobile v" + shownVersion(), color = Look.OnNavyDim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp).testTag("brand-version-$text"))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    /** 1.0.4: the logo's size in the header - 72 (half again as big as 1.0.3's 48) on a phone of usual width, 64 on a narrow one or
     *  with big text, 56 on a very narrow one - so the title beside it keeps its lines. */
    @Composable
    fun logoDp(): Int {
        val cfg = androidx.compose.ui.platform.LocalConfiguration.current
        return when {
            cfg.screenWidthDp >= 400 && cfg.fontScale <= 1.15f -> 72
            cfg.screenWidthDp >= 350 && cfg.fontScale <= 1.3f -> 64
            else -> 56
        }
    }

    @Composable
    fun Logo(size: Int, tag: String) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.restalker.mobile.R.drawable.ic_launcher), contentDescription = "Restalker",
            modifier = Modifier.padding(end = 12.dp).size(size.dp).clip(RoundedCornerShape((size / 4).dp)).testTag(tag))
    }

    /** 1.0: how the Bought list is grouped now (Buys.ALL / PRODUCT / STORE / DEVICE). */
    private val boughtBy = mutableIntStateOf(Buys.ALL)

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    fun BoughtTab(r: Int) {
        // 2.4 (owner: 'the "bought" section of the app should include the data from the PC version as well ... time, device, price, store,
        // and browser'): every linked device's purchases, newest first
        // 1.0 (owner: '"Bought" should be sortable by all, product, store, etc. This should keep the same color scheme of navy being primary
        // features and amber being secondary. Match the other tabs and options'): amber choices as on Watch - All (by month), Product,
        // Store, Device; each group under a navy bar with an amber fold button (its count and what it cost together on the bar)
        val list = remember(r) { try { DeskLink.get().buys(DeskLink.name()) } catch (e: Exception) { emptyList() } }
        val by = boughtBy.intValue
        val groups = remember(list, by) { try { Buys.grouped(list, by) } catch (e: Exception) { emptyList() } }
        LazyColumn(Modifier.fillMaxSize().testTag("bought"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { TabHeader("Bought") }
            if (list.isEmpty()) item { MascotNote("Nothing bought yet (by this device or the linked PC).") }
            else item {
                val total = list.mapNotNull { it.price }.sum()
                Text("${list.size} purchase${if (list.size == 1) "" else "s"}" + (if (total > 0) " · \$${Engine.money(total)} in all" else ""),
                    color = Look.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp).testTag("bought-total"))
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Product", "Store", "Device").forEachIndexed { i, l ->
                        FilterChip(by == i, { boughtBy.intValue = i }, { Text(l) }, modifier = Modifier.testTag("bsort$i"),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Look.Accent, selectedLabelColor = Look.Ink))
                    }
                }
            }
            groups.forEachIndexed { i, g ->
                val key = "b:" + g.key
                val isFolded = key in folded
                stickyHeader(key = key) {
                    GroupHeader(g.title, g.rows.size, null, isFolded, "bgrp-$i", extra = if (g.total > 0) "\$" + Engine.money(g.total) else null) { fold(key) }
                }
                if (!isFolded) items(g.rows) { x ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().background(Color.White, Look.card).padding(14.dp).testTag("buy-row")) {
                        BuyLines(x, withName = by != Buys.PRODUCT)
                    }
                }
            }
        }
    }

    /** 2.4: one purchase - when, price, store, which device and which browser bought it. */
    @Composable
    fun BuyLines(x: com.restalker.mobile.core.Buy, withName: Boolean) {
        Column {
            if (withName) Text(x.name.ifBlank { x.asin }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp)
            Text(SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(x.at)) + (x.price?.let { "  ·  \$${Engine.money(it)}" } ?: ""),
                fontSize = 13.sp, color = Look.Ink, fontWeight = if (withName) FontWeight.Normal else FontWeight.SemiBold)
            Text("${x.store}  ·  ${x.device}" + (if (x.mine) " (this device)" else "") + "  ·  ${x.by}", fontSize = 12.sp, color = Look.Muted)
            if (x.mine && x.verified != null) Chip(if (x.verified) "In your Amazon orders" else x.note.ifBlank { "Not found in your orders - check Amazon" },
                if (x.verified) Look.TealSoft else Look.AmberSoft, if (x.verified) Look.TealText else Look.AmberText, Modifier.padding(top = 6.dp))
        }
    }

    /** 2.15 (owner: "In the "Activity" tab, we are still missing color. Lets add a navy bar that displays the date, with all activity then
     *  separated by date ... This should only be displaying checkout activities, instock alerts, etc. Not information about changing things
     *  to watch or buy, or communications with the desktop"): restocks, checkouts, purchases, price drops and problems, one navy bar per day
     *  (pinned while its day scrolls). Everything else is still kept for the reports. */
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    fun ActivityTab(r: Int) {
        val L = com.restalker.mobile.core.ActivityLog
        // 1.0.2 (owner: "each notification should have a basic one line of what happened ... only once for each event ... a restock happened,
        // what action was taken, and the ultimate result in the same notification"): one entry per event - the product, then one line
        val days = remember(r) { L.eventsByDay(L.events(RkApp.store().log().filter { L.important(it) })) }
        val now = System.currentTimeMillis()
        LazyColumn(Modifier.fillMaxSize().testTag("log")) {
            // (1.0.2: the report button moved to Settings, Help and about - Activity shows only what happened)
            item { TabHeader("Activity") }
            if (days.isEmpty()) item { MascotNote("Nothing yet - restocks, checkouts and purchases show up here.") }
            // 1.0 (owner: "Activities are now sorted correctly, but should also be collapsible"): a day folds with a tap on its bar (the
            // same navy bar and amber button as on Watch and Bought) and stays folded
            val dayKeys = days.map { "day:" + it.first }
            for ((d, evs) in days) {
                val key = "day:$d"
                val isFolded = key in folded
                stickyHeader(key = "day-$d") { GroupHeader(L.dayLabel(d, now), evs.size, null, isFolded, "day-$d") { fold(key, dayKeys) } }
                if (!isFolded) items(evs) { e ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp))
                        .then(if (e.tone > 0) Modifier.border(1.5.dp, Look.GoodBorder, RoundedCornerShape(8.dp)) else Modifier).padding(horizontal = 10.dp, vertical = 7.dp)
                        .testTag("event")) {
                        Text(e.time, fontSize = 12.sp, color = Look.Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
                        Column(Modifier.weight(1f)) {
                            if (e.product.isNotEmpty()) Text(e.product, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.text, fontSize = 12.sp, lineHeight = 16.sp, color = when { e.tone < 0 -> Look.Red; e.tone > 0 -> Look.GoodText; else -> Look.Ink })
                        }
                    }
                }
            }
        }
    }


    // =================================================================== Settings
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun SettingsTab(st: Settings, r: Int, onRealAsk: () -> Unit) {
        val store = RkApp.store()
        val signed = remember(r) { signedIn() }
        val notes = remember(r) { notesOk() }
        val batt = remember(r) { battOk() }
        val (linked, linkLine) = remember(r) { DeskLink.get().state() }
        var typing by remember { mutableStateOf(false) }
        var masterAsk by remember { mutableStateOf(false) }
        var confirmUnlink by remember { mutableStateOf(false) }
        val devCount = remember(r, st) { if (!linked) 0 else try { DeskLink.get().devices(DeskLink.name()).size } catch (e: Exception) { 0 } }
        val alertCh = remember(r, st.alertTopic) { DeskLink.alertChannel() }
        // 2.12 (owner: "Settings should be organized into a hierarchy where the full options aren't displayed until they are selected. This
        // holds true for pretty much everything on that tab"): each part is one line - its name and where it stands - and opens when tapped
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings").padding(bottom = 24.dp)) {
            TabHeader("Settings")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // ---- the desktop link
                                // ---- 1.0.2 (owner, 2026-11-19: "we have too many small categories ... condense the options under less categories ... reduce
                // the total headings by about half"): 6 parts instead of 13 - Linked devices; Amazon, buying and this phone; Notifications;
                // Products; Community; Help and about
                Section("desk", "Linked devices", if (linked) "Desktop linked · $devCount devices · " + linkLine.removePrefix("Linked - ").removePrefix("Linked") else "Desktop not linked - tap to link it",
                    navy = true, titleTag = "link-title", summaryTag = "link-state") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        if (!linked) Text("Link the desktop Restalker: your products stay the same on both, and only one of them buys each product. On the desktop click Link phone, then scan its code.",
                            color = Look.Muted, fontSize = 13.sp, lineHeight = 18.sp)
                        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scan() }, colors = Look.btn(),
                                modifier = Modifier.heightIn(min = 44.dp).testTag("scan")) { Text(if (linked) "Scan again" else "Scan the desktop's QR code", fontWeight = FontWeight.SemiBold) }
                            TextButton(onClick = { typing = true }, modifier = Modifier.testTag("pastecode")) { Text("Type the code", color = Look.Navy) }
                            if (linked) TextButton(onClick = { confirmUnlink = true }, modifier = Modifier.testTag("unlink")) { Text("Unlink", color = Look.Red) }
                        }
                    }
                    if (linked) { SubHead("Each device and its buying choice"); LinkedDevices(st, r, inSection = true) }
                    // 1.0.4 (owner, 2026-11-20: "If a user starts using the program with a mobile device, how do we get them to download and
                    // link their PC? ... Maybe a manual email with a link the program can send which the user can open on their PC?"): the
                    // steps, and "Send me the link" (the phone's own share sheet: e-mail it to yourself, open it on the PC). The link itself
                    // is made the usual way round - the PC shows the code, this phone scans it - so no camera is needed on the PC, and
                    // this phone's products go to the PC at the first link.
                    else {
                        SubHead("No Restalker on your PC yet?")
                        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("getpc")) {
                            Text("Restalker for Windows watches every store (Amazon, Target, Walmart, Best Buy, GameStop) and the restock trackers all day, " +
                                "and tells this phone at once. It is free, and each one works on its own too.", fontSize = 13.sp, lineHeight = 18.sp, color = Look.Muted)
                            listOf("Tap Send me the link and e-mail it to yourself.", "On the PC, open the e-mail and the link: the page has the download and the 4 start-up steps.",
                                "In Restalker on the PC click Phone - it shows a code.", "Here, tap Scan the desktop's QR code (above) and point the phone at it. Your products here go to the PC.")
                                .forEachIndexed { i, t -> Text("${i + 1}. $t", fontSize = 13.sp, lineHeight = 18.sp, color = Look.Ink, modifier = Modifier.padding(top = 4.dp)) }
                            Button(onClick = {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Restalker for my PC")
                                    .putExtra(Intent.EXTRA_TEXT, "Restalker for Windows - open this on the PC:\n$PC_URL\n\nThen in Restalker on the PC click Phone, and scan the code it shows with the app " +
                                        "(Settings, Linked devices, Scan the desktop's QR code)."), "Send me the link"))
                            }, colors = Look.btn(), modifier = Modifier.padding(top = 10.dp).heightIn(min = 44.dp).testTag("getpc-send")) { Text("Send me the link", fontWeight = FontWeight.SemiBold) }
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(PC_URL.removePrefix("https://"), fontSize = 12.sp, color = Look.Muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp).testTag("getpc-url")) }
                        }
                    }
                }
                // ---- Amazon and buying
                                // ---- 2.0: a practice checkout with ANY Amazon item in stock (nothing is bought)
                var paLink by remember { mutableStateOf("") }
                var paErr by remember { mutableStateOf("") }
                                Section("amazon", "Amazon, buying and this phone", listOf(if (signed) "Signed in" else "Not signed in",
                    if (!st.buying) "buying off" else if (st.practice) "practice (nothing is bought)" else "real purchases",
                    "looks every ${if (st.intervalSec >= 180) "3 min" else "${st.intervalSec} s"}", if (batt) "" else "battery not allowed").filter { it.isNotEmpty() }.joinToString(" · "),
                    summaryColor = if (signed && batt) Look.Muted else Look.Red, attn = "amazon") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Amazon account", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(if (signed) "Signed in" else "Not signed in", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (signed) Look.Good else Look.Red)
                        }
                        TextButton(onClick = { openBrowser(BrowserActivity.SIGNIN_URL) }, modifier = Modifier.testTag("signin")) { Text(if (signed) "Open" else "Sign in") }
                    }
                    HorizontalDivider(color = Look.Line)
                    SwitchRow("Buy automatically", "Off = only messages, nothing is bought", st.buying, "buying") { on ->
                        store.setSettings { it.copy(buying = on) }; store.log("Buy automatically switched ${if (on) "ON" else "OFF"}.") }
                    HorizontalDivider(color = Look.Line)
                    SwitchRow("Real purchases", if (st.practice) "Off = practice: every checkout stops before Place your order" else "On: products set to Buy are really ordered",
                        !st.practice, "practice") { on ->
                        if (!on) { store.setSettings { it.copy(practice = true) }; store.log("Practice mode switched ON (nothing is bought).") } else onRealAsk()
                    }
                    HorizontalDivider(color = Look.Line)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Look every", fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Text("Faster uses more battery and data", fontSize = 12.sp, color = Look.Muted) }
                        listOf(60 to "60 s", 90 to "90 s", 180 to "3 min").forEach { (v, l) ->
                            FilterChip(st.intervalSec == v, { store.setSettings { it.copy(intervalSec = v) }; store.log("Look every $l.") }, { Text(l, fontSize = 13.sp) },
                                Modifier.padding(start = 4.dp).testTag("iv-$v"))
                        }
                    }
                    HorizontalDivider(color = Look.Line)
                    // 1.0.4 (owner, 2026-11-20: "Is it possible to allow the user to select times of the day to start and stop the watcher
                    // automatically? It might be nice to allow the device to look hard overnight when it's not otherwise in use")
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("hours")) {
                        val WH = com.restalker.mobile.core.WatchHours
                        val setHours: ((Settings) -> Settings) -> Unit = { f ->
                            val nu = store.setSettings(f)
                            store.log("Watch hours: ${WH.words(nu)}.")
                            WatchService.instance?.let { it.hoursCheck(); it.engine.send(Engine.Cmd.Wake) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Watch hours", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(WH.words(st) + if (!WH.inside(st, System.currentTimeMillis())) " · outside them now" else "", fontSize = 12.sp, color = Look.Muted,
                                    modifier = Modifier.testTag("hours-now"))
                            }
                            FilterChip(!st.hoursOn, { if (st.hoursOn) setHours { it.copy(hoursOn = false) } }, { Text("All day", fontSize = 13.sp) }, Modifier.padding(start = 4.dp).testTag("hours-all"))
                            FilterChip(st.hoursOn, { if (!st.hoursOn) setHours { it.copy(hoursOn = true) } }, { Text("Set hours", fontSize = 13.sp) }, Modifier.padding(start = 4.dp).testTag("hours-set"))
                        }
                        if (st.hoursOn) {
                            HourRow("Start", st.hoursFrom, "hours-from") { v -> setHours { it.copy(hoursFrom = v) } }
                            HourRow("Stop", st.hoursTo, "hours-to") { v -> setHours { it.copy(hoursTo = v) } }
                            Text("Outside these hours this device does not look or buy by itself (Check now and Buy now still work; a linked PC keeps going). " +
                                "For a hard look overnight: set the night's hours here and choose Look every 60 s above.",
                                fontSize = 12.sp, color = Look.Muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    HorizontalDivider(color = Look.Line)
                    SwitchRow("Wi-Fi only", "Saves mobile data", st.wifiOnly, "wifionly") { on -> store.setSettings { it.copy(wifiOnly = on) }; store.log("Wi-Fi only switched ${if (on) "ON" else "OFF"}.") }
                    SubHead("Practice checkout")
                    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("practice-any")) {
                        Text("Tests the whole checkout on this phone, without the PC: paste a link of ANY Amazon item that is in stock (a cheap one is fine). " +
                            "It goes all the way to \"Place your order\" and stops there - nothing is bought, and the item is not added to your list.",
                            fontSize = 12.sp, color = Look.Muted, lineHeight = 16.sp)
                        OutlinedTextField(paLink, { paLink = it; paErr = "" }, label = { Text("Amazon link or product code") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("pa-link"))
                        if (paErr.isNotEmpty()) Text(paErr, color = Look.Red, fontSize = 12.sp, modifier = Modifier.testTag("pa-err"))
                        var paAt by remember { mutableLongStateOf(0L) }
                        val paBusy = working(paAt, 180_000) { System.currentTimeMillis() - paAt > 3_000 && WatchService.instance?.engine?.idle() != false }
                        Button(colors = Look.btn(), enabled = !paBusy, onClick = {
                            paAt = System.currentTimeMillis()
                            val t = paLink
                            Thread {
                                val a = Asin.resolve(t) { u -> followOnce(u) }
                                runOnUiThread {
                                    if (a == null) paErr = "That is not an Amazon product link or code."
                                    else { paErr = ""; WatchService.cmd(this@MainActivity, "practiceany", a); store.log("Practice checkout asked for $a.") }
                                }
                            }.start()
                        }, modifier = Modifier.padding(top = 8.dp).testTag("pa-go")) { if (paBusy) Spinner("busy-pa"); Text(if (paBusy) "Running..." else "Run the practice") }
                        if (!signed) Text("Sign in to Amazon first (above).", color = Look.AmberText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    SubHead("This phone")
                    // 1.7 (owner: "Why do i have to manually download everything still?") / 2.9: says exactly whether Android will still ask (and why)
                    // 1.0.7: the Google Play edition never updates itself - Google Play does; nothing to switch
                    if (BuildConfig.PLAY) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("autoupdate")) {
                        Text("Updates", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(Updater.modeText("play"), fontSize = 13.sp, lineHeight = 18.sp, color = Look.Muted)
                    } else
                    SwitchRow("Update by itself", (Updater.modeText(Updater.mode(this@MainActivity)) + " " + Updater.state).trim(), st.autoUpdate, "autoupdate") { on ->
                        store.setSettings { it.copy(autoUpdate = on) }; store.log("Updating by itself switched ${if (on) "ON" else "OFF"}."); if (on) Updater.tick(this@MainActivity, force = true, explicit = true) }
                    HorizontalDivider(color = Look.Line)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        // 1.0.4 (owner: "I'm not sure I understand "watching all day""): said in plain words - this is the phone's battery
                        // setting that lets the app keep running in the background (without it Android puts it to sleep)
                        Step(batt, if (batt) "Android lets Restalker keep running in the background (battery setting)" else "Android may put Restalker to sleep - allow it to keep running (battery setting)", "Allow") { openBattery() }
                        if (!batt && BuildConfig.PLAY) Text(BATTERY_HOW, fontSize = 12.sp, color = Look.Muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { openBrowser(null) }, modifier = Modifier.testTag("browser")) { Text("Show browser") }
                            TextButton(onClick = {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, "Restalker Mobile diagnostics").putExtra(Intent.EXTRA_TEXT, Diag.text(this@MainActivity)), "Send diagnostics"))
                            }, modifier = Modifier.testTag("diag")) { Text("Send diagnostics") }
                        }
                        WatchService.instance?.dataText()?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Look.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
                    }
                }
                // ---- 2.12: notifications - which kinds, and the watching notice
                                Section("notes", "Notifications", if (!notes) "Not allowed on this phone - tap to fix" else com.restalker.mobile.core.NotifyPrefs.summary(st) +
                    (if (alertCh != null && st.alertsOn) " · other devices too" else ""),
                    summaryColor = if (notes) Look.Muted else Look.Red, summaryTag = "notes-summary", attn = "notes") {
                    NotificationsBody(st, notes)
                    SubHead("On your other devices (ntfy)")
                    Box(Modifier.testTag("sub-ntfy")) { AlertsCard(r, inSection = true) }
                }
                // ---- 2.2: all products from the central list (no caps, nothing watched)
                var catMsg by remember { mutableStateOf("") }
                var catBusy by remember { mutableStateOf(false) }
                                Section("catalog", "Products", "Add every product Restalker knows") {
                    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("catalog")) {
                        Text("Adds every Magic product Restalker knows (names, Amazon codes, MSRP and other stores' links) that is not in your list yet. " +
                            "They come in WITHOUT a cap and are not watched: set a cap and switch Watch / Buy on for the ones you want.",
                            fontSize = 12.sp, color = Look.Muted, lineHeight = 16.sp)
                        Button(colors = Look.btn(), enabled = !catBusy, onClick = {
                            catBusy = true; catMsg = "Getting the list..."
                            Thread {
                                val r = try {
                                    val c = java.net.URL(com.restalker.mobile.core.Catalog.URL + "?t=" + System.currentTimeMillis() / 60_000).openConnection() as java.net.HttpURLConnection
                                    c.connectTimeout = 15_000; c.readTimeout = 20_000; c.setRequestProperty("User-Agent", "RestalkerMobile")
                                    val body = try { c.inputStream.bufferedReader().use { it.readText().take(2_000_000) } } finally { c.disconnect() }
                                    com.restalker.mobile.core.Catalog.import(body, store)
                                } catch (e: Exception) { null }
                                runOnUiThread {
                                    catBusy = false
                                    catMsg = when {
                                        r == null -> "The list could not be read - check the internet and try again."
                                        r.added == 0 -> "Nothing new - you have every product already (${r.already})."
                                        else -> "${r.added} products added (not watched yet). Switch Watch on for the ones you want; set a cap only to buy."
                                    }
                                    bump()
                                }
                            }.start()
                        }, modifier = Modifier.padding(top = 8.dp).testTag("catalog-go")) { Text("Get all products") }
                        if (catMsg.isNotEmpty()) Text(catMsg, fontSize = 12.sp, color = Look.TealText, modifier = Modifier.padding(top = 4.dp).testTag("catalog-msg"))
                    }
                }
                // ---- 2.6: the Restalker crowd
                                // ---- 2.0: share the app (its download page)
                                Section("community", "Community", (if (st.crowd) "Sharing with the crowd" else "Not sharing") + " · " + crowdCount() + " · Discord · share the app") {
                    DiscordBody(st)
                    SubHead("Restalker crowd")
                    Column(Modifier.fillMaxWidth().testTag("crowd")) {
                        SwitchRow("Share with the Restalker crowd", crowdCount().replaceFirstChar { it.uppercase() } + ". Shares only when a product of the central list comes in stock " +
                            "(product, store, price, time) - nothing personal.", st.crowd, "crowd-on") { on ->
                            store.setSettings { it.copy(crowd = on) }; store.log("Sharing with the Restalker crowd switched ${if (on) "ON" else "OFF"}.")
                            if (on) CrowdNet.tick(force = true) }
                    }
                    SubHead("Share Restalker")
                    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("share"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Another phone scans this code to download the app. (Your own tablet: install it there, then link it to the desktop the same way - all stay the same, and only one of them buys.)",
                            fontSize = 12.sp, color = Look.Muted, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
                        val qr = remember { qrBitmap(SHARE_URL, 480) }
                        if (qr != null) androidx.compose.foundation.Image(qr.asImageBitmap(), contentDescription = "Download page QR code",
                            modifier = Modifier.size(180.dp).testTag("share-qr"))
                        TextButton(onClick = {
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "Restalker for Android: $SHARE_URL"), "Share Restalker"))
                        }, modifier = Modifier.testTag("share-link")) { Text("Share the link") }
                        // 1.0.1 (owner, 2026-11-19: "The qr code failed to install on a newer phone. A Samsung ... it said blocked though the
                        // user manually allowed it some other automated feature denied it"): that is Samsung's Auto Blocker (on by default
                        // on new Galaxy phones; it refuses every app from outside the store, also after "allow"). No app can switch it
                        // off - so the way round it stands here, where the code is shown, and at the top of the download page.
                        if (!BuildConfig.PLAY) Text("The other phone says the install is blocked? Newer Samsung phones do that. On that phone: Settings > Security and privacy > Auto Blocker > off. " +
                            "Install Restalker, then switch Auto Blocker back on.", fontSize = 12.sp, color = Look.Muted, lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 4.dp).testTag("share-blocked"))
                    }
                }
                // ---- 2.6 (owner: 'a section at the bottom of settings for "about"')
                                Section("about", "Help and about", "Restalker Mobile v" + shownVersion() + (if (DeskLink.master()) " · master" else "") + " · problems, words used", mascot = true) {
                    // 1.0.4 (owner: "a generic message to allow automatic reporting, and sending a manual report")
                    SwitchRow("Send problem reports automatically",
                        if (Reporter.direct()) "When something goes wrong - at most one every 2 hours (nothing personal is sent)" else "When something goes wrong - looked at once a day. Sent: $REPORT_WHAT.", st.autoReport, "autoreport") { on ->
                        store.setSettings { it.copy(autoReport = on, reportAsked = true) }; store.log("Automatic problem reports switched ${if (on) "ON" else "OFF"}.") }
                    HorizontalDivider(color = Look.Line)
                    Row(Modifier.fillMaxWidth().clickable { report() }.padding(horizontal = 16.dp, vertical = 14.dp).heightIn(min = 24.dp).testTag("report"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Send a report now", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = Look.Muted)
                    }
                    SubHead("Words used")
                    Glossary()
                    SubHead("About")
                    Row(Modifier.fillMaxWidth().clickable { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) } catch (e: Exception) { } }
                        .padding(horizontal = 16.dp, vertical = 14.dp).heightIn(min = 24.dp).testTag("privacy"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Privacy policy", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("What is sent, to whom, and what never leaves this device", fontSize = 13.sp, color = Look.Muted)
                        }
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = Look.Muted)
                    }
                    HorizontalDivider(color = Look.Line)
                    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("about")) {
                        Text("Restocks of sought-after Magic products often last only minutes, and no single phone or PC can look at every store all " +
                            "the time. When you share with the Restalker crowd, each device that spots a product from the central list in stock tells " +
                            "every other Restalker right away - so we all see more restocks, sooner, without each of us checking every store. Only the " +
                            "product, the store, the price and the time are shared: no names, accounts, orders or addresses. A sighting from the crowd " +
                            "only makes your device look for itself - it buys only what it sees in stock under your own cap. The more devices share, " +
                            "the better it works for everyone.",
                            fontSize = 13.sp, lineHeight = 19.sp, color = Look.Ink, modifier = Modifier.testTag("about-text"))
                        Text("Fair play: Restalker buys each product at most ${com.restalker.mobile.core.Limit.MAX} times, counting every linked device, so restocks " +
                            "go to players rather than resellers.", fontSize = 12.sp, lineHeight = 17.sp, color = Look.Muted, modifier = Modifier.padding(top = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Text("Restalker Mobile v" + shownVersion() + (if (isBeta()) " (b = beta: newer than the version everyone has)" else "") +
                                if (DeskLink.master()) " · master" else "", color = Look.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f).testTag("about-version"))
                            TextButton(onClick = { masterAsk = true }, modifier = Modifier.testTag("master-open")) { Text("Master code", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        if (masterAsk) {
            var mc by remember { mutableStateOf("") }
            var wrong by remember { mutableStateOf(false) }
            AlertDialog(onDismissRequest = { masterAsk = false }, title = { Text("Master code") }, text = {
                Column {
                    Text("For the owner only: no purchase limit on this device.", fontSize = 13.sp)
                    OutlinedTextField(mc, { mc = it.take(40); wrong = false }, singleLine = true, isError = wrong, modifier = Modifier.testTag("master-code"))
                    if (wrong) Text("That is not the master code.", color = Look.Red, fontSize = 12.sp)
                }
            }, confirmButton = { Button(colors = Look.btn(), onClick = {
                if (com.restalker.mobile.core.Limit.codeOk(mc)) { store.setSettings { it.copy(masterCode = mc.trim()) }; store.log("This device is the master (no purchase limit)."); masterAsk = false }
                else wrong = true
            }, modifier = Modifier.testTag("master-save")) { Text("Save") } }, dismissButton = { TextButton(onClick = { masterAsk = false }) { Text("Cancel") } })
        }
        if (typing) {
            var code by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { typing = false }, title = { Text("Type the link code") }, text = {
                Column {
                    Text("On the desktop: Link phone. The code is under the QR picture (starts with RK1.).", fontSize = 13.sp)
                    OutlinedTextField(code, { code = it }, singleLine = true, label = { Text("RK1....") }, modifier = Modifier.testTag("link-code"))
                }
            }, confirmButton = { Button(colors = Look.btn(), onClick = { typing = false; linkWith(code) }, modifier = Modifier.testTag("link-save")) { Text("Link") } },
                dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } })
        }
        if (confirmUnlink) AlertDialog(onDismissRequest = { confirmUnlink = false }, title = { Text("Unlink the desktop?") },
            text = { Text("The phone keeps its products and buys on its own again. Products are no longer shared.") },
            confirmButton = { Button(colors = Look.btn(), onClick = { confirmUnlink = false; Thread { DeskLink.get().unlink(); runOnUiThread { bump() } }.start() }, modifier = Modifier.testTag("unlink-yes")) { Text("Unlink") } },
            dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text("Cancel") } })
    }

    /** 2.12: the Settings part that is open (all start folded: one line each - its name and where it stands). 2.15 (owner: "only one
     *  selection should be made at a time (if i select "notifications" then previously opened selections will automatically minimize)"). */
    private val openSecs = mutableStateListOf<String>()

    // ------------------------------------------------------------------------------------------------------------ 2.15: red marks
    /** Everything that needs you now (2.15, owner: "anything that requires user input should be flagged with a small red triangle"). */
    private fun attnItems(): List<com.restalker.mobile.core.Attention.Item> {
        val out = ArrayList<com.restalker.mobile.core.Attention.Item>()
        try {
            if (!signedIn()) out += com.restalker.mobile.core.Attention.Item("signin", "amazon", "Sign in to Amazon")
            if (!notesOk()) out += com.restalker.mobile.core.Attention.Item("notes", "notes", "Allow notifications")
            // (1.0.2: "This phone" is part of "Amazon, buying and this phone" - its marks show there)
            if (!battOk()) out += com.restalker.mobile.core.Attention.Item("battery", "amazon", "Allow watching all day")
            if (Updater.state.contains("allow Restalker to install") || Updater.state.contains("tap Update now")) out += com.restalker.mobile.core.Attention.Item("update", "amazon", "An update waits for you")
            for (p in RkApp.store().products()) if (p.watch && attnStatus(p)) out += com.restalker.mobile.core.Attention.Item("product:" + p.asin, "watch", p.label)
        } catch (e: Exception) { }
        return out
    }
    /** A product that needs you: a checkout that did not finish, an unclear order, a page that does not open. */
    private fun attnStatus(p: Product): Boolean = p.status.startsWith("NOT BOUGHT") || p.status.startsWith("STOPPED") ||
        p.status.startsWith("ORDER MAY HAVE BEEN PLACED") || p.status == "the page did not load" || p.status == "the page did not answer"
    private val attnTick = mutableIntStateOf(0)
    private fun attnShowing(): List<com.restalker.mobile.core.Attention.Item> {
        attnTick.intValue
        val st = RkApp.store().settings()
        return com.restalker.mobile.core.Attention.showing(attnItems(), st.attnSeen, st.attnDaily, com.restalker.mobile.core.Attention.day(System.currentTimeMillis()))
    }
    private fun attnOn(where: String) = attnShowing().any { it.where == where }
    private fun attnSeenKey(key: String) = attnShowing().any { it.key == key }
    /** Tapping a part: its marks go away (until tomorrow, when "remind me daily" is on and the thing is still not done). */
    private fun attnTap(where: String) {
        val all = attnItems(); if (all.none { it.where == where }) return
        RkApp.store().setSettings { it.copy(attnSeen = com.restalker.mobile.core.Attention.tapped(it.attnSeen, all, where, com.restalker.mobile.core.Attention.day(System.currentTimeMillis()))) }
        attnTick.intValue++
    }
    private fun attnTapKey(key: String) {
        RkApp.store().setSettings { it.copy(attnSeen = it.attnSeen + (key to com.restalker.mobile.core.Attention.day(System.currentTimeMillis()))) }
        attnTick.intValue++
    }

    @Composable
    fun AttnMark(tag: String, size: Int = 20) {
        Icon(Icons.Filled.Warning, contentDescription = "Needs you", tint = Look.AttnRed, modifier = Modifier.size(size.dp).testTag(tag))
    }

    /** 2.12: one folding part of Settings - tap the line to open or close it. 2.15 (owner: "lets make all options the navy color (to look
     *  like the "desktop linked" option. Then when a selection is made, the dropdown has a clear color distinction between the header and
     *  the included options below it"): a navy header; what is inside sits in white, framed by the navy. [attn]: where its red mark is. */
    @Composable
    fun Section(key: String, title: String, summary: String, @Suppress("UNUSED_PARAMETER") navy: Boolean = true, summaryColor: Color = Look.Muted, mascot: Boolean = false,
                titleTag: String? = null, summaryTag: String? = null, attn: String? = null, content: @Composable ColumnScope.() -> Unit) {
        val open = key in openSecs
        val warn = summaryColor == Look.Red
        val mark = attn != null && attnOn(attn)
        Column(Modifier.fillMaxWidth().background(Look.Navy, Look.card).testTag("sec-$key")) {
            Row(Modifier.fillMaxWidth().clickable {
                    if (open) openSecs.remove(key) else { openSecs.clear(); openSecs.add(key); if (attn != null) attnTap(attn) }
                }.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 44.dp).testTag("sech-$key"), verticalAlignment = Alignment.CenterVertically) {
                if (mascot) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.restalker.mobile.R.drawable.ic_mascot), contentDescription = null,
                    modifier = Modifier.size(32.dp).padding(end = 8.dp))
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White, modifier = if (titleTag != null) Modifier.testTag(titleTag) else Modifier)
                    Text(summary, fontSize = 12.sp, lineHeight = 16.sp, color = if (warn) Look.OnNavyWarn else Look.OnNavyDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp).then(if (summaryTag != null) Modifier.testTag(summaryTag) else Modifier))
                }
                if (mark) Box(Modifier.padding(end = 8.dp)) { AttnMark("attn-$key") }
                Box(Modifier.size(28.dp).background(Look.Accent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight, contentDescription = if (open) "Close" else "Open",
                        tint = Look.Ink, modifier = Modifier.size(20.dp))
                }
            }
            if (open) Column(Modifier.padding(start = 3.dp, end = 3.dp, bottom = 3.dp).fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp, topStart = 6.dp, topEnd = 6.dp)).testTag("secb-$key")) {
                content()
            }
        }
    }

    /** 1.0.2: a heading inside an open part of Settings (the parts were joined: one heading per old part). */
    @Composable
    fun SubHead(text: String) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = Look.Line)
            Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Look.Navy, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp))
        }
    }

    /** 1.0.2 (owner: "If necessary, we can add a terms or definitions area to the settings tab somewhere"): the words Activity and the
     *  product cards use, in one place. */
    @Composable
    fun Glossary() {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("glossary"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                "Watch" to "Restalker looks at the product all day and tells you when it is in stock. No cap needed.",
                "Buy" to "Restalker orders it by itself when it is in stock at or under your cap (one order, unless you chose 2 or 3).",
                "Cap" to "The most you will pay. Needed only to buy.",
                "In stock" to "Seen for sale - by this device, your PC or another Restalker.",
                "In stock now" to "Seen for sale in the last 6 hours. Older than that it says \"Was in stock\" in grey.",
                "Checked / seen ... ago" to "How old that look is. A watched product is looked at all day; one that is not watched only once when the app opens, or by your linked PC.",
                "Over cap" to "For sale, but above your cap - not bought.",
                "Missed (sold out)" to "It sold out at Amazon before the order went through. Nothing was bought.",
                "Checkout (2 tries)" to "Restalker tried twice during the same restock.",
                "Another of your devices buys it" to "Your PC or another linked phone is the one that orders; this one stands by.",
                "Stalkers active now" to "Every phone, tablet and PC running Restalker and sharing right now - all hunting restocks together. The same number on every device.",
                "Heard ... ago" to "When a linked device last gave a sign of life (it says \"I am here\" every 15 minutes).",
                "Watch hours" to "The times of day this device watches by itself (Settings, Amazon, buying and this phone). All day unless you set them.",
                "b (as in v1.0.2b)" to "A beta: a version only your own devices have until you release it to everyone.",
            ).forEach { (w, m) ->
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)); append(w); pop(); append(" - "); append(m)
                }, fontSize = 12.sp, lineHeight = 16.sp, color = Look.Ink)
            }
        }
    }

    /** 2.15: Restalker on Discord - the invite, whether this device's restocks reach it, and the optional "connect my Discord". */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun DiscordBody(st: Settings) {
        var linkedName by remember { mutableStateOf<String?>(RkApp.store().readFile("discord_name.txt")?.trim()) }
        var code by remember { mutableStateOf<String?>(null) }
        var msg by remember { mutableStateOf("") }
        val sharing = st.crowd
        run {
            SubHead("Restalker on Discord" + if (!linkedName.isNullOrEmpty()) " · connected as $linkedName" else "")
            LaunchedEffect(Unit) {
                Thread { val n = Relay.linkedName(); if (n != null) runOnUiThread { linkedName = n; RkApp.store().writeFile("discord_name.txt", n) } }.start()
            }
            Column(Modifier.fillMaxWidth().padding(16.dp).testTag("discord")) {
                Text(if (sharing) "Your device shares the restocks it sees with the Restalker crowd; the ones 2 or more devices confirm are posted on the Restalker Discord - with pings for the sets and stores people follow."
                    else "Sharing with the Restalker crowd is off (below), so your device's restocks do not reach the Discord. You can still join and get its pings.",
                    fontSize = 13.sp, lineHeight = 18.sp, color = Look.Muted, modifier = Modifier.testTag("discord-sharing"))
                Text("Discord is optional - Restalker works the same without it.", fontSize = 12.sp, color = Look.Muted, modifier = Modifier.padding(top = 6.dp))
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        Thread {
                            val u = Relay.invite()
                            runOnUiThread { if (u != null) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { msg = u } else msg = "Could not get the invite (no internet?) - try again." }
                        }.start()
                    }, colors = Look.btn(), modifier = Modifier.heightIn(min = 44.dp).testTag("discord-join")) { Text("Join Restalker on Discord", fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(onClick = {
                        Thread { val c = Relay.linkCode(); runOnUiThread { if (c != null) code = c else msg = "Could not get a code (no internet?) - try again." } }.start()
                    }, modifier = Modifier.heightIn(min = 44.dp).testTag("discord-connect")) { Text(if (linkedName.isNullOrEmpty()) "Connect my Discord" else "Connect again") }
                }
                code?.let { c ->
                    Text("In the Restalker Discord server, type:", fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                    Text("/link $c", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Look.Navy,
                        modifier = Modifier.padding(top = 4.dp).testTag("discord-code"))
                    Text("The code works for 15 minutes. Connecting gives you the Restalker user role there; nothing else is shared.", fontSize = 12.sp, color = Look.Muted)
                }
                if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = Look.Red, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    /** 2.12 (owner: "a series of settings that allow the user to decide if they want all notifications, restock only, price drop alerts,
     *  purchase alerts, etc." and "We do not need a constant notification that the app is running and watching"): the kinds of message
     *  (a quick choice and a switch for each), the watching notice, a test. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun NotificationsBody(st: Settings, notes: Boolean) {
        val store = RkApp.store()
        val NP = com.restalker.mobile.core.NotifyPrefs
        if (!notes) Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { Step(false, "Notifications are not allowed on this phone", "Allow") { openNotifSettings() } }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
            Text("Which messages", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val cur = NP.preset(st)
                listOf(NP.USUAL to "Recommended", NP.ALL to "All", NP.RESTOCKS to "Restocks only", NP.BUYS to "Purchases only").forEach { (k, l) ->
                    FilterChip(cur == k, { store.setSettings { NP.apply(it, k) }; store.log("Notifications: $l.") }, { Text(l, fontSize = 13.sp) },
                        Modifier.testTag("np-$k"), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Look.Accent, selectedLabelColor = Look.Ink))
                }
                if (cur == NP.CUSTOM || cur == NP.NONE) FilterChip(true, { }, { Text(if (cur == NP.NONE) "None" else "Custom", fontSize = 13.sp) }, Modifier.testTag("np-custom"),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Look.Accent, selectedLabelColor = Look.Ink))
            }
        }
        SwitchRow("Restocks", "In stock at or under your cap (when it is not bought by itself)", st.nRestock, "n-restock") { on ->
            store.setSettings { it.copy(nRestock = on) }; store.log("Restock messages switched ${if (on) "ON" else "OFF"}.") }
        HorizontalDivider(color = Look.Line)
        // 1.0.4 (owner: "the alert option should include things that are under our cap as well ... watched but not armed")
        SwitchRow("Price drops", "A watched product gets at least 5% cheaper - over your cap, or under it when it is not set to Buy", st.nDrop, "n-drop") { on ->
            store.setSettings { it.copy(nDrop = on) }; store.log("Price drop messages switched ${if (on) "ON" else "OFF"}.") }
        HorizontalDivider(color = Look.Line)
        SwitchRow("Purchases", "Bought (or not), and practice checkouts", st.nBuy, "n-buy") { on ->
            store.setSettings { it.copy(nBuy = on) }; store.log("Purchase messages switched ${if (on) "ON" else "OFF"}.") }
        HorizontalDivider(color = Look.Line)
        SwitchRow("When it needs you", if (st.nNeed) "Sign in, Amazon's \"is it you\" check, an order to look at" else "Off: a sign-in or a robot check can then stop it without you knowing",
            st.nNeed, "n-need") { on -> store.setSettings { it.copy(nNeed = on) }; store.log("\"Needs you\" messages switched ${if (on) "ON" else "OFF"}.") }
        HorizontalDivider(color = Look.Line)
        // 2.15: the red marks of things that need you - back every day while not done (owner: "unless the user manually disables the option")
        SwitchRow("Remind me daily", "The red marks of things that need you come back each day until they are done", st.attnDaily, "attn-daily") { on ->
            store.setSettings { it.copy(attnDaily = on) }; attnTick.intValue++ }
        HorizontalDivider(color = Look.Line)
        // the watching notice (1.5 / 2.0 / 2.12): Android requires a notice while an app watches all day
        SwitchRow("Show a \"watching\" icon", if (st.runIcon) "An icon at the top while it watches" else
            "No icon, no sound, not on the lock screen - just a line at the very bottom of your notifications",
            st.runIcon, "runicon") { on ->
            store.setSettings { it.copy(runIcon = on, noticeHidden = if (on) false else it.noticeHidden) }; store.log("Running icon switched ${if (on) "ON" else "OFF"}.")
            WatchService.instance?.notifier?.refreshNotice()
        }
        HorizontalDivider(color = Look.Line)
        // 1.0.4 (owner, 2026-11-20: "I still have this constant message that pops up all the time in my notifications bar. I know the
        // program is running so I don't think I need this to continue to pop up"): Android puts the notice up again each time watching
        // starts (an update, a restart) and an app may not take its own down - a swipe only hides it until then. The phone's own switch
        // for it is the one thing that keeps it away: this row opens that page, and says when it is done.
        val noticeOff = try { Notifier.noticeOff(this@MainActivity) } catch (e: Exception) { false }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text("\"Restalker is watching\" notice", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(if (noticeOff) "✓ Switched off for good - Restalker keeps watching without it."
                    else "Android shows it again each time watching starts (after an update or a restart). To switch it off for good: tap Hide it, then turn off the switch on the page that opens. Watching goes on.",
                    fontSize = 12.sp, color = if (noticeOff) Look.Good else Look.Muted, lineHeight = 16.sp, modifier = Modifier.testTag("notice-state"))
            }
            if (Build.VERSION.SDK_INT >= 26) OutlinedButton(onClick = {
                try { startActivity(Notifier.noticeSettings(this@MainActivity)) } catch (e: Exception) { openNotifSettings() }
            }, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.heightIn(min = 44.dp).testTag("notice-off")) { Text(if (noticeOff) "Show it" else "Hide it", fontSize = 13.sp, maxLines = 1) }
        }
        FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { Notifier.test(this@MainActivity); store.log("Test message sent.") }, modifier = Modifier.testTag("testmsg")) { Text("Test message") }
        }
    }

    /** 2.9: Settings - the person's ntfy alerts channel: set it up once; the code, a QR for other devices, open in the ntfy app, a test. */
    @Composable
    fun AlertsCard(r: Int, inSection: Boolean = false) {
        val store = RkApp.store()
        val st = store.settings()
        val ch = remember(r, st.alertTopic) { DeskLink.alertChannel() }
        val fromPc = remember(r) { DeskLink.pcHasChannel() }
        Column(Modifier.fillMaxWidth().then(if (inSection) Modifier else Modifier.background(Color.White, Look.card)).padding(16.dp).testTag("alerts")) {
            if (!inSection) Text("Alerts on any device (ntfy)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (ch == null) {
                Text("Not needed on a device with this app: it shows its own alerts, and your linked PC's too. This is for a phone, tablet " +
                    "or PC WITHOUT Restalker - your restock, purchase and \"needs you\" alerts there, with the free ntfy app. One code for you, " +
                    "the same on every device you link.", fontSize = 13.sp, lineHeight = 18.sp, color = Look.Muted, modifier = Modifier.padding(top = 4.dp).testTag("alerts-why"))
                Button(onClick = {
                    store.setSettings { it.copy(alertTopic = com.restalker.mobile.core.NtfyAlerts.make(), alertsOn = true) }
                    store.log("ntfy alerts set up (your own alert code - linked devices use the same one)."); bump()
                }, colors = Look.btn(), modifier = Modifier.padding(top = 10.dp).heightIn(min = 44.dp).testTag("alerts-setup")) { Text("Set up ntfy alerts", fontWeight = FontWeight.SemiBold) }
                return@Column
            }
            Text("Your alert code" + (if (fromPc) " (from your PC)" else "") + " - every device linked with you uses this same one:", fontSize = 13.sp,
                color = Look.Muted, modifier = Modifier.padding(top = 4.dp))
            Text(ch, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Look.Navy,
                modifier = Modifier.padding(top = 4.dp).testTag("alerts-code"))
            val qr = remember(ch) { qrBitmap(com.restalker.mobile.core.NtfyAlerts.webLink(ch), 360) }
            if (qr != null) androidx.compose.foundation.Image(qr.asImageBitmap(), contentDescription = "QR code of your alert code",
                modifier = Modifier.padding(top = 8.dp).size(140.dp).testTag("alerts-qr"))
            Text("On another device: install ntfy, tap +, and enter the code (or scan this QR).", fontSize = 12.sp, color = Look.Muted)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.restalker.mobile.core.NtfyAlerts.appLink(ch)))) }
                    catch (e: Exception) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=io.heckel.ntfy"))) }
                        catch (e2: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.restalker.mobile.core.NtfyAlerts.webLink(ch)))) } }
                }, colors = Look.btn(), modifier = Modifier.heightIn(min = 44.dp).testTag("alerts-open")) { Text("Open in ntfy", fontWeight = FontWeight.SemiBold) }
                OutlinedButton(onClick = {
                    (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Restalker alert code", ch))
                }, modifier = Modifier.heightIn(min = 44.dp).testTag("alerts-copy")) { Text("Copy") }
                OutlinedButton(onClick = {
                    NtfyOut.send("test", "Restalker test alert", "Alerts from ${DeskLink.name()} reach this channel.", force = true)
                    store.log("ntfy alerts: a test alert was sent.")
                }, modifier = Modifier.heightIn(min = 44.dp).testTag("alerts-test")) { Text("Test") }
            }
            SwitchRow("Send this device's alerts there", if (fromPc) "Your PC sends its own alerts there too; this device adds its purchases and the ones that need you."
                else "Restocks, purchases and \"needs you\" from this device.", st.alertsOn, "alerts-on") { on ->
                store.setSettings { it.copy(alertsOn = on) }; store.log("ntfy alerts switched ${if (on) "ON" else "OFF"}."); bump() }
        }
    }

    /** 2.0: a QR code picture (ZXing, already in the app for scanning). */
    private fun qrBitmap(text: String, px: Int): android.graphics.Bitmap? = try {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, px, px, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.RGB_565).also { b ->
            val row = IntArray(px)
            for (y in 0 until px) { for (x in 0 until px) row[x] = if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE; b.setPixels(row, 0, px, 0, y, px, 1) }
        }
    } catch (e: Exception) { null }

    private fun hm(t: Long) = SimpleDateFormat("h:mm a", Locale.US).format(Date(t))
    /** 1.0.4: "54s ago", "12 min ago", "6 h ago", "3 days ago" (was a date and time after an hour - an age reads at a glance). */
    private fun ago(t: Long): String = com.restalker.mobile.core.Ago.words(System.currentTimeMillis() - t)

    companion object {
        @Volatile var lookedAtStart = false          // 2.15: the start-up look - once per start of the app
        @Volatile var startLookInTests = false
        @Volatile var betaForTests: Boolean? = null
        /** 1.7: the app's screen is in front (an update's "Install" question can then be shown at once). */
        @Volatile var shown = false
        // (1.0.7: the Google Play edition shares its page in the store)
        val SHARE_URL = if (BuildConfig.PLAY) "https://play.google.com/store/apps/details?id=com.restalker.mobile" else "https://github.com/stevenmanuel01-cloud/Restalker/releases/latest"
        /** 1.0.7: what is sent, to whom, and what stays on the device - also asked for by Google Play. */
        const val PRIVACY_URL = "https://github.com/stevenmanuel01-cloud/Restalker/blob/main/PRIVACY.md"
        const val BATTERY_HOW = "In the list that opens: choose \"All apps\" at the top, tap Restalker, then \"Don't optimize\" (or \"Unrestricted\")."
        const val REPORT_WHAT = "the app's log, your product list and what this app bought (no order numbers), and the phone's model - no name, address, password or card"
        const val UPDATE_URL = "https://raw.githubusercontent.com/stevenmanuel01-cloud/Restalker/main/mobile/latest.json"
        const val RELEASES = "https://github.com/stevenmanuel01-cloud/Restalker/releases"
        /** 1.0.4: where the PC program is downloaded (the README's part for it) - "Send me the link" sends this. */
        const val PC_URL = "https://github.com/stevenmanuel01-cloud/Restalker#get-the-pc-program-windows"
    }
}

/** 2.15 (owner: the count "should be dynamic and tied directly to the total active number of devices working"): the relay's live count
 *  when known, else the devices heard so far. */
private fun crowdCount(): String { val a = Relay.active
    return if (a > 0) com.restalker.mobile.core.Crowd.activeWords(a) else "${CrowdNet.count()} contributors heard so far" }

/** 1.0.2: a "cap" line of the log (one per $5 step in older copies). */
private val CAP_LINE = Regex(": cap \\$[0-9]")

/** 1.0.2 (owner, 2026-11-19: "The price change should only be noted a single time for that scroll. Maybe giving the update a 30 second
 *  delay would give enough time for the user to fully decide and revise the cap. Timer starts when the user starts to change price, then
 *  the update locks in after 30 seconds and displays it"): a cap being changed (the bar, - / +) waits [WAIT] from the first change, then
 *  it is saved - and noted once, "cap $555.00 → $580.00" - and the linked PC gets it. Until then the cap before it stays in force.
 *  A cap typed in the box (Done) or "Save now" is saved at once. */
object CapLater {
    const val WAIT = 30_000L
    data class Held(val from: Double, val value: Double, val at: Long, val label: String)
    val pending = androidx.compose.runtime.mutableStateMapOf<String, Held>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun change(p: Product, v: Double) {
        if (v <= 0) return
        val cur = pending[p.id]
        if (cur == null) {
            pending[p.id] = Held(p.cap, v, System.currentTimeMillis(), p.label)
            main.postDelayed({ if (left(p.id) <= 0) commit(p.id) }, WAIT + 50)
        } else pending[p.id] = cur.copy(value = v)
    }
    /** - / + : $5 from the cap being held (read at the tap - quick taps in a row each count, also before the screen is redrawn). */
    fun step(p: Product, up: Boolean) {
        val base = pending[p.id]?.value ?: RkApp.store().product(p.id)?.cap ?: p.cap
        change(p, if (up) CapStep.up(base) else CapStep.down(base))
    }
    fun left(id: String): Long = pending[id]?.let { maxOf(0L, it.at + WAIT - System.currentTimeMillis()) } ?: 0L
    fun now(p: Product, v: Double) { val from = pending.remove(p.id)?.from ?: p.cap; save(p.id, p.label, from, v) }
    fun commit(id: String) { val h = pending.remove(id) ?: return; save(id, h.label, h.from, h.value) }
    private fun save(id: String, label: String, from: Double, v: Double) {
        val s = RkApp.store()
        if (v <= 0 || Math.abs(v - (s.product(id)?.cap ?: from)) < 0.005) return
        s.update(id) { it.copy(cap = v) }
        s.log("$label: cap " + (if (from > 0) "\$${Engine.money(from)} → " else "") + "\$${Engine.money(v)}.")
    }
}
