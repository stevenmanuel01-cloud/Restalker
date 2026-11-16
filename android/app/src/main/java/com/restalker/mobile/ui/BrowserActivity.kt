package com.restalker.mobile.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.restalker.mobile.BrowserShown
import com.restalker.mobile.RkApp
import com.restalker.mobile.WatchService
import com.restalker.mobile.web.AmazonWeb

/** The app's Amazon browser on screen: to sign in to Amazon yourself, to finish a check Amazon shows, or to watch a checkout live.
 *  When watching is running it shows the very browser the watcher uses (watching pauses meanwhile, a checkout in progress continues);
 *  otherwise a browser of its own (the sign-in is shared: the app keeps one set of Amazon cookies). Nothing is typed by the app. */
class BrowserActivity : Activity() {
    private var shared: AmazonWeb? = null
    private var own: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 16, 24, 8); gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = if (url == SIGNIN_URL) "Sign in to Amazon yourself (the app never types it)" else "Restalker's Amazon browser"
            textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val done = Button(this).apply { text = "Done"; setOnClickListener { finish() } }
        bar.addView(title); bar.addView(done)
        root.addView(bar)
        val box = FrameLayout(this)
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val svc = WatchService.instance
        if (svc != null) {
            val aw = svc.web; shared = aw
            BrowserShown.on = true
            if (svc.engine.busyWith == "idle") svc.engine.pausedFor = "you are using the browser"
            (aw.web.parent as? ViewGroup)?.removeView(aw.web)
            aw.ctxWrap.baseContext = this
            box.addView(aw.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (url != null && svc.engine.busyWith == "idle") aw.web.loadUrl(url)
        } else {
            val w = WebView(this)
            w.settings.javaScriptEnabled = true; w.settings.domStorageEnabled = true
            w.settings.useWideViewPort = true; w.settings.loadWithOverviewMode = true
            w.settings.builtInZoomControls = true; w.settings.displayZoomControls = false
            w.settings.userAgentString = AmazonWeb.desktopAgent(w.settings.userAgentString)
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
            w.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) =
                    request.isForMainFrame && !AmazonWeb.allowed(request.url)
            }
            own = w
            box.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            w.loadUrl(url ?: "https://www.amazon.com/")
        }
    }

    override fun onDestroy() {
        shared?.let { aw ->
            (aw.web.parent as? ViewGroup)?.removeView(aw.web)
            aw.ctxWrap.baseContext = applicationContext
            aw.layoutDetached()
            WatchService.instance?.engine?.pausedFor = null
        }
        BrowserShown.on = false
        own?.let { CookieManager.getInstance().flush(); it.destroy() }
        CookieManager.getInstance().flush()
        RkApp.store().log("Browser closed.")
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val SIGNIN_URL = "https://www.amazon.com/gp/sign-in.html"
    }
}
