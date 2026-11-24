const { chromium } = require('/opt/npm-tools/node_modules/playwright');
(async () => {
  const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1243/chrome-linux64/chrome', args: ['--no-sandbox'] });
  const p = await b.newPage(); const errs = []; p.on('pageerror', e => errs.push(String(e).slice(0, 120)));
  await p.goto('http://127.0.0.1:18955/', { waitUntil: 'domcontentloaded' }); await p.waitForTimeout(2500);
  const out = []; const say = (n, ok, m) => { out.push(ok); console.log((ok ? 'PASS ' : 'FAIL ') + n + (m ? '  | ' + m : '')); };
  say('1 the page loads without script errors and the indicator is there, hidden', errs.length === 0 && await p.evaluate(() => !!document.getElementById('rkBusy') && getComputedStyle(document.getElementById('rkBusy')).display === 'none'), errs.join(' / '));
  await p.waitForTimeout(4000);
  say('2 the page\'s own quiet refreshing does not show it', await p.evaluate(() => !document.getElementById('rkBusy').classList.contains('on')));
  await p.route('**/api/diagnostics', async route => { await new Promise(r => setTimeout(r, 2500)); await route.continue(); });   // (on your PC the diagnostics take about 20 s; here they are instant)
  const dl = p.waitForEvent('download', { timeout: 60000 }).catch(() => null);
  await p.click('#diagBtn');
  await p.waitForTimeout(700);
  const mid = await p.evaluate(() => ({ on: document.getElementById('rkBusy').classList.contains('on'), text: document.getElementById('rkBusyText').textContent,
    anim: getComputedStyle(document.querySelector('#rkBusy .rk-spin')).animationName, btn: document.getElementById('diagBtn').innerHTML.slice(0, 60) }));
  say('3 while the diagnostics are prepared a turning circle shows, with words', mid.on && mid.anim === 'rkspin' && /diagnostics/.test(mid.text) && /rk-spin/.test(mid.btn), JSON.stringify(mid));
  const d = await dl;
  await p.waitForTimeout(600);
  const after = await p.evaluate(() => ({ on: document.getElementById('rkBusy').classList.contains('on'), btn: document.getElementById('diagBtn').innerText }));
  say('4 when the file is there the circle is gone and the button reads as before', !!d && !after.on && /Diagnostics/.test(after.btn), JSON.stringify(after));
  const bad = out.filter(x => !x).length; console.log(bad ? `SOME FAILED: ${bad} of ${out.length}` : `ALL PASS (${out.length})`);
  await b.close(); process.exit(bad ? 1 : 0);
})().catch(e => { console.log('TEST ERROR', String(e).slice(0, 300)); process.exit(2); });
