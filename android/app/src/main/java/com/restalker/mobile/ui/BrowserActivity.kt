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
 *  When watching is running it shows the very browser the watcher uses (watching pauses while it is on screen - also when you leave it
 *  with Home: it goes on again at once); otherwise a browser of its own (the sign-in is shared: the app keeps one set of Amazon
 *  cookies). Back goes back a page. Nothing is typed by the app. */
class BrowserActivity : Activity() {
    private var shared: AmazonWeb? = null
    private var own: WebView? = null
    private lateinit var box: FrameLayout
    private lateinit var note: TextView
    private var url: String? = null
    private var loadedOnce = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 16, 24, 8); gravity = Gravity.CENTER_VERTICAL }
        note = TextView(this).apply {
            text = if (url == SIGNIN_URL) "Sign in to Amazon yourself. Tick \"Keep me signed in\" so the app stays signed in."
                   else "Restalker's Amazon browser - watching pauses while it is open."
            textSize = 14f; setTextColor(Color.DKGRAY); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val done = Button(this).apply { text = "Done"; setOnClickListener { finish() } }
        bar.addView(note); bar.addView(done)
        root.addView(bar)
        box = FrameLayout(this)
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (WatchService.instance == null) makeOwn()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeOwn() {
        val w = WebView(this)
        w.settings.javaScriptEnabled = true; w.settings.domStorageEnabled = true
        w.settings.useWideViewPort = true; w.settings.loadWithOverviewMode = true
        w.settings.builtInZoomControls = true; w.settings.displayZoomControls = false
        val agent = w.settings.userAgentString
        w.settings.userAgentString = AmazonWeb.desktopAgent(agent)
        AmazonWeb.lookLikeChrome(w.settings, agent)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) =
                request.isForMainFrame && !AmazonWeb.allowed(request.url)
        }
        own = w
        box.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        w.loadUrl(url ?: "https://www.amazon.com/")
    }

    override fun onStart() {
        super.onStart()
        val svc = WatchService.instance ?: return
        val aw = svc.web; shared = aw
        BrowserShown.on = true
        val busy = svc.engine.busyWith != "idle"
        if (!busy) svc.engine.pausedFor = "you are using the browser"
        else note.text = "A checkout is running right now - please don't tap in the page."
        (aw.web.parent as? ViewGroup)?.removeView(aw.web)
        aw.ctxWrap.baseContext = this
        box.addView(aw.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (!loadedOnce && url != null && !busy) aw.web.loadUrl(url!!)
        loadedOnce = true
    }

    override fun onStop() {
        shared?.let { aw ->
            (aw.web.parent as? ViewGroup)?.removeView(aw.web)
            aw.ctxWrap.baseContext = applicationContext
            aw.layoutDetached()
            WatchService.instance?.engine?.pausedFor = null
        }
        shared = null
        BrowserShown.on = false
        // you may have signed in: checkouts may try again (if Amazon still wants a sign-in, it asks again and you are told)
        if (RkApp.store().settings().signinNeededAt > 0) { RkApp.store().setSettings { it.copy(signinNeededAt = 0) }; RkApp.store().log("Sign-in hold cleared (the browser was used).") }
        CookieManager.getInstance().flush()
        super.onStop()
    }

    @Deprecated("Back goes back a page in the browser first.")
    override fun onBackPressed() {
        val w = shared?.web ?: own
        if (w != null && w.canGoBack()) w.goBack() else finish()
    }

    override fun onDestroy() {
        own?.let { CookieManager.getInstance().flush(); it.destroy() }
        RkApp.store().log("Browser closed.")
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val SIGNIN_URL = "https://www.amazon.com/gp/sign-in.html"
    }
}
