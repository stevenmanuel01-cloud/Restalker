// many devices holding a connection open on a local relay: how long until ONE sighting has reached ALL of them?
const base = process.argv[2], N = Number(process.argv[3] || 1000);
const hex = n => Array.from({ length: n }, () => '0123456789abcdef'[Math.floor(Math.random() * 16)]).join('');
const st = await (await fetch(base + '/v1/stats')).json(); const prod = (st.last || [{}])[0].a;
const socks = []; let hello = 0, failed = 0; const got = new Array(N).fill(0);
const t0 = Date.now();
await new Promise(done => {
  let i = 0; const next = () => {
    if (i >= N) return; const k = i++;
    const ws = new WebSocket(base.replace('http', 'ws') + '/v1/ws?c=' + hex(12)); socks.push(ws);
    ws.onmessage = e => { const s = String(e.data); if (s.includes('"t":"hello"')) { hello++; if (hello + failed >= N) done(); } else if (s.includes('"ch":"crowd"')) got[k] = Date.now(); };
    ws.onerror = () => { failed++; if (hello + failed >= N) done(); };
    ws.onopen = () => next();
  };
  for (let j = 0; j < 40; j++) next();
  setTimeout(done, 120000);
});
console.log(`connected ${hello} of ${N} in ${((Date.now() - t0) / 1000).toFixed(1)} s (${failed} failed)`);
const stores = ['Target', 'Walmart', 'Best Buy', 'GameStop'];
for (let round = 0; round < 3; round++) {
  got.fill(0);
  const sender = socks[round]; await new Promise(r => setTimeout(r, 300)); got.fill(0); const ts = Date.now();
  sender.send(JSON.stringify({ t: 's', l: [{ v: 1, k: 's', a: prod, s: stores[round], st: 'in', t: Date.now() / 1000, p: 55.5 }] }));
  sender.addEventListener('message', e => { const x = String(e.data); if (x.includes('"t":"ok"') || x.includes('"t":"no"')) console.log('   relay answered the sender: ' + x.slice(0, 60)); }, { once: false });
  await new Promise(r => setTimeout(r, 4000));
  const d = got.filter(x => x).map(x => x - ts).sort((a, b) => a - b);
  console.log(`sighting ${round + 1}: reached ${d.length} of ${hello} devices; middle ${d[Math.floor(d.length / 2)]} ms, 99 of 100 within ${d[Math.floor(d.length * 0.99)]} ms, the last after ${d[d.length - 1]} ms`);
}
for (const w of socks) try { w.close(); } catch {}
setTimeout(() => process.exit(0), 500);
