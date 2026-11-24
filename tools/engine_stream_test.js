// The real buying engine in Chromium against a stand-in Amazon that sends its product page IN PIECES, as the real one does (v155).
// The purchase must start the moment the buy box is complete - not when the page has been read to its end - and never earlier.
// usage: node stream.js <buyer_engine folder> [old]      ("old" = an engine before v155: only the timing is measured)
const { chromium } = require('/opt/npm-tools/node_modules/playwright');
const https = require('https'), fs = require('fs'), os = require('os'), path = require('path');
const ext = path.resolve(process.argv[2]), OLD = process.argv[3] === 'old';
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
  if (OLD) {
    const a = await run('boxfirst', 1, 12000);
    console.log(`MEASURED (engine before v155) buy box complete at ${D1} ms, page end at ${D1 + D2} ms -> Buy Now pressed ${a.press} ms after the page request`);
    await ctx.close(); server.close(); process.exit(0);
  }
  // 1. Amazon's own order: buy box first, then title and price, then the long rest of the page
  let a = await run('boxfirst', 1);
  console.log(`MEASURED buy box complete at ${D1} ms, page end at ${D1 + D2} ms -> Buy Now pressed ${a.press} ms after the page request`);
  say('1 Buy Now is pressed as soon as the buy box, title and price are in the page - long before the page has been read to its end', a.press !== null && a.press >= D1 && a.press < D1 + 700, a.press + ' ms (the page ended at ' + (D1 + D2) + ' ms)');
  say('2 the form was complete when it was pressed (its last field went to Amazon)', /offerListingID=LASTFIELD/.test(a.body) && /merchantID=ATVPDKIKX0DER/.test(a.body) && /submit\.buy-now/.test(a.body), a.body.slice(0, 120));
  let pre = await SW().evaluate(() => ({ pre: RK_EARLY.pre || 0, yes: RK_EARLY.yes, starts: RK_PRE.starts, reached: RK_PRE.reached, miss: RK_PRE.miss, off: !!RK_EARLY.off }));
  say('3 it is counted as a start "while the page was still arriving", and its arrival at Amazon\'s next page is noted', pre.pre >= 1 && pre.starts >= 1 && pre.reached >= 1 && pre.miss === 0 && !pre.off, JSON.stringify(pre));
  // 2. title and price BEFORE the buy box; the form is still open for a while -> not pressed before its end
  D1 = 1100; a = await run('titlefirst', 2);
  say('4 title, price and button are there but the form is still arriving: NOT pressed until the form is complete', a.press !== null && a.press >= D1 && a.press < D1 + 700 && /offerListingID=LASTFIELD/.test(a.body), a.press + ' ms (form complete at ' + D1 + ' ms); last field sent: ' + /LASTFIELD/.test(a.body));
  // 3. the form ends only together with the page -> pressed when the page has been read (as before v155), with the whole form
  D1 = 700; D2 = 1500; a = await run('nobox-end', 3);
  say('5 a buy box that ends only with the page: pressed when the page has been read, with the whole form', a.press !== null && a.press >= D1 + D2 && /offerListingID=LASTFIELD/.test(a.body), a.press + ' ms (page end ' + (D1 + D2) + ' ms)');
  // 4. the whole page in one piece -> as always
  D1 = 600; D2 = 2500; a = await run('whole', 4);
  say('6 a page that comes in one piece is bought as always', a.press !== null && a.press < 1500 && /LASTFIELD/.test(a.body), a.press + ' ms');
  // 5. Amazon is slow to answer at all (first byte late): nothing is pressed on the page that is being left
  //    (the old page still shows nothing buyable here, so this checks the new page is the one that is asked)
  firstByte = 1500; a = await run('boxfirst', 5, 12000); firstByte = 0;
  say('7 a late first answer from Amazon: pressed only after the new page\'s buy box is there', a.press !== null && a.press >= 1500 + D1 && a.press < 1500 + D1 + 700, a.press + ' ms (first byte 1500 ms + buy box ' + D1 + ' ms)');
  // 6. THE PAGE THAT IS BEING LEFT must never answer: the tab shows a buyable page; a reload to a slow, sold-out page is under way
  {
    stock = true; mode = 'whole'; firstByte = 0;
    s = await state(pid);
    const r = await SW().evaluate(async ({ pid }) => {
      const p = (await catalog()).find(x => x.id === pid), st = await ps(p), rp = runtimeProduct(p, st), asin = rp.asin, t = st.tabId;
      await setp(p, { armed: false, autoBuy: false, monitor: false });                 // (nothing may be bought in this check: only the question is tested)
      await new Promise(r => { const fn = (i, x) => { if (i === t && x.status === 'complete') { chrome.tabs.onUpdated.removeListener(fn); r(); } }; chrome.tabs.onUpdated.addListener(fn); chrome.tabs.update(t, { url: 'https://www.amazon.com/dp/' + asin + '?old=1' }); setTimeout(r, 8000); });
      const probe = { type: 'REVALIDATE_QUEUED', product: { ...rp, asin, url: rp.url, cap: 90, monitor: true, autoBuy: true } };
      const before = await chrome.tabs.sendMessage(t, probe).catch(e => ({ err: String(e) }));
      return { t, before, probe };
    }, { pid });
    stock = false; firstByte = 1800;                                                     // the page it is reloaded to: sold out, and slow to start
    const r2 = await SW().evaluate(async ({ t, probe }) => {
      const y0 = RK_EARLY.yes, p0 = RK_EARLY.pre || 0, t0 = Date.now();
      const w = waitForCompleteOrBuyable(t, probe); await chrome.tabs.reload(t); const ok = await w;
      return { ok, ms: Date.now() - t0, early: (RK_EARLY.yes - y0) + ((RK_EARLY.pre || 0) - p0) };
    }, { t: r.t, probe: r.probe });
    firstByte = 0;
    say('8 the page that is being left shows "buyable", the new page is sold out: the old page is never taken for the answer', r.before && r.before.eligible === true && r2.early === 0 && r2.ms >= 1800, 'old page said eligible=' + (r.before && r.before.eligible) + '; the wait ended after ' + r2.ms + ' ms, early answers: ' + r2.early);
    await send({ type: 'ARM_PRODUCT', productId: pid }); await dash.waitForTimeout(4500);
  }
  // 7. sold out while arriving: nothing is pressed
  { stock = false; await rearm(); const t0 = Date.now(); await signal(8); await dash.waitForTimeout(6000);
    const np = reqs.filter(r => r.m === 'POST' && r.t >= t0).length, ng = reqs.filter(r => r.m === 'GET' && r.t >= t0).length;
    say('9 a sold-out page that arrives in pieces: it is looked at, nothing is pressed', np === 0 && ng >= 1, np + ' presses, ' + ng + ' looks'); }
  // 8. early starts that arrive nowhere switch themselves off after three in a row; then the engine starts as before v155
  postMode = 'nowhere'; stock = false; await rearm(); mode = 'boxfirst'; stock = true; const tN = Date.now(); await signal(20);
  let offAt = null; for (let i = 0; i < 300 && offAt === null; i++) { await sleep(500); if (await SW().evaluate(() => !!RK_EARLY.off)) offAt = Date.now() - tN; if (i % 40 === 39) { stock = true; await rearm(); await signal(21 + i); } }
  stock = false;
  pre = await SW().evaluate(() => ({ starts: RK_PRE.starts, reached: RK_PRE.reached, miss: RK_PRE.miss, off: !!RK_EARLY.off }));
  say('10 three early starts in a row that arrive nowhere: starting early switches itself off', pre.off === true && pre.miss >= 3, JSON.stringify(pre) + (offAt ? ' after ' + Math.round(offAt / 1000) + ' s' : ''));
  postMode = 'checkout';
  a = await run('boxfirst', 30, 12000);
  say('11 after that the engine starts as before v155 (when the page has been read) - it still buys', a.press !== null && a.press >= D1 + D2, a.press + ' ms (page end ' + (D1 + D2) + ' ms)');
  const bad = out.filter((x) => !x[1]).length;
  console.log(bad ? `SOME FAILED: ${bad} of ${out.length}` : `ALL PASS (${out.length})`);
  await ctx.close(); server.close(); process.exit(bad ? 1 : 0);
})().catch((e) => { console.log('HARNESS ERROR ' + (e && e.stack || e)); process.exit(2); });
