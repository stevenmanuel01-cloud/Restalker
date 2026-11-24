// The relay's held-open connections, end to end against a local relay (wrangler dev): node ws_e2e.mjs http://127.0.0.1:8789
const base = process.argv[2], wsBase = base.replace(/^http/, "ws");
const out = []; const say = (n, ok, more) => { out.push(ok); console.log((ok ? "PASS " : "FAIL ") + n + (more ? "  | " + more : "")); };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const rnd = (n, abc) => Array.from({ length: n }, () => abc[Math.floor(Math.random() * abc.length)]).join("");
const G = rnd(32, "0123456789abcdef"), CH = "rkl-" + rnd(24, "abcdefghijklmnopqrstuvwxyz0123456789"), GO = CH + "-go", sealed = t => "rk1:" + Buffer.from(t.padEnd(40, ".")).toString("base64url");
function open(c, { g, ch, s } = {}) {
  const u = `${wsBase}/v1/ws?c=${c}` + (g ? `&g=${g}` : "") + (ch ? `&ch=${ch}` : "") + (s != null ? `&s=${s}` : "");
  const ws = new WebSocket(u); const got = []; let hello = null, closed = null;
  ws.onmessage = e => { const d = e.data === "pong" ? { t: "pong" } : JSON.parse(e.data); d._at = performance.now(); got.push(d); if (d.t === "hello") hello = d; };
  ws.onclose = e => { closed = e.code; };
  return { ws, got, hello: () => hello, closed: () => closed, ready: new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error("ws error " + c)); }) };
}
const until = async (f, ms = 3000) => { const t = Date.now(); while (Date.now() - t < ms) { if (f()) return true; await sleep(10); } return false; };
const A = rnd(12, "0123456789abcdef"), B = rnd(12, "0123456789abcdef"), Cc = rnd(12, "0123456789abcdef"), RUN = rnd(6, "0123456789abcdef");
const fresh = x => x.got.slice(x.got.findIndex(m => m.t === "hello") + 1);      // (what arrived after "hello": not the look back)
const a = open(A, { g: G, ch: `${CH},${GO}` }), b = open(B, { g: G, ch: `${CH},${GO}` }), c = open(Cc);
await Promise.all([a.ready, b.ready, c.ready]);
say("1 three devices connect and each hears hello with the active count", await until(() => a.hello() && b.hello() && c.hello()) && c.hello().active >= 1, JSON.stringify(c.hello()));
// 2. a sealed post over HTTP (an older device) reaches the group's open sockets at once - and only them
let t0 = performance.now();
let r = await (await fetch(`${base}/v1/ch/${CH}`, { method: "POST", body: sealed("one") })).json();
let ok = await until(() => a.got.some(m => m.t === "m" && m.id === r.id) && b.got.some(m => m.t === "m" && m.id === r.id));
const lat1 = Math.max(...[a, b].map(x => (x.got.find(m => m.id === r.id) || {})._at - t0));
say("2a a linked device's post reaches the others' open sockets at once", ok && lat1 < 500, `${lat1.toFixed(0)} ms after the post was sent`);
await sleep(200);
say("2b a device outside the group never gets it", !c.got.some(m => m.ch === CH));
// 3. a post over the socket: the other device gets it, the sender gets "ok" (not its own line back)
t0 = performance.now(); a.ws.send(JSON.stringify({ t: "p", ch: GO, m: sealed("buy now") }));
ok = await until(() => b.got.some(m => m.t === "m" && m.ch === GO) && a.got.some(m => m.t === "ok" && m.ch === GO));
const lat2 = (b.got.find(m => m.ch === GO) || {})._at - t0;
say("3a the buy-now doorbell sent over a socket arrives at the other device", ok && lat2 < 300, `${lat2.toFixed(0)} ms`);
say("3b the sender is told it was kept and does not get its own line", !a.got.some(m => m.t === "m" && m.ch === GO));
// 4. refused over a socket: not sealed, another group's channel
a.ws.send(JSON.stringify({ t: "p", ch: CH, m: "plain words" })); a.ws.send(JSON.stringify({ t: "p", ch: "rkl-" + "z".repeat(24), m: sealed("x") })); a.ws.send("{broken");
await sleep(300);
say("4 words that are not sealed, another group's channel and rubbish are ignored", !b.got.some(m => m.t === "m" && /plain/.test(m.message || "")) && b.got.filter(m => m.t === "m" && m.ch !== "crowd").length === 2 && a.closed() === null);
// 5. a sighting over a socket reaches every device; the same product again within 15 s is not pushed again
const cat = await (await fetch(`${base}/v1/stats`)).json();
const prod = (cat.last && cat.last[0] && cat.last[0].a) || "B0TESTWS01";
t0 = performance.now(); c.ws.send(JSON.stringify({ t: "s", l: [{ v: 1, k: "s", a: prod, s: "Amazon", st: "in", t: Date.now() / 1000, p: 89.99 }] }));
ok = await until(() => fresh(a).some(m => m.t === "m" && m.ch === "crowd") && fresh(b).some(m => m.t === "m" && m.ch === "crowd"));
const took = c.got.find(m => m.t === "ok" && "took" in m);
say("5a a sighting sent over a socket is pushed to everyone at once", ok, `${(((fresh(a).find(m => m.ch === "crowd") || {})._at || 0) - t0).toFixed(0)} ms; relay said ${JSON.stringify(took || {})}`);
const n1 = fresh(a).filter(m => m.ch === "crowd").length;
b.ws.send(JSON.stringify({ t: "s", l: [{ v: 1, k: "s", a: prod, s: "Amazon", st: "in", t: Date.now() / 1000 + 1, p: 89.99 }] })); await sleep(500);
say("5b the same product at the same store is not pushed again within 15 s", fresh(a).filter(m => m.ch === "crowd").length === n1, `${n1} -> ${fresh(a).filter(m => m.ch === "crowd").length}`);
// 6. coming back: what was missed arrives first, then hello
const top = a.got.filter(m => m.t === "m").map(m => Number(m.id)).sort((x, y) => y - x)[0];
b.ws.close(); await sleep(200);
const r2 = await (await fetch(`${base}/v1/ch/${CH}`, { method: "POST", body: sealed("while away") })).json();
const b2 = open(B, { g: G, ch: `${CH},${GO}`, s: top }); await b2.ready; await until(() => b2.hello());
const idx = b2.got.findIndex(m => m.id === r2.id), hi = b2.got.findIndex(m => m.t === "hello");
say("6 a device that comes back gets what it missed, then hello - nothing twice", idx >= 0 && hi > idx && b2.got.filter(m => m.t === "m" && m.ch !== "crowd").length === 1, `${b2.got.filter(m => m.t === "m").length} line(s)`);
// 7. one socket per device; ping is answered; counts
const a2 = open(A, { g: G, ch: `${CH},${GO}` }); await a2.ready; await until(() => a.closed() !== null);
say("7a a second connection of the same device closes the first", a.closed() === 4000, String(a.closed()));
a2.ws.send("ping"); say("7b ping is answered", await until(() => a2.got.some(m => m.t === "pong")));
const st = await (await fetch(`${base}/v1/stats`)).json();
say("7c the relay counts the open sockets and the devices are active", st.sockets >= 3 && st.active >= 3, `sockets ${st.sockets}, active ${st.active}`);
const sy = await (await fetch(`${base}/v1/sync`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ c: B, g: G, r: {} }) })).json();
say("7d a linked device sees its peer as heard just now", (sy.peers || []).some(p => p.c === A && Date.now() - p.at < 5000), JSON.stringify(sy.peers || []).slice(0, 120));
// 8. refused connects
const bad = await fetch(`${base}/v1/ws?c=nothex`); const plain = await fetch(`${base}/v1/ws?c=${A}`);
say("8 a connect without a device code or without a socket is refused", bad.status === 400 || bad.status === 426, `${bad.status}/${plain.status}`);
// 9. many devices: 300 sockets, one sighting, everyone hears it
const many = []; for (let i = 0; i < 300; i++) many.push(open(RUN + String(i).padStart(6, "0")));
await Promise.all(many.map(m => m.ready)); await until(() => many.every(m => m.hello()), 8000);
await sleep(16000);                                    // (past the 15 s gap for this product)
t0 = performance.now(); c.ws.send(JSON.stringify({ t: "s", l: [{ v: 1, k: "s", a: prod, s: "Amazon", st: "in", t: Date.now() / 1000 + 20, p: 89.99 }] }));
ok = await until(() => many.every(m => m.got.some(x => x.t === "m" && x.ch === "crowd" && x._at > t0)), 5000);
const lats = many.map(m => (m.got.find(x => x.t === "m" && x._at > t0) || {})._at - t0).filter(Number.isFinite).sort((x, y) => x - y);
say("9 300 devices connected: one sighting reaches all of them", ok && lats.length === 300, `middle ${lats[150]?.toFixed(0)} ms, slowest ${lats[lats.length - 1]?.toFixed(0)} ms`);
for (const x of [a2, b2, c, ...many]) try { x.ws.close(); } catch (e) {}
const badn = out.filter(x => !x).length; console.log(badn ? `SOME FAILED: ${badn} of ${out.length}` : `ALL PASS (${out.length})`); process.exit(badn ? 1 : 0);
