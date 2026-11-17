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
        Notifier.channels(this)
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

        @Synchronized fun resetForTests(dir: File) { dir.deleteRecursively(); _store = null; store(dir); DeskLink.reset() }
    }
}
