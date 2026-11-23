package com.restalker.mobile.core

/** 1.1.1 (owner, 2026-11-23: "i want to be able to save and reload all my data when i download the new app from the play store").
 *  The version from Google Play carries Google's seal, the one from the download page Restalker's own - a phone will not put one
 *  over the other, so the old one is removed first and everything it kept goes with it. "Save my data" writes it all into one file
 *  the user keeps (Downloads); "Load my data" puts it back.
 *
 *  What is in the file: the products with their caps and switches, the settings, the purchases, Activity (the log), which groups
 *  are folded, and - for the same kind of device only - the link with the other devices and this device's own codes.
 *  What is not: the Amazon sign-in (it lives in the app's browser; sign in again), downloaded lists and pictures, and anything
 *  that is only true for a moment (watching is off after a load: it starts with the user's own tap, as always). */
object Backup {
    const val FORMAT = 1
    const val MARK = "restalker_backup"
    const val MAX_CHARS = 6_000_000
    /** Kept on any device. */
    val FILES = listOf("products.json", "settings.json", "purchases.json", "log.txt", "folded.json", "suggested.txt", "fix16.done")
    /** This device's own: the link with the other devices (its code seals what they tell each other) and the codes this device goes
     *  by. Put back only on the same kind of device - a file loaded on another phone must not make two devices that claim to be one. */
    val DEVICE_FILES = listOf("link.json", "crowd.json", "discord_name.txt")
    val DEVICE_SETTINGS = listOf("masterCode", "reportTopic", "alertTopic", "alertsOn", "deviceName")

    fun fileName(now: Long): String = "restalker-data-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now)) + ".json"

    /** The whole file as text. [device] = this device's maker and model. */
    fun make(store: Store, app: String, device: String, now: Long): String {
        store.flushLog()
        val files = LinkedHashMap<String, Any?>()
        files["products.json"] = store.readFile("products.json") ?: "[]"      // (a device that never had a product has no list yet - its file still loads)
        for (n in FILES + DEVICE_FILES) if (n != "products.json") store.readFile(n)?.let { files[n] = it }
        return MiniJson.write(linkedMapOf(MARK to FORMAT, "app" to app, "at" to now, "device" to device,
            "products" to store.products().size, "purchases" to store.purchases().size, "files" to files))
    }

    /** A file that was read: [why] is empty when it can be loaded, otherwise what is wrong with it in plain words. */
    data class Info(val why: String, val app: String = "", val at: Long = 0, val device: String = "", val sameDevice: Boolean = false,
                    val products: Int = 0, val purchases: Int = 0, val linked: Boolean = false, val files: Map<String, String> = emptyMap()) {
        val ok: Boolean get() = why.isEmpty()
    }

    fun read(text: String?, device: String): Info {
        if (text.isNullOrBlank()) return Info("The file is empty.")
        if (text.length > MAX_CHARS) return Info("The file is too big to be saved Restalker data.")
        val m = MiniJson.obj(text) ?: return Info("This is not a file saved by Restalker (Settings, Help and about, Save my data).")
        val f = m[MARK].asLong() ?: return Info("This is not a file saved by Restalker (Settings, Help and about, Save my data).")
        if (f > FORMAT) return Info("This file was saved by a newer Restalker. Update this app first, then load it again.")
        val raw = m["files"].asMap() ?: return Info("The file is damaged: nothing is in it.")
        val known = (FILES + DEVICE_FILES).toSet()
        val files = LinkedHashMap<String, String>()
        for ((k, v) in raw) if (k in known && v is String) files[k] = v
        val pt = files["products.json"] ?: return Info("The file is damaged: the product list is missing.")
        val products = list(pt) { Product.fromMap(it) } ?: return Info("The file is damaged: the product list can not be read.")
        val put = files["purchases.json"]
        val purchases = if (put == null) emptyList() else (list(put) { Purchase.fromMap(it) } ?: return Info("The file is damaged: the purchases can not be read."))
        val st = files["settings.json"]
        if (st != null && MiniJson.obj(st) == null) return Info("The file is damaged: the settings can not be read.")
        val dev = m["device"].asStr() ?: ""
        val same = dev.isNotBlank() && dev.equals(device, ignoreCase = true)
        return Info("", app = m["app"].asStr() ?: "", at = m["at"].asLong() ?: 0, device = dev, sameDevice = same, products = products.size,
            purchases = purchases.size, linked = same && files["link.json"]?.let { MiniJson.obj(it)?.get("code").asStr()?.isNotBlank() } == true, files = files)
    }

    private fun <T> list(text: String, f: (Map<String, Any?>) -> T?): List<T>? =
        try { (MiniJson.read(text) as? List<*>)?.mapNotNull { e -> e.asMap()?.let(f) } } catch (e: Exception) { null }

    /** What the question before loading says. */
    fun summary(i: Info): String {
        val day = if (i.at > 0) java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.US).format(java.util.Date(i.at)) else "an unknown day"
        return "Saved $day" + (if (i.device.isNotBlank()) " on ${i.device}" else "") + (if (i.app.isNotBlank()) " (Restalker ${i.app})" else "") + ".\n\n" +
            "${i.products} product${if (i.products == 1) "" else "s"}, ${i.purchases} purchase${if (i.purchases == 1) "" else "s"}, your settings and Activity.\n\n" +
            (if (i.sameDevice) "The link with your other devices comes back too."
             else "It was saved on another kind of device, so the link with your other devices is not loaded - link this device again afterwards.") +
            "\n\nIt replaces what is on this device now. You sign in to Amazon again afterwards, and press Start to watch."
    }

    /** Puts a read file in place ([Store.restore]). Returns the line written to Activity. */
    fun apply(store: Store, i: Info, now: Long): String {
        require(i.ok) { i.why }
        val cur = store.settings().toMap()
        val sm = (i.files["settings.json"]?.let { MiniJson.obj(it) } ?: emptyMap()).toMutableMap()
        sm["watching"] = false                                    // watching starts with the user's own tap
        if (!i.sameDevice) for (k in DEVICE_SETTINGS) sm[k] = cur[k]
        val out = LinkedHashMap<String, String?>()
        for (n in FILES) out[n] = i.files[n]                      // (a file the saved data did not have is removed here too)
        out["settings.json"] = MiniJson.write(Settings.fromMap(sm).toMap())
        // purchases already written down on this device stay (2.7: each product is bought at most 3 times - loading an older file
        // must not make a purchase forgotten); a fresh install has none, and gets the file's list as it is
        val mine = store.purchases()
        if (mine.isNotEmpty()) {
            val theirs = i.files["purchases.json"]?.let { t -> list(t) { Purchase.fromMap(it) } } ?: emptyList()
            val have = theirs.map { Triple(it.at, it.asin, it.order) }.toSet()
            val all = (theirs + mine.filter { Triple(it.at, it.asin, it.order) !in have }).sortedByDescending { it.at }.take(500)
            out["purchases.json"] = MiniJson.write(all.map { it.toMap() })
        }
        if (i.sameDevice) for (n in DEVICE_FILES) i.files[n]?.let { out[n] = it }
        val line = "Saved data loaded (${i.products} product${if (i.products == 1) "" else "s"}, ${i.purchases} purchase${if (i.purchases == 1) "" else "s"}" +
            (if (i.device.isNotBlank()) "; saved on ${i.device}" else "") + (if (i.sameDevice) "" else "; the device link was not loaded - link again") + ")"
        store.restore(out)
        store.log(line); store.flushLog()
        return line
    }
}
