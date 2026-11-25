// node --test relay/test/logic.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import * as L from "../src/logic.js";

const now = 1_795_100_000_000;
const known = new Set(["B0HNTNV94J", "B0GXC89N66"]);
const prods = [
  { a: "B0HNTNV94J", n: "Nauctis Collector Booster Box", ms: 323.88, gs: "Nauctis", gy: "2026", i: "https://tcgplayer-cdn.tcgplayer.com/x.jpg", tp: "1" },
  { a: "B0GXC89N66", n: "The Hobbit Collector Booster Box", ms: 359.99, gs: "The Hobbit", gy: "2026", sx: [{ s: "Walmart", u: "https://www.walmart.com/ip/1" }] },
];

test("clean: real crowd messages pass, anything odd is dropped", () => {
  const s = L.clean({ v: 1, c: "4ee4ffd1cda3", k: "s", a: "B0HNTNV94J", s: "Amazon", st: "in", t: now / 1000 - 30, p: 323.884 }, known, now);
  assert.deepEqual(s, { c: "4ee4ffd1cda3", a: "B0HNTNV94J", s: "Amazon", t: now - 30000, p: 323.88 });
  assert.deepEqual(L.clean({ c: "42f461d2e159", k: "h" }, known, now), { hello: true, c: "42f461d2e159" });
  const base = { c: "4ee4ffd1cda3", k: "s", a: "B0HNTNV94J", s: "Amazon", st: "in", t: now, p: 10 };
  assert.equal(L.clean({ ...base, c: "Steve" }, known, now), null);                 // not a device code
  assert.equal(L.clean({ ...base, a: "B000000000" }, known, now), null);            // not on the central list
  assert.equal(L.clean({ ...base, s: "eBay" }, known, now), null);                   // not a store we know
  assert.equal(L.clean({ ...base, st: "out" }, known, now), null);
  assert.equal(L.clean({ ...base, t: now - 31 * 60_000 }, known, now), null);       // old news
  assert.equal(L.clean({ ...base, t: now + 10 * 60_000 }, known, now), null);       // from the future
  assert.equal(L.clean({ ...base, p: -5 }, known, now).p, null);                     // odd price dropped, sighting kept
  assert.equal(L.clean("hello", known, now), null);
  assert.equal(L.clean(null, known, now), null);
});

test("merge: one restock per product + store; confirmed when a 2nd device sees it, once", () => {
  const a = { c: "aaaaaaaaaaaa", a: "B0HNTNV94J", s: "Amazon", t: now, p: 323.88 };
  const r1 = L.merge([], a);
  assert.equal(r1.isNew, true); assert.equal(r1.confirmedNow, false); assert.equal(r1.ev.n, 1);
  const evs = [{ ...r1.ev, id: 1 }];
  const r2 = L.merge(evs, { ...a, t: now + 60_000 });                           // the same device again
  assert.equal(r2.isNew, false); assert.equal(r2.grew, false); assert.equal(r2.confirmedNow, false);
  const r3 = L.merge(evs, { ...a, c: "bbbbbbbbbbbb", t: now + 120_000, p: 320 });
  assert.equal(r3.confirmedNow, true); assert.equal(r3.ev.n, 2); assert.equal(r3.ev.p, 320); assert.equal(r3.ev.id, 1);
  const evs2 = [{ ...r3.ev }];
  const r4 = L.merge(evs2, { ...a, c: "cccccccccccc", t: now + 180_000 });
  assert.equal(r4.confirmedNow, false); assert.equal(r4.grew, true); assert.equal(r4.ev.n, 3); // (grows: the message is edited)
  const r5 = L.merge(evs2, { ...a, c: "dddddddddddd", s: "Walmart" });             // another store: its own restock
  assert.equal(r5.isNew, true);
  const r6 = L.merge(evs2, { ...a, c: "dddddddddddd", t: now + 3 * 3600_000 });   // hours later: a new restock
  assert.equal(r6.isNew, true);
});

test("restock message: name, store, price vs MSRP and TCG, devices, link, picture, pings only the given roles", () => {
  const ev = { a: "B0HNTNV94J", s: "Amazon", p: 323.88, n: 3, first_at: now };
  const m = L.restockMessage(ev, prods[0], 410.5, ["111", "222"]);
  assert.equal(m.content, "<@&111> <@&222>");
  assert.deepEqual(m.allowed_mentions, { parse: [], roles: ["111", "222"] });
  const e = m.embeds[0];
  assert.equal(e.title, "IN STOCK: Nauctis Collector Booster Box");
  assert.equal(e.url, "https://www.amazon.com/dp/B0HNTNV94J");
  assert.equal(e.fields[1].value, "$323.88  (MSRP $323.88 · TCG $410.50)");
  assert.equal(e.fields[2].value, "3 Restalker devices");
  assert.equal(e.image.url, "https://tcgplayer-cdn.tcgplayer.com/x.jpg");     // (a medium picture; the name is the link)
  assert.ok(!JSON.stringify(m).includes("amazon.com/dp/B0HNTNV94J ") && !m.content.includes("http"));
  const w = L.restockMessage({ ...ev, a: "B0GXC89N66", s: "Walmart", n: 1, p: null }, prods[1], null, []);
  assert.equal(w.embeds[0].url, "https://www.walmart.com/ip/1");
  assert.equal(w.embeds[0].fields[1].value, "?  (MSRP $359.99)");
  assert.equal(w.embeds[0].fields[2].value, "1 Restalker device");
  assert.equal(w.content, "");
  assert.equal(L.restockMessage({ ...ev, s: "Target" }, prods[0], null, []).embeds[0].url, undefined);   // no link known: none made up
});

test("daily summary and price movers", () => {
  const evs = [{ s: "Amazon", n: 2, first_at: now - 3600_000 }, { s: "Amazon", n: 1, first_at: now - 7200_000 },
    { s: "Walmart", n: 3, first_at: now - 10 * 3600_000 }, { s: "Target", n: 5, first_at: now - 30 * 3600_000 }];
  const t = L.dailySummary(evs, 4, now);
  assert.match(t, /Restocks spotted: \*\*3\*\* \(confirmed by 2\+ devices: \*\*2\*\*\)/);
  assert.match(t, /By store: Amazon 2 · Walmart 1/);
  assert.match(t, /sharing: \*\*4\*\*/);
  const hist = { B0HNTNV94J: [[100, 400, 1], [104, 390, 0], [110, 360, 2]], B0GXC89N66: [[100, 500, 0], [110, 505, 0]] };
  const m = L.movers(hist, prods, 7, 3);
  assert.equal(m.drops.length, 1); assert.equal(m.rises.length, 0);
  assert.equal(m.drops[0].name, "Nauctis Collector Booster Box"); assert.equal(m.drops[0].from, 400);
  assert.match(L.moversText(m), /▼ Nauctis Collector Booster Box: \$400.00 → \$360.00 \(-10.0%\)/);
  assert.equal(L.moversText({ drops: [], rises: [] }), null);
});

test("release notes section, set groups, role changes, price info", () => {
  const md = "# Restalker Mobile 2.14 (x)\n- one\n- two\n\n# Restalker Mobile 2.13\n- old\n";
  assert.equal(L.newestSection(md), "- one\n- two");
  assert.equal(L.newestSection(""), "");
  const many = Array.from({ length: 30 }, (_, i) => ({ gs: "Set" + i, gy: String(2000 + i) }));
  const g = L.setGroups(many);
  assert.equal(g.length, 2); assert.equal(g[0].length, 25); assert.equal(g[0][0], "Set29"); assert.equal(g[1].length, 5);
  assert.deepEqual(L.roleChanges(["1", "2", "3"], ["1", "3", "9"], ["2", "3", "7"]), { add: ["1"], remove: ["2"] });
  const pi = L.priceInfo(prods[0], [[1, 400, 0], [2, 350, 0], [3, 380, 0]]);
  assert.match(pi, /TCG market now: \$380.00/); assert.match(pi, /low \$350.00 · high \$400.00 · -5.0%/);
  assert.match(L.priceInfo(prods[1], []), /No TCG history yet/);
});

test("shared checkout lock: first device wins, runs out by itself, bought holds a day, only the holder releases", () => {
  const t0 = now;
  const a = L.lockDecide(null, "aaaaaaaaaaaa", t0);
  assert.equal(a.grant, true); assert.equal(a.row.owner, "aaaaaaaaaaaa"); assert.equal(a.row.until, t0 + L.LOCK_MS);
  const b = L.lockDecide(a.row, "bbbbbbbbbbbb", t0 + 1000);
  assert.equal(b.grant, false); assert.equal(b.row.owner, "aaaaaaaaaaaa");                 // the race is lost: no second order
  assert.equal(L.lockDecide(a.row, "aaaaaaaaaaaa", t0 + 2000).grant, true);               // the holder may claim again
  assert.equal(L.lockDecide(a.row, "bbbbbbbbbbbb", t0 + L.LOCK_MS + 1).grant, true);      // a stopped device never blocks for long
  assert.equal(L.lockDecide(null, "a", t0, 10 * 3600_000).row.until, t0 + L.LOCK_MAX_MS); // (no endless claims)
  assert.equal(L.releaseDecide(a.row, "bbbbbbbbbbbb", "failed", t0), undefined);          // only the holder
  assert.equal(L.releaseDecide(a.row, "aaaaaaaaaaaa", "failed", t0), null);                // sold out under it: free for the others
  const bought = L.releaseDecide(a.row, "aaaaaaaaaaaa", "bought", t0);
  assert.equal(bought.state, "bought"); assert.equal(bought.until, t0 + L.BOUGHT_HOLD_MS);
  assert.equal(L.lockDecide(bought, "bbbbbbbbbbbb", t0 + L.LOCK_MS + 5).grant, false);    // bought: the others stay out for a day
  assert.equal(L.lockDecide(bought, "aaaaaaaaaaaa", t0 + 5).row.state, "bought");
  assert.ok(L.GROUP.test("0123456789abcdef0123456789abcdef") && !L.GROUP.test("xyz"));
  assert.ok(L.ANY_ASIN.test("B0HNTNV94J") && L.ANY_ASIN.test("bb:walmart_hobbit") && !L.ANY_ASIN.test("bb:Walmart Hobbit"));
});

test("presence: a linked device's group and sealed words; the others of its group come back, newest first", () => {
  const g = "0123456789abcdef0123456789abcdef", w = "rk1:" + "A".repeat(60);
  assert.deepEqual(L.presenceIn({ c: "aaaaaaaaaaaa", g, w }), { g, w });
  assert.deepEqual(L.presenceIn({ c: "aaaaaaaaaaaa" }), { g: null, w: null });                  // not linked (or an older version): no group
  assert.deepEqual(L.presenceIn({ g: "nope", w }), { g: null, w: null });                       // words without a real group are not kept
  assert.deepEqual(L.presenceIn({ g, w: "<script>" }), { g, w: null });                         // only sealed words
  assert.deepEqual(L.presenceIn({ g, w: "rk1:" + "A".repeat(2000) }), { g, w: null });          // not a place to store things
  assert.deepEqual(L.presenceIn(null), { g: null, w: null });
  const rows = [{ c: "aaaaaaaaaaaa", at: now - 1000, w }, { c: "bbbbbbbbbbbb", at: now - 5 * 60_000, w: "rk1:" + "B".repeat(60) },
    { c: "cccccccccccc", at: now - 60_000, w: null }, { c: "dddddddddddd", at: now - 31 * 86400_000, w }, { c: "eeeeeeeeeeee", at: now + 3600_000, w }];
  const out = L.presenceOut(rows, "aaaaaaaaaaaa", now);
  assert.deepEqual(out.map(r => r.c), ["cccccccccccc", "bbbbbbbbbbbb"]);                         // not itself, not 31 days old, not from the future
  assert.equal(out[0].w, null); assert.equal(out[1].at, now - 5 * 60_000);
  const many = Array.from({ length: 30 }, (_, i) => ({ c: String(i).padStart(12, "0"), at: now - i * 1000, w }));
  assert.equal(L.presenceOut(many, "x", now).length, L.PRESENCE_MAX);
  assert.deepEqual(L.presenceOut(null, "x", now), []);
});

test("report and suggestion digests, link codes", () => {
  assert.equal(L.reportDigest([], now), null);
  const d = L.reportDigest([{ c: "a", title: "not bought - sold out $89.99" }, { c: "b", title: "not bought - sold out $323.88", note: "it said sold out" },
    { c: "a", title: "pictures missing" }], now);
  assert.match(d, /3 from 2 devices/); assert.match(d, /2× not bought - sold out \$ - "it said sold out"/); assert.match(d, /1× pictures missing/);
  const sd = L.suggestionDigest([{ c: "a", a: "B0NEW00001", name: "New Box" }, { c: "b", a: "B0NEW00001" }, { c: "a", a: "B0HNTNV94J" }], new Set(["B0HNTNV94J"]));
  assert.match(sd, /New Box - B0NEW00001 - asked by 2 devices/); assert.ok(!sd.includes("B0HNTNV94J"));
  assert.equal(L.suggestionDigest([{ c: "a", a: "B0HNTNV94J" }], new Set(["B0HNTNV94J"])), null);
  let i = 0; const code = L.linkCode(() => (i++ % 7) / 7);
  assert.match(code, /^[A-HJ-NP-Z2-9]{6}$/);
});

test("release notes only for a newer version (a version taken back is not announced)", () => {
  assert.equal(L.verNewer("v141", "v140"), true);
  assert.equal(L.verNewer("v140", "v141"), false);
  assert.equal(L.verNewer("2.15", "2.14"), true);
  assert.equal(L.verNewer("2.14", "2.15"), false);
  assert.equal(L.verNewer("2.15", "2.15"), false);
  assert.equal(L.verNewer("2.10", "2.9"), true);
  // the phone app's numbering started again at 1.0 (code 27) after 2.15 (code 26): its release note goes by the code
  assert.equal(L.verNewer("1.0", "2.14"), false);                    // (by name it looks older - why the code is used)
  assert.equal(L.phoneNewer(27, 25, "1.0", "2.14"), true);
  assert.equal(L.phoneNewer(28, 27, "1.1", "1.0"), true);
  assert.equal(L.phoneNewer(27, 27, "1.0", "1.0"), false);
  assert.equal(L.phoneNewer(25, 27, "2.14", "1.0"), false);          // (a version taken back is never announced)
  assert.equal(L.phoneNewer(25, 0, "2.14", "2.14"), false);          // (the last code not known yet: by name - nothing new)
  assert.equal(L.phoneNewer(26, 0, "2.15", "2.14"), true);
  assert.equal(L.phoneNewer("27", "25", "1.0", "2.14"), true);       // (kept as text)
  assert.equal(L.phoneNewer(0, 25, "1.0", "2.14"), false);           // (a note without a code: by name)
});

test("active now: one count for every version (the SQL itself, on a real table)", async () => {
  const { DatabaseSync } = await import("node:sqlite");
  const db = new DatabaseSync(":memory:");
  db.exec("CREATE TABLE seen(c TEXT PRIMARY KEY, at INTEGER, first INTEGER, sightings INTEGER, g TEXT, w TEXT, hb INTEGER)");
  const put = db.prepare("INSERT INTO seen(c, at, first, sightings, hb) VALUES(?, ?, ?, 0, ?)");
  const min = 60_000, h = 3600_000;
  const count = t => db.prepare(L.ACTIVE_SQL).get(...L.activeArgs(t)).n;
  // versions that check in (every 15 minutes): active for 30 minutes after the last word
  put.run("new-now", now - 2 * min, now - 9 * h, now - 2 * min);
  put.run("new-29m", now - 29 * min, now - 9 * h, now - 29 * min);
  put.run("new-31m", now - 31 * min, now - 9 * h, now - 31 * min);          // stopped: not active
  put.run("new-5h", now - 5 * h, now - 9 * h, now - 5 * h);                  // stopped hours ago: not active (it WOULD have checked in)
  assert.equal(count(now), 2);
  // versions from before check-ins (hello on the crowd channel every 6 hours, never a check-in): active for 6.5 hours
  put.run("old-1h", now - 1 * h, now - 30 * h, null);
  put.run("old-6h", now - 6 * h - 20 * min, now - 30 * h, null);
  put.run("old-7h", now - 7 * h, now - 30 * h, null);                        // missed its hello: not active
  assert.equal(count(now), 4);
  // every device asking gets this same number - nothing about the asker is in the count
  assert.equal(L.activeArgs(now).length, 2); assert.ok(!/\bc\s*=/.test(L.ACTIVE_SQL));
  // an old version is updated: from its first check-in it follows the 30-minute rule
  db.prepare("UPDATE seen SET at = ?, hb = ? WHERE c = ?").run(now - 40 * min, now - 40 * min, "old-1h");
  assert.equal(count(now), 3);
  // a device that checks in also posts on the crowd channel (at moves, hb stays): still by its last word
  db.prepare("UPDATE seen SET at = ? WHERE c = ?").run(now - 1 * min, "new-5h");
  assert.equal(count(now), 4);
  assert.equal(count(now + 7 * h), 0);
});

test("the devices' own lines: channel names, marks, what a round may read, when to ask again", () => {
  const ch = "rkl-" + "a2".repeat(12);
  assert.ok(L.chOk(ch)); assert.ok(L.chOk(ch + "-go")); assert.ok(L.chOk("crowd"));
  for (const bad of ["rkl-short", ch + "-x", "RKL-" + "a".repeat(24), "crowd2", "", null, 5, ch + "/../x", "rkr-" + "a".repeat(20)]) assert.ok(!L.chOk(bad), String(bad));
  assert.ok(L.isGo(ch + "-go")); assert.ok(!L.isGo(ch));
  assert.equal(L.keepOf(ch).n, L.CH_KEEP_N); assert.equal(L.keepOf(ch + "-go").ms, L.GO_KEEP_MS); assert.equal(L.keepOf("crowd").ms, L.CROWD_KEEP_MS);
  assert.equal(L.sinceId("17"), 17); assert.equal(L.sinceId(17), 17); assert.equal(L.sinceId("0"), 0);
  for (const bad of ["", "12h", "abc", "-1", "1.5", null, undefined, {}, "9".repeat(16), "1 OR 1=1"]) assert.equal(L.sinceId(bad), null, String(bad));
  assert.deepEqual(L.syncReads({ [ch]: "5", crowd: "", bad: "1", [ch + "-go"]: "x" }), [[ch, 5], ["crowd", null], [ch + "-go", null]]);
  assert.deepEqual(L.syncReads(null), []); assert.deepEqual(L.syncReads([ch]), []); assert.deepEqual(L.syncReads("crowd"), []);
  const many = Object.fromEntries(Array.from({ length: 9 }, (_, i) => ["rkl-" + String(i).repeat(24), "1"]));
  assert.equal(L.syncReads(many).length, L.SYNC_CHANNELS);
  // sealed posts only (what a device's seal makes), of a sane size
  assert.ok(L.SEALED.test("rk1:" + "A".repeat(60))); assert.ok(!L.SEALED.test("rk1:" + "A".repeat(10)));
  assert.ok(!L.SEALED.test("hello")); assert.ok(!L.SEALED.test("rk1:" + "A".repeat(60) + " <script>")); assert.ok(!L.SEALED.test("rk1:" + "A".repeat(60) + "\n"));
  // a stored post, handed out the way ntfy hands out a message
  assert.deepEqual(L.line({ id: 7, at: 1795203760777, m: "rk1:xyz" }), { id: "7", time: 1795203760.8, event: "message", message: "rk1:xyz" });
  // when to ask again: a linked device every minute, the crowd alone every 3 - stretched for all when the free plan would be passed
  assert.equal(L.nextPoll(true, 0), 60); assert.equal(L.nextPoll(false, 0), 180); assert.equal(L.nextPoll(true, L.SYNC_BUDGET_DAY), 60);
  assert.equal(L.nextPoll(true, 2 * L.SYNC_BUDGET_DAY), 120); assert.equal(L.nextPoll(false, 2 * L.SYNC_BUDGET_DAY), 360);
  assert.equal(L.nextPoll(true, 1e9), L.SYNC_MAX); assert.equal(L.nextPoll(false, 1e9), L.SYNC_MAX);
  // 2026-11-24: the load is counted from the devices heard (stored), not from a count in memory
  assert.equal(L.syncDemand(0, 0), 0); assert.equal(L.syncDemand(1, 0), 1440); assert.equal(L.syncDemand(0, 3), 1440); assert.equal(L.syncDemand(-5, -5), 0);
  assert.equal(L.nextPoll(true, L.syncDemand(41, 0)), 60);                      // 41 linked devices: 59,040 a day - inside the plan
  assert.equal(L.nextPoll(true, L.syncDemand(84, 0)), 125);                     // 84 linked devices: twice the plan -> every 2 minutes
  assert.equal(L.nextPoll(false, L.syncDemand(84, 0)), 365);
  assert.ok(L.syncDemand(84, 0) / (L.nextPoll(true, L.syncDemand(84, 0)) / L.SYNC_BASE_LINKED) <= L.SYNC_BUDGET_DAY);   // slowed down, the day fits the plan again
  assert.equal(L.nextPoll(true, L.syncDemand(5000, 5000)), L.SYNC_MAX);
  // the crowd's line: the devices' own message shape; one line per device, product, store and second
  const sg = { c: "aaaaaaaaaaaa", a: "B0HNTNV94J", s: "Amazon", t: 1795203760777, p: 323.88 };
  assert.deepEqual(JSON.parse(L.crowdMsg(sg)), { v: 1, c: "aaaaaaaaaaaa", k: "s", a: "B0HNTNV94J", s: "Amazon", st: "in", t: 1795203760.8, p: 323.88 });
  assert.ok(!("p" in JSON.parse(L.crowdMsg({ ...sg, p: null }))));
  assert.equal(L.crowdKey(sg), L.crowdKey({ ...sg, t: sg.t + 100, p: 1 })); assert.notEqual(L.crowdKey(sg), L.crowdKey({ ...sg, t: sg.t + 2000 }));
  assert.notEqual(L.crowdKey(sg), L.crowdKey({ ...sg, c: "bbbbbbbbbbbb" }));
});

test("held-open connections: channels, what a socket may send, capacity", () => {
  const a = "rkl-" + "a".repeat(24), b = "rkl-" + "b".repeat(24);
  assert.deepEqual(L.wsChannels(`${a},${a}-go,${a},nope,crowd,${b}`), [a, a + "-go", b]);
  assert.equal(L.wsChannels(Array.from({ length: 20 }, (_, i) => "rkl-" + String(i).padStart(24, "c")).join(",")).length, L.WS_CHANNELS);
  assert.deepEqual(L.wsChannels(null), []);
  assert.equal(L.wsSince("17"), 17); assert.equal(L.wsSince("0"), 0); assert.equal(L.wsSince(""), null); assert.equal(L.wsSince(null), null);
  assert.equal(L.wsSince("-3"), null); assert.equal(L.wsSince("1.5"), null); assert.equal(L.wsSince("x"), null);
  const sealed = "rk1:" + "A".repeat(60);
  assert.deepEqual(L.wsIn(JSON.stringify({ t: "p", ch: a, m: sealed }), [a]), { kind: "post", ch: a, m: sealed });
  assert.equal(L.wsIn(JSON.stringify({ t: "p", ch: b, m: sealed }), [a]), null);              // not a channel this socket carries
  assert.equal(L.wsIn(JSON.stringify({ t: "p", ch: a, m: "plain words" }), [a]), null);       // not sealed: never kept
  assert.equal(L.wsIn(JSON.stringify({ t: "p", ch: "crowd", m: sealed }), ["crowd"]), null);   // the crowd's line takes sightings only
  assert.equal(L.wsIn("{not json", [a]), null); assert.equal(L.wsIn(42, [a]), null); assert.equal(L.wsIn("[1]", [a]), null);
  assert.equal(L.wsIn(JSON.stringify({ t: "s", l: [{ a: "B0TEST0001", s: "Amazon" }] }), []).kind, "sight");
  assert.equal(L.wsIn(JSON.stringify({ t: "s", l: Array(50).fill({ a: "x" }) }), []).list.length, 20);
  assert.equal(L.wsIn(JSON.stringify({ t: "s", l: [1, 2] }), []), null);
  assert.deepEqual(L.wsIn("here", []), { kind: "here", w: null });
  assert.deepEqual(L.wsIn(JSON.stringify({ t: "h", w: sealed }), []), { kind: "here", w: sealed });
  assert.deepEqual(L.wsIn(JSON.stringify({ t: "h", w: "not sealed" }), []), { kind: "here", w: null });
  const ln = JSON.parse(L.wsLine("crowd", { id: 7, at: 1795000000123, m: "hello" }));
  assert.deepEqual(ln, { t: "m", ch: "crowd", id: "7", time: 1795000000.1, event: "message", message: "hello" });
  // what the free plan carries: a phone that reconnects 20 times a day and sends 40 messages -> 4,545 devices; polling carried ~60
  assert.equal(L.wsCapacity(20, 40), 4545); assert.equal(L.wsCapacity(5, 20), 16666); assert.ok(L.wsCapacity(50, 100) >= 1800);
});

test("the owner's secret is compared exactly, whatever the lengths", () => {
  assert.equal(L.sameText("abcdefghijklmnop", "abcdefghijklmnop"), true);
  assert.equal(L.sameText("abcdefghijklmnop", "abcdefghijklmnoq"), false);
  assert.equal(L.sameText("abcdefghijklmnop", "abcdefghijklmno"), false);
  assert.equal(L.sameText("abc", "abcabc"), false);        // (a repeat of the shorter one is not the same)
  assert.equal(L.sameText("", ""), false); assert.equal(L.sameText("", "x"), false); assert.equal(L.sameText(undefined, "undefined"), false);
});

test("an owner's request: what is signed, how long it counts, and a notice's text", () => {
  const now = 1_800_000_000_000;
  assert.equal(L.ownerWords("reports", now, "123"), "restalker-owner:reports:1800000000000:123");
  assert.notEqual(L.ownerWords("notice", now, "a"), L.ownerWords("notice", now, "b"));           // (another text is another signature)
  assert.equal(L.ownerFresh(now - 9 * 60_000, now), true); assert.equal(L.ownerFresh(now + 9 * 60_000, now), true);
  assert.equal(L.ownerFresh(now - 11 * 60_000, now), false); assert.equal(L.ownerFresh("x", now), false); assert.equal(L.ownerFresh(0, now), false);
  const n = L.noticeIn({ title: "  Update <script>now</script> ", text: "Line one\nline two\u0007", days: 2 }, now);
  assert.equal(n.title, "Update scriptnow/script"); assert.equal(n.text, "Line one\nline two"); assert.equal(n.until, now + 2 * 86400_000);
  assert.equal(L.noticeIn({ title: "x", text: "long enough" }, now), null); assert.equal(L.noticeIn({ title: "long enough" }, now), null);
  assert.equal(L.noticeIn({ title: "t".repeat(300), text: "x".repeat(5000), days: 999 }, now).until, now + 30 * 86400_000);
  assert.equal(L.noticeIn({ title: "t".repeat(300), text: "x".repeat(5000) }, now).text.length, L.NOTICE_TEXT);
  assert.deepEqual(L.noticeOut({ id: 4, at: 5, until: now + 1, title: "a", text: "b" }, now), { id: 4, at: 5, title: "a", text: "b" });
  assert.equal(L.noticeOut({ id: 4, at: 5, until: now, title: "a", text: "b" }, now), null); assert.equal(L.noticeOut(null, now), null);
});

test("a report is handed on without the device's code", () => {
  const o = L.reportOut({ id: 9, at: 7, c: "abcdef012345", app: "phone", ver: "1.1.7", title: "t", note: "n", text: "x" }, "1a2b3c4d");
  assert.equal(o.who, "1a2b3c4d"); assert.equal("c" in o, false); assert.equal(JSON.stringify(o).includes("abcdef012345"), false);
  assert.deepEqual([...L.b64uToBytes("AQID-_8")], [1, 2, 3, 251, 255]); assert.equal(L.bytesToB64(new Uint8Array([1, 2, 3])), "AQID");
});
