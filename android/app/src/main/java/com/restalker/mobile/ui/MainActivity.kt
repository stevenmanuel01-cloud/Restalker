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
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.restalker.mobile.BuildConfig
import com.restalker.mobile.Diag
import com.restalker.mobile.Notifier
import com.restalker.mobile.RkApp
import com.restalker.mobile.WatchService
import com.restalker.mobile.core.APP_VERSION
import com.restalker.mobile.core.Asin
import com.restalker.mobile.core.Engine
import com.restalker.mobile.core.MiniJson
import com.restalker.mobile.core.Product
import com.restalker.mobile.core.Settings
import com.restalker.mobile.core.asLong
import com.restalker.mobile.core.asStr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val rev = mutableIntStateOf(0)
    private val bump: () -> Unit = { rev.intValue++ }
    private var shared = mutableStateOf<String?>(null)
    private var update = mutableStateOf<Pair<String, String>?>(null)
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { bump(); ticker.postDelayed(this, 5_000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RkApp.listeners.add(bump)
        sharedText(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (RkApp.store().settings().watching && WatchService.instance == null) try { WatchService.start(this) } catch (e: Exception) { }
        checkUpdate()
        setContent { MaterialTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); sharedText(intent) }
    override fun onResume() { super.onResume(); bump(); ticker.post(tick) }
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Screen() {
        val r = rev.intValue
        val store = RkApp.store()
        val st = remember(r) { store.settings() }
        val products = remember(r) { store.products() }
        var tab by remember { mutableIntStateOf(0) }
        var adding by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<Product?>(null) }
        var confirmReal by remember { mutableStateOf(false) }
        if (shared.value != null && !adding) adding = true
        Scaffold(topBar = { TopAppBar(title = { Text("Restalker Mobile v$APP_VERSION", fontWeight = FontWeight.Bold, modifier = Modifier.testTag("title")) }) }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                update.value?.let { (v, apk) ->
                    Card(Modifier.fillMaxWidth().padding(8.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Update available: v$v", Modifier.weight(1f))
                            Button(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apk))) }) { Text("Get it") }
                        }
                    }
                }
                TabRow(selectedTabIndex = tab) {
                    listOf("Products", "Purchases", "Log").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }, modifier = Modifier.testTag("tab$i")) }
                }
                when (tab) {
                    0 -> ProductsTab(st, products, r, onAdd = { adding = true }, onEdit = { editing = it }, onRealAsk = { confirmReal = true })
                    1 -> PurchasesTab(r)
                    else -> LogTab(r)
                }
            }
        }
        if (adding) AddDialog(shared.value, onDone = { adding = false; shared.value = null })
        editing?.let { p -> EditDialog(p) { editing = null } }
        if (confirmReal) AlertDialog(onDismissRequest = { confirmReal = false }, title = { Text("Make real purchases?") },
            text = { Text("With Practice mode off, products set to Buy are really ordered on Amazon when they are in stock at or under their cap.") },
            confirmButton = { Button(onClick = {
                store.setSettings { it.copy(practice = false) }; store.log("Practice mode switched OFF (real purchases)."); confirmReal = false
            }, modifier = Modifier.testTag("confirm-real")) { Text("Yes, real purchases") } },
            dismissButton = { TextButton(onClick = { confirmReal = false }) { Text("Keep practicing") } })
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun SetupCard(products: List<Product>, r: Int) {
        val notes = remember(r) { notesOk() }; val signed = remember(r) { signedIn() }; val batt = remember(r) { battOk() }
        val practiced = products.any { it.practiceOkAt > 0 }
        if (notes && signed && batt && products.isNotEmpty() && practiced) return
        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("setup"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text("Getting started", fontWeight = FontWeight.Bold)
                Step(notes, "1. Allow notifications (restock and purchase messages)", "Allow") { openNotifSettings() }
                Step(signed, "2. Sign in to Amazon inside the app", "Sign in") { openBrowser(BrowserActivity.SIGNIN_URL) }
                Step(batt, "3. Let it watch all day (battery setting)", "Allow") { openBattery() }
                Step(products.isNotEmpty(), "4. Add a product (paste or share an Amazon link)", null) {}
                Step(practiced, "5. Run one Practice on an in-stock product (nothing is bought)", null) {}
                if (!batt) Text("Samsung / Xiaomi / OnePlus phones: also set Restalker to \"Unrestricted\" in the phone's battery settings for the app.", fontSize = 12.sp)
            }
        }
    }

    @Composable
    fun Step(done: Boolean, text: String, button: String?, onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text((if (done) "✓ " else "• ") + text, fontSize = 13.sp, modifier = Modifier.weight(1f),
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer)
            if (!done && button != null) TextButton(onClick = onClick) { Text(button) }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun ProductsTab(st: Settings, products: List<Product>, r: Int, onAdd: () -> Unit, onEdit: (Product) -> Unit, onRealAsk: () -> Unit) {
        val store = RkApp.store()
        val signed = remember(r) { signedIn() }
        val notes = remember(r) { notesOk() }
        val svc = WatchService.instance
        val status = svc?.notifier?.lastStatus?.ifBlank { null } ?: if (st.watching) "starting..." else "Not watching."
        val now = System.currentTimeMillis()
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            item { SetupCard(products, r) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(status, fontSize = 13.sp, modifier = Modifier.testTag("status"))
                        svc?.dataText()?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp) }
                        if (svc != null && !svc.web.available) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("This phone's \"Android System WebView\" is too old for Restalker - update it in the Play Store.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview"))) } catch (e: Exception) { } }) { Text("Update") }
                        }
                        if (!notes) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Notifications are OFF - you won't hear about restocks or purchases.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = { openNotifSettings() }) { Text("Fix") }
                        }
                        if (st.snoozeUntil > now) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Restock messages snoozed until ${SimpleDateFormat("h:mm a", Locale.US).format(Date(st.snoozeUntil))}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { store.setSettings { it.copy(snoozeUntil = 0) }; store.log("Snooze ended by you.") }) { Text("Unsnooze") }
                        }
                        if (st.cooldownUntil > now) Text("Resting: Amazon showed a robot check. Show browser and finish it yourself; it looks again at ${SimpleDateFormat("h:mm a", Locale.US).format(Date(st.cooldownUntil))}.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        Toggle("Watching", "Looks at each product on its own, all day (a notification stays while it watches).", st.watching, "watching") { on ->
                            store.setSettings { it.copy(watching = on) }
                            store.log("Watching switched ${if (on) "ON" else "OFF"}.")
                            try { if (on) WatchService.start(this@MainActivity) else WatchService.stop(this@MainActivity) } catch (e: Exception) { store.log("! Could not start watching: ${e.message}") }
                        }
                        Toggle("Buy automatically", "Products set to Buy are bought when in stock at or under their cap. Off = only messages.", st.buying, "buying") { on ->
                            store.setSettings { it.copy(buying = on) }; store.log("Buy automatically switched ${if (on) "ON" else "OFF"}.")
                        }
                        Toggle("Practice mode", "ON: every checkout stops right before \"Place your order\" - nothing is bought. Switch off for real purchases.", st.practice, "practice") { on ->
                            if (on) { store.setSettings { it.copy(practice = true) }; store.log("Practice mode switched ON (nothing is bought).") } else onRealAsk()
                        }
                        Toggle("Wi-Fi only", "Look only while on Wi-Fi (saves mobile data).", st.wifiOnly, "wifionly") { on ->
                            store.setSettings { it.copy(wifiOnly = on) }; store.log("Wi-Fi only switched ${if (on) "ON" else "OFF"}.")
                        }
                        Text("Amazon: " + if (signed) "signed in" else "NOT signed in - tap Amazon sign-in", fontSize = 13.sp,
                            color = if (signed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { openBrowser(BrowserActivity.SIGNIN_URL) }, modifier = Modifier.testTag("signin")) { Text("Amazon sign-in") }
                            OutlinedButton(onClick = { openBrowser(null) }) { Text("Show browser") }
                            OutlinedButton(onClick = { Notifier.test(this@MainActivity); store.log("Test message sent.") }, modifier = Modifier.testTag("testmsg")) { Text("Test message") }
                            OutlinedButton(onClick = {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, "Restalker Mobile diagnostics").putExtra(Intent.EXTRA_TEXT, Diag.text(this@MainActivity)), "Send diagnostics"))
                            }, modifier = Modifier.testTag("diag")) { Text("Send diagnostics") }
                        }
                        IntervalRow(st)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Products (${products.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Button(onClick = onAdd, modifier = Modifier.testTag("add")) { Text("Add product") }
                }
                if (products.isEmpty()) Text("No products yet. Tap Add product, or share a product from the Amazon app to Restalker.", fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                if (products.size > 10) Text("Tip: a phone watches a few products best (each is looked at once every ${st.intervalSec} s).", fontSize = 12.sp)
                if (products.any { it.buy } && !signed) Text("Products are set to Buy but Amazon is not signed in - nothing can be bought until you sign in.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            items(products, key = { it.id }) { p -> ProductCard(p, onEdit) }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    @Composable
    fun IntervalRow(st: Settings) {
        var txt by remember(st.intervalSec) { mutableStateOf(st.intervalSec.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Look at each product every", fontSize = 13.sp)
            OutlinedTextField(txt, { txt = it.filter(Char::isDigit).take(4) }, Modifier.width(84.dp).padding(horizontal = 6.dp), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("s", fontSize = 13.sp)
            TextButton(onClick = {
                val v = (txt.toIntOrNull() ?: 90).coerceIn(Settings.MIN_INTERVAL, 3600)
                RkApp.store().setSettings { it.copy(intervalSec = v) }; txt = v.toString()
            }) { Text("Set") }
        }
    }

    @Composable
    fun Toggle(label: String, help: String, on: Boolean, tag: String, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(label, fontWeight = FontWeight.SemiBold); Text(help, fontSize = 12.sp) }
            Switch(checked = on, onCheckedChange = set, modifier = Modifier.testTag(tag))
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun ProductCard(p: Product, onEdit: (Product) -> Unit) {
        val store = RkApp.store()
        Card(Modifier.fillMaxWidth().padding(vertical = 5.dp).testTag("product-${p.asin}")) {
            Column(Modifier.padding(12.dp)) {
                Text(p.label, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text("Amazon ${p.asin} · cap \$${Engine.money(p.cap)} · ${p.kind}", fontSize = 12.sp)
                Text(p.status + (if (p.lastCheck > 0) " · ${ago(p.lastCheck)}" else ""), fontSize = 13.sp, modifier = Modifier.testTag("pstatus-${p.asin}"),
                    color = if (p.buyable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (p.purchased || p.attempted) Text(if (p.purchased) "Bought${p.orderNumber?.let { " (order $it)" } ?: ""} - not bought again until Re-arm" else "An order may have been placed - check Amazon, then Re-arm",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                if (p.lockedUntil > System.currentTimeMillis()) Text("Paused after failed checkouts until ${SimpleDateFormat("h:mm a", Locale.US).format(Date(p.lockedUntil))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                FlowRow(verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Watch", fontSize = 13.sp); Switch(p.watch, { v -> store.update(p.id) { it.copy(watch = v) } }, Modifier.padding(horizontal = 6.dp).testTag("watch-${p.asin}"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Buy", fontSize = 13.sp); Switch(p.buy, { v -> store.update(p.id) { it.copy(buy = v, watch = it.watch || v) }; store.log("${p.label}: Buy ${if (v) "ON" else "OFF"}.") },
                            Modifier.padding(horizontal = 6.dp).testTag("buy-${p.asin}"))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { WatchService.cmd(this@MainActivity, "check", p.id) }, modifier = Modifier.testTag("check-${p.asin}")) { Text("Check now") }
                    TextButton(onClick = { WatchService.cmd(this@MainActivity, "practice", p.id) }, modifier = Modifier.testTag("practice-${p.asin}")) { Text("Practice") }
                    TextButton(onClick = { openBrowser(p.url) }) { Text("Open") }
                    TextButton(onClick = { onEdit(p) }, modifier = Modifier.testTag("edit-${p.asin}")) { Text("Edit") }
                    if (p.purchased || p.attempted || p.lockedUntil > System.currentTimeMillis()) TextButton(onClick = {
                        store.update(p.id) { it.copy(purchased = false, attempted = false, inProgress = false, fails = 0, lockedUntil = 0, orderNumber = null) }
                        store.log("${p.label}: re-armed by you (it may be bought again).")
                    }, modifier = Modifier.testTag("rearm-${p.asin}")) { Text("Re-arm") }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun KindChips(kind: String, set: (String) -> Unit) {
        FlowRow { listOf("box", "bundle", "pack", "other").forEach { k -> FilterChip(kind == k, { set(k) }, { Text(k) }, Modifier.padding(end = 4.dp)) } }
    }

    @Composable
    fun AddDialog(prefill: String?, onDone: () -> Unit) {
        var link by remember { mutableStateOf(prefill ?: "") }
        var cap by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf("other") }
        var err by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var bigOk by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!busy) onDone() }, title = { Text("Add an Amazon product") }, text = {
            Column {
                OutlinedTextField(link, { link = it; err = "" }, label = { Text("Amazon link or product code (ASIN)") }, modifier = Modifier.testTag("f-link"))
                OutlinedTextField(cap, { cap = it; err = ""; bigOk = false }, label = { Text("Price cap \$ (most you will pay for the item)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.testTag("f-cap"))
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional - taken from Amazon)") }, singleLine = true)
                Text("Kind (the page must show the same kind; \"other\" = don't check):", fontSize = 12.sp)
                KindChips(kind) { kind = it }
                if (busy) Text("Opening the short link...", fontSize = 13.sp)
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.testTag("f-err"))
            }
        }, confirmButton = {
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
                        if (!s.add(Product(id = s.newId(), asin = asin, name = name.trim(), cap = c, kind = kind, watch = true, buy = false))) err = "That product is already in the list."
                        else { s.log("Added Amazon $asin (cap \$${Engine.money(c)}). Set it to Buy when you want it bought."); onDone() }
                    }
                }.start()
            }, modifier = Modifier.testTag("f-save")) { Text("Add") }
        }, dismissButton = { TextButton(onClick = onDone, enabled = !busy) { Text("Cancel") } })
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
    fun EditDialog(p: Product, onDone: () -> Unit) {
        var cap by remember { mutableStateOf(Engine.money(p.cap)) }
        var name by remember { mutableStateOf(p.name) }
        var kind by remember { mutableStateOf(p.kind) }
        var confirmDelete by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = onDone, title = { Text("Edit") }, text = {
            Column {
                OutlinedTextField(cap, { cap = it; err = "" }, label = { Text("Price cap \$") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.testTag("e-cap"))
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                KindChips(kind) { kind = it }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                TextButton(onClick = { if (confirmDelete) { RkApp.store().remove(p.id); RkApp.store().log("${p.label}: removed."); onDone() } else confirmDelete = true }, modifier = Modifier.testTag("e-remove")) {
                    Text(if (confirmDelete) "Tap again to remove it" else "Remove this product", color = MaterialTheme.colorScheme.error)
                }
            }
        }, confirmButton = {
            Button(onClick = {
                val c = cap.replace("$", "").replace(",", "").trim().toDoubleOrNull()
                if (c == null || c <= 0) { err = "Enter the price cap (for example 449.99)."; return@Button }
                RkApp.store().update(p.id) { it.copy(cap = c, name = name.trim(), kind = kind) }
                RkApp.store().log("${p.label}: cap \$${Engine.money(c)}.")
                onDone()
            }, modifier = Modifier.testTag("e-save")) { Text("Save") }
        }, dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } })
    }

    @Composable
    fun PurchasesTab(r: Int) {
        val list = remember(r) { RkApp.store().purchases() }
        LazyColumn(Modifier.fillMaxSize().padding(10.dp)) {
            if (list.isEmpty()) item { Text("Nothing bought by the app yet.") }
            items(list) { x ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(x.name, fontWeight = FontWeight.SemiBold)
                        Text(SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(x.at)) + (x.price?.let { " · \$${Engine.money(it)}" } ?: "") +
                            (x.order?.let { " · order $it" } ?: ""), fontSize = 13.sp)
                        Text(if (x.verified) "In your Amazon orders" else (x.note.ifBlank { "Not found in your order list - check Amazon" }), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    @Composable
    fun LogTab(r: Int) {
        val lines = remember(r) { RkApp.store().log().asReversed() }
        LazyColumn(Modifier.fillMaxSize().padding(10.dp).testTag("log")) {
            if (lines.isEmpty()) item { Text("Nothing yet.") }
            items(lines) { Text(it, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }

    private fun ago(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000
        return when { s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60} min ago"; else -> SimpleDateFormat("MMM d h:mm a", Locale.US).format(Date(t)) }
    }

    companion object {
        const val UPDATE_URL = "https://raw.githubusercontent.com/stevenmanuel01-cloud/Restalker/main/mobile/latest.json"
        const val RELEASES = "https://github.com/stevenmanuel01-cloud/Restalker/releases"
    }
}
