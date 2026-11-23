// Runs the real buying engine (the browser extension inside Restalker's buying browser) in Chromium against a stand-in Amazon and
// presses "buy again" on a product that was added by link and already bought (v149, diagnostics 97).
// usage: node tools/engine_rearm_test.js <unpacked update>/buyer_engine   (needs Playwright and its Chromium)
const { chromium } = require('/opt/npm-tools/node_modules/playwright');
const fs = require('fs'), os = require('os'), path = require('path');
const ext = path.resolve(process.argv[2]);
const A1 = 'B0TESTAS01', A2 = 'B0TESTAS02', A3 = 'B0TESTAS03', A4 = 'B0TESTAS04';
const titles = { [A1]: 'Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle', [A2]: 'Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle',
                 [A3]: 'Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle', [A4]: 'Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle' };
const down = new Set();
const page = (asin) => `<!doctype html><html><head><title>Amazon.com</title></head><body><div id="dp"><span id="productTitle">${titles[asin] || ''}</span>
<div id="availability"><span>Currently unavailable.</span></div><p>We don't know when or if this item will be back in stock.</p></div></body></html>`;
const out = []; const say = (name, ok, more) => { out.push([name, ok]); console.log((ok ? 'PASS ' : 'FAIL ') + name + (more ? '  | ' + more : '')); };
(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rkeng'));
  const ctx = await chromium.launchPersistentContext(dir, { headless: false, executablePath: '/opt/pw-browsers/chromium-1243/chrome-linux64/chrome',
    args: ['--headless=new', '--no-sandbox', `--disable-extensions-except=${ext}`, `--load-extension=${ext}`] });
  await ctx.route(/^https:\/\/(www\.)?amazon\.com\//, (route) => {
    const u = route.request().url(); const m = u.match(/\/dp\/([A-Z0-9]{10})/);
    if (m && down.has(m[1])) return route.abort();
    if (m) return route.fulfill({ status: 200, contentType: 'text/html', body: page(m[1]) });
    return route.fulfill({ status: 200, contentType: 'text/html', body: '<html><body>stand-in</body></html>' });
  });
  await ctx.route(/^https?:\/\/(?!(www\.)?amazon\.com)(?!127\.0\.0\.1)(?!localhost)[^/]*\//, (route) => route.abort()).catch(() => {});
  let sw = ctx.serviceWorkers()[0] || await ctx.waitForEvent('serviceworker', { timeout: 20000 });
  const id = sw.url().split('/')[2];
  const dash = await ctx.newPage(); await dash.goto(`chrome-extension://${id}/dashboard.html`); await dash.waitForTimeout(1500);
  const send = (m) => dash.evaluate((m) => new Promise((res) => { const t = setTimeout(() => res({ ok: false, reason: 'no answer in 40 s' }), 40000);
    chrome.runtime.sendMessage(m).then((r) => { clearTimeout(t); res(r); }, (e) => { clearTimeout(t); res({ ok: false, reason: 'error: ' + e }); }); }), m);
  const state = async (pid) => { const r = await send({ type: 'GET_ALL' }); return (r.states || {})[pid] || {}; };
  // what Restalker's bridge does when a product is added by link (detector_bridge.js addAsin)
  const add = async (asin) => { const made = await send({ type: 'ADD_CUSTOM_PRODUCT' }); const set = await send({ type: 'SET_CUSTOM_ASIN', productId: made.id, asin }); return { id: made.id, set }; };
  const bought = async (pid) => { await send({ type: 'SET_PRODUCT', productId: pid, values: { cap: 90 } });
    return send({ type: 'SET_PRODUCT', productId: pid, values: { purchased: true, attempted: true, armed: false, autoBuy: false, submitAt: 1700000000000, orderNumber: '111-0000000-0000001', status: 'BOUGHT at $89.99' } }); };
  const again = (pid, asin) => send({ type: 'REARM_PURCHASED', productId: pid, expectedAsin: asin, confirmedAdditionalPurchase: true, expectedPriorSubmitAt: 1700000000000 });

  // 1. the owner's case: added by link, bought, "buy again"
  const p1 = await add(A1); let s = await state(p1.id);
  say('1a a product added by link: its name and words are read from its Amazon page', p1.set.ok && Array.isArray(s.customTokens) && s.customTokens.includes('nauctis') && s.customTokens.includes('bundle'), JSON.stringify(p1.set).slice(0, 160));
  await bought(p1.id); s = await state(p1.id);
  say('1b it is bought (not armed)', s.purchased === true && !s.armed);
  let r = await again(p1.id, A1); s = await state(p1.id);
  say('1c BUY AGAIN on it is accepted: armed for one more, the earlier order kept on record', r.ok === true && s.armed === true && s.purchased === false && s.autoBuy === true && (s.priorPurchaseHistory || []).length === 1, JSON.stringify(r));

  // 2. a product from before the fix (its words are there, the "checked" mark is not): checked on the spot, on a sold-out page too
  const p2 = await add(A2); await bought(p2.id);
  await send({ type: 'SET_PRODUCT', productId: p2.id, values: { customMetadataVerifiedAt: 0, customTokens: ['nauctis', 'sunken', 'realm', 'secret', 'lair', 'bundle'], customName: 'Nauctis Secret Lair Bundle' } });
  r = await again(p2.id, A2); s = await state(p2.id);
  say('2a a product added before this fix: BUY AGAIN checks its Amazon page now and accepts', r.ok === true && s.armed === true && s.purchased === false && Number(s.customMetadataVerifiedAt) > 0, JSON.stringify(r));
  say('2b its own name and words are kept as they were', s.customName === 'Nauctis Secret Lair Bundle' && (s.customTokens || []).length === 6);

  // 3. safety kept: the page now names another product -> refused, nothing armed
  const p3 = await add(A3); await bought(p3.id);
  await send({ type: 'SET_PRODUCT', productId: p3.id, values: { customMetadataVerifiedAt: 0 } });
  titles[A3] = 'Stainless Steel Kitchen Mixing Bowls Set of Five';
  const pg3 = ctx.pages().find((x) => x.url().includes(A3)); if (pg3) await pg3.reload().catch(() => {});
  await dash.waitForTimeout(800);
  r = await again(p3.id, A3); s = await state(p3.id);
  say('3 the Amazon page names another product: refused, still bought, not armed', r.ok === false && /another product/.test(r.reason || '') && s.purchased === true && !s.armed, JSON.stringify(r));

  // 4. safety kept: no words known and the page can not be read -> refused with the reason
  const p4 = await add(A4); await bought(p4.id);
  await send({ type: 'SET_PRODUCT', productId: p4.id, values: { customMetadataVerifiedAt: 0, customTokens: null } });
  titles[A4] = '';
  const pg4 = ctx.pages().find((x) => x.url().includes(A4)); if (pg4) await pg4.reload().catch(() => {});
  await dash.waitForTimeout(800);
  r = await again(p4.id, A4); s = await state(p4.id);
  say('4 nothing known and the page shows no name: refused, still bought, not armed', r.ok === false && /must be verified/.test(r.reason || '') && s.purchased === true && !s.armed, JSON.stringify(r));

  // 5. the other guards are as before
  r = await again(p1.id, A1);
  say('5a BUY AGAIN on a product that is not bought (already re-armed): refused', r.ok === false && /No confirmed prior purchase/.test(r.reason || ''), JSON.stringify(r));
  r = await send({ type: 'REARM_PURCHASED', productId: p3.id, expectedAsin: 'B0WRONG001', confirmedAdditionalPurchase: true, expectedPriorSubmitAt: 1700000000000 });
  say('5b BUY AGAIN naming another product code: refused', r.ok === false, JSON.stringify(r));
  r = await send({ type: 'REARM_PURCHASED', productId: p3.id, expectedAsin: A3, confirmedAdditionalPurchase: true, expectedPriorSubmitAt: 5 });
  say('5c BUY AGAIN for another order than the one on record: refused', r.ok === false, JSON.stringify(r));
  const bad = out.filter((x) => !x[1]).length;
  console.log(bad ? `SOME FAILED: ${bad} of ${out.length}` : `ALL PASS (${out.length})`);
  await ctx.close(); process.exit(bad ? 1 : 0);
})().catch((e) => { console.log('HARNESS ERROR ' + (e && e.stack || e)); process.exit(2); });
