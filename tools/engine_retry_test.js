// The real buying engine in Chromium against a stand-in Amazon that sends its product page IN PIECES, as the real one does (v155).
// The purchase must start the moment the buy box is complete - not when the page has been read to its end - and never earlier.
// usage: node stream.js <buyer_engine folder> [old]      ("old" = an engine before v155: only the timing is measured)
const { chromium } = require('/opt/npm-tools/node_modules/playwright');
const https = require('https'), fs = require('fs'), os = require('os'), path = require('path');
const ext = path.resolve(process.argv[2]), OLD = false;
const A = 'B0TESTST01', TITLE = 'Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle';
let mode = 'whole', stock = true, reqs = [], D1 = 600, D2 = 2500, firstByte = 0, postMode = 'checkout';
const head = `<!doctype html><html><head><title>Amazon.com: Nauctis Bundle</title></head><body><div id="dp" class="a-container">`;
const formOpen = `<div id="rightCol"><div id="buybox"><form id="addToCart" method="post" action="/checkout/standin"><input type="hidden" name="ASIN" value="${A}"><input type="hidden" id="ASIN" value="${A}"><input type="hidden" id="merchantID" name="merchantID" value="ATVPDKIKX0DER">
<span id="submit.add-to-cart"><input id="add-to-cart-button" name="submit.add-to-cart" type="submit" value="Add to Cart"></span>
<span id="submit.buy-now"><input id="buy-now-button" name="submit.buy-now" type="submit" value="Buy Now"></span>`;
const formEnd = `<input type="hidden" name="offerListingID" value="LASTFIELD"></form></div></div>`;
const center = `<div id="centerCol"><span id="productTitle">${TITLE}</span><div id="corePrice_feature_div"><span class="a-price"><span class="a-offscreen">$89.99</span></span></div>
<div id="availability"><span class="a-size-medium a-color-success">In Stock</span></div><div id="merchantInfoFeature_feature_div"><span class="offer-display-feature-text-message">Amazon.com</span></div>`;
const tail = `</div><div id="feature-bullets">the rest of the page ${'x'.repeat(2000)}</div></div></body></html>`;
const soldOut = `<div id="centerCol"><span id="productTitle">${TITLE}</span><div id="availability"><span>Currently unavailable.</span></div><p>We don't know when or if this item will be back in stock.</p>`;
const pieces = () => {
  if (!stock) return [[firstByte, head + soldOut], [D2, tail]];
  if (mode === 'boxfirst') return [[firstByte, head + formOpen], [D1, formEnd + center], [D2, tail]];               // Amazon's own order: buy box, then title and price
  if (mode === 'titlefirst') return [[firstByte, head + center + '</div>' + formOpen], [D1, formEnd + '<div id="after">after the box</div>'], [D2, tail.replace('</div><div id="feature', '<div id="feature')]];
  if (mode === 'nobox-end') return [[firstByte, head + center + '</div>' + formOpen], [D1 + D2, formEnd + tail.replace('</div><div id="feature', '<div id="feature')]];   // the form ends only with the page
  return [[firstByte, head + formOpen + formEnd + center + tail]];
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const server = https.createServer({ key: fs.readFileSync(__dirname + '/cert/key.pem'), cert: fs.readFileSync(__dirname + '/cert/cert.pem') }, async (req, res) => {
  const pth = req.url.split('?')[0];
  if (pth.startsWith('/dp/' + A)) {
    const rec = { t: Date.now(), m: 'GET', p: '/dp', mode, stock }; reqs.push(rec);
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
    for (const [wait, text] of pieces()) { await sleep(wait); if (res.destroyed) { rec.cut = Date.now(); return; } res.write(text); }
    rec.end = Date.now(); return res.end();
  }
  if (pth.startsWith('/checkout/')) {
    let body = ''; req.on('data', d => body += d); await new Promise(r => req.on('end', r));
    reqs.push({ t: Date.now(), m: req.method, p: pth, body });
    if (postMode === 'nowhere') { res.writeHead(204); return res.end(); }  // (Amazon answers with nothing: the press leads nowhere, the page stays)
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    return res.end(`<!doctype html><html><head><title>Checkout</title></head><body><div id="checkout-main"><h1>Review your order</h1><div data-asin="${A}"><span>${TITLE}</span><div class="a-alert-content">This item is currently unavailable. It has been removed from your order.</div></div></div></body></html>`);
  }
  res.writeHead(200, { 'content-type': 'text/html' }); res.end('<html><body><h1>stand-in</h1></body></html>');
});
const out = []; const say = (name, ok, more) => { out.push([name, ok]); console.log((ok ? 'PASS ' : 'FAIL ') + name + (more ? '  | ' + more : '')); };
(async () => {
  await new Promise(r => server.listen(0, '127.0.0.1', r)); const port = server.address().port;
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rkst'));
  const ctx = await chromium.launchPersistentContext(dir, { headless: false, executablePath: '/opt/pw-browsers/chromium-1243/chrome-linux64/chrome',
    args: ['--headless=new', '--no-sandbox', `--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--ignore-certificate-errors', '--no-proxy-server',
      `--host-resolver-rules=MAP www.amazon.com 127.0.0.1:${port}, MAP amazon.com 127.0.0.1:${port}, MAP * ~NOTFOUND, EXCLUDE 127.0.0.1, EXCLUDE localhost`] });
  let sw = ctx.serviceWorkers()[0] || await ctx.waitForEvent('serviceworker', { timeout: 20000 });
  const id = sw.url().split('/')[2];
  const dash = await ctx.newPage(); await dash.goto(`chrome-extension://${id}/dashboard.html`); await dash.waitForTimeout(1500);
  const send = (m) => dash.evaluate((m) => new Promise((res) => { const t = setTimeout(() => res({ ok: false, reason: 'no answer in 40 s' }), 40000);
    chrome.runtime.sendMessage(m).then((r) => { clearTimeout(t); res(r); }, (e) => { clearTimeout(t); res({ ok: false, reason: 'error: ' + e }); }); }), m);
  const state = async (pid) => ((await send({ type: 'GET_ALL' })).states || {})[pid] || {};
  const SW = () => (sw = ctx.serviceWorkers()[0] || sw);
  const made = await send({ type: 'ADD_CUSTOM_PRODUCT' }); const pid = made.id;
  mode = 'whole'; stock = false;
  await send({ type: 'SET_CUSTOM_ASIN', productId: pid, asin: A });
  await send({ type: 'SET_PRODUCT', productId: pid, values: { cap: 90 } });
  await send({ type: 'ARM_PRODUCT', productId: pid });
  await dash.waitForTimeout(5000);
  let s = await state(pid);
  say('0 the listing is added, capped and armed (sold out for now)', s.armed === true && s.monitor === true, 'status=' + String(s.status).slice(0, 60));
  const signal = (n) => SW().evaluate(async ({ asin, n }) => { try { await buyerDetectorBridge.onRestock({ type: 'restock', store: 'amazon', amazonAsin: asin,
    eventId: 'st-' + asin + '-' + Date.now() + '-' + n, detectedAt: new Date().toISOString(), source: 'test' }); return 'ok'; } catch (e) { return 'ERR ' + (e && e.message || e); } }, { asin: A, n });
  // (waits until the try before has ended completely - its own retry too - and arms the listing again if the engine gave it up)
  const rearm = async () => { for (let i = 0; i < 80; i++) { const x = await state(pid); if (!x.inProgress && !x.checkoutRetryPending && !x.checkoutOwned) break; await dash.waitForTimeout(400); }
    await dash.waitForTimeout(1500); const x = await state(pid); if (!x.armed || !x.monitor) { await send({ type: 'ARM_PRODUCT', productId: pid }); await dash.waitForTimeout(4500); } };
  // one restock with the page sent in pieces -> {press: ms from the page request to Buy Now, end: ms the page took, body}
  const run = async (m, n, waitMs = 9000) => {
    stock = false; await rearm(); await dash.waitForTimeout(600);
    mode = m; stock = true; const t0 = Date.now();
    await signal(n);
    const tEnd = Date.now() + waitMs; let post = null, get = null;
    while (Date.now() < tEnd) { get = reqs.find(r => r.m === 'GET' && r.t >= t0 && r.stock); post = reqs.find(r => r.m === 'POST' && r.t >= t0); if (post) break; await sleep(25); }
    stock = false;                                                        // (what it loads after this try is sold out again)
    await dash.waitForTimeout(postMode === 'nowhere' ? 500 : 2500);
    return { press: post && get ? post.t - get.t : null, body: post ? post.body : '', pageEnd: get ? ((get.end || get.cut || Date.now()) - get.t) : null, cut: !!(get && get.cut) };
  };
  // a checkout that Amazon refuses, again and again, while the product page keeps saying buyable and arrives slowly: after each refusal
  // the engine looks again by itself - every such look must give the page's real answer
  D1 = 500; D2 = Number(process.argv[4] || 4000); firstByte = Number(process.argv[5] || 1500);
  const seen = [];
  const watch = setInterval(async () => { try { const x = await state(pid); const k = String(x.status).slice(0, 90); if (!seen.length || seen[seen.length - 1] !== k) seen.push(k); } catch {} }, 100);
  mode = 'boxfirst'; stock = true;
  await rearm();
  const t0 = Date.now();
  for (let i = 0; i < 10; i++) { await signal(100 + i); await dash.waitForTimeout(2600); }
  await dash.waitForTimeout(6000); clearInterval(watch);
  const wrong = seen.filter(k => /not the product|could not be read/.test(k));
  console.log('STATUSES ' + JSON.stringify(seen).slice(0, 1500));
  const looks = reqs.filter(r => r.m === 'GET' && r.t >= t0).length, presses = reqs.filter(r => r.m === 'POST' && r.t >= t0).length;
  say('R1 ' + looks + ' looks, ' + presses + ' refused checkouts in a row: never "the product name could not be read" from a page that has only begun to arrive', wrong.length === 0 && presses >= 2, wrong.length + ' wrong answers');
  const bad = out.filter((x) => !x[1]).length;
  console.log(bad ? `SOME FAILED: ${bad} of ${out.length}` : `ALL PASS (${out.length})`);
  await ctx.close(); server.close(); process.exit(bad ? 1 : 0);
})().catch((e) => { console.log('HARNESS ERROR ' + (e && e.stack || e)); process.exit(2); });
