package com.restalker.mobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.restalker.mobile.BuildConfig
import com.restalker.mobile.RkApp
import com.restalker.mobile.WatchService
import com.restalker.mobile.core.APP_VERSION
import com.restalker.mobile.core.Backup

/** 1.1.1 (owner, 2026-11-23: "i want to be able to save and reload all my data when i download the new app from the play store"):
 *  Settings, Help and about, "Your data" - Save my data / Load my data (core/Backup.kt says what the file holds and why).
 *  The file is written and read through Android's own file chooser: the app needs no permission for files, and the file stays
 *  where the user put it (Downloads) when the app is removed. */
object DataMove {
    /** This device's maker and model - the link with the other devices is only loaded on the same kind of device. */
    fun device(): String = listOf(Build.MANUFACTURER ?: "", Build.MODEL ?: "").filter { it.isNotBlank() }.joinToString(" ")

    /** Tests: the file used instead of Android's file chooser, and what happens instead of the restart. */
    @Volatile var testFile: java.io.File? = null
    @Volatile var testRestart: (() -> Unit)? = null

    fun text(): String = Backup.make(RkApp.store(), APP_VERSION, device(), System.currentTimeMillis())

    /** Writes the data to the place the user chose. Returns what to tell them. */
    fun saveTo(ctx: Context, uri: Uri): String = try {
        val t = text()
        (ctx.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("the file could not be opened")).use { it.write(t.toByteArray(Charsets.UTF_8)) }
        RkApp.store().log("Data saved to a file (${RkApp.store().products().size} products)")
        saved()
    } catch (e: Exception) { "The file could not be written (${e.message ?: e.javaClass.simpleName}). Nothing was changed - try again, or pick another folder." }

    /** (The edition from the download page says how to move to Google Play; the one from Google Play is already there.) */
    fun saved(): String = "Saved. The file holds your products, settings, purchases, Activity and the link with your other devices - keep it to yourself.\n\n" +
        (if (BuildConfig.PLAY) "To put it back, on this phone or a new one: Settings, Help and about, Load my data."
         else "To move to the app from Google Play: remove this app, install Restalker from Google Play, then Settings, Help and about, Load my data.")

    /** Reads a chosen file (never more than a saved file can be). */
    fun readFrom(ctx: Context, uri: Uri): Backup.Info = try {
        val t = (ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("the file could not be opened")).use { s ->
            val r = s.reader(Charsets.UTF_8); val buf = CharArray(65_536); val sb = StringBuilder()
            while (sb.length <= Backup.MAX_CHARS) { val n = r.read(buf); if (n < 0) break; sb.append(buf, 0, n) }
            sb.toString()
        }
        Backup.read(t, device())
    } catch (e: Exception) { Backup.Info("The file could not be read (${e.message ?: e.javaClass.simpleName}).") }

    /** Puts the read data in place and restarts the app, so every part of it starts from what was loaded. */
    fun load(ctx: Context, info: Backup.Info) {
        val store = RkApp.store()
        try { store.setSettings { it.copy(watching = false) }; WatchService.stop(ctx) } catch (e: Exception) { }
        Backup.apply(store, info, System.currentTimeMillis())
        testRestart?.let { it(); return }
        store.freeze()
        try {
            val main = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.component
            if (main != null) ctx.startActivity(Intent.makeRestartActivityTask(main))
        } catch (e: Exception) { }
        Runtime.getRuntime().exit(0)
    }
}

@Composable
fun DataMoveRows() {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf<Backup.Info?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) msg = DataMove.saveTo(ctx, uri) }
    val loader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { val i = DataMove.readFrom(ctx, uri); if (i.ok) ask = i else msg = i.why }
    }
    fun save() {
        val f = DataMove.testFile
        if (f != null) { msg = try { f.writeText(DataMove.text()); DataMove.saved() } catch (e: Exception) { "The file could not be written." }; return }
        try { saver.launch(Backup.fileName(System.currentTimeMillis())) } catch (e: Exception) { msg = "This device has no file chooser, so the file can not be saved here." }
    }
    fun load() {
        val f = DataMove.testFile
        if (f != null) { val i = Backup.read(try { f.readText() } catch (e: Exception) { null }, DataMove.device()); if (i.ok) ask = i else msg = i.why; return }
        try { loader.launch(arrayOf("*/*")) } catch (e: Exception) { msg = "This device has no file chooser, so a file can not be loaded here." }
    }
    Row(Modifier.fillMaxWidth().clickable { save() }.padding(horizontal = 16.dp, vertical = 14.dp).heightIn(min = 24.dp).testTag("data-save"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Save my data", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("Products, caps, settings, purchases and Activity in one file you keep - for a new phone, or before moving to the app from Google Play",
                fontSize = 13.sp, color = Look.Muted)
        }
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = Look.Muted)
    }
    HorizontalDivider(color = Look.Line)
    Row(Modifier.fillMaxWidth().clickable { load() }.padding(horizontal = 16.dp, vertical = 14.dp).heightIn(min = 24.dp).testTag("data-load"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Load my data", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("Put a saved file back. It replaces what is on this device now", fontSize = 13.sp, color = Look.Muted)
        }
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = Look.Muted)
    }
    msg?.let { m ->
        AlertDialog(onDismissRequest = { msg = null }, title = { Text("Your data") }, text = { Text(m, modifier = Modifier.testTag("data-msg")) },
            confirmButton = { TextButton(onClick = { msg = null }, modifier = Modifier.testTag("data-ok")) { Text("OK") } })
    }
    ask?.let { i ->
        AlertDialog(onDismissRequest = { ask = null }, title = { Text("Load this data?") }, text = { Text(Backup.summary(i), modifier = Modifier.testTag("data-summary")) },
            confirmButton = { Button(colors = Look.btn(), onClick = { ask = null; DataMove.load(ctx, i) }, modifier = Modifier.testTag("data-confirm")) { Text("Load it") } },
            dismissButton = { TextButton(onClick = { ask = null }, modifier = Modifier.testTag("data-cancel")) { Text("Cancel") } })
    }
}
