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
import com.restalker.mobile.BuildConfig
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RkApp.listeners.add(bump)
        sharedText(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (RkApp.store().settings().watching && WatchService.instance == null) WatchService.start(this)
        checkUpdate()
        setContent { MaterialTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); sharedText(intent) }
    override fun onResume() { super.onResume(); bump() }
    override fun onDestroy() { RkApp.listeners.remove(bump); super.onDestroy() }

    private fun sharedText(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.let { t -> Regex("https?://\\S+").find(t)?.value?.let { shared.value = it } ?: run { shared.value = t } }
    }

    private fun checkUpdate() = Thread {
        try {
            val c = java.net.URL(UPDATE_URL).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            val m = MiniJson.obj(c.inputStream.bufferedReader().use { it.readText() }) ?: return@Thread
            val code = m["code"].asLong() ?: 0
            if (code > BuildConfig.VERSION_CODE) runOnUiThread { update.value = (m["version"].asStr() ?: "?") to (m["apk"].asStr() ?: RELEASES) }
        } catch (e: Exception) { }
    }.start()

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
                    0 -> ProductsTab(st, products, r, onAdd = { adding = true }, onEdit = { editing = it })
                    1 -> PurchasesTab(r)
                    else -> LogTab(r)
                }
            }
        }
        if (adding) AddDialog(shared.value, onDone = { adding = false; shared.value = null })
        editing?.let { p -> EditDialog(p) { editing = null } }
    }

    @Composable
    fun ProductsTab(st: Settings, products: List<Product>, r: Int, onAdd: () -> Unit, onEdit: (Product) -> Unit) {
        val store = RkApp.store()
        val signedIn = remember(r) { CookieManager.getInstance().getCookie("https://www.amazon.com")?.let { it.contains("at-main=") || it.contains("x-main=") || it.contains("sess-at-main=") } == true }
        val battOk = remember(r) { (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) }
        val status = WatchService.instance?.notifier?.lastStatus ?: if (st.watching) "starting..." else "Not watching."
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(status, fontSize = 13.sp, modifier = Modifier.testTag("status"))
                        Toggle("Watching", "Looks at each product on its own, all day (a notification stays while it watches).", st.watching, "watching") { on ->
                            store.setSettings { it.copy(watching = on) }
                            store.log("Watching switched ${if (on) "ON" else "OFF"}.")
                            if (on) WatchService.start(this@MainActivity) else WatchService.stop(this@MainActivity)
                        }
                        Toggle("Buy automatically", "Products set to Buy are bought when in stock at or under their cap. Off = only messages.", st.buying, "buying") { on ->
                            store.setSettings { it.copy(buying = on) }; store.log("Buy automatically switched ${if (on) "ON" else "OFF"}.")
                        }
                        Toggle("Practice mode", "Every checkout stops right before \"Place your order\" - nothing is bought. Switch off when a practice went well.", st.practice, "practice") { on ->
                            store.setSettings { it.copy(practice = on) }; store.log("Practice mode switched ${if (on) "ON (nothing is bought)" else "OFF (real purchases)"}.")
                        }
                        Text("Amazon: " + if (signedIn) "signed in" else "NOT signed in - press Amazon sign-in", fontSize = 13.sp,
                            color = if (signedIn) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { startActivity(Intent(this@MainActivity, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, BrowserActivity.SIGNIN_URL)) }, modifier = Modifier.testTag("signin")) { Text("Amazon sign-in") }
                            OutlinedButton(onClick = { startActivity(Intent(this@MainActivity, BrowserActivity::class.java)) }) { Text("Show browser") }
                        }
                        if (!battOk) TextButton(onClick = {
                            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                        }) { Text("Allow watching all day (battery setting)") }
                        IntervalRow(st)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Products (${products.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Button(onClick = onAdd, modifier = Modifier.testTag("add")) { Text("Add product") }
                }
                if (products.size > 10) Text("Tip: a phone watches a few products best (each is looked at once every ${st.intervalSec} s).", fontSize = 12.sp)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Watch", fontSize = 13.sp); Switch(p.watch, { v -> store.update(p.id) { it.copy(watch = v) } }, Modifier.padding(horizontal = 6.dp).testTag("watch-${p.asin}"))
                    Text("Buy", fontSize = 13.sp); Switch(p.buy, { v -> store.update(p.id) { it.copy(buy = v, watch = it.watch || v) } }, Modifier.padding(horizontal = 6.dp).testTag("buy-${p.asin}"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { WatchService.cmd(this@MainActivity, "check", p.id) }, modifier = Modifier.testTag("check-${p.asin}")) { Text("Check now") }
                    TextButton(onClick = { WatchService.cmd(this@MainActivity, "practice", p.id) }, modifier = Modifier.testTag("practice-${p.asin}")) { Text("Practice") }
                    TextButton(onClick = { onEdit(p) }) { Text("Edit") }
                    if (p.purchased || p.attempted || p.lockedUntil > System.currentTimeMillis()) TextButton(onClick = {
                        store.update(p.id) { it.copy(purchased = false, attempted = false, inProgress = false, fails = 0, lockedUntil = 0, orderNumber = null) }
                        store.log("${p.label}: re-armed by you (it may be bought again).")
                    }) { Text("Re-arm") }
                }
            }
        }
    }

    @Composable
    fun AddDialog(prefill: String?, onDone: () -> Unit) {
        var link by remember { mutableStateOf(prefill ?: "") }
        var cap by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf("other") }
        var err by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = onDone, title = { Text("Add an Amazon product") }, text = {
            Column {
                OutlinedTextField(link, { link = it }, label = { Text("Amazon link or product code (ASIN)") }, modifier = Modifier.testTag("f-link"))
                OutlinedTextField(cap, { cap = it }, label = { Text("Price cap \$ (most you will pay for the item)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.testTag("f-cap"))
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional - taken from Amazon)") }, singleLine = true)
                Text("Kind (the page must show the same kind):", fontSize = 12.sp)
                Row { listOf("box", "bundle", "pack", "other").forEach { k -> FilterChip(kind == k, { kind = k }, { Text(k) }, Modifier.padding(end = 4.dp)) } }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }, confirmButton = {
            Button(onClick = {
                val asin = Asin.parse(link)
                val c = cap.replace("$", "").replace(",", "").trim().toDoubleOrNull()
                err = when {
                    asin == null -> "That is not an Amazon product link or code (short amzn.to links don't show the product: open it, then share or copy the full link)."
                    c == null || c <= 0 -> "Enter the price cap (for example 449.99)."
                    else -> ""
                }
                if (err.isEmpty()) {
                    val s = RkApp.store()
                    if (!s.add(Product(id = s.newId(), asin = asin!!, name = name.trim(), cap = c!!, kind = kind, watch = true, buy = false))) err = "That product is already in the list."
                    else { s.log("Added Amazon $asin (cap \$${Engine.money(c)}). Set it to Buy when you want it bought."); onDone() }
                }
            }, modifier = Modifier.testTag("f-save")) { Text("Add") }
        }, dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } })
    }

    @Composable
    fun EditDialog(p: Product, onDone: () -> Unit) {
        var cap by remember { mutableStateOf(Engine.money(p.cap)) }
        var name by remember { mutableStateOf(p.name) }
        var kind by remember { mutableStateOf(p.kind) }
        var confirmDelete by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = onDone, title = { Text("Edit") }, text = {
            Column {
                OutlinedTextField(cap, { cap = it }, label = { Text("Price cap \$") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row { listOf("box", "bundle", "pack", "other").forEach { k -> FilterChip(kind == k, { kind = k }, { Text(k) }, Modifier.padding(end = 4.dp)) } }
                TextButton(onClick = { if (confirmDelete) { RkApp.store().remove(p.id); RkApp.store().log("${p.label}: removed."); onDone() } else confirmDelete = true }) {
                    Text(if (confirmDelete) "Tap again to remove it" else "Remove this product", color = MaterialTheme.colorScheme.error)
                }
            }
        }, confirmButton = {
            Button(onClick = {
                val c = cap.replace("$", "").replace(",", "").trim().toDoubleOrNull()
                if (c != null && c > 0) {
                    RkApp.store().update(p.id) { it.copy(cap = c, name = name.trim(), kind = kind) }
                    RkApp.store().log("${p.label}: cap \$${Engine.money(c)}.")
                    onDone()
                }
            }) { Text("Save") }
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
