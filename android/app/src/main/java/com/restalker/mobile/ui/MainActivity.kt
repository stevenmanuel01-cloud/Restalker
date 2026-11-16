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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import com.restalker.mobile.core.APP_VERSION
import com.restalker.mobile.core.Asin
import com.restalker.mobile.core.Engine
import com.restalker.mobile.core.Groups
import com.restalker.mobile.core.MiniJson
import com.restalker.mobile.core.Product
import com.restalker.mobile.core.Settings
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
    private fun report() {
        reportResult.value = "Sending..."
        Reporter.send(this, "sent by you") { ok ->
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
        checkUpdate()
        setContent { RkTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); sharedText(intent) }
    override fun onResume() {
        super.onResume(); bump(); ticker.post(tick)
        if (DeskLink.get().linked()) DeskLink.cycleAsync { bump() }          // (opening the app: the desktop's latest at once)
    }
    override fun onPause() { ticker.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { RkApp.listeners.remove(bump); super.onDestroy() }

    private fun sharedText(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.let { t -> shared.value = Asin.firstLink(t) ?: t }
    }

    private fun checkUpdate() = Thread {
        try {
            val c = java.net.URL(UPDATE_URL).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000; c.useCaches = false
            val m = MiniJson.obj(c.inputStream.bufferedReader().use { it.readText() }) ?: return@Thread
            val code = m["code"].asLong() ?: 0
            if (code > BuildConfig.VERSION_CODE) runOnUiThread { update.value = (m["version"].asStr() ?: "?") to (m["apk"].asStr() ?: RELEASES) }
        } catch (e: Exception) { }
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
    }

    // =================================================================== the screen
    @Composable
    fun Screen() {
        val r = rev.intValue
        val store = RkApp.store()
        val st = remember(r) { store.settings() }
        val products = remember(r) { store.products() }
        var tab by remember { mutableIntStateOf(0) }
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
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label, maxLines = 1, fontSize = 12.sp) }, modifier = Modifier.testTag("tab$i"),
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Look.Teal, selectedTextColor = Look.Teal, indicatorColor = Look.TealSoft,
                            unselectedIconColor = Look.Muted, unselectedTextColor = Look.Muted))
                }
            }
        }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (open != null) ProductScreen(open, r, onBack = { openId = null }, onEdit = { editing = it })
                else when (tab) {
                    0 -> WatchTab(st, products, r, onOpen = { openId = it.id }, onAdd = { adding = true }, onSettings = { tab = 3 })
                    1 -> BoughtTab(r)
                    2 -> ActivityTab(r)
                    else -> SettingsTab(st, r, onRealAsk = { confirmReal = true })
                }
            }
        }
        if (adding) AddSheet(shared.value, onDone = { adding = false; shared.value = null })
        editing?.let { p -> CapDialog(p) { editing = null } }
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
            confirmButton = { Button(onClick = {
                store.setSettings { it.copy(practice = false) }; store.log("Practice mode switched OFF (real purchases)."); confirmReal = false
            }, modifier = Modifier.testTag("confirm-real")) { Text("Yes, real purchases") } },
            dismissButton = { TextButton(onClick = { confirmReal = false }) { Text("Keep practicing") } })
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
        val deskBuys = remember(r) { DeskLink.get().desktopBuys() }
        LazyColumn(Modifier.fillMaxSize().testTag("list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.fillMaxWidth().background(Look.Navy).padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Restalker Mobile v$APP_VERSION", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 25.sp,
                            maxLines = 2, modifier = Modifier.weight(1f).testTag("title"))
                        Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = Look.TealBright, contentColor = Look.Navy),
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
                            if (linked) Text(if (deskBuys) "Desktop linked · it buys" else "Desktop linked · this phone buys", color = Look.Mint, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp).testTag("linkchip"))
                        }
                        Switch(checked = st.watching, onCheckedChange = { setWatching(it) }, modifier = Modifier.testTag("watching"),
                            colors = SwitchDefaults.colors(checkedTrackColor = Look.TealBright, checkedThumbColor = Color.White))
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    update.value?.let { (v, apk) -> Banner("Update available: Restalker Mobile v$v", "Get it", "update") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apk))) } }
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
                if (products.isEmpty()) Text("No products yet. Tap Add, or open a product in the Amazon app, tap Share and choose Restalker." +
                    if (!linked) " Or link the desktop (Settings) to get its products." else "",
                    color = Look.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                else Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "In stock", "Set to buy").forEachIndexed { i, l ->
                        FilterChip(filter.intValue == i, { filter.intValue = i }, { Text(l) }, modifier = Modifier.testTag("filter$i"),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Look.TealSoft, selectedLabelColor = Look.TealText))
                    }
                }
            }
            // 1.4 (owner: "The desktop version had several collapsible groups, and can easily be scrolled"): the desktop's groups -
            // release year, then set - each folds with a tap (remembered); what is in stock now stays on top
            val shown = products.filter { when (filter.intValue) { 1 -> it.buyable && !it.purchased; 2 -> it.buy; else -> true } }
            val hot = shown.filter { it.buyable && !it.purchased }
            if (filter.intValue == 0 && hot.isNotEmpty()) {
                item(key = "hdr-hot") { GroupHeader("In stock now", hot.size, null, false, "grp-hot") {} }
                items(hot, key = { "hot-" + it.id }) { p -> ProductCard(p, deskBuys, "top-${p.asin}") { onOpen(p) } }
            }
            if (products.isNotEmpty() && shown.isEmpty()) item { Text(if (filter.intValue == 1) "Nothing is in stock right now." else "No product is set to buy.",
                color = Look.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
            val years = shown.groupBy { Groups.of(it).first }.toList().sortedBy { Groups.yearRank(it.first) }
            for ((year, inYear) in years) {
                val yKey = "y:$year"
                item(key = yKey) { GroupHeader(year, inYear.size, inYear.count { it.buyable && !it.purchased }, yKey in folded, "grp-$year") { fold(yKey) } }
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
            Text(if (done) "✓" else "•", color = if (done) Look.Teal else Look.Muted, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
            Text(text, fontSize = 14.sp, modifier = Modifier.weight(1f), color = if (done) Look.Muted else Look.Ink)
            if (!done && button != null) TextButton(onClick = onClick) { Text(button) }
        }
    }

    /** The product's state in one chip: (text, background, text color). */
    private fun chip(p: Product): Triple<String, Color, Color> = when {
        p.purchased -> Triple("Bought", Look.TealSoft, Look.TealText)
        p.lastCheck == 0L -> Triple("Not checked yet", Look.GreySoft, Look.GreyText)
        p.buyable -> Triple("In stock" + (p.price?.let { " · \$${Engine.money(it)}" } ?: ""), Look.TealSoft, Look.TealText)
        p.price != null && p.price > p.cap -> Triple("Over cap · \$${Engine.money(p.price)}", Look.OrangeSoft, Look.OrangeText)
        p.status.contains("stock", true) || p.status.contains("unavailable", true) -> Triple("Out of stock", Look.GreySoft, Look.GreyText)
        else -> Triple(p.status.take(28), Look.GreySoft, Look.GreyText)
    }

    private fun buyLine(p: Product, deskBuys: Boolean): String = when {
        p.inProgress -> "Buying now…"
        p.purchased -> "Bought${p.orderNumber?.let { " · order $it" } ?: ""}"
        p.attempted -> "An order may have been placed - check Amazon"
        p.lockedUntil > System.currentTimeMillis() -> "Paused after failed tries until ${hm(p.lockedUntil)}"
        !p.watch -> "Off (not watched)"
        p.buy && deskBuys -> "Buy on · the desktop buys it"
        p.buy -> "Buy on" + if (p.lastCheck > 0) " · checked ${ago(p.lastCheck)}" else ""
        else -> "Alerts only" + if (p.lastCheck > 0) " · checked ${ago(p.lastCheck)}" else ""
    }

    @Composable
    fun Thumb(p: Product, size: Int) {
        Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(Look.GreySoft), contentAlignment = Alignment.Center) {
            Text(p.label.split(Regex("[^A-Za-z0-9]+")).filter { it.length > 2 && it.lowercase() !in setOf("the", "magic", "gathering") }.take(2)
                .joinToString("") { it.take(1).uppercase() }.ifBlank { "?" }, color = Look.GreyText, fontWeight = FontWeight.Bold, fontSize = (size / 3).sp)
        }
    }

    @Composable
    fun ProductCard(p: Product, deskBuys: Boolean, tag: String = "product-${p.asin}", onClick: () -> Unit) {
        val (c, bg, fg) = chip(p)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(Look.card).background(Color.White)
            .then(if (p.inProgress || (p.buyable && p.buy)) Modifier.border(2.dp, Look.TealBright, Look.card) else Modifier)
            .clickable(onClick = onClick).padding(14.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            Thumb(p, 56)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(p.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 5.dp)) {
                    Chip(c, bg, fg, Modifier.testTag("pstatus-${p.asin}"))
                    Text("  cap \$${Engine.money(p.cap)}", color = Look.Muted, fontSize = 12.sp, maxLines = 1)
                }
                Text(buyLine(p, deskBuys), color = if (p.inProgress) Look.Teal else if (p.attempted) Look.Red else Look.Muted, fontSize = 12.sp,
                    fontWeight = if (p.inProgress) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    // =================================================================== one product
    @Composable
    fun ProductScreen(p: Product, r: Int, onBack: () -> Unit, onEdit: (Product) -> Unit) {
        val store = RkApp.store()
        var confirmRemove by remember(p.id) { mutableStateOf(false) }
        val deskBuys = remember(r) { DeskLink.get().desktopBuys() }
        val lines = remember(r) { store.log().filter { it.contains(p.label) }.takeLast(6).asReversed() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("product-screen")) {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("back")) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                Text("Product", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { openBrowser(p.url) }) { Text("Open on Amazon") }
            }
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Thumb(p, 76)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(p.label, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 23.sp)
                        Text("Amazon · ${p.asin} · ${p.kind}", color = Look.Muted, fontSize = 12.sp)
                    }
                }
                Column(Modifier.fillMaxWidth().background(Color.White, Look.card).padding(16.dp)) {
                    val (c, bg, fg) = chip(p)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Now", color = Look.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Chip(c, bg, fg) }
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
                        Text(p.price?.let { "\$${Engine.money(it)}" } ?: "—", fontWeight = FontWeight.Bold, fontSize = 30.sp)
                        Text("   your cap \$${Engine.money(p.cap)}", color = Look.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 5.dp))
                    }
                    val frac = p.price?.let { (it / p.cap).toFloat().coerceIn(0f, 1f) } ?: 0f
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = if ((p.price ?: 0.0) > p.cap) Look.OrangeText else Look.TealBright, trackColor = Look.GreySoft)
                    Text((if (p.lastCheck > 0) "Checked ${ago(p.lastCheck)}" else "Not checked yet") + " · " + p.status, color = Look.Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
                Column(Modifier.fillMaxWidth().background(Color.White, Look.card)) {
                    SwitchRow("Buy when in stock", if (deskBuys && p.buy) "The linked desktop buys it; this phone takes over if the desktop stops" else "One order, at or under your cap",
                        p.buy, "buy-${p.asin}") { v -> store.update(p.id) { it.copy(buy = v, watch = it.watch || v) }; store.log("${p.label}: Buy ${if (v) "ON" else "OFF"}.") }
                    HorizontalDivider(color = Look.Line)
                    SwitchRow("Watch", "Looked at all day; a phone message when it comes back", p.watch, "watch-${p.asin}") { v -> store.update(p.id) { it.copy(watch = v) } }
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
                if (lines.isNotEmpty()) {
                    Text("Latest", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Look.GreyText)
                    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        lines.forEach { Text(it.replace(p.label + ": ", ""), fontSize = 12.sp, lineHeight = 16.sp) }
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
                colors = SwitchDefaults.colors(checkedTrackColor = Look.Teal, checkedThumbColor = Color.White))
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
                Button(enabled = !busy, onClick = {
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
            Switch(checked = on, onCheckedChange = set, modifier = Modifier.testTag(tag), colors = SwitchDefaults.colors(checkedTrackColor = Look.Teal))
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
            Button(onClick = {
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
        val list = remember(r) { RkApp.store().purchases() }
        LazyColumn(Modifier.fillMaxSize().testTag("bought")) {
            item { TabHeader("Bought") }
            if (list.isEmpty()) item { Text("Nothing bought by the app yet.", color = Look.Muted, modifier = Modifier.padding(horizontal = 20.dp)) }
            items(list) { x ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().background(Color.White, Look.card).padding(14.dp)) {
                    Text(x.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(x.at)) + (x.price?.let { " · \$${Engine.money(it)}" } ?: "") +
                        (x.order?.let { " · order $it" } ?: ""), fontSize = 13.sp, color = Look.Muted)
                    Chip(if (x.verified) "In your Amazon orders" else x.note.ifBlank { "Not found in your orders - check Amazon" },
                        if (x.verified) Look.TealSoft else Look.AmberSoft, if (x.verified) Look.TealText else Look.AmberText, Modifier.padding(top = 6.dp))
                }
            }
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
                        Button(onClick = { scan() }, colors = ButtonDefaults.buttonColors(containerColor = Look.TealBright, contentColor = Look.Navy),
                            modifier = Modifier.heightIn(min = 44.dp).testTag("scan")) { Text(if (linked) "Scan again" else "Scan the desktop's QR code", fontWeight = FontWeight.SemiBold) }
                        TextButton(onClick = { typing = true }, modifier = Modifier.testTag("pastecode")) { Text("Type the code", color = Look.Mint) }
                        if (linked) TextButton(onClick = { confirmUnlink = true }, modifier = Modifier.testTag("unlink")) { Text("Unlink", color = Look.Mint) }
                    }
                }
                // ---- Amazon and buying
                Column(Modifier.fillMaxWidth().background(Color.White, Look.card)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Amazon account", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(if (signed) "Signed in" else "Not signed in", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (signed) Look.Teal else Look.Red)
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
                Text("Restalker Mobile v$APP_VERSION", color = Look.Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
        if (typing) {
            var code by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { typing = false }, title = { Text("Type the link code") }, text = {
                Column {
                    Text("On the desktop: Link phone. The code is under the QR picture (starts with RK1.).", fontSize = 13.sp)
                    OutlinedTextField(code, { code = it }, singleLine = true, label = { Text("RK1....") }, modifier = Modifier.testTag("link-code"))
                }
            }, confirmButton = { Button(onClick = { typing = false; linkWith(code) }, modifier = Modifier.testTag("link-save")) { Text("Link") } },
                dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } })
        }
        if (confirmUnlink) AlertDialog(onDismissRequest = { confirmUnlink = false }, title = { Text("Unlink the desktop?") },
            text = { Text("The phone keeps its products and buys on its own again. Products are no longer shared.") },
            confirmButton = { Button(onClick = { confirmUnlink = false; Thread { DeskLink.get().unlink(); runOnUiThread { bump() } }.start() }, modifier = Modifier.testTag("unlink-yes")) { Text("Unlink") } },
            dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text("Cancel") } })
    }

    private fun hm(t: Long) = SimpleDateFormat("h:mm a", Locale.US).format(Date(t))
    private fun ago(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000
        return when { s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60} min ago"; else -> SimpleDateFormat("MMM d h:mm a", Locale.US).format(Date(t)) }
    }

    companion object {
        const val UPDATE_URL = "https://raw.githubusercontent.com/stevenmanuel01-cloud/Restalker/main/mobile/latest.json"
        const val RELEASES = "https://github.com/stevenmanuel01-cloud/Restalker/releases"
    }
}
