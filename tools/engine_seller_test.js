// The real buying engine in Chromium against a stand-in Amazon: Amazon's purchase limit is kept PER SELLER (v155).
// Amazon refuses seller A with "purchase limit" -> A's offer is left alone (no checkout is opened), another seller's offer is bought,
// Restalker is told which seller, and what Restalker sends back (its own list, e.g. after a restart or Buy again) is obeyed.
// usage: node seller.js <buyer_engine folder>
const { chromium } = require('/opt/npm-tools/node_modules/playwright');
const fs = require('fs'), os = require('os'), path = require('path');
const ext = path.resolve(process.argv[2]);
const A = 'B0TESTSL01';
let seller = { id: 'A1SELLERAAAAAA', name: 'Card Kingdom Games' }, withId = true, reqs = [], price = '89.99';
const page = () => `<!doctype html><html><head><title>Amazon.com: Nauctis Bundle</title></head><body><div id="dp" class="a-container">
<span id="productTitle">Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle</span>
<div id="corePrice_feature_div"><span class="a-price"><span class="a-offscreen">$${price}</span></span></div>
<div id="availability"><span class="a-size-medium a-color-success">In Stock</span></div>
<div id="merchantInfoFeature_feature_div"><div class="offer-display-feature-text"><span class="offer-display-feature-text-message">${seller.name}</span></div></div>
<form id="addToCart" method="post" action="/checkout/standin"><input type="hidden" name="ASIN" value="${A}"><input type="hidden" id="ASIN" value="${A}">
${withId ? `<input type="hidden" id="merchantID" name="merchantID" value="${seller.id}">` : ''}
<span id="submit.add-to-cart"><input id="add-to-cart-button" name="submit.add-to-cart" type="submit" value="Add to Cart"></span>
<span id="submit.buy-now"><input id="buy-now-button" name="submit.buy-now" type="submit" value="Buy Now"></span></form></div>
<div id="feature-bullets">the rest of the page</div></body></html>`;
const limited = new Set(['A1SELLERAAAAAA', 'name:nameonly games']);
const out = []; const say = (name, ok, more) => { out.push([name, ok]); console.log((ok ? 'PASS ' : 'FAIL ') + name + (more ? '  | ' + more : '')); };
(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rksl'));
  const ctx = await chromium.launchPersistentContext(dir, { headless: false, executablePath: '/opt/pw-browsers/chromium-1243/chrome-linux64/chrome',
    args: ['--headless=new', '--no-sandbox', `--disable-extensions-except=${ext}`, `--load-extension=${ext}`] });
  await ctx.route(/^https:\/\/(www\.)?amazon\.com\//, (route) => {
    const u = route.request().url(), pth = new URL(u).pathname, doc = route.request().resourceType() === 'document';
    if (u.includes('/dp/' + A) && doc) { reqs.push({ t: Date.now(), m: 'GET', p: '/dp', seller: seller.id }); return route.fulfill({ status: 200, contentType: 'text/html', body: page() }); }
    if (/^\/checkout\//.test(pth)) {
      const body = route.request().postData() || '', mid = (body.match(/merchantID=([A-Z0-9]+)/) || [])[1] || ('name:' + seller.name.toLowerCase());
      reqs.push({ t: Date.now(), m: route.request().method(), p: pth.slice(0, 40), seller: mid });
      const lim = limited.has(mid);
      return route.fulfill({ status: 200, contentType: 'text/html', body: `<!doctype html><html><head><title>Checkout</title></head><body><div id="checkout-main"><h1>Review your order</h1><div data-asin="${A}"><span>Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle</span><div class="a-alert-content">${lim ? 'There was a problem with some of the items in your order (see below for more information): Sorry, you’ve reached the purchase limit for this item. Please remove the item to continue.' : 'This item is currently unavailable. It has been removed from your order.'}</div></div></div></body></html>` });
    }
    return route.fulfill({ status: 200, contentType: 'text/html', body: '<html><body><h1>stand-in</h1></body></html>' });
  });
  await ctx.route(/^https?:\/\/(?!(www\.)?amazon\.com)(?!127\.0\.0\.1)(?!localhost)[^/]*\//, (route) => route.abort()).catch(() => {});
  let sw = ctx.serviceWorkers()[0] || await ctx.waitForEvent('serviceworker', { timeout: 20000 });
  const id = sw.url().split('/')[2];
  const dash = await ctx.newPage(); await dash.goto(`chrome-extension://${id}/dashboard.html`); await dash.waitForTimeout(1500);
  const send = (m) => dash.evaluate((m) => new Promise((res) => { const t = setTimeout(() => res({ ok: false, reason: 'no answer in 40 s' }), 40000);
    chrome.runtime.sendMessage(m).then((r) => { clearTimeout(t); res(r); }, (e) => { clearTimeout(t); res({ ok: false, reason: 'error: ' + e }); }); }), m);
  const state = async (pid) => ((await send({ type: 'GET_ALL' })).states || {})[pid] || {};
  const SW = () => (sw = ctx.serviceWorkers()[0] || sw);
  // what the engine tells Restalker is caught here (the bridge's own "tell")
  await SW().evaluate(() => { globalThis.__told = []; const b = buyerDetectorBridge, old = b.tell; b.tell = (m) => { try { globalThis.__told.push(m); } catch {} return old ? old(m) : false; }; });
  const made = await send({ type: 'ADD_CUSTOM_PRODUCT' }); const pid = made.id;
  const set = await send({ type: 'SET_CUSTOM_ASIN', productId: pid, asin: A });
  await send({ type: 'SET_PRODUCT', productId: pid, values: { cap: 90 } });
  const arm = async () => { const r = await send({ type: 'ARM_PRODUCT', productId: pid }); return r; };
  const posts = (since = 0) => reqs.filter(r => r.m === 'POST' && r.t >= since);
  const gets = (since = 0) => reqs.filter(r => r.m === 'GET' && r.t >= since);
  const signal = (n) => SW().evaluate(async ({ asin, n }) => { try { await buyerDetectorBridge.onRestock({ type: 'restock', store: 'amazon', amazonAsin: asin,
    eventId: 'sl-' + asin + '-' + Date.now() + '-' + n, detectedAt: new Date().toISOString(), source: 'test' }); return 'ok'; } catch (e) { return 'ERR ' + (e && e.message || e); } }, { asin: A, n });
  const rearm = async () => { let s = await state(pid); if (!s.armed || !s.monitor) { await arm(); await dash.waitForTimeout(3500); } };
  let a = await arm();
  await dash.waitForTimeout(9000);                     // its own first look after arming finds seller A buyable and goes to the checkout
  let s = await state(pid);
  let told = await SW().evaluate(() => globalThis.__told.filter(m => m && m.type === 'limit'));
  let lim = await SW().evaluate((asin) => rkLimitFor(asin), A);
  say('1 seller A: Amazon says "purchase limit" -> one checkout, then it stops (no second try)', posts().length === 1 && posts()[0].seller === 'A1SELLERAAAAAA' && /purchase limit/i.test(String(s.status)), posts().length + ' checkout(s); status: ' + String(s.status).slice(0, 110));
  say('2 the limit is kept for THAT seller (by Amazon\'s own seller number) and Restalker is told which', !!(lim && lim['A1SELLERAAAAAA'] && lim['A1SELLERAAAAAA'].name === 'Card Kingdom Games') && told.length === 1 && told[0].seller === 'A1SELLERAAAAAA' && told[0].asin === A && told[0].name === 'Card Kingdom Games', JSON.stringify(told).slice(0, 170));
  // the same seller again: looked at, NOT bought (no checkout opened)
  await rearm(); let t0 = Date.now();
  for (let i = 0; i < 3; i++) { await signal(i); await dash.waitForTimeout(3500); }
  s = await state(pid);
  say('3 the same seller in the buy box again (3 restock signals): the page is looked at, no checkout is opened', posts(t0).length === 0 && gets(t0).length >= 1 && /^WAITING — seller limit/.test(String(s.status)) && /Card Kingdom Games/.test(String(s.status)), posts(t0).length + ' checkouts, ' + gets(t0).length + ' looks; status: ' + String(s.status).slice(0, 120));
  say('4 it stays armed and watched meanwhile (nothing is locked)', s.armed === true && s.monitor === true && !s.purchased && !s.attempted, 'armed=' + s.armed + ' monitor=' + s.monitor);
  // another seller takes the buy box at the same price: bought (a checkout is opened)
  seller = { id: 'A2SELLERBBBBBB', name: 'Other Shop' }; t0 = Date.now();
  await rearm(); await signal(10); await dash.waitForTimeout(7000);
  say('5 ANOTHER seller in the buy box: its offer is bought (checkout opened) although seller A is limited', posts(t0).length >= 1 && posts(t0).every(p => p.seller === 'A2SELLERBBBBBB'), posts(t0).length + ' checkout(s) for ' + [...new Set(posts(t0).map(p => p.seller))].join(','));
  lim = await SW().evaluate((asin) => rkLimitFor(asin), A);
  say('6 seller A stays limited, seller B is not on the list', !!(lim && lim['A1SELLERAAAAAA']) && !lim['A2SELLERBBBBBB'], JSON.stringify(Object.keys(lim || {})));
  // a seller whose number is not in the page: kept and recognised by its name
  await dash.waitForTimeout(12000);
  seller = { id: 'X', name: 'NameOnly Games' }; withId = false; t0 = Date.now();
  await rearm(); await signal(20); await dash.waitForTimeout(8000);
  lim = await SW().evaluate((asin) => rkLimitFor(asin), A);
  const n1 = posts(t0).length;
  await rearm(); const t1 = Date.now(); await signal(21); await dash.waitForTimeout(4000);
  s = await state(pid);
  say('7 a seller without a number in the page is kept by name, and its offer is then left alone too', n1 === 1 && !!(lim && lim['n:nameonly games']) && posts(t1).length === 0 && /NameOnly Games/.test(String(s.status)), n1 + ' checkout, then ' + posts(t1).length + '; keys ' + JSON.stringify(Object.keys(lim || {})));
  // Restalker's own list is obeyed: it ends A's limit (your "Buy again") -> A is bought again
  await SW().evaluate((asin) => rkLimitSet(asin, {}), A);
  seller = { id: 'A1SELLERAAAAAA', name: 'Card Kingdom Games' }; withId = true; limited.delete('A1SELLERAAAAAA'); t0 = Date.now();
  await rearm(); await signal(30); await dash.waitForTimeout(6000);
  say('8 when Restalker ends the limit (Buy again), seller A\'s offer is bought again', posts(t0).length >= 1 && posts(t0)[0].seller === 'A1SELLERAAAAAA', posts(t0).length + ' checkout(s)');
  // Restalker's list for a seller the engine never met (another browser hit the limit): obeyed without a checkout
  await dash.waitForTimeout(12000);
  await SW().evaluate((asin) => rkLimitSet(asin, { 'A1SELLERAAAAAA': { until: Date.now() + 3600000, name: 'Card Kingdom Games' } }), A); t0 = Date.now();
  await rearm(); await signal(40); await dash.waitForTimeout(4500);
  s = await state(pid);
  say('9 a limit Restalker sends (another browser was refused) is obeyed: no checkout for that seller', posts(t0).length === 0 && /WAITING — seller limit/.test(String(s.status)), posts(t0).length + ' checkouts; ' + String(s.status).slice(0, 60));
  // an expired limit is no limit
  await SW().evaluate((asin) => { RK_LIMIT[asin] = { 'A1SELLERAAAAAA': { until: Date.now() - 1000, name: 'x' } }; }, A); t0 = Date.now();
  await rearm(); await signal(50); await dash.waitForTimeout(6000);
  say('10 a limit whose time is over is no limit: the offer is bought again', posts(t0).length >= 1, posts(t0).length + ' checkout(s)');
  // '*' (seller unknown): the whole listing rests, whoever sells
  await dash.waitForTimeout(12000);
  await SW().evaluate((asin) => rkLimitSet(asin, { '*': { until: Date.now() + 3600000, name: '' } }), A);
  seller = { id: 'A2SELLERBBBBBB', name: 'Other Shop' }; t0 = Date.now();
  await rearm(); await signal(60); await dash.waitForTimeout(4500);
  say('11 a limit without a known seller rests the whole listing (as v154 did)', posts(t0).length === 0, posts(t0).length + ' checkouts');
  // over the cap stays over the cap (the seller rule never buys what the cap forbids)
  await SW().evaluate((asin) => rkLimitSet(asin, {}), A); price = '120.00'; t0 = Date.now();
  await rearm(); await signal(70); await dash.waitForTimeout(4500);
  s = await state(pid);
  say('12 a price over the cap is never bought, whoever sells', posts(t0).length === 0 && /OVER CAP/.test(String(s.status)), String(s.status).slice(0, 40));
  const bad = out.filter((x) => !x[1]).length;
  console.log(bad ? `SOME FAILED: ${bad} of ${out.length}` : `ALL PASS (${out.length})`);
  await ctx.close(); process.exit(bad ? 1 : 0);
})().catch((e) => { console.log('HARNESS ERROR ' + (e && e.stack || e)); process.exit(2); });
