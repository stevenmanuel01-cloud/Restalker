// After every deployment: the REAL relay's held-open connection, checked from outside (GitHub's machine).
// usage: node relay_live_check.mjs https://restalker-relay.<name>.workers.dev
const base = process.argv[2].replace(/^http/, 'ws');
const dev = 'c1c1c1c1c1c1';                      // (one fixed test device: it never shares a sighting)
const out = []; const say = (n, ok, more) => { out.push(ok); console.log((ok ? 'PASS ' : 'FAIL ') + n + (more ? '  | ' + more : '')); };
const t0 = Date.now();
const ws = new WebSocket(base + '/v1/ws?c=' + dev);
const got = []; let openMs = null;
ws.onopen = () => { openMs = Date.now() - t0; };
ws.onmessage = e => got.push({ at: Date.now(), text: String(e.data) });
const until = (f, ms) => new Promise(r => { const end = Date.now() + ms; (function tick() { const v = f(); if (v || Date.now() > end) r(v); else setTimeout(tick, 20); })(); });
const hello = await until(() => got.find(m => m.text.includes('"t":"hello"')), 15000);
say('1 the live relay opens a held-open connection and says hello', !!hello, hello ? `open after ${openMs} ms; ${hello.text.slice(0, 90)}` : 'nothing within 15 s');
if (hello) {
  let t = Date.now(); ws.send('ping');
  const pong = await until(() => got.find(m => m.text === 'pong'), 8000);
  say('2 a ping is answered (by Cloudflare itself, without waking the relay)', !!pong, pong ? (pong.at - t) + ' ms' : 'no answer');
  t = Date.now(); ws.send(JSON.stringify({ t: 'h' }));
  const here = await until(() => got.find(m => m.text.includes('"t":"here"')), 8000);
  say('3 "I am here" is answered with the active count', !!here && /"active":\d+/.test(here.text), here ? `${here.at - t} ms; ${here.text.slice(0, 80)}` : 'no answer');
}
// (the relay sends the active count by itself with its once-a-minute tick; a protocol-level check is in tools/pc_ws_test.py)
try { ws.close(); } catch {}
const bad = out.filter(x => !x).length; console.log(bad ? `SOME FAILED: ${bad} of ${out.length}` : `ALL PASS (${out.length})`); process.exit(bad ? 1 : 0);
