package com.restalker.mobile

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.restalker.mobile.core.Store
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class RkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        com.restalker.mobile.core.Pics.broken = { u -> Pics2.broken(u) }
        Notifier.channels(this)
        // 2.15 (owner, 2026-11-19: "it should be happening simultaneously with the PC. Its a race to checkout, so whichever happens first
        // is the winner"): once, every device that only watched ("PC only") is set to buy too (Who buys: both). With a PC that does not
        // know the shared lock yet, "both" still waits for the PC's own try at Place your order (2.2) - never two orders.
        try {
            val st = store()
            if (st.settings().modeV < 1) store().setSettings { it.copy(modeV = 1, buyMode = if (it.buyMode == "pc") "both" else it.buyMode,
                buyModeAt = if (it.buyMode == "pc") System.currentTimeMillis() else it.buyModeAt) }
        } catch (e: Exception) { }
        // 2.1: after an update the linked PC learns this app's version at once (it then sends no "new version" message)
        Thread {
            try {
                val s = store(); val v = BuildConfig.VERSION_CODE.toString()
                if (s.readFile("lastav") != v) { if (DeskLink.get().linked()) DeskLink.get().touched(); s.writeFile("lastav", v) }
            } catch (e: Exception) { }
        }.start()
        // 1.7: a crash is written down; the self-check sends it to Claude by itself the next time the app runs
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                HealthRunner.crashFile(this).writeText("${e.javaClass.name}: ${e.message?.take(200)} (thread ${t.name}, " +
                    "v${com.restalker.mobile.core.APP_VERSION}, ${java.util.Date()})\n" + sw.toString().take(6000))
            } catch (x: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        lateinit var app: RkApp
            private set
        private var _store: Store? = null
        private val main = Handler(Looper.getMainLooper())
        /** Called on the main thread whenever products / settings / the log change (the screens redraw). */
        val listeners = CopyOnWriteArrayList<() -> Unit>()

        private var lastDir: File? = null
        @Synchronized fun store(dirOverride: File? = null): Store {
            _store?.let { return it }
            val d = dirOverride ?: File(app.filesDir, "rk"); lastDir = d
            val s = Store(d)
            s.onChange = { main.post { listeners.forEach { it() } } }
            _store = s
            return s
        }

        /** Tests only: start again with an empty folder. */
        /** Tests: read everything again from the files (as when the app is started again). */
        @Synchronized fun resetStoreKeepFiles() { val d = lastDir; _store = null; store(d) }

        @Synchronized fun resetForTests(dir: File) { dir.deleteRecursively(); _store = null; store(dir); DeskLink.reset(); CrowdNet.reset() }
    }
}

/** 2.8: a picture that failed to load twice here (any of the sizes tried). */
internal object Pics2 {
    fun broken(u: String): Boolean = PicCache.failures(u) >= 2
}
