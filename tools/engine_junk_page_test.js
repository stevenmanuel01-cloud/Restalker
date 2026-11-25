// The real buying engine in Chromium against a stand-in Amazon that sends its product page IN PIECES, as the real one does (v155).
// The purchase must start the moment the buy box is complete - not when the page has been read to its end - and never earlier.
// usage: node stream.js <buyer_engine folder> [old]      ("old" = an engine before v155: only the timing is measured)
const { chromium } = require('/opt/npm-tools/node_modules/playwright');
const https = require('https'), fs = require('fs'), os = require('os'), path = require('path');
const ext = path.resolve(process.argv[2]), OLD = false; let junkAfterPost = 0, junkLeft = 0;
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
  if (mode === 'titlelate') return [[firstByte, head + formOpen + formEnd + '<div id="centerCol">'], [D1, center.replace('<div id="centerCol">', '')], [D2, tail]];   // the buy box at once, the name 1.5 s later
  if (mode === 'boxfirst') return [[firstByte, head + formOpen], [D1, formEnd + center], [D2, tail]];               // Amazon's own order: buy box, then title and price
  if (mode === 'titlefirst') return [[firstByte, head + center + '</div>' + formOpen], [D1, formEnd + '<div id="after">after the box</div>'], [D2, tail.replace('</div><div id="feature', '<div id="feature')]];
  if (mode === 'nobox-end') return [[firstByte, head + center + '</div>' + formOpen], [D1 + D2, formEnd + tail.replace('</div><div id="feature', '<div id="feature')]];   // the form ends only with the page
  return [[firstByte, head + formOpen + formEnd + center + tail]];
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const server = https.createServer({ key: fs.readFileSync(__dirname + '/cert/key.pem'), cert: fs.readFileSync(__dirname + '/cert/cert.pem') }, async (req, res) => {
  const pth = req.url.split('?')[0];
  if (pth.startsWith('/dp/' + A) && junkLeft > 0) {
    junkLeft--; reqs.push({ t: Date.now(), m: 'GET', p: '/dp', junk: true });
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
    return res.end('<!doctype html><html><head><title>Amazon.com</title></head><body><div class="a-box"><h4>Click the button below to continue shopping</h4><form method="get" action="/dp/' + A + '"><button type="submit" class="a-button-text">Continue shopping</button></form></div></body></html>');
  }
  if (pth.startsWith('/dp/' + A)) {
    const rec = { t: Date.now(), m: 'GET', p: '/dp', mode, stock }; reqs.push(rec);
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
    for (const [wait, text] of pieces()) { await sleep(wait); if (res.destroyed) { rec.cut = Date.now(); return; } res.write(text); }
    rec.end = Date.now(); return res.end();
  }
  if (pth.startsWith('/checkout/')) {
    let body = ''; req.on('data', d => body += d); await new Promise(r => req.on('end', r));
    reqs.push({ t: Date.now(), m: req.method, p: pth, body });
    junkLeft = junkAfterPost;
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
  const seen = [];
  const watch = setInterval(async () => { try { const x = await state(pid); const k = String(x.status).slice(0, 110); if (!seen.length || seen[seen.length - 1] !== k) seen.push(k); } catch {} }, 80);
  const junkOf = () => seen.filter(k => /^UNREADABLE/.test(k)).length, wrongOf = () => seen.filter(k => /not the product of your table row|RETRY STOPPED — new Amazon evidence (BLOCKED|WAITING)/.test(k));
  // 1. after a checkout Amazon refuses, the next two product pages are pages WITHOUT the product (as on your PC), then the real page again
  mode = 'whole'; stock = true; junkAfterPost = 2;
  await rearm(); let t0 = Date.now();
  await signal(1); await dash.waitForTimeout(16000);
  let posts = reqs.filter(r => r.m === 'POST' && r.t >= t0).length, junkServed = reqs.filter(r => r.junk && r.t >= t0).length;
  let ab = await SW().evaluate(() => JSON.parse(JSON.stringify({ mode: RK_AB.mode, early: RK_AB.early, read: RK_AB.read, junkPages: RK_AB.junkPages, ex: RK_AB.examples.slice(-1)[0] || null })));
  console.log('STATUSES ' + JSON.stringify(seen).slice(0, 900));
  say('1 a page without the product is told as UNREADABLE - never as "not the product of your table row" or as sold out', junkServed >= 2 && junkOf() >= 1 && wrongOf().length === 0, junkServed + ' such pages served; wrong answers: ' + JSON.stringify(wrongOf()).slice(0, 160));
  say('2 the waiting retry is kept: when the real page is back, the engine goes to the checkout again by itself', posts >= 2, posts + ' checkouts');
  say('3 what the page showed is recorded (title, headings, button, size, Amazon\'s answer number) and counted after a press "when the page was read"', !!(ab.ex && /Amazon\.com/.test(ab.ex.title) && ab.ex.heads.join(' ').includes('continue shopping') && ab.ex.code === 200 && ab.ex.after === 'read' && ab.ex.chars < 400) && ab.read.junk >= 1 && ab.read.presses >= 1 && ab.early.presses === 0, JSON.stringify(ab).slice(0, 330));
  // 2. it happens three times after presses made the "read" way -> early pressing is cleared and comes back by itself
  junkAfterPost = 1;
  for (let i = 0; i < 6 && (await SW().evaluate(() => RK_AB.mode)) === 'read'; i++) { stock = false; await rearm(); stock = true; await signal(10 + i); await dash.waitForTimeout(9000); }
  ab = await SW().evaluate(() => JSON.parse(JSON.stringify({ mode: RK_AB.mode, off: !!RK_EARLY.off, decided: RK_AB.decided, early: RK_AB.early, read: RK_AB.read })));
  say('4 three such pages after presses made when the page was read: early pressing is cleared by the numbers and switched on again', ab.mode === 'early' && ab.off === false && ab.read.junk >= 3 && /not the cause/.test(ab.decided), JSON.stringify(ab).slice(0, 260));
  const kept = await SW().evaluate(async () => (await chrome.storage.local.get('rkAb')).rkAb);
  say('5 the count and the decision are kept across a restart of the buying browser', !!(kept && kept.mode === 'early' && kept.read && kept.read.junk >= 3), JSON.stringify(kept).slice(0, 120));
  // 3. a question that arrives while the page is still arriving is answered from the page once it has been read - never from half a page
  junkAfterPost = 0; stock = false; await rearm(); await dash.waitForTimeout(1500);
  {
    mode = 'titlelate'; stock = true; D1 = 1500; D2 = 500;
    const r = await SW().evaluate(async ({ pid }) => {
      const p = (await catalog()).find(x => x.id === pid), st = await ps(p), rp = runtimeProduct(p, st), t = st.tabId;
      await setp(p, { armed: false, autoBuy: false, monitor: false });
      const committed = new Promise(res => { const f = d => { if (d.tabId === t && d.frameId === 0) { chrome.webNavigation.onCommitted.removeListener(f); res(Date.now()); } }; chrome.webNavigation.onCommitted.addListener(f); });
      await chrome.tabs.update(t, { url: 'https://www.amazon.com/dp/' + rp.asin + '?late=1' }); const c0 = await committed;
      const before = (await ps(p)).status;
      let ans = null; for (let i = 0; i < 20 && !ans; i++) { try { ans = await chrome.tabs.sendMessage(t, { type: 'CHECK_PRODUCT', product: { ...rp, asin: rp.asin, url: rp.url, cap: 90, monitor: true, autoBuy: false } }); } catch (e) { await new Promise(r => setTimeout(r, 30)); } }
      return { ms: Date.now() - c0, status: (await ps(p)).status, before };
    }, { pid });
    say('6 asked 0 s after the new page began (its name arrives 1.5 s later): the answer waits for the page and is the real one', r.ms >= 1400 && !/UNREADABLE|not the product/.test(String(r.status)) && /ELIGIBLE|WAITING|OVER CAP|CHECKOUT/.test(String(r.status)), r.ms + ' ms; status: ' + String(r.status).slice(0, 70));
  }
  clearInterval(watch);
  const bad = out.filter((x) => !x[1]).length;
  console.log(bad ? `SOME FAILED: ${bad} of ${out.length}` : `ALL PASS (${out.length})`);
  await ctx.close(); server.close(); process.exit(bad ? 1 : 0);
})().catch((e) => { console.log('HARNESS ERROR ' + (e && e.stack || e)); process.exit(2); });
