// The per-seller purchase limit across the REAL link between the engine (in Chromium) and Restalker (amazon_buy), both ends (v155).
// usage: node limitlink.js <buyer_engine folder of a program whose limit_link_server.py is running>
const { chromium } = require('/opt/npm-tools/node_modules/playwright');
const fs = require('fs'), os = require('os'), path = require('path');
const ext = path.resolve(process.argv[2]);
const out = []; const say = (name, ok, more) => { out.push([name, ok]); console.log((ok ? 'PASS ' : 'FAIL ') + name + (more ? '  | ' + more : '')); };
const kept = () => { try { return JSON.parse(fs.readFileSync('/tmp/rk_limits.json', 'utf8')); } catch { return {}; } };
(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rkll'));
  const ctx = await chromium.launchPersistentContext(dir, { headless: false, executablePath: '/opt/pw-browsers/chromium-1243/chrome-linux64/chrome',
    args: ['--headless=new', '--no-sandbox', '--no-proxy-server', `--disable-extensions-except=${ext}`, `--load-extension=${ext}`] });
  await ctx.route(/^https?:\/\/(?!127\.0\.0\.1)(?!localhost)[^/]*\//, (route) => route.fulfill({ status: 200, contentType: 'text/html', body: '<html><body>stand-in</body></html>' })).catch(() => {});
  let sw = ctx.serviceWorkers()[0] || await ctx.waitForEvent('serviceworker', { timeout: 20000 });
  const id = sw.url().split('/')[2];
  const dash = await ctx.newPage(); await dash.goto(`chrome-extension://${id}/dashboard.html`);
  const SW = () => (sw = ctx.serviceWorkers()[0] || sw);
  let linked = false; for (let i = 0; i < 60 && !linked; i++) { await dash.waitForTimeout(500); linked = await SW().evaluate(() => buyerDetectorBridge.connected()).catch(() => false); }
  say('1 the engine is linked to Restalker (the real link, both ends)', linked === true);
  let got = null; for (let i = 0; i < 30; i++) { got = await SW().evaluate(() => rkLimitFor('B0PUSHED01')); if (got) break; await dash.waitForTimeout(300); }
  say('2 a limit Restalker kept (from before the buying browser started) reaches the engine after it connects', !!(got && got['A9SELLERZZZZZZ'] && got['A9SELLERZZZZZZ'].name === 'Kept Seller' && got['A9SELLERZZZZZZ'].until > Date.now()), JSON.stringify(got));
  await SW().evaluate(() => buyerDetectorBridge.tell({ type: 'limit', asin: 'B0TOLD0001', seller: 'A1TOLDSELLER01', name: 'Told Shop', untilMs: Date.now() + 6 * 3600000 }));
  let k = {}; for (let i = 0; i < 30; i++) { await dash.waitForTimeout(300); k = kept(); if (k.limit && k.limit['B0TOLD0001']) break; }
  say('3 what the engine tells (Amazon refused seller X) is kept by Restalker for that seller, with one message', !!(k.limit && k.limit['B0TOLD0001'] && k.limit['B0TOLD0001']['A1TOLDSELLER01']) && (k.events || []).filter(e => e[0] === 'B0TOLD0001').length === 1 && /Told Shop/.test(String((k.events || [])[0] && k.events[0][2])), JSON.stringify(k.events));
  got = null; for (let i = 0; i < 20; i++) { got = await SW().evaluate(() => rkLimitFor('B0TOLD0001')); if (got) break; await dash.waitForTimeout(300); }
  say('4 ... and Restalker sends it back as its own list (the engine has it either way)', !!(got && got['A1TOLDSELLER01']));
  fs.writeFileSync('/tmp/rk_buyagain_B0PUSHED01', '1');
  let gone = false; for (let i = 0; i < 30 && !gone; i++) { await dash.waitForTimeout(300); gone = (await SW().evaluate(() => rkLimitFor('B0PUSHED01'))) === null; }
  say('5 "Buy again" in Restalker ends the limit in the engine at once', gone === true);
  await SW().evaluate(() => buyerDetectorBridge.tell({ type: 'early_start', starts: 4, reached: 3, miss: 1, off: false }));
  await dash.waitForTimeout(1200); k = kept();
  say('6 the count of early starts reaches Restalker for its diagnostics', !!(k.early && k.early.starts === 4 && k.early.reached === 3), JSON.stringify(k.early));
  const bad = out.filter((x) => !x[1]).length; console.log(bad ? `SOME FAILED: ${bad} of ${out.length}` : `ALL PASS (${out.length})`);
  await ctx.close(); process.exit(bad ? 1 : 0);
})().catch((e) => { console.log('HARNESS ERROR ' + (e && e.stack || e)); process.exit(2); });
