package com.restalker.mobile

/** Stand-in Amazon for the phone tests - the same pages as android/testsite/fake_amazon.js (a port of the desktop's t88/fakeamz.py):
 *  product pages, the checkout page that keeps changing while it loads, Place your order -> thank-you page, the order list, a robot-check
 *  page. Nothing reaches the real Amazon while the tests run (every request is answered here). */
class FakeAmazonSite {
    data class Item(var stock: Boolean, var price: Double, var title: String, var captcha: Boolean = false)
    val items = mutableMapOf(
        "B0TEST0001" to Item(true, 99.99, TITLE),
        "B0TEST0002" to Item(false, 99.99, "Magic: The Gathering Bloomburrow Bundle"),
        "B0TEST0003" to Item(true, 199.99, "Magic: The Gathering Duskmourn Collector Booster Box"),
        "B0TEST0004" to Item(true, 49.99, "Magic: The Gathering Foundations Bundle", captcha = true),
    )
    val orders = mutableListOf<Pair<String, String>>()      // order number -> ASIN
    @Volatile var places = 0
    @Volatile var loadMs = 1500
    // traps at the checkout (each must end in NO order)
    @Volatile var checkoutPrice: Double? = null       // the checkout shows another price than the product page
    @Volatile var shipping = 0.0                       // shipping cost at the checkout
    @Volatile var soldOutAtCheckout = false            // "no longer available" at the checkout
    @Volatile var extraItem = false                    // another item is in the order
    @Volatile var signinAtCheckout = false             // Amazon asks to sign in after Buy Now
    @Volatile var noOrderNumber = false                // the thank-you page shows no order number (the order IS placed)
    val requests = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
    private fun page(body: String, title: String = "Amazon.com") = "<!doctype html><html><head><meta charset=\"utf-8\"><title>${esc(title)}</title></head><body>$body</body></html>"
    private fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun pdp(asin: String): String {
        val it = items[asin] ?: return page("<h1>Looking for something?</h1><p>We're sorry. The Web address you entered is not a functioning page on our site.</p>")
        if (it.captcha) return page("<h4>Enter the characters you see below</h4><p>Sorry, we just need to make sure you're not a robot.</p><form><input name=\"field-keywords\"></form>")
        val buy = if (it.stock) "<div id=\"desktop_qualifiedBuyBox\"><span class=\"a-button\"><input id=\"buy-now-button\" name=\"submit.buy-now\" type=\"submit\" value=\"Buy Now\" " +
            "onclick=\"location.href='${if (signinAtCheckout) "/ap/signin?openid.return_to=checkout" else "/checkout/p/p-106-1234567-7654321/itemselect?asin=$asin"}';return false\"></span>" +
            "<input id=\"add-to-cart-button\" name=\"submit.add-to-cart\" type=\"submit\" value=\"Add to Cart\"></div>" else "<div id=\"availability\">Currently unavailable.</div>"
        return page("<input type=\"hidden\" id=\"ASIN\" value=\"$asin\"><span id=\"productTitle\">${esc(it.title)}</span>" +
            (if (it.stock) "<div id=\"corePriceDisplay_desktop_feature_div\"><span class=\"priceToPay\"><span class=\"a-offscreen\">\$${"%.2f".format(it.price)}</span></span></div>" else "") +
            "<div id=\"merchant-info\">Ships from and sold by Amazon.com</div>$buy" +
            "<div id=\"ads\"></div><script>setInterval(()=>{document.getElementById(\"ads\").textContent=Math.random()},50)</script>", "Amazon.com: ${it.title}")
    }

    private fun checkout(asin: String): String {
        val it = items[asin] ?: items.getValue("B0TEST0001"); val p = checkoutPrice ?: it.price; val ship = shipping
        fun m(x: Double) = "%.2f".format(x)
        val item = "<div class=\"order-item\" data-asin=\"$asin\"><a href=\"/dp/$asin\">${esc(it.title)}</a><span class=\"a-price\"><span class=\"a-offscreen\">\$${m(p)}</span></span>" +
            "<span class=\"a-dropdown-prompt\">1</span><div>Sold by: Amazon.com</div></div>" +
            (if (extraItem) "<div class=\"order-item\" data-asin=\"B0OTHER001\"><a href=\"/dp/B0OTHER001\">Pokemon Scarlet and Violet Elite Trainer Box</a><span class=\"a-price\"><span class=\"a-offscreen\">\$49.99</span></span><span class=\"a-dropdown-prompt\">1</span></div>" else "") +
            (if (soldOutAtCheckout) "<div class=\"a-alert-content\">This item is no longer available from the seller.</div>" else "")
        val order = "<div id=\"checkout-container\">$item<div id=\"subtotals-marketplace-table\"><table><tr><td>Items:</td><td>\$${m(p)}</td></tr>" +
            "<tr><td>Shipping &amp; handling:</td><td>\$${m(ship)}</td></tr><tr><td>Estimated tax to be collected:</td><td>\$${m(p * 0.0775)}</td></tr>" +
            "<tr><td>Order total:</td><td>\$${m(p * 1.0775 + ship + (if (extraItem) 49.99 else 0.0))}</td></tr></table></div><span id=\"submitOrderButtonId\"><input type=\"submit\" name=\"placeYourOrder1\" " +
            "value=\"Place your order\" onclick=\"fetch('/__place?asin=$asin',{method:'POST'}).then(r=>r.json()).then(j=>{location.href=j.ok?'/gp/buy/thankyou/handlers/display.html?purchaseId=1':'/checkout/p/p-106-1234567-7654321/spc?err=1'});return false\"></span></div>"
        return page("<div id=\"nav\"><span class=\"a-dropdown-prompt\">Hello</span></div><div id=\"spin\">Loading</div><div id=\"root\"></div><script>" +
            "let n=0;const t=setInterval(()=>{document.getElementById('spin').textContent='Loading '+(n++);},20);" +
            "setTimeout(()=>{clearInterval(t);document.getElementById(\"spin\").remove();document.getElementById(\"root\").innerHTML=${js(order)};},$loadMs);</script>", "Amazon.com Checkout")
    }

    private fun thanks(): String {
        val o = orders.lastOrNull()?.first ?: "111-0000000-0000000"
        return page("<div id=\"x\"></div><script>let n=0;setInterval(()=>{document.getElementById(\"x\").textContent=n++},30);</script>" +
            "<h4>Order placed, thanks!</h4><div>Confirmation will be sent to your email.</div>" + (if (noOrderNumber) "" else "<div>Order # $o</div>"), "Amazon.com Thanks You")
    }

    private fun orderList() = page("<h1>Your Orders</h1>" + orders.reversed().joinToString("") { (o, a) ->
        "<div class=\"order-card\"><span>ORDER # $o</span><a href=\"/dp/$a?ref=ppx\">${esc(items[a]?.title ?: "")}</a></div>" }, "Your Orders")

    @Synchronized fun handle(url: String, method: String): Pair<String, String>? {
        val u = android.net.Uri.parse(url)
        val host = u.host ?: ""
        requests.add("$method $url")
        if (!(host == "amazon.com" || host.endsWith(".amazon.com"))) return null
        val path = u.path ?: "/"
        if (method == "POST" && path.startsWith("/__place")) {
            places++
            val o = "111-${1000000 + orders.size}-${2000000 + places}"
            orders.add(o to (u.getQueryParameter("asin") ?: "B0TEST0001"))
            return "application/json" to "{\"ok\":true}"
        }
        Regex("/(?:dp|gp/product)/([A-Z0-9]{10})").find(path)?.let { return "text/html" to pdp(it.groupValues[1]) }
        if (path.startsWith("/checkout/p/")) return "text/html" to checkout(u.getQueryParameter("asin") ?: "B0TEST0001")
        if (path.startsWith("/gp/buy/thankyou")) return "text/html" to thanks()
        if (path.startsWith("/gp/css/order-history")) return "text/html" to orderList()
        if (path.startsWith("/ap/signin")) return "text/html" to page("<h1>Sign in</h1><form name=\"signIn\"><label>Email or mobile phone number</label><input type=\"email\" name=\"email\" id=\"ap_email\"><input type=\"submit\" id=\"continue\" value=\"Continue\"></form>", "Amazon Sign-In")
        if (path.startsWith("/favicon")) return "image/x-icon" to ""
        return "text/html" to page("<div id=\"nav-link-accountList\">Hello, Tester - Account &amp; Lists</div><h1>Amazon.com</h1>")
    }

    companion object {
        const val TITLE = "Magic: The Gathering | The Hobbit Collector Booster Box | 12 Packs + 1 Foil Box Topper Card | Collectible Trading Card Game"
    }
}
