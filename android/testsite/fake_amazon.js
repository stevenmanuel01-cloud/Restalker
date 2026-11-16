// Stand-in Amazon for Restalker Mobile tests (a port of the desktop's t88/fakeamz.py): product pages, the checkout page that keeps
// changing while it loads, Place your order -> thank-you page, the order list, a robot-check page. State is in S; handle(url, method).
// Used by the browser test here (Node) and copied into the Android emulator test (assets) - one stand-in for both.
(function (root) {
  const TITLE = 'Magic: The Gathering | The Hobbit Collector Booster Box | 12 Packs + 1 Foil Box Topper Card | Collectible Trading Card Game';
  function make() {
    const S = {items: {
      B0TEST0001: {stock: true, price: 99.99, title: TITLE},
      B0TEST0002: {stock: false, price: 99.99, title: 'Magic: The Gathering Bloomburrow Bundle'},
      B0TEST0003: {stock: true, price: 199.99, title: 'Magic: The Gathering Duskmourn Collector Booster Box'},
      B0TEST0004: {stock: true, price: 49.99, title: 'Magic: The Gathering Foundations Bundle', captcha: true},
    }, orders: [], places: 0, loadMs: 1500, placeFail: 0, last: '', checkoutPrice: null, shipping: 0, soldOutAtCheckout: false, extraItem: false, signinAtCheckout: false, noOrderNumber: false};
    const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;');
    const page = (body, title) => '<!doctype html><html><head><meta charset="utf-8"><title>' + esc(title || 'Amazon.com') + '</title></head><body>' + body + '</body></html>';
    function pdp(asin) {
      const it = S.items[asin];
      if (!it) return page('<h1>Looking for something?</h1><p>We\'re sorry. The Web address you entered is not a functioning page on our site.</p>');
      if (it.captcha) return page('<h4>Enter the characters you see below</h4><p>Sorry, we just need to make sure you\'re not a robot.</p><form><input name="field-keywords"></form>');
      const buy = it.stock ? ('<div id="desktop_qualifiedBuyBox"><span class="a-button"><input id="buy-now-button" name="submit.buy-now" type="submit" value="Buy Now" ' +
        'onclick="location.href=\'' + (S.signinAtCheckout ? '/ap/signin?openid.return_to=checkout' : '/checkout/p/p-106-1234567-7654321/itemselect?asin=' + asin) + '\';return false"></span>' +
        '<input id="add-to-cart-button" name="submit.add-to-cart" type="submit" value="Add to Cart"></div>') : '<div id="availability">Currently unavailable.</div>';
      return page('<input type="hidden" id="ASIN" value="' + asin + '"><span id="productTitle">' + esc(it.title) + '</span>' +
        (it.stock ? '<div id="corePriceDisplay_desktop_feature_div"><span class="priceToPay"><span class="a-offscreen">$' + it.price.toFixed(2) + '</span></span></div>' : '') +
        '<div id="merchant-info">Ships from and sold by Amazon.com</div>' + buy +
        '<div id="ads"></div><script>setInterval(()=>{document.getElementById("ads").textContent=Math.random()},50)</script>', 'Amazon.com: ' + it.title);
    }
    function checkout(asin) {
      const it = S.items[asin] || S.items.B0TEST0001, p = S.checkoutPrice || it.price, ship = S.shipping;
      const item = '<div class="order-item" data-asin="' + asin + '"><a href="/dp/' + asin + '">' + esc(it.title) + '</a><span class="a-price"><span class="a-offscreen">$' + p.toFixed(2) + '</span></span>' +
        '<span class="a-dropdown-prompt">1</span><div>Sold by: Amazon.com</div></div>' +
        (S.extraItem ? '<div class="order-item" data-asin="B0OTHER001"><a href="/dp/B0OTHER001">Pokemon Scarlet and Violet Elite Trainer Box</a><span class="a-price"><span class="a-offscreen">$49.99</span></span><span class="a-dropdown-prompt">1</span></div>' : '') +
        (S.soldOutAtCheckout ? '<div class="a-alert-content">This item is no longer available from the seller.</div>' : '');
      const order = '<div id="checkout-container">' + item + '<div id="subtotals-marketplace-table"><table><tr><td>Items:</td><td>$' + p.toFixed(2) + '</td></tr>' +
        '<tr><td>Shipping &amp; handling:</td><td>$' + ship.toFixed(2) + '</td></tr><tr><td>Estimated tax to be collected:</td><td>$' + (p * 0.0775).toFixed(2) + '</td></tr>' +
        '<tr><td>Order total:</td><td>$' + (p * 1.0775 + ship + (S.extraItem ? 49.99 : 0)).toFixed(2) + '</td></tr></table></div><span id="submitOrderButtonId"><input type="submit" name="placeYourOrder1" ' +
        'value="Place your order" onclick="fetch(\'/__place?asin=' + asin + '\',{method:\'POST\'}).then(r=>r.json()).then(j=>{location.href=j.ok?\'/gp/buy/thankyou/handlers/display.html?purchaseId=1\':\'/checkout/p/p-106-1234567-7654321/spc?err=1\'});return false"></span></div>';
      return page('<div id="nav"><span class="a-dropdown-prompt">Hello</span></div><div id="spin">Loading</div><div id="root"></div><script>' +
        "let n=0;const t=setInterval(()=>{document.getElementById('spin').textContent='Loading '+(n++);},20);" +
        'setTimeout(()=>{clearInterval(t);document.getElementById("spin").remove();document.getElementById("root").innerHTML=' + JSON.stringify(order) + ';},' + S.loadMs + ');</script>', 'Amazon.com Checkout');
    }
    function thanks() {
      const o = S.orders.length ? S.orders[S.orders.length - 1].order : '111-0000000-0000000';
      return page('<div id="x"></div><script>let n=0;setInterval(()=>{document.getElementById("x").textContent=n++},30);</script>' +
        '<h4>Order placed, thanks!</h4><div>Confirmation will be sent to your email.</div>' + (S.noOrderNumber ? '' : '<div>Order # ' + o + '</div>'), 'Amazon.com Thanks You');
    }
    function orders() {
      return page('<h1>Your Orders</h1>' + S.orders.slice().reverse().map(o => '<div class="order-card"><span>ORDER # ' + o.order + '</span><a href="/dp/' + o.asin + '?ref=ppx">' + esc((S.items[o.asin] || {}).title || '') + '</a></div>').join(''), 'Your Orders');
    }
    function handle(url, method) {
      const u = new URL(url), path = u.pathname;
      S.last = method + ' ' + path;
      if (method === 'POST' && path.startsWith('/__place')) {
        S.places++;
        if (S.placeFail > 0) { S.placeFail--; return {type: 'application/json', body: '{"ok":false}'}; }
        const o = '111-' + (1000000 + S.orders.length) + '-' + (2000000 + S.places);
        S.orders.push({order: o, asin: u.searchParams.get('asin') || 'B0TEST0001', at: Date.now()});
        return {type: 'application/json', body: '{"ok":true}'};
      }
      const m = path.match(/\/(?:dp|gp\/product)\/([A-Z0-9]{10})/);
      if (m) return {type: 'text/html', body: pdp(m[1])};
      if (path.startsWith('/checkout/p/')) return {type: 'text/html', body: checkout(u.searchParams.get('asin') || 'B0TEST0001')};
      if (path.startsWith('/gp/buy/thankyou')) return {type: 'text/html', body: thanks()};
      if (path.startsWith('/gp/css/order-history') || path.startsWith('/your-orders')) return {type: 'text/html', body: orders()};
      if (path.startsWith('/ap/signin')) return {type: 'text/html', body: page('<h1>Sign in</h1><form name="signIn"><label>Email or mobile phone number</label><input type="email" name="email" id="ap_email"><input type="submit" id="continue" value="Continue"></form>', 'Amazon Sign-In')};
      if (path.startsWith('/favicon')) return {type: 'image/x-icon', body: ''};
      return {type: 'text/html', body: page('<div id="nav-link-accountList">Hello, Tester - Account &amp; Lists</div><h1>Amazon.com</h1>')};
    }
    return {S, handle};
  }
  root.RkFakeAmazon = {make, TITLE};
  if (typeof module !== 'undefined') module.exports = root.RkFakeAmazon;
})(typeof globalThis !== 'undefined' ? globalThis : this);
