// The real buying engine in Chromium against a stand-in Amazon: many restock signals in a row for one armed listing ("watching closely",
// v150). Sold-out looks must change nothing and stay inside the engine's own traffic rule; the look that finds it buyable must start
// the checkout.   usage: node burst.js <buyer_engine folder>
const { chromium } = require('/opt/npm-tools/node_modules/playwright');
const fs = require('fs'), os = require('os'), path = require('path');
const ext = path.resolve(process.argv[2]);
const A = 'B0TESTBW01';
const LAT = 0, SLOW = Number(process.argv[3] || 3000);      // the page's last picture takes this long (the text of the page is there at once)
let stock = false, loads = [], reqs = [], pinLoads = [], plainLoads = [];
const page = () => stock ? `<!doctype html><html><head><title>Amazon.com: Nauctis Bundle</title></head><body><div id="dp" class="a-container">
<span id="productTitle">Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle</span>
<div id="corePrice_feature_div"><span class="a-price"><span class="a-offscreen">$89.99</span></span></div>
<div id="availability"><span class="a-size-medium a-color-success">In Stock</span></div>
<div id="merchantInfoFeature_feature_div"><span>Ships from Amazon.com</span> <span>Sold by Amazon.com</span></div>
<div id="tabular-buybox"><span class="a-size-small">Ships from</span><span class="tabular-buybox-text">Amazon.com</span><span>Sold by</span><span class="tabular-buybox-text">Amazon.com</span></div>
<form id="addToCart" method="post" action="/checkout/standin"><input type="hidden" name="ASIN" value="${A}"><input type="hidden" id="ASIN" value="${A}">
<span id="submit.add-to-cart"><input id="add-to-cart-button" name="submit.add-to-cart" type="submit" value="Add to Cart"></span>
<span id="submit.buy-now"><input id="buy-now-button" name="submit.buy-now" type="submit" value="Buy Now"></span></form></div>
<div id="feature-bullets">the rest of the page</div><img src="/slow.png" width="10" height="10"></body></html>`
 : `<!doctype html><html><head><title>Amazon.com</title></head><body><div id="dp"><span id="productTitle">Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle</span>
<div id="availability"><span>Currently unavailable.</span></div><p>We don't know when or if this item will be back in stock.</p></div></body></html>`;
const out = []; const say = (name, ok, more) => { out.push([name, ok]); console.log((ok ? 'PASS ' : 'FAIL ') + name + (more ? '  | ' + more : '')); };
(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rkbw'));
  const ctx = await chromium.launchPersistentContext(dir, { headless: false, executablePath: '/opt/pw-browsers/chromium-1243/chrome-linux64/chrome',
    args: ['--headless=new', '--no-sandbox', `--disable-extensions-except=${ext}`, `--load-extension=${ext}`] });
  await ctx.route(/^https:\/\/(www\.)?amazon\.com\//, (route) => {
    const u = route.request().url();
    if (u.includes('/dp/' + A) && route.request().resourceType() === 'document') { const pinned = new URL(u).searchParams.get('m') === 'ATVPDKIKX0DER'; (pinned ? pinLoads : plainLoads).push(Date.now()); stock = pinned; loads.push(Date.now()); reqs.push({ t: Date.now(), m: 'GET', p: '/dp', doc: true }); return new Promise(r => setTimeout(r, LAT)).then(() => route.fulfill({ status: 200, contentType: 'text/html', body: page() })); }
    if (new URL(u).pathname === '/slow.png') return new Promise(r => setTimeout(r, SLOW)).then(() => route.fulfill({ status: 200, contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64') }));
    const pth = new URL(u).pathname; reqs.push({ t: Date.now(), m: route.request().method(), p: pth.slice(0, 40), doc: route.request().resourceType() === 'document' });
    if (/^\/checkout\//.test(pth)) return new Promise(r => setTimeout(r, LAT)).then(() => route.fulfill({ status: 200, contentType: 'text/html', body: `<!doctype html><html><head><title>Checkout</title></head><body><div id="checkout-main"><h1>Review your order</h1><div data-asin="${A}"><span>Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle</span><div class="a-alert-content">This item is currently unavailable. It has been removed from your order.</div></div></div></body></html>` }));
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
  const fs3 = require('fs');
  await dash.waitForTimeout(6000);
  sw = ctx.serviceWorkers()[0] || sw;
  const LINK = process.argv[4] === 'link';
  const linked = await sw.evaluate(() => buyerDetectorBridge.connected());
  if (LINK) say('1 the engine is linked to Restalker (the real link, both ends)', linked === true, String(linked));
  if (LINK) {
    fs3.writeFileSync('/tmp/rk_pin_' + A, '1'); await dash.waitForTimeout(1500);
    const pinnedL = await (ctx.serviceWorkers()[0] || sw).evaluate((a) => RK_PIN.has(a), A);
    say('2 the pin from Restalker reaches the engine over the link', pinnedL === true);
    await dash.waitForTimeout(3000);
    const up = await (ctx.serviceWorkers()[0] || sw).evaluate(() => ({ kept: RestalkerTiming.all().length, up: RestalkerTiming.sentUp() }));
    say('3 the page timings are sent up the link to Restalker (v151 never sent them)', up.kept >= 1 && up.up === up.kept, JSON.stringify(up));
    const bad = out.filter((x) => !x[1]).length; console.log(bad ? `SOME FAILED: ${bad} of ${out.length}` : `ALL PASS (${out.length})`);
    await ctx.close(); process.exit(bad ? 1 : 0);
  }
  // a restock signal while only the ordinary page exists for the engine: it looks, the page says sold out, nothing is bought
  reqs = []; pinLoads = []; plainLoads = [];
  await signal(1); await dash.waitForTimeout(5000);
  say('2 without a pin the look goes to the ordinary page (sold out there): no checkout', plainLoads.length >= 1 && pinLoads.length === 0 && reqs.filter(r => r.m === 'POST').length === 0, `ordinary ${plainLoads.length}, seller page ${pinLoads.length}`);
  // Restalker sends the pin (as it does when its reader saw the offer on the seller page)
  await (ctx.serviceWorkers()[0] || sw).evaluate((a) => rkPin(a, Date.now() + 12000), A);
  const pinned = await (ctx.serviceWorkers()[0] || sw).evaluate((a) => RK_PIN.has(a), A);
  say('3 the pin is set in the engine', pinned === true);
  reqs = []; pinLoads = []; plainLoads = [];
  const t1 = Date.now(); await signal(2); await dash.waitForTimeout(6000);
  const posts = reqs.filter(r => r.m === 'POST');
  say('4 with the pin the look goes to the page with Amazon as the seller, finds it buyable and starts the checkout', pinLoads.length >= 1 && posts.length >= 1, `seller page ${pinLoads.length}, ordinary ${plainLoads.length}, checkout starts ${posts.length}, first ${posts[0] ? posts[0].t - t1 : '-'} ms after the signal`);
  await dash.waitForTimeout(45000);        // (the checkout tries run out: the stand-in checkout says unavailable; the pin is over after 12 s)
  reqs = []; pinLoads = []; plainLoads = [];
  s = await state(pid);
  const armAgain = await send({ type: 'ARM_PRODUCT', productId: pid }); await dash.waitForTimeout(1500);
  await signal(3); await dash.waitForTimeout(6000);
  const still = await (ctx.serviceWorkers()[0] || sw).evaluate((a) => RK_PIN.has(a) && RK_PIN.get(a) > Date.now(), A);
  say('5 when the pin is over the look is back on the ordinary page', still === false && plainLoads.length >= 1 && pinLoads.length === 0, `ordinary ${plainLoads.length}, seller page ${pinLoads.length}; status ${String(s.status).slice(0, 60)}`);
  const bad = out.filter((x) => !x[1]).length;
  console.log(bad ? `SOME FAILED: ${bad} of ${out.length}` : `ALL PASS (${out.length})`);
  await ctx.close(); process.exit(bad ? 1 : 0);
})().catch((e) => { console.log('HARNESS ERROR ' + (e && e.stack || e)); process.exit(2); });
