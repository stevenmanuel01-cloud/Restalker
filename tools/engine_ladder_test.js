// v159: the ladder of the buying engine - the fastest way first, a slower way only on proof, and back by itself. The real engine in
// Chromium; its own counting functions are driven directly (what a press and a page without the product do to it).
// usage: node ladder.js <buyer_engine folder>
const { chromium } = require('/opt/npm-tools/node_modules/playwright');
const fs = require('fs'), os = require('os'), path = require('path');
const ext = path.resolve(process.argv[2]);
const out = []; const say = (n, ok, more) => { out.push(!!ok); console.log((ok ? 'PASS ' : 'FAIL ') + n + (more ? '  | ' + String(more).slice(0, 300) : '')); };
(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rklad'));
  const launch = () => chromium.launchPersistentContext(dir, { headless: false, executablePath: '/opt/pw-browsers/chromium-1243/chrome-linux64/chrome',
    args: ['--headless=new', '--no-sandbox', `--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--no-proxy-server', '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1, EXCLUDE localhost'] });
  let ctx = await launch();
  let sw = ctx.serviceWorkers()[0] || await ctx.waitForEvent('serviceworker', { timeout: 20000 });
  const id = sw.url().split('/')[2];
  await new Promise(r => setTimeout(r, 1500));
  const SW = () => ctx.serviceWorkers()[0] || sw;
  const st = () => SW().evaluate(() => JSON.parse(JSON.stringify({ mode: RK_AB.mode, stage: RK_AB.stage, off: !!RK_EARLY.off, until: RK_AB.until, hold: RK_AB.hold, quiet: RK_AB.quiet, quietUntil: RK_AB.quietUntil, w: RK_AB.w,
    early: RK_AB.early, read: RK_AB.read, why: RK_AB.decided, trials: RK_AB.trials, proven: RK_AB.proven, now: Date.now() })));
  const press = (early, junk, n = 1) => SW().evaluate(([early, junk, n]) => { for (let i = 0; i < n; i++) { rkAbPress(early); if (junk) rkAbJunk(-1, { title: 'Amazon.com', heads: ['Click the button below to continue shopping'], chars: 120, html: 900 }); } }, [early, junk, n]);
  let s = await st();
  say('1 a new engine starts with the FASTEST way: Buy Now as soon as the buy box is read', s.mode === 'early' && s.stage === 'default' && s.off === false, JSON.stringify(s).slice(0, 200));
  await press(true, false, 30); await press(true, true, 2); s = await st();
  say('2 two pages without the product after 32 early presses: nothing is changed (not proven, not even suspected)', s.mode === 'early' && s.off === false && s.trials === 0, JSON.stringify({ mode: s.mode, w: s.w }));
  await press(true, true, 8); s = await st();
  say('3 such pages after 1 early press in 5 or more: the slower way is TRIED (10 presses or 30 minutes) - a trial, not a decision', s.mode === 'read' && s.stage === 'trial' && s.off === true && s.trials === 1 && s.until - s.now > 29 * 60000 && s.until - s.now <= 30 * 60000, JSON.stringify({ mode: s.mode, stage: s.stage, why: s.why }));
  await press(false, false, 10); s = await st();
  say('4 ten presses the slower way, no such page: PROVEN better - kept for 30 minutes (not for ever)', s.mode === 'read' && s.stage === 'hold' && s.proven === 1 && s.until - s.now > 29 * 60000 && s.until - s.now <= 30 * 60000, JSON.stringify({ stage: s.stage, why: s.why }));
  await SW().evaluate(() => { RK_AB.until = Date.now() - 1; rkAbJudge(); }); s = await st();
  say('5 when the 30 minutes are over the fastest way is back BY ITSELF', s.mode === 'early' && s.stage === 'retry' && s.off === false, JSON.stringify({ mode: s.mode, stage: s.stage, why: s.why }));
  await press(true, true, 3); s = await st(); const t2 = s.mode === 'read' && s.stage === 'trial' && s.trials === 2;
  await press(false, false, 10); s = await st();
  say('6 the problem comes back with the fastest way and the slower one proves itself again: kept twice as long (1 hour) - at most 8 hours', t2 && s.stage === 'hold' && s.hold === 3600000 && s.proven === 2, JSON.stringify({ stage: s.stage, hold: s.hold }));
  await SW().evaluate(() => { RK_AB.until = Date.now() - 1; rkAbJudge(); });
  await press(true, true, 3); s = await st(); const t3 = s.stage === 'trial';
  await press(false, true, 10); s = await st();
  say('7 such pages follow the slower presses just as often: NOT proven - back to the fastest at once; the slower way is not tried again for 2 hours', t3 && s.mode === 'early' && s.stage === 'cleared' && s.off === false && s.quietUntil - s.now > 119 * 60000 && s.quietUntil - s.now <= 120 * 60000 && s.proven === 2, JSON.stringify({ mode: s.mode, stage: s.stage, why: s.why }));
  await press(true, true, 6); s = await st();
  say('8 ... and during those 2 hours more such pages do not start another trial (no flip-flopping)', s.mode === 'early' && s.trials === 3 && s.off === false, JSON.stringify({ mode: s.mode, trials: s.trials, w: s.w }));
  await SW().evaluate(() => { RK_AB.quietUntil = Date.now() - 1; RK_AB.w = { presses: 0, junk: 0 }; }); await press(true, true, 3); s = await st(); const t4 = s.stage === 'trial' && s.trials === 4;
  await SW().evaluate(() => { RK_AB.until = Date.now() - 1; }); await press(false, false, 2); s = await st();
  say('9 a trial with too few presses to tell (2 in its 30 minutes): not proven - back to the fastest (assumed nothing)', t4 && s.mode === 'early' && s.stage === 'cleared' && s.quiet === 4 * 3600000, JSON.stringify({ mode: s.mode, stage: s.stage, why: s.why, quiet: s.quiet }));
  await press(true, false, 20); s = await st();
  say('10 twenty clean early presses: everything is set back (30 minutes / 2 hours again)', s.mode === 'early' && s.stage === 'default' && s.hold === 0 && s.quiet === 0 && s.quietUntil === 0, JSON.stringify({ stage: s.stage, hold: s.hold, quiet: s.quiet }));
  // a long clean past must not hide a new problem
  await press(true, false, 400); await press(true, true, 6); s = await st();
  say('11 after 400 clean presses, 6 such pages in a row are still noticed (the newest presses count)', s.mode === 'read' && s.stage === 'trial', JSON.stringify({ mode: s.mode, stage: s.stage, w: s.w }));
  // kept across a restart - and a v158 engine's stored "read by default" is not carried over
  await new Promise(r => setTimeout(r, 400)); await ctx.close(); ctx = await launch(); sw = ctx.serviceWorkers()[0] || await ctx.waitForEvent('serviceworker', { timeout: 20000 }); await new Promise(r => setTimeout(r, 1500));
  s = await st();
  say('12 the state is kept across a restart of the buying browser (a trial goes on)', s.mode === 'read' && s.stage === 'trial' && s.off === true && s.trials === 5, JSON.stringify({ mode: s.mode, stage: s.stage, trials: s.trials }));
  await SW().evaluate(() => chrome.storage.local.set({ rkAb: { mode: 'read', early: { presses: 31, junk: 9 }, read: { presses: 12, junk: 0 }, junkPages: 9, decided: '', examples: [] } }));
  await new Promise(r => setTimeout(r, 300)); await ctx.close(); ctx = await launch(); sw = ctx.serviceWorkers()[0] || await ctx.waitForEvent('serviceworker', { timeout: 20000 }); await new Promise(r => setTimeout(r, 1500));
  s = await st();
  say('13 an engine that comes from v157/v158 ("press after reading" as its default): the fastest way is its default again, its counts are kept', s.mode === 'early' && s.stage === 'default' && s.off === false && s.early.presses === 31 && s.read.presses === 12, JSON.stringify({ mode: s.mode, stage: s.stage, early: s.early, read: s.read }));
  // early starts that arrive nowhere: off for 10 minutes, not until the restart
  const dash = await ctx.newPage(); await dash.goto(`chrome-extension://${id}/dashboard.html`); await dash.waitForTimeout(1200);
  const msg = (m) => dash.evaluate((m) => chrome.runtime.sendMessage(m).catch(() => null), m);
  for (let i = 0; i < 3; i++) await msg({ type: 'RK_PRE_DOM', productId: 'p' + i, t: i, start: true });
  await dash.waitForTimeout(16500);
  let p = await SW().evaluate(() => ({ off: !!RK_EARLY.off, left: RK_PRE.offUntil - Date.now(), offs: RK_PRE.offs, miss: RK_PRE.miss, mode: RK_AB.mode }));
  say('14 three early starts in a row that arrive nowhere: starting early is switched off for 10 MINUTES (until v158: until the browser restarted)', p.off === true && p.left > 9 * 60000 && p.left <= 10 * 60000 && p.offs === 1 && p.mode === 'early', JSON.stringify(p));
  await SW().evaluate(() => { RK_PRE.offUntil = Date.now() - 1; });
  await dash.waitForTimeout(21000);
  p = await SW().evaluate(() => ({ off: !!RK_EARLY.off }));
  say('15 when the 10 minutes are over it is on again by itself (the engine\'s own 20-second check - nothing has to happen first)', p.off === false, JSON.stringify(p));
  await msg({ type: 'RK_PRE_DOM', productId: 'q', t: 9, start: true }); await msg({ type: 'RK_PRE_DOM', productId: 'q', t: 9, ok: true });
  p = await SW().evaluate(() => ({ reached: RK_PRE.reached, offFor: RK_PRE.offFor, miss: RK_PRE.miss }));
  say('16 one early start that arrives sets it all back (the next switch-off would be 10 minutes again, not 20)', p.reached === 1 && p.offFor === 0 && p.miss === 0, JSON.stringify(p));
  await ctx.close();
  console.log(`\n${out.filter(Boolean).length} of ${out.length} passed`); process.exit(out.every(Boolean) ? 0 : 1);
})().catch(e => { console.error('ERROR', e); process.exit(2); });
