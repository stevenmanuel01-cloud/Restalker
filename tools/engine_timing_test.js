// The real buying engine in Chromium against a stand-in Amazon: many restock signals in a row for one armed listing ("watching closely",
// v150). Sold-out looks must change nothing and stay inside the engine's own traffic rule; the look that finds it buyable must start
// the checkout.   usage: node burst.js <buyer_engine folder>
const { chromium } = require('/opt/npm-tools/node_modules/playwright');
const fs = require('fs'), os = require('os'), path = require('path');
const ext = path.resolve(process.argv[2]);
const A = 'B0TESTBW01';
const LAT = 0, SLOW = Number(process.argv[3] || 1500);      // a script in the middle of each page that takes this long (the rest of the page waits for it, like on the real site)
let stock = true, loads = [], reqs = [];
const page = () => stock ? `<!doctype html><html><head><title>Amazon.com: Nauctis Bundle</title></head><body><div id="dp" class="a-container">
<span id="productTitle">Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle</span>
<div id="corePrice_feature_div"><span class="a-price"><span class="a-offscreen">$89.99</span></span></div>
<div id="availability"><span class="a-size-medium a-color-success">In Stock</span></div>
<div id="merchantInfoFeature_feature_div"><span>Ships from Amazon.com</span> <span>Sold by Amazon.com</span></div>
<div id="tabular-buybox"><span class="a-size-small">Ships from</span><span class="tabular-buybox-text">Amazon.com</span><span>Sold by</span><span class="tabular-buybox-text">Amazon.com</span></div>
<form id="addToCart" method="post" action="/checkout/standin"><input type="hidden" name="ASIN" value="${A}"><input type="hidden" id="ASIN" value="${A}">
<span id="submit.add-to-cart"><input id="add-to-cart-button" name="submit.add-to-cart" type="submit" value="Add to Cart"></span>
<span id="submit.buy-now"><input id="buy-now-button" name="submit.buy-now" type="submit" value="Buy Now"></span></form></div>
<script src="/slow.js"></script><div id="feature-bullets">the rest of the page</div></body></html>`
 : `<!doctype html><html><head><title>Amazon.com</title></head><body><div id="dp"><span id="productTitle">Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle</span>
<div id="availability"><span>Currently unavailable.</span></div><p>We don't know when or if this item will be back in stock.</p></div></body></html>`;
const out = []; const say = (name, ok, more) => { out.push([name, ok]); console.log((ok ? 'PASS ' : 'FAIL ') + name + (more ? '  | ' + more : '')); };
(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rkbw'));
  const ctx = await chromium.launchPersistentContext(dir, { headless: false, executablePath: '/opt/pw-browsers/chromium-1243/chrome-linux64/chrome',
    args: ['--headless=new', '--no-sandbox', `--disable-extensions-except=${ext}`, `--load-extension=${ext}`] });
  await ctx.route(/^https:\/\/(www\.)?amazon\.com\//, (route) => {
    const u = route.request().url();
    if (u.includes('/dp/' + A) && route.request().resourceType() === 'document') { loads.push(Date.now()); reqs.push({ t: Date.now(), m: 'GET', p: '/dp', doc: true }); return new Promise(r => setTimeout(r, LAT)).then(() => route.fulfill({ status: 200, contentType: 'text/html', body: page() })); }
    if (new URL(u).pathname === '/slow.js') return new Promise(r => setTimeout(r, SLOW)).then(() => route.fulfill({ status: 200, contentType: 'application/javascript', body: '/* slow */' }));
    const pth = new URL(u).pathname; reqs.push({ t: Date.now(), m: route.request().method(), p: pth.slice(0, 40), doc: route.request().resourceType() === 'document' });
    if (/^\/checkout\//.test(pth)) return new Promise(r => setTimeout(r, LAT)).then(() => route.fulfill({ status: 200, contentType: 'text/html', body: `<!doctype html><html><head><title>Checkout</title></head><body><div id="checkout-main"><h1>Review your order</h1><div data-asin="${A}"><span>Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle</span><div class="a-alert-content">This item is currently unavailable. It has been removed from your order.</div></div></div><script src="/slow.js"></script><p>the rest of the checkout page</p></body></html>` }));
    return route.fulfill({ status: 200, contentType: 'text/html', body: '<html><body><h1>stand-in</h1></body></html>' });
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
  await dash.waitForTimeout(14000);
  sw = ctx.serviceWorkers()[0] || sw;
  const rows = await sw.evaluate(() => RestalkerTiming.all());
  const prod = rows.filter(r => r.kind === 'product' && 'buyNow' in r.marks), chk = rows.filter(r => r.kind === 'checkout');
  console.log('ROWS ' + JSON.stringify(rows.slice(0, 6)));
  const dps = reqs.filter(r => r.doc && r.p === '/dp'), posts = reqs.filter(r => r.m === 'POST');
  const pressAfter = posts.map(p => { const d = dps.filter(x => x.t <= p.t).pop(); return d ? p.t - d.t : null; }).filter(x => x !== null);
  say('1 product pages are timed: the Buy Now button, price, seller and title are noted', prod.length >= 1 && prod.every(r => ['buyNow', 'price', 'seller', 'title'].every(k => k in r.marks) && ('idle' in r.marks || 'beginEarly' in r.marks)), JSON.stringify(prod[0] && prod[0].marks));
  // v155: the engine no longer waits for the page to be read to its end (until v154 these two checks measured that wait: about SLOW ms)
  const earlyRows = prod.filter(r => 'beginEarly' in r.marks);
  say('2 the measurement shows it: Buy Now was pressed right after the button was in the page (until v154: about ' + SLOW + ' ms later)', earlyRows.length >= 2 && earlyRows.every(r => r.marks.beginEarly - r.marks.buyNow >= -80 && r.marks.beginEarly - r.marks.buyNow < 400), earlyRows.map(r => r.marks.beginEarly - r.marks.buyNow).join(', ') + ' ms');
  say('3 ... and the stand-in store saw the press that soon after the page request (the first one is the look that checks the listing when it is armed)', pressAfter.length >= 3 && pressAfter.slice(1).every(x => x < 500), 'press after page request: ' + pressAfter.join(', ') + ' ms');
  say('4 checkout pages are timed: Amazon\'s "unavailable" is noted, and when the engine started', chk.length >= 1 && chk.every(r => 'unavailable' in r.marks && 'idle' in r.marks && r.marks.idle - r.marks.unavailable >= SLOW - 400), JSON.stringify(chk[0] && chk[0].marks));
  say('5 nothing personal is in a row (kind, listing, path, times only)', rows.every(r => Object.keys(r).sort().join() === 'asin,at,first,got,kind,marks,path'), Object.keys(rows[0] || {}).join());
  const fs2 = require('fs'); fs2.writeFileSync('/tmp/timing_rows.json', JSON.stringify(rows));
  const bad = out.filter((x) => !x[1]).length;
  console.log(bad ? `SOME FAILED: ${bad} of ${out.length}` : `ALL PASS (${out.length})`);
  await ctx.close(); process.exit(bad ? 1 : 0);
})().catch((e) => { console.log('HARNESS ERROR ' + (e && e.stack || e)); process.exit(2); });
