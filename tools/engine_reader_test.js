// v159: the store reader in a real Chromium against a stand-in Target: www.target.com (the reader's tab) and redsky.target.com (the
// data address). How the data request is sent (with / without the browser's own cookies), the fall-back, the trial and the way back.
// usage: node reader.js <reader_engine folder>
const { chromium } = require('/opt/npm-tools/node_modules/playwright');
const https = require('https'), fs = require('fs'), os = require('os'), path = require('path');
const ext = path.resolve(process.argv[2]);
let allow = 'cred', refuse = () => false, seen = [];
const out = []; const say = (n, ok, more) => { out.push(!!ok); console.log((ok ? 'PASS ' : 'FAIL ') + n + (more ? '  | ' + String(more).slice(0, 260) : '')); };
const server = https.createServer({ key: fs.readFileSync(__dirname + '/cert/key.pem'), cert: fs.readFileSync(__dirname + '/cert/cert.pem') }, (req, res) => {
  const host = String(req.headers.host || '').split(':')[0];
  if (host === 'redsky.target.com') {
    const ck = /visitorId=abc/.test(String(req.headers.cookie || ''));
    seen.push({ cookie: ck, origin: req.headers.origin || '' });
    const h = allow === 'cred' ? { 'Access-Control-Allow-Origin': 'https://www.target.com', 'Access-Control-Allow-Credentials': 'true' } : { 'Access-Control-Allow-Origin': '*' };
    if (refuse(ck)) { res.writeHead(435, { ...h, 'Content-Type': 'application/json' }); return res.end('{"appId":"PX","blockScript":"/captcha.js"}'); }
    res.writeHead(200, { ...h, 'Content-Type': 'application/json' }); return res.end(JSON.stringify({ data: { product_summaries: [{ tcin: '1' }] }, cookie: ck }));
  }
  res.writeHead(200, { 'Content-Type': 'text/html', 'Set-Cookie': 'visitorId=abc; Domain=target.com; Path=/; Secure' });
  res.end('<!doctype html><html><head><title>Target</title></head><body>Target</body></html>');
});
(async () => {
  await new Promise(r => server.listen(0, '127.0.0.1', r)); const port = server.address().port;
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rkrd'));
  const launch = () => chromium.launchPersistentContext(dir, { headless: false, executablePath: '/opt/pw-browsers/chromium-1243/chrome-linux64/chrome',
    args: ['--headless=new', '--no-sandbox', `--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--ignore-certificate-errors', '--no-proxy-server',
      `--host-resolver-rules=MAP www.target.com 127.0.0.1:${port}, MAP redsky.target.com 127.0.0.1:${port}, MAP * ~NOTFOUND, EXCLUDE 127.0.0.1, EXCLUDE localhost`] });
  let ctx = await launch();
  let sw = ctx.serviceWorkers()[0] || await ctx.waitForEvent('serviceworker', { timeout: 20000 });
  const U = 'https://redsky.target.com/redsky_aggregations/v1/web/product_summary?tcins=1';
  const get = (n = 1) => sw.evaluate(async ([u, n]) => { let r; for (let i = 0; i < n; i++) r = await RestalkerReader.fetch(u, 8000, null); return r; }, [U, n]);
  // 1. the store allows its own way
  let r = await get();
  say('1 the data request goes the way the store\'s own page sends it: WITH the browser\'s cookies for the store, and is answered', r.ok && r.status === 200 && r.sent === 'include' && seen.length === 1 && seen[0].cookie === true && seen[0].origin === 'https://www.target.com', JSON.stringify({ sent: r.sent, status: r.status, seen }));
  say('1b the answer says how it was asked (for the diagnostics)', r.cred && r.cred.mode === 'include' && r.cred.stage === 'default' && r.cred.include.n === 1 && r.cred.omit.n === 0, JSON.stringify(r.cred));
  // 2. the browser does not allow it -> asked again without, at once: no round is lost
  allow = 'star'; seen = [];
  r = await get();
  say('2 the browser does not allow "with cookies" (the store gives no permission): the SAME request is asked again without them at once and answered', r.ok && r.status === 200 && r.sent === 'omit' && seen.length === 2 && seen[1].cookie === false, JSON.stringify({ sent: r.sent, seen, cred: r.cred }));
  say('2b ... counted, and "with cookies" rests 30 minutes before it is tried again (not for ever)', r.cred.cors === 1 && r.cred.corsLeftMin >= 29 && r.cred.corsLeftMin <= 30 && r.cred.mode === 'omit', JSON.stringify(r.cred));
  seen = []; r = await get(3);
  say('2c while it rests, requests go without - one request each, nothing doubled', r.ok && seen.length === 3 && seen.every(x => !x.cookie), JSON.stringify(seen));
  await sw.evaluate(() => chrome.storage.local.get('rkCred').then(x => { const c = x.rkCred['redsky.target.com']; c.corsUntil = Date.now() - 1; return chrome.storage.local.set({ rkCred: x.rkCred }); }));
  // (the rest is over - in the engine's memory too)
  await ctx.close(); ctx = await launch(); sw = ctx.serviceWorkers()[0] || await ctx.waitForEvent('serviceworker', { timeout: 20000 });
  await new Promise(r => setTimeout(r, 800));
  allow = 'cred'; seen = []; r = await get();
  say('2d after the rest (and a restart of the reader: what it learned is kept) "with cookies" is tried again by itself and works', r.ok && r.sent === 'include' && r.cred.cors === 1 && r.cred.corsLeftMin === 0 && seen.length === 1 && seen[0].cookie, JSON.stringify({ sent: r.sent, cred: r.cred, seen }));
  // 3. refusals with cookies, none without -> the other way is tried, proven, kept for a time, and the default comes back
  let k = 0; refuse = (ck) => ck && (k++ % 3 === 0);            // every third request WITH cookies is refused; none without
  seen = []; r = await get(20);
  let c = r.cred;
  say('3 one in three refused the default way: after 15 answers the other way is TRIED (not assumed)', c.stage === 'trial' && c.mode === 'omit' && c.trials === 1, JSON.stringify(c));
  r = await get(15); c = r.cred;
  say('3b 15 answers the other way, none refused: PROVEN better - kept for 30 minutes, then the default is tried again', c.stage === 'hold' && c.mode === 'omit' && c.proven === 1 && c.leftMin >= 29 && c.leftMin <= 30, JSON.stringify(c));
  await sw.evaluate(() => chrome.storage.local.get('rkCred').then(x => { x.rkCred['redsky.target.com'].until = Date.now() - 1; return chrome.storage.local.set({ rkCred: x.rkCred }); }));
  await ctx.close(); ctx = await launch(); sw = ctx.serviceWorkers()[0] || await ctx.waitForEvent('serviceworker', { timeout: 20000 });
  await new Promise(r => setTimeout(r, 800));
  refuse = () => false; seen = []; r = await get(); c = r.cred;
  say('3c when that time is over the default (with cookies) is back by itself', r.sent === 'include' && c.mode === 'include' && c.stage === 'retry' && seen[0].cookie === true, JSON.stringify(c));
  // 4. refusals that do not depend on the way -> the trial does NOT prove the other way; back to the default at once
  r = await get(40); c = r.cred;
  say('4a 40 clean answers: everything is set back to the default', c.stage === 'default' && c.mode === 'include', JSON.stringify(c));
  k = 0; refuse = () => (k++ % 4 === 0);                        // every fourth request is refused whatever the way
  r = await get(20); c = r.cred;
  const t1 = c.stage === 'trial' && c.mode === 'omit';
  r = await get(15); c = r.cred;
  say('4b one in four refused whatever the way: the other way is tried and NOT proven - back to the default at once, no new trial for 2 hours', t1 && c.stage === 'cleared' && c.mode === 'include' && c.proven === 1 && c.trials === 2, JSON.stringify(c));
  r = await get(30); c = r.cred;
  say('4c ... and it stays at the default while the refusals go on (no flip-flopping)', c.mode === 'include' && c.stage === 'cleared' && c.trials === 2, JSON.stringify({ mode: c.mode, stage: c.stage, trials: c.trials }));
  // 5. the same site only, and a same-origin page request is untouched
  r = await sw.evaluate(() => RestalkerReader.fetch('https://www.target.com/p/x/-/A-1', 8000, null));
  say('5 a request to the store\'s own pages is sent as before (same address: nothing of this applies)', r.ok && r.sent === 'same' && !r.cred, JSON.stringify({ sent: r.sent, cred: r.cred || null }));
  await ctx.close(); server.close();
  console.log(`\n${out.filter(Boolean).length} of ${out.length} passed`); process.exit(out.every(Boolean) ? 0 : 1);
})().catch(e => { console.error('ERROR', e); process.exit(2); });
