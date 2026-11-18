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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.restalker.mobile.core.APP_VERSION
import com.restalker.mobile.core.Asin
import com.restalker.mobile.core.CapStep
import com.restalker.mobile.core.Engine
import com.restalker.mobile.core.Groups
import com.restalker.mobile.core.MiniJson
import com.restalker.mobile.core.Product
import com.restalker.mobile.core.Pics
import com.restalker.mobile.core.PriceMarks
import com.restalker.mobile.core.Settings
import com.restalker.mobile.core.Buys
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
    /** 1.6: "Report a problem" first asks what went wrong (optional) - your words go to Claude with the report. */
    private var asking = mutableStateOf(false)
    private fun report() { asking.value = true }
    private fun sendReport(note: String) {
        reportResult.value = "Sending..."
        val n = note.replace(Regex("\\s+"), " ").trim().take(1500)
        Reporter.send(this, if (n.isEmpty()) "sent by you" else "sent by you - your note: $n") { ok ->
            reportResult.value = if (ok) "Sent to Claude.\nYour report code:\n${com.restalker.mobile.core.Report.code(RkApp.store())}\n\nTell Claude \"reported\" (the first time, also send this code)."
                                 else "Could not send it (no internet?). Try again."
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
        setContent { RkTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); sharedText(intent) }
    override fun onResume() {
        super.onResume(); shown = true; bump(); ticker.post(tick)
        // (back from "allow installs": go on at once - 2.4: only then, NOT after Android's install question closes, which also brings
        // the app back to the front: that asked again in a loop)
        if (update.value != null && Updater.state.contains("allow Restalker to install") && Updater.allowed(this)) checkUpdate(explicit = true)
        if (DeskLink.get().linked()) DeskLink.cycleAsync { bump() }          // (opening the app: the desktop's latest at once)
        com.restalker.mobile.HealthRunner.tick(this)                          // 1.7: the self-check (a report by itself when something is wrong)
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

    private fun signedIn() = CookieManager.getInstance().getCookie("https://www.amazon.com")?.let { it.contains("at-main=") || it.contains("x-main=") || it.contains("sess-at-main=") } == true
    private fun battOk() = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    private fun notesOk() = NotificationManagerCompat.from(this).areNotificationsEnabled()
    private fun openBrowser(url: String?) = startActivity(Intent(this, BrowserActivity::class.java).also { if (url != null) it.putExtra(BrowserActivity.EXTRA_URL, url) })
    private fun openNotifSettings() = startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName))
    private fun openBattery() = try {
        startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    } catch (e: Exception) { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
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
                    NavigationBarItem(selected = pager.currentPage == i, onClick = { goTab(i) }, icon = { Icon(icon, contentDescription = null) },
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
            }, confirmButton = { Button(colors = Look.btn(), onClick = { asking.value = false; sendReport(note) }, modifier = Modifier.testTag("report-send")) { Text("Send to Claude") } },
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

    /** 2.3: pull down to refresh: the PC link is read, an update is looked for, the screen is redrawn. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Refreshable(tag: String, content: @Composable () -> Unit) {
        var busy by remember { mutableStateOf(false) }
        PullToRefreshBox(isRefreshing = busy, onRefresh = {
            busy = true
            Thread {
                try { if (DeskLink.get().linked()) DeskLink.cycleAsync() } catch (e: Exception) { }
                try { Updater.tick(this@MainActivity, force = true) } catch (e: Exception) { }
                runOnUiThread { busy = false; bump() }
            }.start()
        }, modifier = Modifier.fillMaxSize().testTag(tag)) { content() }
    }

    /** 2.3 (owner: "For each individual product page, there should be an option to swipe down and refresh all pages for that item,
     *  essentially forcing a new check"): pulling down looks at this product's Amazon page now (like Check now) and reads the PC link
     *  (the other stores' latest). The circle stays until that look is done (at most a minute). */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ProductRefresh(p: Product, r: Int, content: @Composable () -> Unit) {
        var since by remember(p.id) { mutableLongStateOf(0L) }
        val busy = since > 0 && p.lastCheck < since && System.currentTimeMillis() - since < 60_000
        LaunchedEffect(since) { if (since > 0) { kotlinx.coroutines.delay(60_000); bump() } }
        PullToRefreshBox(isRefreshing = busy, onRefresh = {
            since = System.currentTimeMillis()
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
    fun LinkedDevices(st: com.restalker.mobile.core.Settings, r: Int) {
        val store = RkApp.store()
        val list = remember(r, st) { try { DeskLink.get().devices(DeskLink.name()) } catch (e: Exception) { emptyList() } }
        var renaming by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().background(Color.White, Look.card).padding(16.dp).testTag("devices")) {
            Text("Linked devices", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("Each phone or tablet has its own buying choice. Changing one here changes only that device.",
                fontSize = 12.sp, color = Look.Muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
            for (d in list) {
                HorizontalDivider(color = Look.Line, modifier = Modifier.padding(vertical = 10.dp))
                Column(Modifier.testTag(if (d.me) "dev-me" else if (d.pc) "dev-pc" else "dev-${d.id}")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name + if (d.me) " (this device)" else "", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (d.me) TextButton(onClick = { renaming = true }, modifier = Modifier.testTag("dev-rename")) { Text("Rename") }
                    }
                    val alive = d.agoS < 20 * 60
                    Text(when {
                        d.me -> "Version ${com.restalker.mobile.core.APP_VERSION}"
                        else -> listOfNotNull(d.vn.takeIf { it.isNotBlank() }?.let { "Version $it" }, "heard ${agoS(d.agoS)}").joinToString(" · ")
                    }, fontSize = 12.sp, color = if (d.me || alive) Look.Muted else Look.AmberText)
                    if (d.pc) {
                        val handed = try { DeskLink.get().pcHanded() } catch (e: Exception) { false }
                        Text(if (handed) "Does not buy on Amazon now: a device below is set to Instead of PC. It keeps watching every store."
                            else "Buys on Amazon, and watches every store.", fontSize = 12.sp, color = Look.Ink, lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 2.dp).testTag("dev-pc-line"))
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
                runOnUiThread { if (!ok) store.log("${d.name}: the change could not be sent yet - it goes with the next link round."); bump() } }.start()
        }
        bump()
    }

    // =================================================================== Watch (home)
    @Composable
    fun WatchTab(st: Settings, products: List<Product>, r: Int, onOpen: (Product) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit) {
        val store = RkApp.store()
        val signed = remember(r) { signedIn() }
        val notes = remember(r) { notesOk() }
        val svc = WatchService.instance
        val now = System.currentTimeMillis()
        val (linked, linkLine) = remember(r) { DeskLink.get().state() }
        val deskBuys = remember(r) { DeskLink.standsBy() }
        LazyColumn(Modifier.fillMaxSize().testTag("list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.fillMaxWidth().background(Look.Navy).padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Restalker Mobile v$APP_VERSION", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 25.sp,
                                maxLines = 2, modifier = Modifier.testTag("title"))
                            // 2.6 (owner: "small text ... the total number of workers (individual devices contributing) ... XXX total
                            // contributors and counting")
                            val crowdN = remember(r) { CrowdNet.count() }
                            Text(if (!st.crowd) "Crowd sharing is off (Settings)" else if (crowdN > 1) "$crowdN total contributors and counting" else "Joining the Restalker crowd",
                                color = Look.OnNavyDim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp).testTag("contributors"))
                        }
                        Button(onClick = onAdd, colors = Look.btn(),
                            contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.heightIn(min = 44.dp).testTag("add")) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                            Text("Add", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth().background(Look.NavyCard, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val watched = products.count { it.watch }
                            Text(if (st.watching) "Watching $watched product${if (watched == 1) "" else "s"}" else "Not watching", color = Color.White,
                                fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            val line = if (!st.watching) "Switch on to start" else svc?.notifier?.lastStatus?.ifBlank { null } ?: "starting..."
                            Text(line, color = Look.OnNavyDim, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 3, modifier = Modifier.testTag("status"))
                            if (linked) Text(if (deskBuys) "Desktop linked · this device watches (Settings, Linked devices)" else "Desktop linked · this device buys", color = Look.Mint, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp).testTag("linkchip"))
                        }
                        Switch(checked = st.watching, onCheckedChange = { setWatching(it) }, modifier = Modifier.testTag("watching"),
                            colors = Look.sw())
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
                if (products.any { it.watch }) TextButton(onClick = {
                    WatchService.cmd(this@MainActivity, "checkall"); store.log("Check all now: every watched product is looked at now.")
                }, modifier = Modifier.padding(start = 8.dp).testTag("checkall")) { Text("Check all now (${products.count { it.watch }})") }
                if (products.isEmpty()) Text("No products yet. Tap Add, or open a product in the Amazon app, tap Share and choose Restalker." +
                    if (!linked) " Or link the desktop (Settings) to get its products." else "",
                    color = Look.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                else Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "In stock", "Set to buy", "Watched").forEachIndexed { i, l ->
                        FilterChip(filter.intValue == i, { filter.intValue = i }, { Text(l) }, modifier = Modifier.testTag("filter$i"),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Look.TealSoft, selectedLabelColor = Look.TealText))
                    }
                }
            }
            // 1.4 (owner: "The desktop version had several collapsible groups, and can easily be scrolled"): the desktop's groups -
            // release year, then set - each folds with a tap (remembered); what is in stock now stays on top
            // 1.5 (owner: "the app does not show anything in stock when sorted. There are several items in stock, just above the cap" -
            // and: "an additional filter in the app for watched (separate from armed)"): In stock = for sale at ANY price (the phone's own
            // look or the linked desktop's, whichever is newer); Watched = watched, whether set to buy or not
            val shown = products.filter { when (filter.intValue) { 1 -> it.inStock && !it.purchased; 2 -> it.buy; 3 -> it.watch; else -> true } }
            val hot = shown.filter { it.inStock && !it.purchased }
            if (filter.intValue == 0 && hot.isNotEmpty()) {
                item(key = "hdr-hot") { GroupHeader("In stock now", hot.size, null, false, "grp-hot") {} }
                items(hot, key = { "hot-" + it.id }) { p -> ProductCard(p, deskBuys, "top-${p.asin}") { onOpen(p) } }
            }
            if (products.isNotEmpty() && shown.isEmpty()) item { Text(when (filter.intValue) { 1 -> "Nothing is in stock right now."; 2 -> "No product is set to buy."; else -> "No product is watched." },
                color = Look.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
            val years = shown.groupBy { Groups.of(it).first }.toList().sortedBy { Groups.yearRank(it.first) }
            for ((year, inYear) in years) {
                val yKey = "y:$year"
                item(key = yKey) { GroupHeader(year, inYear.size, inYear.count { it.inStock && !it.purchased }, yKey in folded, "grp-$year") { fold(yKey) } }
                if (yKey in folded) continue
                for ((set, inSet) in inYear.groupBy { Groups.of(it).second }.toList().sortedBy { it.first.lowercase() }) {
                    val sKey = "s:$year|$set"
                    if (inYear.size > 1 || set != year) item(key = sKey) { SetHeader(set, inSet.size, sKey in folded, "set-$set") { fold(sKey) } }
                    if (sKey in folded) continue
                    items(inSet, key = { it.id }) { p -> ProductCard(p, deskBuys) { onOpen(p) } }
                }
            }
        }
    }

    // the folded groups (kept in the app's folder, so they stay folded)
    private val folded = mutableStateListOf<String>().apply {
        try { MiniJson.read(RkApp.store().readFile("folded.json") ?: "[]").let { (it as? List<*>)?.mapNotNull { x -> x as? String }?.let { l -> addAll(l) } } } catch (e: Exception) { }
    }
    private val filter = mutableIntStateOf(0)
    private fun fold(key: String) {
        if (key in folded) folded.remove(key) else folded.add(key)
        try { RkApp.store().writeFile("folded.json", MiniJson.write(folded.toList())) } catch (e: Exception) { }
    }

    @Composable
    fun GroupHeader(title: String, count: Int, hot: Int?, isFolded: Boolean, tag: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(enabled = hot != null, onClick = onClick).padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)
            .heightIn(min = 32.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (hot == null) Look.Teal else Look.Ink)
            Text("  $count", color = Look.Muted, fontSize = 13.sp)
            if (hot != null && hot > 0) Chip("$hot in stock", Look.TealSoft, Look.TealText, Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            if (hot != null) Icon(if (isFolded) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, contentDescription = if (isFolded) "Show" else "Hide", tint = Look.Muted)
        }
    }

    @Composable
    fun SetHeader(title: String, count: Int, isFolded: Boolean, tag: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 2.dp).heightIn(min = 32.dp).testTag(tag),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isFolded) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, contentDescription = if (isFolded) "Show" else "Hide",
                tint = Look.Muted, modifier = Modifier.size(20.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Look.GreyText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Text("$count", color = Look.Muted, fontSize = 13.sp)
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
            Step(batt, "Let it watch all day (battery)", "Allow") { openBattery() }
            Step(products.isNotEmpty(), "Add a product, or link the desktop", "Add") { onAdd() }
            Step(practiced, "Run one Practice checkout (open a product)", null) {}
            if (!batt) Text("Samsung / Xiaomi / OnePlus: also set Restalker to \"Unrestricted\" in the phone's battery settings.", fontSize = 12.sp, color = Look.Muted)
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
        return when {
            p.purchased && p.qty <= 1 -> Triple("Bought", Look.TealSoft, Look.TealText)
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
        !p.watch -> "Off (not watched)"
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
        // 2.2: the scale stays put while the cap mark is dragged (from the values without the dragged cap; room up to 1.6 x)
        val scale = remember(p.id, p.cap, nowPr, p.msrp, p.tcg) { CapStep.scale(nowPr, p.cap, p.msrp, p.tcg) }
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
            val texts = PriceMarks.labels(nowPr, cap, p.msrp, p.tcg)
            val sizes = texts.mapValues { (k, t) -> tm.measure(t, styleOf(k), maxLines = 1).size }
            val padPx = with(dens) { 6.dp.toPx() }
            val marks = PriceMarks.of(nowPr, cap, p.msrp, p.tcg, scale, sizes.mapValues { (_, z) -> ((z.width + padPx) / wPx).toDouble() })
            val rowH = with(dens) { (sizes.values.maxOfOrNull { it.height } ?: 40).toDp() } + 3.dp
            val lineY = rowH * marks.rows + 10.dp
            Box(Modifier.fillMaxWidth().height(lineY + rowH + 26.dp)) {
                val valueAt = { x: Float -> CapStep.snap(scale * (x / wPx).coerceIn(0f, 1f)) }
                // (only a drag that starts ON the cap mark moves it - a tap or a swipe elsewhere on the bar changes nothing)
                val capPx = wPx * (cap / scale).toFloat().coerceIn(0f, 1f)
                val grabPx = with(dens) { 36.dp.toPx() }
                Box(Modifier.fillMaxSize().testTag("capdrag-${p.asin}").pointerInput(p.id, scale, p.cap) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> grabbed = Math.abs(o.x - capPx) <= grabPx; if (grabbed) { last = valueAt(o.x); onDrag(last) } },
                        onDragEnd = { if (grabbed) onDrop(last); grabbed = false }, onDragCancel = { if (grabbed) onDrop(last); grabbed = false },
                        onHorizontalDrag = { ch, _ -> if (grabbed) { ch.consume(); last = valueAt(ch.position.x); onDrag(last) } })
                })
                // 2.5: the whole bar is a gradient - dark green (cheap) to dark red (very expensive); a white marker shows the price now
                val (g1, g2, g3) = PriceMarks.gradient(marks.max, cap, p.msrp, p.tcg)
                Box(Modifier.offset(y = lineY - 1.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Look.BarGreen, g1.toFloat() to Look.BarGreenLight, g2.toFloat() to Look.BarMid,
                        g3.toFloat() to Look.BarRedLight, 1f to Look.BarRed)).testTag("pricebar-gradient"))
                if (nowPr != null) Box(Modifier.offset(x = (w * marks.frac(nowPr) - 6.dp).coerceIn(0.dp, w - 12.dp), y = lineY - 6.dp).size(12.dp, 20.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color.White).border(2.5.dp, Look.Ink, RoundedCornerShape(4.dp))
                    .testTag(if (under) "pricebar-green" else "pricebar-red"))
                for (m in marks.items) {
                    val x = w * marks.frac(m.value)
                    val now = m.kind == PriceMarks.NOW
                    val c = when (m.kind) { PriceMarks.NOW -> barColor; PriceMarks.CAP -> Look.Ink; else -> Look.GreyText }
                    val lw = w * marks.width(m).toFloat()
                    val lx = w * marks.centre(m).toFloat() - lw / 2
                    if (m.above) {
                        // a tick from the label's row down to the line
                        val top = lineY - rowH * m.row - 4.dp
                        Box(Modifier.offset(x = (x - 1.dp).coerceIn(0.dp, w - 2.dp), y = top).width(if (now) 2.dp else 1.dp).height(lineY + 8.dp - top).background(c))
                        Text(m.label, style = styleOf(m.kind), color = c, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, softWrap = false,
                            modifier = Modifier.offset(x = lx, y = top - rowH + 1.dp).width(lw).testTag("tick-${m.kind}"))
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
    }

    /** 2.2: a new cap from the bar or the - / + buttons (the same as Change cap: saved, logged, and the linked PC gets it). */
    private fun saveCap(p: Product, v: Double) {
        if (v <= 0 || Math.abs(v - p.cap) < 0.005) return
        RkApp.store().update(p.id) { it.copy(cap = v) }
        RkApp.store().log("${p.label}: cap \$${Engine.money(v)}.")
        bump()
    }

    /** 2.2: another store's own product page, in your browser (you buy it there yourself). */
    private fun openOutside(u: String) = try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { }

    @Composable
    fun ProductCard(p: Product, deskBuys: Boolean, tag: String = "product-${p.asin}", onClick: () -> Unit) {
        val (c, bg, fg) = chip(p)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(Look.card).background(Color.White)
            .then(if (p.inProgress || (p.stock().first == "in" && p.buy)) Modifier.border(2.dp, Look.GoodBorder, Look.card) else Modifier)
            .clickable(onClick = onClick).padding(14.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            Thumb(p, 56)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(p.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 5.dp)) {
                    Chip(c, bg, fg, Modifier.testTag("pstatus-${p.asin}"))
                    Text(if (p.cap > 0) "  cap \$${Engine.money(p.cap)}" else "  no cap yet", color = if (p.cap > 0) Look.Muted else Look.AmberText, fontSize = 12.sp, maxLines = 1)
                }
                Text(buyLine(p, deskBuys), color = if (p.inProgress) Look.Teal else if (p.attempted) Look.Red else Look.Muted, fontSize = 12.sp,
                    fontWeight = if (p.inProgress) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    // =================================================================== one product
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun ProductScreen(p: Product, r: Int, onBack: () -> Unit, onEdit: (Product) -> Unit) {
        val store = RkApp.store()
        var confirmRemove by remember(p.id) { mutableStateOf(false) }
        val deskBuys = remember(r) { DeskLink.standsBy() }
        val lines = remember(r) { store.log().filter { it.contains(p.label) }.takeLast(6).asReversed() }
        val buys = remember(r) { try { Buys.of(DeskLink.get().buys(DeskLink.name()), p.asin, p.label) } catch (e: Exception) { emptyList() } }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("product-screen")) {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("back")) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                Text("Product", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { openBrowser(p.url) }) { Text("Open on Amazon") }
            }
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 2.0 (owner: "the picture to be much larger (you can place the product title underneath it instead of on the side)")
                BigPicture(p)
                Column {
                    Text(p.label, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 23.sp, modifier = Modifier.testTag("ptitle-${p.asin}"))
                    Text("Amazon · ${p.asin} · ${p.kind}", color = Look.Muted, fontSize = 12.sp)
                }
                Column(Modifier.fillMaxWidth().background(Color.White, Look.card).padding(16.dp)) {
                    val (c, bg, fg) = chip(p)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Now", color = Look.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Chip(c, bg, fg) }
                    val nowPr = p.stock().second
                    // 2.2 (owner: "the ability to change the cap higher on the page, closer to where the price indicator bar is ... a slider option
                    // for the bar to change the cap - limited to $5 increments"): drag the cap mark on the bar, or - / + $5
                    var drag by remember(p.id, p.cap) { mutableStateOf<Double?>(null) }
                    val capNow = drag ?: p.cap
                    Text(nowPr?.let { "\$${Engine.money(it)}" } ?: "—", fontWeight = FontWeight.Bold, fontSize = 30.sp, modifier = Modifier.padding(top = 8.dp))
                    PriceBar(p, nowPr, capNow, onDrag = { drag = it }, onDrop = { v -> drag = null; saveCap(p, v) })
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(if (capNow > 0) "Your cap" else "No cap yet", fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp, color = if (capNow > 0) Look.Ink else Look.AmberText, modifier = Modifier.weight(1f).testTag("cap-${p.asin}"))
                        // 2.3 (owner: "Next to the cap, we should have a field to manually input a cap if the user doesnt want to use the slider
                        // or buttons"): an exact amount (cents allowed), saved with the keyboard's Done
                        var capText by remember(p.id, capNow) { mutableStateOf(if (capNow > 0) Engine.money(capNow).removeSuffix(".00") else "") }
                        val focus = LocalFocusManager.current
                        val typed = capText.toDoubleOrNull()?.takeIf { it > 0 && it < 100000 }
                        OutlinedTextField(value = capText, onValueChange = { t -> capText = t.filter { it.isDigit() || it == '.' }.take(8) },
                            prefix = { Text("\$") }, singleLine = true, isError = capText.isNotEmpty() && typed == null,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { typed?.let { saveCap(p, Math.round(it * 100) / 100.0) }; focus.clearFocus() }),
                            modifier = Modifier.width(112.dp).testTag("capfield-${p.asin}"))
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { saveCap(p, CapStep.down(p.cap)) }, enabled = p.cap > CapStep.STEP, contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(48.dp).testTag("capdown-${p.asin}")) { Text("−", fontSize = 20.sp) }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { saveCap(p, CapStep.up(p.cap)) }, contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(48.dp).testTag("capup-${p.asin}")) { Text("+", fontSize = 20.sp) }
                    }
                    Text("Drag the cap knob under the bar, tap − / + (\$5 steps), or type an exact cap in the box and tap Done.", color = Look.Muted, fontSize = 11.sp)
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
                    // 2.5: the cheapest place that has it in stock right now, with a link to that store's page
                    val best = remember(p) { com.restalker.mobile.core.BestPrice.of(p) }
                    if (best != null) Row(Modifier.padding(top = 4.dp).testTag("best-${p.asin}"), verticalAlignment = Alignment.CenterVertically) {
                        Text("Best price: ", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Look.Ink)
                        Text("\$${Engine.money(best.price)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (best.price <= p.cap) Look.Good else Look.BadText)
                        Text(" @ ", fontSize = 13.sp, color = Look.Ink)
                        Text(best.store + " ↗", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Look.Link,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            modifier = Modifier.clickable { if (best.store == "Amazon") openBrowser(best.url) else openOutside(best.url) }.testTag("bestlink-${p.asin}"))
                    } else Text("Best price: not in stock anywhere right now", fontSize = 12.sp, color = Look.Muted, modifier = Modifier.padding(top = 4.dp).testTag("best-${p.asin}"))
                }
                Column(Modifier.fillMaxWidth().background(Color.White, Look.card)) {
                    SwitchRow("Buy when in stock", if (deskBuys && p.buy) "The linked desktop buys it; this phone takes over if the desktop stops" else "One order, at or under your cap",
                        p.buy, "buy-${p.asin}") { v ->
                        if (v && p.cap <= 0) { onEdit(p); return@SwitchRow }            // (2.2: a product without a cap gets one first)
                        store.update(p.id) { it.copy(buy = v, watch = it.watch || v) }; store.log("${p.label}: Buy ${if (v) "ON" else "OFF"}.") }
                    HorizontalDivider(color = Look.Line)
                    SwitchRow("Watch", "Looked at all day; a phone message when it comes back", p.watch, "watch-${p.asin}") { v ->
                        if (v && p.cap <= 0) { onEdit(p); return@SwitchRow }
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Action("Check now", "check-${p.asin}", Modifier.weight(1f)) { WatchService.cmd(this@MainActivity, "check", p.id) }
                    Action("Change cap", "edit-${p.asin}", Modifier.weight(1f)) { onEdit(p) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Action("Practice checkout", "practice-${p.asin}", Modifier.weight(1f)) { WatchService.cmd(this@MainActivity, "practice", p.id) }
                    Action(if (confirmRemove) "Tap again" else "Remove", "remove-${p.asin}", Modifier.weight(1f), danger = true) {
                        if (confirmRemove) { store.remove(p.id); store.log("${p.label}: removed."); onBack() } else confirmRemove = true
                    }
                }
                // 2.4 (owner: "individual product pages should have a "bought" section above the "latest" section which shows the activity
                // as well"): this product's purchases on every linked device
                if (buys.isNotEmpty()) {
                    Text("Bought", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Look.GreyText)
                    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).padding(12.dp).testTag("pbought-${p.asin}"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) { buys.forEach { BuyLines(it, withName = false) } }
                }
                if (lines.isNotEmpty()) {
                    Text("Latest", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Look.GreyText)
                    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        lines.forEach { Text(it.replace(p.label + ": ", ""), fontSize = 12.sp, lineHeight = 16.sp) }
                    }
                }
                // 1.5: the product's other stores, as the linked desktop sees them. 2.2 (owner: "the information for "other stores" to be placed
                // towards the bottom of the page ... include external links to any product that is in stock so that the user can manually
                // purchase it"): at the bottom; a store that has it in stock gets Open (its own page, in your browser - you buy it there yourself)
                if (p.others.isNotEmpty()) Column(Modifier.fillMaxWidth().background(Color.White, Look.card).padding(16.dp).testTag("others-${p.asin}"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Other stores", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("What the linked PC sees there. In stock: Open takes you to that store to buy it yourself (this app buys on Amazon only).",
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
                            if (u != null && (st == "in" || st == "over")) TextButton(onClick = { openOutside(u) }, modifier = Modifier.testTag("open-$name")) { Text("Open") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun Action(text: String, tag: String, modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
        OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp).testTag(tag), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = if (danger) Look.Red else Look.Ink)) {
            Text(text, fontWeight = FontWeight.SemiBold, maxLines = 2, fontSize = 14.sp)
        }
    }

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
                OutlinedTextField(cap, { cap = it; err = ""; bigOk = false }, label = { Text("Most you'll pay (price cap) \$") }, singleLine = true,
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
                    if (c == null || c <= 0) { err = "Enter the price cap (for example 449.99)."; return@Button }
                    if (c >= 1000 && !bigOk) { err = "A cap of \$${Engine.money(c)} - is that right? Tap Add again to keep it."; bigOk = true; return@Button }
                    busy = true
                    Thread {
                        val asin = Asin.resolve(link) { u -> followOnce(u) }
                        runOnUiThread {
                            busy = false
                            if (asin == null) { err = "That is not an Amazon product link or code. Open the product in the Amazon app, tap Share, and choose Restalker."; return@runOnUiThread }
                            val s = RkApp.store()
                            if (!s.add(Product(id = s.newId(), asin = asin, name = "", cap = c, kind = kind, watch = true, buy = buy))) err = "That product is already in the list."
                            else { s.log("Added Amazon $asin (cap \$${Engine.money(c)})" + if (buy) ", set to Buy." else ". Set it to Buy when you want it bought."); onDone() }
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
    @Composable
    fun TabHeader(text: String) = Text(text, fontWeight = FontWeight.Bold, fontSize = 24.sp, modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 10.dp))

    @Composable
    fun BoughtTab(r: Int) {
        // 2.4 (owner: 'the "bought" section of the app should include the data from the PC version as well ... time, device, price, store,
        // and browser'): every linked device's purchases, newest first
        val list = remember(r) { try { DeskLink.get().buys(DeskLink.name()) } catch (e: Exception) { emptyList() } }
        LazyColumn(Modifier.fillMaxSize().testTag("bought")) {
            item { TabHeader("Bought") }
            if (list.isEmpty()) item { Text("Nothing bought yet (by this device or the linked PC).", color = Look.Muted, modifier = Modifier.padding(horizontal = 20.dp)) }
            else item {
                val total = list.mapNotNull { it.price }.sum()
                Text("${list.size} purchase${if (list.size == 1) "" else "s"}" + (if (total > 0) " · \$${Engine.money(total)} in all" else ""),
                    color = Look.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp).testTag("bought-total"))
            }
            items(list) { x ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().background(Color.White, Look.card).padding(14.dp).testTag("buy-row")) {
                    BuyLines(x, withName = true)
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

    @Composable
    fun ActivityTab(r: Int) {
        val lines = remember(r) { RkApp.store().log().asReversed() }
        LazyColumn(Modifier.fillMaxSize().testTag("log")) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
                    Box(Modifier.weight(1f)) { TabHeader("Activity") }
                    OutlinedButton(onClick = { report() }, modifier = Modifier.padding(top = 12.dp).testTag("report-log")) { Text("Send to Claude") }
                }
            }
            if (lines.isEmpty()) item { Text("Nothing yet.", color = Look.Muted, modifier = Modifier.padding(horizontal = 20.dp)) }
            items(lines) { l ->
                val warn = l.contains("  !")
                Text(l, fontSize = 12.sp, lineHeight = 16.sp, color = if (warn) Look.Red else Look.Ink,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
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
        var confirmUnlink by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings").padding(bottom = 24.dp)) {
            TabHeader("Settings")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ---- the desktop link
                Column(Modifier.fillMaxWidth().background(Look.Navy, Look.card).padding(16.dp)) {
                    Text(if (linked) "Desktop: linked" else "Desktop: not linked", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.testTag("link-title"))
                    Text(if (linked) linkLine else "Link the desktop Restalker: your products stay the same on both, and only one of them buys each product. On the desktop click Link phone, then scan its code.",
                        color = Look.OnNavyDim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp).testTag("link-state"))
                    FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scan() }, colors = Look.btn(),
                            modifier = Modifier.heightIn(min = 44.dp).testTag("scan")) { Text(if (linked) "Scan again" else "Scan the desktop's QR code", fontWeight = FontWeight.SemiBold) }
                        TextButton(onClick = { typing = true }, modifier = Modifier.testTag("pastecode")) { Text("Type the code", color = Look.Mint) }
                        if (linked) TextButton(onClick = { confirmUnlink = true }, modifier = Modifier.testTag("unlink")) { Text("Unlink", color = Look.Mint) }
                    }
                }
                // ---- 2.3: every linked device and its own buying choice (owner: "each mobile device should only control its own buying ...
                // in settings, there should be an option to see all devices linked, with options to control the above buying selection
                // manually/remotely")
                if (linked) LinkedDevices(st, r)
                // ---- Amazon and buying
                Column(Modifier.fillMaxWidth().background(Color.White, Look.card)) {
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
                    SwitchRow("Wi-Fi only", "Saves mobile data", st.wifiOnly, "wifionly") { on -> store.setSettings { it.copy(wifiOnly = on) }; store.log("Wi-Fi only switched ${if (on) "ON" else "OFF"}.") }
                    HorizontalDivider(color = Look.Line)
                    // 1.5 (owner: "We can keep a single icon at the top to indicate that its running, but it should be clearable by user option")
                    // 1.7 (owner: "Why do i have to manually download everything still?")
                    SwitchRow("Update by itself", if (st.autoUpdate) "New versions install by themselves (never during a purchase). ${Updater.state}".trim()
                        else "Off - you are told when there is a new version", st.autoUpdate, "autoupdate") { on ->
                        store.setSettings { it.copy(autoUpdate = on) }; store.log("Updating by itself switched ${if (on) "ON" else "OFF"}."); if (on) Updater.tick(this@MainActivity, force = true, explicit = true) }
                    HorizontalDivider(color = Look.Line)
                    SwitchRow("Running icon", (if (st.runIcon) "An icon at the top while it watches" else "No icon - the notice is tucked away at the bottom of your notifications") +
                        ". Swipe the notice away to hide it; watching goes on.",
                        st.runIcon, "runicon") { on ->
                        store.setSettings { it.copy(runIcon = on) }; store.log("Running icon switched ${if (on) "ON" else "OFF"}.")
                        WatchService.instance?.notifier?.status(WatchService.instance?.notifier?.lastStatus ?: "")
                    }
                }
                // ---- 2.0: a practice checkout with ANY Amazon item in stock (nothing is bought)
                var paLink by remember { mutableStateOf("") }
                var paErr by remember { mutableStateOf("") }
                Column(Modifier.fillMaxWidth().background(Color.White, Look.card).padding(16.dp).testTag("practice-any")) {
                    Text("Practice checkout", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Tests the whole checkout on this phone, without the PC: paste a link of ANY Amazon item that is in stock (a cheap one is fine). " +
                        "It goes all the way to \"Place your order\" and stops there - nothing is bought, and the item is not added to your list.",
                        fontSize = 12.sp, color = Look.Muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
                    OutlinedTextField(paLink, { paLink = it; paErr = "" }, label = { Text("Amazon link or product code") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("pa-link"))
                    if (paErr.isNotEmpty()) Text(paErr, color = Look.Red, fontSize = 12.sp, modifier = Modifier.testTag("pa-err"))
                    Button(colors = Look.btn(), onClick = {
                        val t = paLink
                        Thread {
                            val a = Asin.resolve(t) { u -> followOnce(u) }
                            runOnUiThread {
                                if (a == null) paErr = "That is not an Amazon product link or code."
                                else { paErr = ""; WatchService.cmd(this@MainActivity, "practiceany", a); store.log("Practice checkout asked for $a.") }
                            }
                        }.start()
                    }, modifier = Modifier.padding(top = 8.dp).testTag("pa-go")) { Text("Run the practice") }
                    if (!signed) Text("Sign in to Amazon first (above).", color = Look.AmberText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                // ---- 2.2: all products from the central list (no caps, nothing watched)
                var catMsg by remember { mutableStateOf("") }
                var catBusy by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().background(Color.White, Look.card).padding(16.dp).testTag("catalog")) {
                    Text("Get all products", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Adds every Magic product Restalker knows (names, Amazon codes, MSRP and other stores' links) that is not in your list yet. " +
                        "They come in WITHOUT a cap and are not watched: set a cap and switch Watch / Buy on for the ones you want.",
                        fontSize = 12.sp, color = Look.Muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
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
                                    else -> "${r.added} products added (no cap, not watched). Open one, set its cap, then switch Watch / Buy on."
                                }
                                bump()
                            }
                        }.start()
                    }, modifier = Modifier.padding(top = 8.dp).testTag("catalog-go")) { Text("Get all products") }
                    if (catMsg.isNotEmpty()) Text(catMsg, fontSize = 12.sp, color = Look.TealText, modifier = Modifier.padding(top = 4.dp).testTag("catalog-msg"))
                }
                // ---- 2.0: share the app (its download page)
                Column(Modifier.fillMaxWidth().background(Color.White, Look.card).padding(16.dp).testTag("share"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Share Restalker", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
                    Text("Another phone scans this code to download the app. (Your own tablet: install it there, then link it to the desktop the same way - all stay the same, and only one of them buys.)",
                        fontSize = 12.sp, color = Look.Muted, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 4.dp))
                    val qr = remember { qrBitmap(SHARE_URL, 480) }
                    if (qr != null) androidx.compose.foundation.Image(qr.asImageBitmap(), contentDescription = "Download page QR code",
                        modifier = Modifier.size(180.dp).testTag("share-qr"))
                    TextButton(onClick = {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "Restalker for Android: $SHARE_URL"), "Share Restalker"))
                    }, modifier = Modifier.testTag("share-link")) { Text("Share the link") }
                }
                // ---- problems
                Column(Modifier.fillMaxWidth().background(Color.White, Look.card)) {
                    SwitchRow("Send problems to Claude", "Automatic, at most every 30 min", st.autoReport, "autoreport") { on ->
                        store.setSettings { it.copy(autoReport = on) }; store.log("Automatic problem reports switched ${if (on) "ON" else "OFF"}.") }
                    HorizontalDivider(color = Look.Line)
                    Row(Modifier.fillMaxWidth().clickable { report() }.padding(horizontal = 16.dp, vertical = 14.dp).heightIn(min = 24.dp).testTag("report"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Report a problem now", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = Look.Muted)
                    }
                }
                // ---- the phone
                Column(Modifier.fillMaxWidth().background(Color.White, Look.card).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Step(notes, "Notifications allowed", "Allow") { openNotifSettings() }
                    Step(batt, "Watching all day allowed (battery)", "Allow") { openBattery() }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { Notifier.test(this@MainActivity); store.log("Test message sent.") }, modifier = Modifier.testTag("testmsg")) { Text("Test message") }
                        TextButton(onClick = { openBrowser(null) }, modifier = Modifier.testTag("browser")) { Text("Show browser") }
                        TextButton(onClick = {
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "Restalker Mobile diagnostics").putExtra(Intent.EXTRA_TEXT, Diag.text(this@MainActivity)), "Send diagnostics"))
                        }, modifier = Modifier.testTag("diag")) { Text("Send diagnostics") }
                    }
                }
                WatchService.instance?.dataText()?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Look.Muted, fontSize = 12.sp) }
                // ---- 2.6: the Restalker crowd
                Column(Modifier.fillMaxWidth().background(Color.White, Look.card).testTag("crowd")) {
                    SwitchRow("Share with the Restalker crowd", "${CrowdNet.count()} contributors so far. Shares only when a product of the central list comes in stock " +
                        "(product, store, price, time) - nothing personal.", st.crowd, "crowd-on") { on ->
                        store.setSettings { it.copy(crowd = on) }; store.log("Sharing with the Restalker crowd switched ${if (on) "ON" else "OFF"}.")
                        if (on) CrowdNet.tick(force = true) }
                }
                // ---- 2.6 (owner: 'a section at the bottom of settings for "about" which has a brief paragraph explaining how the importance
                // of crowdsourcing the information for everyone to share')
                Column(Modifier.fillMaxWidth().background(Color.White, Look.card).padding(16.dp).testTag("about")) {
                    Text("About", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Restocks of sought-after Magic products often last only minutes, and no single phone or PC can look at every store all " +
                        "the time. When you share with the Restalker crowd, each device that spots a product from the central list in stock tells " +
                        "every other Restalker right away - so we all see more restocks, sooner, without each of us checking every store. Only the " +
                        "product, the store, the price and the time are shared: no names, accounts, orders or addresses. A sighting from the crowd " +
                        "only makes your device look for itself - it buys only what it sees in stock under your own cap. The more devices share, " +
                        "the better it works for everyone.",
                        fontSize = 13.sp, lineHeight = 19.sp, color = Look.Ink, modifier = Modifier.padding(top = 6.dp).testTag("about-text"))
                    Text("Restalker Mobile v$APP_VERSION", color = Look.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
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

    /** 2.0: a QR code picture (ZXing, already in the app for scanning). */
    private fun qrBitmap(text: String, px: Int): android.graphics.Bitmap? = try {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, px, px, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.RGB_565).also { b ->
            val row = IntArray(px)
            for (y in 0 until px) { for (x in 0 until px) row[x] = if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE; b.setPixels(row, 0, px, 0, y, px, 1) }
        }
    } catch (e: Exception) { null }

    private fun hm(t: Long) = SimpleDateFormat("h:mm a", Locale.US).format(Date(t))
    private fun ago(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000
        return when { s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60} min ago"; else -> SimpleDateFormat("MMM d h:mm a", Locale.US).format(Date(t)) }
    }

    companion object {
        /** 1.7: the app's screen is in front (an update's "Install" question can then be shown at once). */
        @Volatile var shown = false
        const val SHARE_URL = "https://github.com/stevenmanuel01-cloud/Restalker/releases/latest"
        const val UPDATE_URL = "https://raw.githubusercontent.com/stevenmanuel01-cloud/Restalker/main/mobile/latest.json"
        const val RELEASES = "https://github.com/stevenmanuel01-cloud/Restalker/releases"
    }
}
