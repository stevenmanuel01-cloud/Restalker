package com.restalker.mobile.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.restalker.mobile.core.MiniJson
import com.restalker.mobile.core.Page
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The app's own browser for Amazon, used by the engine (another thread) through [Page]. Every call to the WebView happens on the main
 *  thread; the engine waits for the answer.
 *  The desktop's Amazon checkout (assets/engine: catalog.js, logic.js, content.js inside amz_part.js - unchanged copies) is put into each
 *  Amazon page after it has loaded, wrapped the way the desktop's other buying browsers do it (rk_phone.js). Its answers come back
 *  through a message channel that only amazon.com pages, main frame only, may use. It never leaves amazon.com: any other address the
 *  page tries to open is refused. Nothing is ever typed into a page by the app (no password, no card number). */
class AmazonWeb(private val appCtx: Context) : Page {
    private val main = Handler(Looper.getMainLooper())
    val ctxWrap = MutableContextWrapper(appCtx.applicationContext)
    lateinit var web: WebView
        private set
    @Volatile private var nav = 0
    @Volatile private var startedNav = -1
    private val token = ByteArray(9).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val script: String by lazy { build(appCtx, token) }
    private val pending = ConcurrentHashMap<String, AtomicReference<Map<String, Any?>?>>()
    private val latches = ConcurrentHashMap<String, CountDownLatch>()
    @Volatile private var loadDone = CountDownLatch(1)
    @Volatile private var lastFinished = ""
    @Volatile var currentUrl = ""; private set
    @Volatile var available = true; private set
    var onPageShown: ((String) -> Unit)? = null

    /** Creates the WebView (main thread). */
    fun create() = onMain {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) available = false
        web = newWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView {
        val w = WebView(ctxWrap)
        val st = w.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = true
        st.databaseEnabled = true
        st.useWideViewPort = true
        st.loadWithOverviewMode = true
        st.builtInZoomControls = true
        st.displayZoomControls = false
        st.setSupportMultipleWindows(false)
        st.javaScriptCanOpenWindowsAutomatically = false
        st.mediaPlaybackRequiresUserGesture = true
        st.textZoom = 100
        st.cacheMode = WebSettings.LOAD_DEFAULT
        val own = st.userAgentString
        st.userAgentString = desktopAgent(own)     // Amazon's desktop pages: the ones the desktop checkout is made for
        lookLikeChrome(st, own)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(w, "RkHost", setOf("https://amazon.com", "https://*.amazon.com")) { _, message, _, isMainFrame, _ ->
                if (!isMainFrame) return@addWebMessageListener
                val m = MiniJson.obj(message.data) ?: return@addWebMessageListener
                val id = m["id"] as? String ?: return@addWebMessageListener
                val ref = pending[id] ?: return@addWebMessageListener
                @Suppress("UNCHECKED_CAST")
                ref.set(when { m["none"] != null -> NONE; m["err"] != null -> mapOf("ok" to false, "error" to m["err"]); else -> (m["v"] as? Map<String, Any?>) ?: mapOf("ok" to false) })
                latches[id]?.countDown()
            }
        }
        w.webViewClient = Client()
        layoutDetached(w)
        return w
    }

    /** Not on screen: the page is still laid out at a desktop size (1280 x 2400 page pixels) so the checkout can see every button. */
    fun layoutDetached(w: View = web) {
        val d = appCtx.resources.displayMetrics.density
        val wPx = (1280 * d).toInt(); val hPx = (2400 * d).toInt()
        w.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY))
        w.layout(0, 0, wPx, hPx)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return !allowed(request.url)                 // (true = refused: the app's browser stays on Amazon)
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val hook = testSite ?: return null
            val r = hook(request.url.toString(), request.method ?: "GET")
            return if (r == null) WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            else WebResourceResponse(r.first, "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(r.second.toByteArray()))
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { currentUrl = url; startedNav = nav }

        override fun onPageFinished(view: WebView, url: String) {
            currentUrl = url
            if (allowed(Uri.parse(url))) view.evaluateJavascript(script, null)
            lastFinished = url
            if (startedNav == nav) loadDone.countDown()       // (only the page asked for - not the end of the one before it)
            onPageShown?.invoke(url)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // the browser's page process ended (low memory): a new browser is made, the app keeps running
            val parent = view.parent as? android.view.ViewGroup
            parent?.removeView(view)
            view.destroy()
            web = newWebView()
            parent?.addView(web)
            loadDone.countDown()
            return true
        }
    }

    // ------------------------------------------------------------------ Page (called from the engine's thread)
    override fun goto(url: String, timeoutMs: Long): Boolean {
        if (!available) return false
        val l = CountDownLatch(1)
        onMain { web.stopLoading(); nav++; loadDone = l; web.loadUrl(url) }
        val ok = l.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ok) onMain { web.stopLoading() }
        Thread.sleep(250)                              // (the page's own scripts start; the part answers right after)
        return ok
    }

    override fun ask(msg: Map<String, Any?>, timeoutMs: Long): Map<String, Any?>? {
        val until = System.currentTimeMillis() + timeoutMs
        val json = MiniJson.write(msg)
        var injectedAgain = false
        while (System.currentTimeMillis() < until) {
            val id = token.take(6) + System.nanoTime().toString(36)
            val ref = AtomicReference<Map<String, Any?>?>(null); val l = CountDownLatch(1)
            pending[id] = ref; latches[id] = l
            val js = "(function(){var P=window.RkHost,f=window['__rk_$token'];if(!P)return 'nohost';" +
                "if(!f){P.postMessage(JSON.stringify({id:'$id',none:1}));return 'none'}" +
                "try{f($json,{},function(v){P.postMessage(JSON.stringify({id:'$id',v:(v===undefined?null:v)}))})}catch(e){P.postMessage(JSON.stringify({id:'$id',err:String(e)}))}return 'asked'})()"
            onMain { web.evaluateJavascript(js, null) }
            val left = until - System.currentTimeMillis()
            l.await(minOf(left.coerceAtLeast(1), 6_000), TimeUnit.MILLISECONDS)
            pending.remove(id); latches.remove(id)
            val v = ref.get()
            if (v != null && v !== NONE) return v
            if (v === NONE && !injectedAgain && lastFinished.isNotEmpty()) {      // the page has loaded but has no part: put it in again
                injectedAgain = true
                onMain { if (allowed(Uri.parse(web.url ?: ""))) web.evaluateJavascript(script, null) }
            }
            Thread.sleep(150)
        }
        return null
    }

    override fun images(on: Boolean) = onMain { web.settings.blockNetworkImage = !on }

    fun destroy() = onMain { try { web.stopLoading(); (web.parent as? android.view.ViewGroup)?.removeView(web); web.destroy() } catch (e: Exception) {} }

    private fun onMain(f: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { f(); return }
        val l = CountDownLatch(1)
        main.post { try { f() } finally { l.countDown() } }
        l.await(10, TimeUnit.SECONDS)
    }

    companion object {
        private val NONE = mapOf<String, Any?>("__none" to true)
        /** Tests only: a stand-in site answers every request (nothing reaches the real internet while it is set). */
        @Volatile var testSite: ((url: String, method: String) -> Pair<String, String>?)? = null

        fun allowed(u: Uri): Boolean {
            val h = (u.host ?: "").lowercase()
            return u.scheme == "https" && (h == "amazon.com" || h.endsWith(".amazon.com"))
        }

        /** The browser also tells sites it is an app's browser (the X-Requested-With header with the app's name, and "Android WebView"
         *  in its client hints). Both are switched to what desktop Chrome sends, where this phone's WebView allows it. */
        fun lookLikeChrome(st: WebSettings, own: String) {
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                    androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(st, emptySet())
            } catch (e: Throwable) { }
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                    val major = Regex("Chrome/([0-9]+)").find(own)?.groupValues?.get(1) ?: "131"
                    fun bv(b: String, v: String) = androidx.webkit.UserAgentMetadata.BrandVersion.Builder().setBrand(b).setMajorVersion(v).setFullVersion("$v.0.0.0").build()
                    val md = androidx.webkit.UserAgentMetadata.Builder()
                        .setBrandVersionList(listOf(bv("Google Chrome", major), bv("Chromium", major), bv("Not_A Brand", "24")))
                        .setFullVersion("$major.0.0.0").setPlatform("Windows").setPlatformVersion("10.0.0").setArchitecture("x86")
                        .setModel("").setMobile(false).setBitness(64).setWow64(false).build()
                    androidx.webkit.WebSettingsCompat.setUserAgentMetadata(st, md)
                }
            } catch (e: Throwable) { }
        }

        fun desktopAgent(own: String): String {
            val chrome = Regex("Chrome/([0-9]+)").find(own)?.groupValues?.get(1) ?: "131"
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome.0.0.0 Safari/537.36"
        }

        /** The page part, built exactly as the desktop builds it (multibuy.py _part_source), plus the phone wrapper. */
        fun build(ctx: Context, token: String): String {
            fun rd(f: String) = ctx.assets.open("engine/$f").bufferedReader().use { it.readText() }
            val bundle = rd("catalog.js") + "\n;\n" + rd("logic.js") + "\n;\n" + rd("content.js") +
                "\n;globalThis.__rkAmzEntry = () => { const e = purchaseEntry(); return e ? e.kind : null; };\n"
            val part = rd("amz_part.js").replace("/*__RK_AMZ_BUNDLE__*/", bundle).replace("__RK_AMZ_PART__", "__rk_${token}_on")
            return rd("rk_phone.js").replace("__TOKEN__", token).replace("/*__RK_PART__*/", part)
        }
    }
}
