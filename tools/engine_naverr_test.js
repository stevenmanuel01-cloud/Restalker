// The real buying engine in Chromium against a stand-in Best Buy whose product page does not load (the browser shows its own error
// page). v151 must name the browser's real reason, load the page again at once, and go on when it loads.
// usage: node naverr.js <buyer_engine folder> [old]      (old = an engine before v151: shows what it did)
const { chromium } = require('/opt/npm-tools/node_modules/playwright');
const fs = require('fs'), os = require('os'), path = require('path');
const ext = path.resolve(process.argv[2]), OLD = process.argv[3] === 'old';
const URL_P = 'https://www.bestbuy.com/product/wizards-of-the-coast-magic-test-bundle/J3KQ2T7HXX';
let mode = 'ok', failFirst = 0, loads = [], others = [];
const good = `<!doctype html><html><head><title>Magic Test Bundle - Best Buy</title></head><body><main><h1>Wizards of the Coast - Magic: The Gathering Test Bundle</h1>
<div>SKU: 6612345</div><div data-testid="price-block" aria-label="Your price for this item is $54.99">$54.99</div>
<button data-testid="pdp-add-to-cart" disabled>Sold Out</button></main></body></html>`;
const out = []; const say = (name, ok, more) => { out.push(ok); console.log((ok ? 'PASS ' : 'FAIL ') + name + (more ? '  | ' + more : '')); };
(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rkne'));
  const ctx = await chromium.launchPersistentContext(dir, { headless: false, executablePath: '/opt/pw-browsers/chromium-1243/chrome-linux64/chrome',
    args: ['--headless=new', '--no-sandbox', `--disable-extensions-except=${ext}`, `--load-extension=${ext}`] });
  await ctx.route(/^https:\/\/(www\.)?bestbuy\.com\//, (route) => {
    const doc = route.request().resourceType() === 'document';
    if (!doc) return route.fulfill({ status: 200, contentType: 'text/plain', body: '' });
    if (!route.request().url().includes('/product/')) { others.push(route.request().url().replace(/\?.*/, '')); return route.fulfill({ status: 200, contentType: 'text/html', body: '<html><body><h1>Your cart is empty</h1></body></html>' }); }
    loads.push(Date.now());
    const failing = mode !== 'ok' && (failFirst === 0 || loads.length <= failFirst);
    if (failing && mode === 'reset') return route.abort('connectionreset');
    if (failing && mode === 'offline') return route.abort('internetdisconnected');
    if (failing && mode === 'timeout') return route.abort('timedout');
    if (failing && mode === '403') return route.fulfill({ status: 403, contentType: 'text/html', body: '' });
    return route.fulfill({ status: 200, contentType: 'text/html', body: good });
  });
  await ctx.route(/^https?:\/\/(?!(www\.)?bestbuy\.com)(?!127\.0\.0\.1)(?!localhost)[^/]*\//, (route) => route.abort()).catch(() => {});
  let sw = ctx.serviceWorkers()[0] || await ctx.waitForEvent('serviceworker', { timeout: 20000 });
  const id = sw.url().split('/')[2];
  const dash = await ctx.newPage(); await dash.goto(`chrome-extension://${id}/dashboard.html`); await dash.waitForTimeout(1500);
  const run = async (m, ff) => {
    mode = m; failFirst = ff; loads = []; others = [];
    sw = ctx.serviceWorkers()[0] || sw;
    const t0 = Date.now();
    const r = await sw.evaluate((u) => Promise.race([RestalkerBestBuy.command({ type: 'BB_PRACTICE', url: u, name: 'Magic Test Bundle', tokens: ['test', 'bundle'], cap: 60 }),
      new Promise(res => setTimeout(() => res({ ok: false, reason: 'no answer in 100 s' }), 100000))]), URL_P);
    const res = (r && r.result) || r || {};
    const steps = (res.steps || []).map(s => s.name + (s.why ? ' [' + s.why + ']' : ''));
    if (process.env.STEPS) console.log('   steps:', (res.steps || []).map(s => s.ms + ' ' + s.name.slice(0, 50)).join(' | '), '| loads at', loads.map(t => t - t0).join(','));
    const gaps = loads.slice(1).map((t, i) => t - loads[i]);
    return { ms: Date.now() - t0, text: JSON.stringify(res).slice(0, 1500), reason: String(res.reason || res.error || ''), steps, loads: loads.length, gaps };
  };
  const perms = await sw.evaluate(() => ({ nav: !!(chrome.webNavigation && chrome.webNavigation.onErrorOccurred), req: !!(chrome.webRequest && chrome.webRequest.onCompleted), mod: !!globalThis.RestalkerNav }));
  if (!OLD) say('0 the engine starts with the page-error recorder', perms.nav && perms.req && perms.mod, JSON.stringify(perms));
  // 1. healthy page: nothing changes
  let r = await run('ok', 0);
  say('1 a page that loads is read as before (no error named, one load)', r.loads === 1 && !/ERR_|error page/i.test(r.text) && (/sold out/i.test(r.reason) || r.steps.some(s => /product page read/.test(s))), `${r.ms} ms; ${r.reason.slice(0, 90)}`);
  // 2. the store cuts the connection twice, then the page loads
  r = await run('reset', 2);
  say('2a cut twice, then it loads: the checkout goes on to read the page', (/sold out/i.test(r.reason) || r.steps.some(s => /product page read/.test(s))) && r.loads === 3, `loads ${r.loads}; pauses between loads ${r.gaps.join(', ')} ms; ${r.reason.slice(0, 60)}`);
  if (!OLD) say('2b each reload came at once and carries the browser\'s real reason', r.gaps.every(g => g < 4500) && r.steps.filter(s => /loading it again/.test(s) && /ERR_CONNECTION_RESET/.test(s)).length >= 1, r.steps.filter(s => /loading it again/.test(s)).join(' || ').slice(0, 260));
  // 3. the store cuts every load: the failure names the reason
  r = await run('reset', 0);
  if (OLD) say('OLD the engine before v151: every load cut', true, `${r.ms} ms, ${r.loads} loads, pauses ${r.gaps.join(', ')} ms :: ${r.reason.slice(0, 200)}`);
  else {
    say('3a every load cut: the failure text names ERR_CONNECTION_RESET and what it means', /ERR_CONNECTION_RESET/.test(r.reason) && /cut the connection/.test(r.reason), r.reason.slice(0, 230));
    say('3b ... after at least four loads (the browser adds reloads of its own), in a few seconds (not half a minute)', r.loads >= 4 && r.ms < 15000, `${r.loads} loads in ${r.ms} ms`);
    // 4. the store refuses with 403 and an empty page
    r = await run('403', 0);
    say('4 an empty "403" answer is named as refused', /403/.test(r.reason) && /refused this browser/.test(r.reason), r.reason.slice(0, 230));
    // 5. this PC's internet is gone
    r = await run('offline', 0);
    say('5 no internet on this PC is named as that (and waited on longer)', /ERR_INTERNET_DISCONNECTED/.test(r.reason) && /this PC's internet/.test(r.reason) && r.ms >= 11000, `${r.reason.slice(0, 160)} | ${r.ms} ms`);
    // 6. the store does not answer
    r = await run('timeout', 0);
    say('6 no answer from the store is named as that', /TIMED_OUT/.test(r.reason) && /did not answer/.test(r.reason), r.reason.slice(0, 160));
    // 7. after all that, a page that loads is read again (an old error is not carried over)
    r = await run('ok', 0);
    say('7 afterwards a page that loads is read normally (the old error is forgotten)', r.loads === 1 && !/ERR_/.test(r.text) && (/sold out/i.test(r.reason) || r.steps.some(s => /product page read/.test(s))), `${r.ms} ms`);
    const counts = await (ctx.serviceWorkers()[0] || sw).evaluate(() => RestalkerNav.counts());
    say('8 the engine keeps count of each reason per store', counts.some(c => /bestbuy ERR_CONNECTION_RESET/.test(c.what) && c.n >= 4), JSON.stringify(counts).slice(0, 200));
  }
  await ctx.close();
  const bad = out.filter(x => !x).length; console.log(bad ? `SOME FAILED: ${bad} of ${out.length}` : `ALL PASS (${out.length})`); process.exit(bad ? 1 : 0);
})().catch(e => { console.log('TEST ERROR', e); process.exit(2); });
