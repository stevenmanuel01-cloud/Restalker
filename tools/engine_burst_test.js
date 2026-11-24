// The real buying engine in Chromium against a stand-in Amazon: many restock signals in a row for one armed listing ("watching closely",
// v150). Sold-out looks must change nothing and stay inside the engine's own traffic rule; the look that finds it buyable must start
// the checkout.   usage: node burst.js <buyer_engine folder>
const { chromium } = require('/opt/npm-tools/node_modules/playwright');
const fs = require('fs'), os = require('os'), path = require('path');
const ext = path.resolve(process.argv[2]);
const A = 'B0TESTBW01';
let stock = false, loads = [];
const page = () => stock ? `<!doctype html><html><head><title>Amazon.com: Nauctis Bundle</title></head><body><div id="dp" class="a-container">
<span id="productTitle">Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle</span>
<div id="corePrice_feature_div"><span class="a-price"><span class="a-offscreen">$89.99</span></span></div>
<div id="availability"><span class="a-size-medium a-color-success">In Stock</span></div>
<div id="merchantInfoFeature_feature_div"><span>Ships from Amazon.com</span> <span>Sold by Amazon.com</span></div>
<div id="tabular-buybox"><span class="a-size-small">Ships from</span><span class="tabular-buybox-text">Amazon.com</span><span>Sold by</span><span class="tabular-buybox-text">Amazon.com</span></div>
<form id="addToCart" method="post" action="/checkout/standin"><input type="hidden" name="ASIN" value="${A}"><input type="hidden" id="ASIN" value="${A}">
<span id="submit.add-to-cart"><input id="add-to-cart-button" name="submit.add-to-cart" type="submit" value="Add to Cart"></span>
<span id="submit.buy-now"><input id="buy-now-button" name="submit.buy-now" type="submit" value="Buy Now"></span></form></div></body></html>`
 : `<!doctype html><html><head><title>Amazon.com</title></head><body><div id="dp"><span id="productTitle">Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle</span>
<div id="availability"><span>Currently unavailable.</span></div><p>We don't know when or if this item will be back in stock.</p></div></body></html>`;
const out = []; const say = (name, ok, more) => { out.push([name, ok]); console.log((ok ? 'PASS ' : 'FAIL ') + name + (more ? '  | ' + more : '')); };
(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rkbw'));
  const ctx = await chromium.launchPersistentContext(dir, { headless: false, executablePath: '/opt/pw-browsers/chromium-1243/chrome-linux64/chrome',
    args: ['--headless=new', '--no-sandbox', `--disable-extensions-except=${ext}`, `--load-extension=${ext}`] });
  await ctx.route(/^https:\/\/(www\.)?amazon\.com\//, (route) => {
    const u = route.request().url();
    if (u.includes('/dp/' + A) && route.request().resourceType() === 'document') { loads.push(Date.now()); return route.fulfill({ status: 200, contentType: 'text/html', body: page() }); }
    return route.fulfill({ status: 200, contentType: 'text/html', body: '<html><body><h1>stand-in checkout</h1></body></html>' });
  });
  await ctx.route(/^https?:\/\/(?!(www\.)?amazon\.com)(?!127\.0\.0\.1)(?!localhost)[^/]*\//, (route) => route.abort()).catch(() => {});
  let sw = ctx.serviceWorkers()[0] || await ctx.waitForEvent('serviceworker', { timeout: 20000 });
  const id = sw.url().split('/')[2];
  const dash = await ctx.newPage(); await dash.goto(`chrome-extension://${id}/dashboard.html`); await dash.waitForTimeout(1500);
  const send = (m) => dash.evaluate((m) => new Promise((res) => { const t = setTimeout(() => res({ ok: false, reason: 'no answer in 40 s' }), 40000);
    chrome.runtime.sendMessage(m).then((r) => { clearTimeout(t); res(r); }, (e) => { clearTimeout(t); res({ ok: false, reason: 'error: ' + e }); }); }), m);
  const all = async () => send({ type: 'GET_ALL' });
  const state = async (pid) => ((await all()).states || {})[pid] || {};
  const made = await send({ type: 'ADD_CUSTOM_PRODUCT' }); const pid = made.id;
  const set = await send({ type: 'SET_CUSTOM_ASIN', productId: pid, asin: A });
  await send({ type: 'SET_PRODUCT', productId: pid, values: { cap: 90 } });
  const arm = await send({ type: 'ARM_PRODUCT', productId: pid });
  await dash.waitForTimeout(4000);                     // (its own first look after arming)
  let s = await state(pid);
  say('0 the listing is added, capped and armed', set.ok && arm.ok && s.armed === true && s.monitor === true, JSON.stringify(arm) + ' status=' + s.status);
  sw = ctx.serviceWorkers()[0] || sw;
  const signal = (n) => sw.evaluate(async ({ asin, n }) => { try { await buyerDetectorBridge.onRestock({ type: 'restock', store: 'amazon', amazonAsin: asin,
    eventId: 'bw-' + asin + '-' + Date.now() + '-' + n, detectedAt: new Date().toISOString(), source: 'watching closely' }); return 'ok'; } catch (e) { return 'ERR ' + (e && e.message || e); } }, { asin: A, n });
  // 1. sold out: 12 signals, one every 0.7 s (far faster than Restalker sends them)
  const before = await state(pid), l0 = loads.length, t0 = Date.now(); const answers = [];
  for (let i = 0; i < 12; i++) { answers.push(await Promise.race([signal(i), new Promise((r) => setTimeout(() => r('slow'), 9000))])); await dash.waitForTimeout(700); }
  await dash.waitForTimeout(2500);
  s = await state(pid); const mine = loads.slice(l0), gaps = mine.slice(1).map((t, i) => t - mine[i]);
  say('1a sold-out looks: every signal is taken without an error', answers.every((a) => a === 'ok' || a === 'slow'), answers.join(','));
  say('1b the engine looked at the page itself, more than once', mine.length >= 2, `${mine.length} page loads in ${((Date.now() - t0) / 1000).toFixed(1)} s`);
  say('1c its own traffic rule held: never two looks within 1.8 s', gaps.every((g) => g >= 1700), 'gaps ms: ' + gaps.join(','));
  say('1d nothing is spent: still armed, not bought, not tried, no checkout', s.armed === true && !s.purchased && !s.attempted && !s.inProgress && !s.submissionPending && !(await all()).global?.cartOwner, 'status=' + s.status);
  say('1e no failure is counted against it', (Number(s.consecutiveErrors) || 0) === (Number(before.consecutiveErrors) || 0), `errors ${before.consecutiveErrors || 0} -> ${s.consecutiveErrors || 0}`);
  // 2. it comes into stock at $89.99 (cap $90): the next looks must start the checkout
  stock = true; let started = false, st2 = {};
  for (let i = 0; i < 8 && !started; i++) { await signal(100 + i); await dash.waitForTimeout(2200); st2 = await state(pid); const gl = (await all()).global || {};
    started = !!(st2.inProgress || st2.checkoutOwned || st2.submissionPending || gl.cartOwner || /CHECKOUT|ELIGIBLE|BUY NOW|ADDING|CART/i.test(String(st2.status || ''))); }
  say('2 in stock at or under the cap: a look starts the checkout', started, 'status=' + String(st2.status).slice(0, 120));
  const bad = out.filter((x) => !x[1]).length;
  console.log(bad ? `SOME FAILED: ${bad} of ${out.length}` : `ALL PASS (${out.length})`);
  await ctx.close(); process.exit(bad ? 1 : 0);
})().catch((e) => { console.log('HARNESS ERROR ' + (e && e.stack || e)); process.exit(2); });
