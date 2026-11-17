package com.restalker.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/** Test builds only: the virtual-phone test (ci/phone.sh) gives the updater a newer version as a local file with its fingerprint -
 *  the same check and the same install as a real update, without GitHub. */
class UpdateTestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val rel = i.getStringExtra("apk") ?: return
        if (rel.contains("..")) return
        val f = File(ctx.dataDir, rel)
        val sha = i.getStringExtra("sha") ?: ""
        val v = i.getStringExtra("version") ?: "test"
        val pr = goAsync()
        Thread {
            try { if (Updater.fingerprintOk(f, sha)) Updater.install(ctx.applicationContext, f, v) } catch (e: Exception) { RkApp.store().log("! test update: ${e.message}") }
            finally { pr.finish() }
        }.start()
    }
}
