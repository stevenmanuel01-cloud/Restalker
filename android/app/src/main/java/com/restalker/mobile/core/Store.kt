package com.restalker.mobile.core

import java.io.File

/** Everything the app keeps, as small JSON files in its own private folder (no account, no passwords: store sign-ins stay inside
 *  the app's browser, like Chrome keeps you signed in). Every write goes to a temporary file first, then replaces the old one. */
class Store(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private var products: List<Product> = emptyList()
    private var settings: Settings = Settings()
    private var purchases: List<Purchase> = emptyList()
    private val logLines = ArrayDeque<String>()
    var onChange: (() -> Unit)? = null

    init {
        dir.mkdirs()
        products = (MiniJson.read(read("products.json") ?: "[]") as? List<*>)?.mapNotNull { it.asMap()?.let(Product::fromMap) } ?: emptyList()
        settings = Settings.fromMap(MiniJson.obj(read("settings.json")))
        purchases = (MiniJson.read(read("purchases.json") ?: "[]") as? List<*>)?.mapNotNull { it.asMap()?.let(Purchase::fromMap) } ?: emptyList()
        read("log.txt")?.lines()?.filter { it.isNotBlank() }?.takeLast(LOG_MAX)?.forEach { logLines.addLast(it) }
        // a checkout that was running when the app was closed: it is not running any more (the lock fields stay as they were)
        if (products.any { it.inProgress }) { products = products.map { it.copy(inProgress = false) }; saveProducts() }
    }

    private fun read(name: String): String? = try { File(dir, name).takeIf { it.exists() }?.readText() } catch (e: Exception) { null }
    private fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp"); tmp.writeText(text)
        if (!tmp.renameTo(File(dir, name))) { File(dir, name).delete(); tmp.renameTo(File(dir, name)) }
    }
    private fun saveProducts() = write("products.json", MiniJson.write(products.map { it.toMap() }))

    fun products(): List<Product> = synchronized(lock) { products }
    fun product(id: String): Product? = synchronized(lock) { products.firstOrNull { it.id == id } }
    fun settings(): Settings = synchronized(lock) { settings }
    fun purchases(): List<Purchase> = synchronized(lock) { purchases }
    fun log(): List<String> = synchronized(lock) { logLines.toList() }

    fun update(id: String, f: (Product) -> Product): Product? {
        val out = synchronized(lock) {
            val i = products.indexOfFirst { it.id == id }; if (i < 0) return null
            val n = f(products[i]); products = products.toMutableList().also { it[i] = n }; saveProducts(); n
        }
        onChange?.invoke(); return out
    }
    fun add(p: Product): Boolean {
        synchronized(lock) { if (products.any { it.asin == p.asin }) return false; products = products + p; saveProducts() }
        onChange?.invoke(); return true
    }
    fun remove(id: String) { synchronized(lock) { products = products.filter { it.id != id }; saveProducts() }; onChange?.invoke() }
    fun setSettings(f: (Settings) -> Settings): Settings {
        val s = synchronized(lock) { settings = f(settings); write("settings.json", MiniJson.write(settings.toMap())); settings }
        onChange?.invoke(); return s
    }
    fun addPurchase(p: Purchase) {
        synchronized(lock) { purchases = (listOf(p) + purchases).take(500); write("purchases.json", MiniJson.write(purchases.map { it.toMap() })) }
        onChange?.invoke()
    }
    fun log(line: String) {
        val t = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(clock()))
        synchronized(lock) {
            logLines.addLast("$t  $line"); while (logLines.size > LOG_MAX) logLines.removeFirst()
            if (++writes % 10 == 0 || line.startsWith("!")) write("log.txt", logLines.joinToString("\n"))
        }
        onChange?.invoke()
    }
    fun flushLog() = synchronized(lock) { write("log.txt", logLines.joinToString("\n")) }
    private var writes = 0

    fun newId(): String = synchronized(lock) { "p" + clock().toString(36) + (products.size + 1) }

    companion object { const val LOG_MAX = 400 }
}
