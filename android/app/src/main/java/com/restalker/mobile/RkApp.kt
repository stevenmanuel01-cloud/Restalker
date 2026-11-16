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
    }

    companion object {
        lateinit var app: RkApp
            private set
        private var _store: Store? = null
        private val main = Handler(Looper.getMainLooper())
        /** Called on the main thread whenever products / settings / the log change (the screens redraw). */
        val listeners = CopyOnWriteArrayList<() -> Unit>()

        @Synchronized fun store(dirOverride: File? = null): Store {
            _store?.let { return it }
            val s = Store(dirOverride ?: File(app.filesDir, "rk"))
            s.onChange = { main.post { listeners.forEach { it() } } }
            _store = s
            return s
        }

        /** Tests only: start again with an empty folder. */
        @Synchronized fun resetForTests(dir: File) { dir.deleteRecursively(); _store = null; store(dir) }
    }
}
