// Restalker relay (Cloudflare Worker + one Durable Object "Hub"). Stage 2 of the growth plan.
// - Every minute it reads the Restalker crowd channel (ntfy) and merges the sightings into restocks (one per product + store).
// - A restock seen by 2+ different devices is posted once to Discord (#all-restocks with the set's and the store's pings, and the
//   store's own channel); when more devices see it, the message's count is updated (no new ping).
// - /v1/feed and /v1/stats: one merged feed for devices (the apps move to it later) and the community count.
// - Discord: /follow, /status, /price and the "Choose my pings" menus (signed interactions at /discord).
// - Every 15 minutes: a new PC / phone version -> #release-notes. Daily at 8:07 AM Pacific: #daily-summary and #price-movers.
// Nothing here can buy anything: a sighting only ever makes a device look for itself.
import * as L from "./logic.js";
import CFG from "./discord.json";
import { OWNER_SPKI_B64 } from "./owner_key.js";

const CROWD = "restalker-crowd-5e1f0c7a92b4";
const RAW = "https://raw.githubusercontent.com/stevenmanuel01-cloud/Restalker/main/";
const API = "https://discord.com/api/v10";
const UA = "DiscordBot (https://github.com/stevenmanuel01-cloud/Restalker, 1)";

const json = (o, status = 200) => new Response(JSON.stringify(o), {
  status, headers: { "content-type": "application/json", "access-control-allow-origin": "*", "cache-control": "no-store" } });
const sleep = ms => new Promise(r => setTimeout(r, ms));
const hub = env => env.HUB.get(env.HUB.idFromName("main"));

export default {
  async fetch(req, env, ctx) {
    const u = new URL(req.url);
    if (u.pathname === "/discord" && req.method === "POST") return interactions(req, env, ctx);
    if (u.pathname.startsWith("/v1/")) return hub(env).fetch(req);
    if (u.pathname === "/") return new Response("Restalker relay - see /v1/stats\n", { headers: { "content-type": "text/plain" } });
    return new Response("not found\n", { status: 404 });
  },
  async scheduled(ev, env, ctx) {
    ctx.waitUntil(hub(env).fetch("https://hub/__cron?c=" + encodeURIComponent(ev.cron)));
  },
};

// ------------------------------------------------------------------------------------------------------------------------- the Hub
export class Hub {
  constructor(state, env) {
    this.state = state; this.env = env; this.sql = state.storage.sql; this.cache = {};
    this.sql.exec(`CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY AUTOINCREMENT, a TEXT, s TEXT, first_at INTEGER,
      last_at INTEGER, p REAL, devs TEXT, n INTEGER, msg TEXT, msg2 TEXT)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS ev_as ON events(a, s, last_at)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS seen(c TEXT PRIMARY KEY, at INTEGER, first INTEGER, sightings INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS rate(k TEXT, at INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS locks(g TEXT, a TEXT, owner TEXT, state TEXT, since INTEGER, until INTEGER, PRIMARY KEY(g, a))`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS notices(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, until INTEGER, title TEXT, text TEXT)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS reports(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, c TEXT, app TEXT, ver TEXT,
      title TEXT, note TEXT, text TEXT)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS suggest(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, c TEXT, a TEXT, name TEXT)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS dlink(code TEXT PRIMARY KEY, c TEXT, until INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS devlink(c TEXT PRIMARY KEY, user TEXT, uname TEXT, at INTEGER)`);
    // presence of one person's linked devices (logic.js): a device's group and its sealed words, kept with its heartbeat row
    for (const col of ["g TEXT", "w TEXT", "hb INTEGER"]) { try { this.sql.exec("ALTER TABLE seen ADD COLUMN " + col); } catch (e) { /* already there */ } }
    this.sql.exec(`CREATE INDEX IF NOT EXISTS seen_g ON seen(g)`);
    // the devices' own lines (logic.js): a linked group's sealed posts, its fast channel, and the crowd's sightings
    this.sql.exec(`CREATE TABLE IF NOT EXISTS msgs(id INTEGER PRIMARY KEY AUTOINCREMENT, ch TEXT, at INTEGER, m TEXT, k TEXT)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS msgs_ch ON msgs(ch, id)`);
    this.sql.exec(`CREATE UNIQUE INDEX IF NOT EXISTS msgs_k ON msgs(ch, k) WHERE k IS NOT NULL`);
    this.mem = new Map(); this.win = { t0: Date.now(), n: 0, perDay: 0 };
    this.pushed = new Map(); this.live = null;
    // (a device's "ping" is answered by Cloudflare itself - the relay is not woken and nothing is counted)
    try { this.state.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong")); } catch (e) { /* older runtime */ }
  }

  kvGet(k) { const r = this.sql.exec("SELECT v FROM kv WHERE k = ?", k).toArray(); return r.length ? r[0].v : null; }
  kvSet(k, v) { this.sql.exec("INSERT INTO kv(k, v) VALUES(?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v", k, String(v)); }
  note(line) {
    const l = JSON.parse(this.kvGet("log") || "[]");
    l.push(new Date().toISOString().slice(5, 19) + " " + line);
    this.kvSet("log", JSON.stringify(l.slice(-40)));
  }

  async cached(name, path, maxMs, asText = false) {
    const c = this.cache[name];
    if (c && Date.now() - c.at < maxMs) return c.v;
    try {
      const r = await fetch(RAW + path + "?t=" + Math.floor(Date.now() / 60_000), { headers: { "User-Agent": "RestalkerRelay" } });
      if (r.ok) { const v = asText ? await r.text() : await r.json(); this.cache[name] = { at: Date.now(), v }; return v; }
    } catch (e) { this.note("! read " + path + ": " + String(e).slice(0, 80)); }
    return c ? c.v : null;
  }
  async products() { return ((await this.cached("cat", "mobile/catalog.json", 3600_000)) || {}).products || []; }
  async history() { return ((await this.cached("hist", "mobile/tcg_history.json", 3 * 3600_000)) || {}).h || {}; }
  async tcgNow(a) { const s = (await this.history())[a]; return s && s.length ? s[s.length - 1][1] : null; }

  // The Hub's own alarm wakes it every minute (it does not depend on the account's timed triggers - on a new account those can
  // take a long time to start); the timed triggers only make sure the alarm is set.
  async ensureAlarm() {
    if ((await this.state.storage.getAlarm()) == null) await this.state.storage.setAlarm(Date.now() + 5_000);
  }
  async alarm() {
    try { await this.tick(); }
    catch (e) { this.note("! tick: " + String(e).slice(0, 120)); }
    finally { await this.state.storage.setAlarm(Date.now() + 60_000); }
  }

  async fetch(req) {
    const u = new URL(req.url);
    try {
      await this.ensureAlarm();
      if (u.pathname === "/__cron") return json({ ok: true, alarm: await this.state.storage.getAlarm() });
      const mch = u.pathname.match(/^\/v1\/ch\/([a-z0-9-]{1,40})$/);
      if (mch && req.method === "POST") return await this.chPost(mch[1], req);
      if (u.pathname === "/v1/sync" && req.method === "POST") return await this.sync(req);
      if (u.pathname === "/v1/ws") return this.wsOpen(req, u);
      if (u.pathname === "/v1/s" && req.method === "POST") {
        // a device posts its own sighting(s) here directly (the apps move to this later; today they post to the crowd channel)
        const ip = req.headers.get("cf-connecting-ip") || "?";
        if (this.rateHit("ip:" + ip, L.RATE_IP)) return json({ ok: false, error: "too many" }, 429);
        const body = await req.json().catch(() => null);
        const list = Array.isArray(body) ? body.slice(0, 20) : [body];
        return json({ ok: true, ...(await this.ingest(list, Date.now(), "api")) });
      }
      if (u.pathname === "/v1/feed") {
        const since = Number(u.searchParams.get("since") || 0) || 0;
        const rows = this.sql.exec(`SELECT id, a, s, first_at, last_at, p, n FROM events WHERE id > ? ORDER BY id LIMIT 100`, since).toArray();
        return json({ ok: true, events: rows, confirm: L.CONFIRM_DEVICES });
      }
      if (u.pathname === "/v1/owner/reports" && req.method === "GET") {
        // The owner's way to READ the reports people send (2026-11-25, owner: "the whole point is that you can accumulate the issues and
        // resolve accordingly"; "I recommend these remain anonymous"). Asked with the owner's signature (or the owner's secret, when
        // one is set); handed on WITHOUT the devices' codes and sealed with the owner's public key - only the owner's side can open it,
        // wherever the answer passes through.
        const since = Number(u.searchParams.get("since") || 0) || (Date.now() - 72 * 3600_000);
        const no = await this.ownerNo(req, u, "reports", String(since));
        if (no) return no;
        const rows = this.sql.exec("SELECT id, at, c, app, ver, title, note, text FROM reports WHERE at > ? ORDER BY id DESC LIMIT 200", since).toArray();
        const out = [];
        for (const r of rows) out.push(L.reportOut(r, (await this.sha(`rk-report:${r.c}`)).slice(0, 8)));
        return json({ ok: true, now: Date.now(), n: out.length, enc: await this.seal(JSON.stringify({ now: Date.now(), reports: out })) });
      }
      if (u.pathname === "/v1/owner/notice" && req.method === "POST") {
        // A notice to EVERY device (owner, 2026-11-25): kept until it runs out, pushed at once to every device that holds its
        // connection open, and given to the others with their next check-in. Plain text; signed by the owner (its text is signed too).
        const b = await req.json().catch(() => null) || {};
        const now = Date.now(), n = L.noticeIn(b, now);
        if (!n) return json({ ok: false, error: "title / text" }, 400);
        const no = await this.ownerNo(req, u, "notice", await this.sha(n.title + "\n" + n.text));
        if (no) return no;
        if (b.clear) this.sql.exec("UPDATE notices SET until = ? WHERE until > ?", now, now);
        this.sql.exec("INSERT INTO notices(at, until, title, text) VALUES(?, ?, ?, ?)", now, n.until, n.title, n.text);
        const nt = this.notice(now);
        let pushed = 0;
        for (const ws of this.state.getWebSockets()) { try { ws.send(JSON.stringify({ t: "notice", notice: nt })); pushed++; } catch (e) { /* gone */ } }
        return json({ ok: true, notice: nt, pushed });
      }
      if (u.pathname === "/v1/notice") return json({ ok: true, notice: this.notice(Date.now()) });
      if (u.pathname === "/v1/stats") return json({ ok: true, ...(await this.stats(u.searchParams.has("log"))) });
      if (u.pathname === "/v1/links") return json({ ok: true, discord: CFG.invite || null });
      if (req.method === "POST" && ["/v1/h", "/v1/lock", "/v1/release", "/v1/report", "/v1/suggest", "/v1/linkcode"].includes(u.pathname)) {
        const ip = req.headers.get("cf-connecting-ip") || "?";
        if (this.rateHit("ipw:" + ip, 300)) return json({ ok: false, error: "too many" }, 429);
        const b = await req.json().catch(() => null) || {};
        const c = typeof b.c === "string" && /^[0-9a-f]{12}$/.test(b.c) ? b.c : null;
        if (!c) return json({ ok: false, error: "device code" }, 400);
        const now = Date.now();
        if (u.pathname === "/v1/h") {          // a device's heartbeat (every 15 min): the live "active now" count
          // + presence: a linked device gives its group and its sealed words, and gets the other devices of its group back
          const { g, w } = L.presenceIn(b);
          // (hb: when it last checked in - a device that has never checked in is a version from before check-ins: logic.js ACTIVE_SQL)
          this.sql.exec(`INSERT INTO seen(c, at, first, sightings, g, w, hb) VALUES(?, ?, ?, 0, ?, ?, ?)
            ON CONFLICT(c) DO UPDATE SET at = MAX(at, excluded.at), g = excluded.g, w = excluded.w, hb = excluded.hb`, c, now, now, g, w, now);
          const out = { ok: true, active: this.active(now) };
          const nt = this.notice(now); if (nt) out.notice = nt;
          if (g) out.peers = this.peers(g, c, now);
          return json(out);
        }
        if (u.pathname === "/v1/lock" || u.pathname === "/v1/release") {
          const g = String(b.g || ""), a = String(b.a || "");
          if (!L.GROUP.test(g) || !L.ANY_ASIN.test(a)) return json({ ok: false, error: "group / product" }, 400);
          const row = this.sql.exec("SELECT owner, state, since, until FROM locks WHERE g = ? AND a = ?", g, a).toArray()[0] || null;
          if (u.pathname === "/v1/lock") {
            const d = L.lockDecide(row, c, now, b.ttl);
            if (d.grant) this.sql.exec(`INSERT INTO locks(g, a, owner, state, since, until) VALUES(?, ?, ?, ?, ?, ?)
              ON CONFLICT(g, a) DO UPDATE SET owner = excluded.owner, state = excluded.state, since = excluded.since, until = excluded.until`,
              g, a, d.row.owner, d.row.state, d.row.since, d.row.until);
            return json({ ok: true, granted: d.grant, owner: d.row.owner, state: d.row.state, until: d.row.until });
          }
          const after = L.releaseDecide(row, c, String(b.result || ""), now);
          if (after === undefined) return json({ ok: true, released: false });
          if (after === null) this.sql.exec("DELETE FROM locks WHERE g = ? AND a = ?", g, a);
          else this.sql.exec("UPDATE locks SET state = ?, until = ? WHERE g = ? AND a = ?", after.state, after.until, g, a);
          return json({ ok: true, released: true, state: after ? after.state : "free" });
        }
        if (u.pathname === "/v1/report") {     // (owner: reports from everyone go to Discord, compiled once a day)
          if (this.rateHit("rep:" + c, 6 * 24)) return json({ ok: false, error: "too many" }, 429);
          this.sql.exec(`INSERT INTO reports(at, c, app, ver, title, note, text) VALUES(?, ?, ?, ?, ?, ?, ?)`, now, c,
            String(b.app || "").slice(0, 10), String(b.ver || "").slice(0, 20), String(b.title || "").slice(0, 200),
            String(b.note || "").slice(0, 1000), String(b.text || "").slice(0, 262_144));
          return json({ ok: true });
        }
        if (u.pathname === "/v1/suggest") {
          const a = String(b.a || "");
          if (!/^[A-Z0-9]{10}$/.test(a)) return json({ ok: false, error: "product code" }, 400);
          if (this.rateHit("sug:" + c, 30)) return json({ ok: false, error: "too many" }, 429);
          this.sql.exec("INSERT INTO suggest(at, c, a, name) VALUES(?, ?, ?, ?)", now, c, a, String(b.name || "").slice(0, 150));
          return json({ ok: true });
        }
        if (u.pathname === "/v1/linkcode") {   // (owner: "The user should have the option to connect with discord, but is not required")
          const code = L.linkCode(Math.random);
          this.sql.exec("DELETE FROM dlink WHERE c = ? OR until < ?", c, now);
          this.sql.exec("INSERT OR REPLACE INTO dlink(code, c, until) VALUES(?, ?, ?)", code, c, now + 15 * 60_000);
          return json({ ok: true, code, minutes: 15 });
        }
      }
      if (u.pathname === "/v1/linked") {
        const c = u.searchParams.get("c") || "";
        const r = this.sql.exec("SELECT uname, at FROM devlink WHERE c = ?", c).toArray()[0];
        return json({ ok: true, linked: !!r, name: r ? r.uname : null });
      }
      if (u.pathname === "/__link" && req.method === "POST") {   // (from the /link command - the worker's own call, not public)
        const b = await req.json().catch(() => ({}));
        const r = this.sql.exec("SELECT c FROM dlink WHERE code = ? AND until > ?", String(b.code || "").toUpperCase(), Date.now()).toArray()[0];
        if (!r) return json({ ok: false });
        this.sql.exec("DELETE FROM dlink WHERE code = ?", String(b.code).toUpperCase());
        this.sql.exec("INSERT OR REPLACE INTO devlink(c, user, uname, at) VALUES(?, ?, ?, ?)", r.c, String(b.user), String(b.uname || "").slice(0, 60), Date.now());
        return json({ ok: true, devices: this.sql.exec("SELECT COUNT(*) AS n FROM devlink WHERE user = ?", String(b.user)).one().n });
      }
      return json({ ok: false, error: "not found" }, 404);
    } catch (e) {
      this.note("! " + u.pathname + ": " + String(e).slice(0, 120));
      return json({ ok: false, error: String(e).slice(0, 200) }, 500);
    }
  }

  // (in memory: nothing is written for it - a round of every device every minute must not cost a stored row each)
  memHit(key, max, windowMs = 10 * 60_000) {
    const now = Date.now();
    if (this.mem.size > 20000) this.mem.clear();
    const e = this.mem.get(key);
    if (!e || now - e.t0 > windowMs) { this.mem.set(key, { t0: now, n: 1 }); return false; }
    e.n++;
    return e.n > max;
  }

  /** A linked device posts on its group's channel (sealed by the device - the relay cannot read it). */
  async chPost(ch, req) {
    if (!L.CH_LINK.test(ch)) return json({ ok: false, error: "channel" }, 400);
    const ip = req.headers.get("cf-connecting-ip") || "?";
    if (this.memHit("chip:" + ip, 400) || this.memHit("ch:" + ch, L.CH_RATE)) return json({ ok: false, error: "too many" }, 429);
    const len = Number(req.headers.get("content-length") || 0);
    if (len > L.CH_MAX_BYTES) return json({ ok: false, error: "too long" }, 413);
    const m = await req.text();
    if (m.length > L.CH_MAX_BYTES) return json({ ok: false, error: "too long" }, 413);
    if (!L.SEALED.test(m)) return json({ ok: false, error: "not sealed" }, 400);
    const now = Date.now();
    const id = this.chStore(ch, m, now);
    return json({ ok: true, id: String(id), time: Math.round(now / 100) / 10 });
  }

  /** A sealed post of a linked group: kept, and pushed at once to the group's devices that hold a socket open. */
  chStore(ch, m, now, except) {
    const keep = L.keepOf(ch);
    const id = this.sql.exec("INSERT INTO msgs(ch, at, m) VALUES(?, ?, ?) RETURNING id", ch, now, m).one().id;
    this.sql.exec(`DELETE FROM msgs WHERE ch = ? AND (at < ? OR id <= COALESCE((SELECT id FROM msgs WHERE ch = ? ORDER BY id DESC LIMIT 1 OFFSET ?), 0))`,
      ch, now - keep.ms, ch, keep.n);
    this.push(ch, { id, at: now, m }, except);
    return id;
  }

  // ------------------------------------------------------------------------------------------------ the owner's requests (logic.js)
  notice(now) {
    return L.noticeOut(this.sql.exec("SELECT id, at, until, title, text FROM notices WHERE until > ? ORDER BY id DESC LIMIT 1", now).toArray()[0] || null, now);
  }
  async sha(text) {
    const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(String(text)));
    return [...new Uint8Array(d)].map(x => x.toString(16).padStart(2, "0")).join("");
  }
  /** -> null when the request is the owner's (signed with the owner's key, or carrying the owner's secret); else the refusal. */
  async ownerNo(req, u, what, extra) {
    const ip = req.headers.get("cf-connecting-ip") || "?";
    const key = String(this.env.DIGEST_TOPIC || ""), got = (req.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
    if (key.length >= 16 && L.sameText(got, key)) return null;
    const t = Number(u.searchParams.get("t") || 0), sig = L.b64uToBytes(u.searchParams.get("s") || "");
    try {
      if (sig && sig.length >= 128 && L.ownerFresh(t, Date.now())) {
        const k = await crypto.subtle.importKey("spki", L.b64uToBytes(OWNER_SPKI_B64), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
        if (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", k, sig, new TextEncoder().encode(L.ownerWords(what, t, extra)))) return null;
      }
    } catch (e) { /* not a signature */ }
    if (this.rateHit("own:" + ip, 20)) return json({ ok: false, error: "too many" }, 429);
    return json({ ok: false, error: "not allowed" }, 403);
  }
  /** Seals text with the owner's public key (a fresh AES-256-GCM key, itself sealed with RSA-OAEP): only the owner's side opens it. */
  async seal(text) {
    const pub = await crypto.subtle.importKey("spki", L.b64uToBytes(OWNER_SPKI_B64), { name: "RSA-OAEP", hash: "SHA-256" }, false, ["encrypt"]);
    const raw = crypto.getRandomValues(new Uint8Array(32)), iv = crypto.getRandomValues(new Uint8Array(12));
    const aes = await crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt"]);
    const data = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, aes, new TextEncoder().encode(text));
    return { k: L.bytesToB64(await crypto.subtle.encrypt({ name: "RSA-OAEP" }, pub, raw)), iv: L.bytesToB64(iv), data: L.bytesToB64(data) };
  }

  // ------------------------------------------------------------------------------------------------ held-open connections (logic.js)
  push(ch, row, except) {
    let n = 0;
    const text = L.wsLine(ch, row);
    for (const ws of this.state.getWebSockets(ch)) { if (ws === except) continue; try { ws.send(text); n++; } catch (e) { /* it is gone */ } }
    return n;
  }

  /** The devices that hold a socket open right now (read at most every 20 s): Set of device codes. */
  liveSet(now) {
    if (this.live && now - this.live.at < 20_000) return this.live.set;
    const set = new Set();
    for (const ws of this.state.getWebSockets()) { try { const a = ws.deserializeAttachment(); if (a && a.c) set.add(a.c); } catch (e) { /* closing */ } }
    this.live = { at: now, set };
    return set;
  }

  wsOpen(req, u) {
    if ((req.headers.get("upgrade") || "").toLowerCase() !== "websocket") return json({ ok: false, error: "websocket" }, 426);
    const c = u.searchParams.get("c") || "";
    if (!/^[0-9a-f]{12}$/.test(c)) return json({ ok: false, error: "device code" }, 400);
    const ip = req.headers.get("cf-connecting-ip") || "?";
    if (this.memHit("wsip:" + ip, L.WS_IP_RATE) || this.memHit("ws:" + c, L.WS_RATE)) return json({ ok: false, error: "too many", next: 120 }, 429);
    const now = Date.now();
    const { g } = L.presenceIn({ g: u.searchParams.get("g") || undefined });
    const chs = L.wsChannels(u.searchParams.get("ch"));
    for (const old of this.state.getWebSockets("c:" + c)) { try { old.close(4000, "a newer connection of this device"); } catch (e) { /* gone */ } }
    if (this.state.getWebSockets().length >= L.WS_MAX) return json({ ok: false, error: "full", next: 300 }, 503);
    const pair = new WebSocketPair();
    const client = pair[0], server = pair[1];
    this.state.acceptWebSocket(server, ["c:" + c, L.CH_CROWD, ...chs]);
    server.serializeAttachment({ c, g, chs, at: now, n: 0, w0: now });
    // "I am here": written once per connect (not every 8 minutes as the polling devices do) - the open socket says the rest
    const row = this.sql.exec("SELECT at, g, hb FROM seen WHERE c = ?", c).toArray()[0];
    if (!row || row.hb == null || now - row.at >= L.SEEN_WRITE_MS || (row.g || null) !== g) {
      this.sql.exec(`INSERT INTO seen(c, at, first, sightings, g, hb) VALUES(?, ?, ?, 0, ?, ?)
        ON CONFLICT(c) DO UPDATE SET at = MAX(at, excluded.at), g = excluded.g, hb = excluded.hb`, c, now, now, g, now);
    }
    if (this.live) this.live.set.add(c);
    this.act = null;
    // what it missed while it was away, then "hello" (so a device knows it has everything up to now)
    const since = L.wsSince(u.searchParams.get("s"));
    const top = this.sql.exec("SELECT COALESCE(MAX(id), 0) AS n FROM msgs").one().n;
    for (const ch of [L.CH_CROWD, ...chs]) {
      const rows = since != null && since <= top
        ? this.sql.exec("SELECT id, at, m FROM msgs WHERE ch = ? AND id > ? ORDER BY id LIMIT ?", ch, since, L.WS_CATCHUP).toArray()
        : this.sql.exec("SELECT id, at, m FROM msgs WHERE ch = ? AND at > ? ORDER BY id LIMIT ?", ch, now - L.firstBackMs(ch), L.WS_CATCHUP).toArray();
      for (const r of rows) server.send(L.wsLine(ch, r));
    }
    server.send(JSON.stringify({ t: "hello", active: this.active(now), top: String(top), next: L.WS_SAFETY_S, notice: this.notice(now) || undefined }));
    return new Response(null, { status: 101, webSocket: client });
  }

  /** What a device sends over its open socket (1/20 of a request each): its sealed posts and its sightings. */
  async webSocketMessage(ws, msg) {
    const now = Date.now();
    let a; try { a = ws.deserializeAttachment() || {}; } catch (e) { a = {}; }
    if (now - (a.w0 || 0) > 10_000) { a.w0 = now; a.n = 0; }
    a.n = (a.n || 0) + 1;
    try { ws.serializeAttachment(a); } catch (e) { /* closing */ }
    if (a.n > L.WS_MSG_MAX) { try { ws.close(4008, "too many messages"); } catch (e) { /* gone */ } return; }
    const m = L.wsIn(typeof msg === "string" ? msg : "", a.chs);
    if (!m) return;
    try {
      if (m.kind === "post") {
        if (this.memHit("ch:" + m.ch, L.CH_RATE)) { ws.send(JSON.stringify({ t: "no", why: "too many" })); return; }
        const id = this.chStore(m.ch, m.m, now, ws);
        ws.send(JSON.stringify({ t: "ok", ch: m.ch, id: String(id) }));
      } else if (m.kind === "here") {
        // the device's sign of life for its linked devices: kept only when it changed (nothing is written for "still here")
        if (a.g && m.w) {
          const row = this.sql.exec("SELECT w FROM seen WHERE c = ?", a.c).toArray()[0];
          if (row && row.w !== m.w) this.sql.exec("UPDATE seen SET w = ? WHERE c = ?", m.w, a.c);
        }
        ws.send(JSON.stringify({ t: "here", active: this.active(now), ...(a.g ? { peers: this.peers(a.g, a.c, now) } : {}) }));
      } else if (m.kind === "sight") {
        await this.ensureAlarm();
        const r = await this.ingest(m.list.map(x => ({ ...x, c: a.c })), now, "ws");
        ws.send(JSON.stringify({ t: "ok", took: r.took }));
      }
    } catch (e) { try { ws.send(JSON.stringify({ t: "no", why: "error" })); } catch (e2) { /* gone */ } }
  }

  async webSocketClose(ws, code) {
    this.live = null; this.act = null;          // (counted anew at the next look: a newer socket of the same device may be open)
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code, "bye"); } catch (e) { /* already closed */ }
  }

  async webSocketError(ws) { try { ws.close(1011, "error"); } catch (e) { /* already closed */ } }

  /** A device's whole round in one request: "I am here" (the active count, its linked devices) and what is new on its channels. */
  async sync(req) {
    const b = await req.json().catch(() => null) || {};
    const c = typeof b.c === "string" && /^[0-9a-f]{12}$/.test(b.c) ? b.c : null;
    if (!c) return json({ ok: false, error: "device code" }, 400);
    if (this.memHit("sy:" + c, L.SYNC_RATE)) return json({ ok: false, error: "too many", next: 120 }, 429);
    const now = Date.now();
    const { g, w } = L.presenceIn(b);
    const row = this.sql.exec("SELECT at, g, hb FROM seen WHERE c = ?", c).toArray()[0];
    // (written when it is new, every 8 minutes, and when its group changed - not with every round)
    if (!row || row.hb == null || now - row.at >= L.SEEN_WRITE_MS || (row.g || null) !== g) {
      this.sql.exec(`INSERT INTO seen(c, at, first, sightings, g, w, hb) VALUES(?, ?, ?, 0, ?, ?, ?)
        ON CONFLICT(c) DO UPDATE SET at = MAX(at, excluded.at), g = excluded.g, w = excluded.w, hb = excluded.hb`, c, now, now, g, w, now);
    }
    const out = { ok: true, active: this.active(now), m: {} };
    if (g) out.peers = this.peers(g, c, now);
    const top = this.sql.exec("SELECT COALESCE(MAX(id), 0) AS n FROM msgs").one().n;
    let linked = false;
    for (const [ch, since0] of L.syncReads(b.r)) {
      if (ch !== L.CH_CROWD) linked = true;
      const since = since0 != null && since0 <= top ? since0 : null;      // (a mark from before this store was made anew: from the start)
      const rows = since != null
        ? this.sql.exec("SELECT id, at, m FROM msgs WHERE ch = ? AND id > ? ORDER BY id LIMIT ?", ch, since, L.SYNC_ROWS).toArray()
        : this.sql.exec("SELECT id, at, m FROM msgs WHERE ch = ? AND at > ? ORDER BY id LIMIT ?", ch, now - L.firstBackMs(ch), L.SYNC_ROWS).toArray();
      out.m[ch] = rows.map(L.line);
    }
    const wn = this.win; wn.n++;
    if (now - wn.t0 >= 10 * 60_000) { wn.perDay = Math.round(wn.n * 86400_000 / (now - wn.t0)); wn.t0 = now; wn.n = 0; }
    out.next = L.nextPoll(linked, Math.max(wn.perDay, this.demand(now)));
    return json(out);
  }

  /** Requests a day the devices heard in the last 20 minutes would make at the normal pace (read from storage at most once a minute). */
  demand(now) {
    const d = this.dem;
    if (d && now - d.at < 60_000) return d.v;
    let v = 0;
    try {
      const since = now - 20 * 60_000;
      const linked = this.sql.exec("SELECT COUNT(*) AS n FROM seen WHERE at > ? AND hb IS NOT NULL AND g IS NOT NULL AND g != ''", since).one().n;
      const all = this.sql.exec("SELECT COUNT(*) AS n FROM seen WHERE at > ? AND hb IS NOT NULL", since).one().n;
      v = L.syncDemand(linked, all - linked);
    } catch (e) { v = d ? d.v : 0; }
    this.dem = { at: now, v };
    return v;
  }

  rateHit(key, max) {
    const now = Date.now();
    const n = this.sql.exec("SELECT COUNT(*) AS n FROM rate WHERE k = ? AND at > ?", key, now - 10 * 60_000).one().n;
    if (n >= max) return true;
    this.sql.exec("INSERT INTO rate(k, at) VALUES(?, ?)", key, now);
    return false;
  }

  async ingest(list, now, src) {
    const prods = await this.products();
    const known = prods.length ? new Set(prods.map(p => p.a)) : null;
    let took = 0, dropped = 0, posted = 0;
    for (const m of list) {
      const s = L.clean(m, known, now);
      if (!s) { dropped++; continue; }
      this.sql.exec(`INSERT INTO seen(c, at, first, sightings) VALUES(?, ?, ?, 0)
        ON CONFLICT(c) DO UPDATE SET at = MAX(at, excluded.at)`, s.c, s.t || now, s.t || now);
      if (s.hello) { took++; continue; }
      // (a device from v147 / 1.0.6 posts its sighting here AND on the old crowd channel - the second copy, read there a minute later,
      // is the same sighting: counted once)
      if (this.sql.exec("SELECT 1 FROM msgs WHERE ch = ? AND k = ?", L.CH_CROWD, L.crowdKey(s)).toArray().length) { took++; continue; }
      if (this.rateHit("dev:" + s.c, L.RATE_DEVICE)) { dropped++; continue; }
      this.sql.exec("UPDATE seen SET sightings = sightings + 1 WHERE c = ?", s.c);
      // the crowd's line for the devices that read it here (one line per device, product, store and second - whichever way it came)
      const made = this.sql.exec("INSERT OR IGNORE INTO msgs(ch, at, m, k) VALUES(?, ?, ?, ?) RETURNING id", L.CH_CROWD, now, L.crowdMsg(s), L.crowdKey(s)).toArray()[0];
      if (made) {
        const pk = s.a + "|" + s.s, last = this.pushed.get(pk) || 0;
        if (now - last >= L.CROWD_PUSH_GAP_MS) {
          this.pushed.set(pk, now);
          if (this.pushed.size > 5000) this.pushed.clear();
          this.push(L.CH_CROWD, { id: made.id, at: now, m: L.crowdMsg(s) });
        }
      }
      const open = this.sql.exec(`SELECT * FROM events WHERE a = ? AND s = ? AND last_at >= ? ORDER BY id DESC LIMIT 3`,
        s.a, s.s, s.t - L.MERGE_MS).toArray().map(r => ({ ...r, devs: JSON.parse(r.devs || "[]") }));
      const r = L.merge(open, s);
      const ev = r.ev;
      if (r.isNew) {
        ev.id = this.sql.exec(`INSERT INTO events(a, s, first_at, last_at, p, devs, n) VALUES(?, ?, ?, ?, ?, ?, ?) RETURNING id`,
          ev.a, ev.s, ev.first_at, ev.last_at, ev.p, JSON.stringify(ev.devs), ev.n).one().id;
      } else {
        this.sql.exec(`UPDATE events SET first_at = ?, last_at = ?, p = ?, devs = ?, n = ? WHERE id = ?`,
          ev.first_at, ev.last_at, ev.p, JSON.stringify(ev.devs), ev.n, ev.id);
      }
      took++;
      if (r.confirmedNow) { await this.post(ev); posted++; }
      else if (r.grew && ev.msg) await this.edit(ev);
    }
    if (took || dropped) this.note(`${src}: ${took} taken, ${dropped} dropped, ${posted} posted`);
    return { took, dropped, posted };
  }

  // ------------------------------------------------------------------------------------------------------------ Discord posts
  async dc(method, path, body) {
    if (!this.env.DISCORD_BOT_TOKEN) return null;
    for (let i = 0; i < 3; i++) {
      const r = await fetch(API + path, { method, headers: { Authorization: "Bot " + this.env.DISCORD_BOT_TOKEN,
        "Content-Type": "application/json", "User-Agent": UA }, body: body ? JSON.stringify(body) : undefined });
      if (r.status === 429) { const j = await r.json().catch(() => ({})); await sleep(Math.min(5000, ((j.retry_after || 1) * 1000) + 100)); continue; }
      if (!r.ok) { this.note(`! discord ${method} ${path.split("?")[0]}: ${r.status} ${(await r.text()).slice(0, 120)}`); return null; }
      return r.status === 204 ? {} : r.json();
    }
    this.note(`! discord ${method} ${path}: still limited`);
    return null;
  }

  async restockMsgs(ev) {
    const prods = await this.products();
    const prod = prods.find(p => p.a === ev.a) || null;
    const tcg = await this.tcgNow(ev.a);
    const roles = CFG.roles || {};
    const ping = [prod && (roles.sets || {})[prod.gs], (roles.stores || {})[ev.s]];
    return { main: L.restockMessage(ev, prod, tcg, ping), store: L.restockMessage(ev, prod, tcg, []) };
  }

  async post(ev) {
    const ch = CFG.channels || {};
    if (!ch.all_restocks) return;
    const m = await this.restockMsgs(ev);
    const a = await this.dc("POST", `/channels/${ch.all_restocks}/messages`, m.main);
    const sc = (ch.stores || {})[ev.s];
    const b = sc ? await this.dc("POST", `/channels/${sc}/messages`, m.store) : null;
    this.sql.exec("UPDATE events SET msg = ?, msg2 = ? WHERE id = ?", a ? a.id : null, b ? b.id : null, ev.id);
    this.note(`posted ${ev.a} ${ev.s} (${ev.n} devices)`);
  }

  async edit(ev) {
    const ch = CFG.channels || {};
    const row = this.sql.exec("SELECT msg, msg2 FROM events WHERE id = ?", ev.id).toArray()[0] || {};
    const m = await this.restockMsgs(ev);
    if (row.msg) await this.dc("PATCH", `/channels/${ch.all_restocks}/messages/${row.msg}`, { embeds: m.main.embeds });
    const sc = (ch.stores || {})[ev.s];
    if (row.msg2 && sc) await this.dc("PATCH", `/channels/${sc}/messages/${row.msg2}`, { embeds: m.store.embeds });
  }

  async say(channel, content) {
    if (!channel) return null;
    return this.dc("POST", `/channels/${channel}/messages`, { content: content.slice(0, 1990), allowed_mentions: { parse: [] } });
  }

  // ------------------------------------------------------------------------------------------------------------ timed work
  async tick() {
    const now = Date.now(), out = {};
    out.crowd = await this.readCrowd();
    if (now - Number(this.kvGet("t15") || 0) >= 15 * 60_000) { this.kvSet("t15", now); out.releases = await this.releases(); this.prune(); }
    // daily at 16:07 UTC (8:07 AM Pacific), once a day
    const d = new Date(now), today = d.toISOString().slice(0, 10);
    if (d.getUTCHours() * 60 + d.getUTCMinutes() >= 16 * 60 + 7 && this.kvGet("daily") !== today) { this.kvSet("daily", today); out.daily = await this.daily(); }
    this.kvSet("tick_at", now);
    out.told = this.tellActive(now);
    return out;
  }

  async readCrowd() {
    const since = this.kvGet("ntfy_since") || "15m";
    let r;
    try { r = await fetch(`https://ntfy.sh/${CROWD}/json?poll=1&since=${encodeURIComponent(since)}`, { headers: { "User-Agent": "RestalkerRelay" } }); }
    catch (e) { this.note("! crowd read: " + String(e).slice(0, 80)); return { error: "fetch" }; }
    if (!r.ok) { if (r.status !== 429 || !this.kvGet("crowd_429")) this.note("! crowd read: " + r.status); this.kvSet("crowd_429", r.status === 429 ? "1" : ""); return { status: r.status }; }
    this.kvSet("crowd_429", "");
    const lines = (await r.text()).split("\n").filter(Boolean);
    const list = []; let last = null;
    for (const l of lines) {
      let x; try { x = JSON.parse(l); } catch { continue; }
      if (x.event !== "message") continue;
      last = x.id;
      let m; try { m = JSON.parse(x.message); } catch { continue; }
      if (m && m.t == null && x.time) m.t = x.time;
      list.push(m);
    }
    if (last) this.kvSet("ntfy_since", last);
    this.kvSet("crowd_at", Date.now());
    return list.length ? await this.ingest(list, Date.now(), "crowd") : { took: 0 };
  }

  prune() {
    const now = Date.now();
    this.sql.exec("DELETE FROM rate WHERE at < ?", now - 3600_000);
    this.sql.exec("DELETE FROM events WHERE last_at < ?", now - 60 * 86400_000);
    // (2026-11-20, the privacy policy says so: a device that has not been heard for 90 days is forgotten - its code and its last time)
    this.sql.exec("DELETE FROM seen WHERE at < ?", now - 90 * 86400_000);
    this.sql.exec("DELETE FROM msgs WHERE at < ? OR (ch = ? AND at < ?) OR (ch LIKE '%-go' AND at < ?)", now - L.CH_KEEP_MS, L.CH_CROWD, now - L.CROWD_KEEP_MS, now - L.GO_KEEP_MS);
  }

  async releases() {
    const ch = (CFG.channels || {}).release_notes;
    const pc = await this.cached("pcrel", "latest.json", 60_000);
    const ph = await this.cached("phrel", "mobile/latest.json", 60_000);
    const out = {};
    if (pc && pc.version) {
      const prev = this.kvGet("rel_pc");
      if (prev && L.verNewer(pc.version, prev) && ch) {
        await this.say(ch, `**Restalker PC ${pc.version} is out.** It installs itself.\n${pc.notes || ""}`);
        out.pc = pc.version;
      }
      if (!prev || L.verNewer(pc.version, prev)) this.kvSet("rel_pc", pc.version);
    }
    if (ph && ph.version) {
      // (the phone app's numbering started again at 1.0 after 2.15: newer = a higher version code - L.phoneNewer)
      const prev = this.kvGet("rel_phone");
      const code = Number(ph.code) || 0, prevCode = Number(this.kvGet("rel_phone_code")) || 0;
      const newer = L.phoneNewer(code, prevCode, ph.version, prev);
      if (prev && newer && ch) {
        const notes = L.newestSection(await this.cached("phnotes", "android/RELEASE_NOTES.md", 60_000, true));
        await this.say(ch, `**Restalker Mobile ${ph.version} is out.** Phones and tablets update by themselves.\n${notes}`);
        out.phone = ph.version;
      }
      if (!prev || newer) this.kvSet("rel_phone", ph.version);
      if (code > 0 && (!prevCode || newer)) this.kvSet("rel_phone_code", code);
    }
    return out;
  }

  /** The other devices of a group; one that holds a socket open was "heard" just now. */
  peers(g, c, now) {
    const live = this.liveSet(now);
    const rows = this.sql.exec("SELECT c, at, w FROM seen WHERE g = ? ORDER BY at DESC LIMIT 40", g).toArray();
    return L.presenceOut(rows.map(r => live.has(r.c) ? { ...r, at: now } : r), c, now);
  }

  // The active count for every device that holds a connection open: sent when it changed, and at least every 5 minutes - by the relay,
  // for free (what the relay sends costs nothing; a device ASKING for it would cost a twentieth of a request each time, which for
  // thousands of devices is the whole free plan). So a connected device never has to ask how many are active.
  tellActive(now) {
    const socks = this.state.getWebSockets();
    if (!socks.length) return 0;
    const n = this.activeNow(now);
    this.act = { at: now, n };
    // (kept in storage, not in memory: between two ticks the relay sleeps and forgets its memory)
    const [n0, at0] = String(this.kvGet("told_active") || "").split("@").map(Number);
    if (n0 === n && now - at0 < L.WS_ACTIVE_EVERY_MS) return 0;
    this.kvSet("told_active", n + "@" + now);
    const text = JSON.stringify({ t: "a", active: n });
    let sent = 0;
    for (const ws of socks) { try { ws.send(text); sent++; } catch (e) { /* it is gone */ } }
    return sent;
  }

  active(now) {
    if (this.act && now - this.act.at < 20_000) return this.act.n;
    const n = this.activeNow(now);
    this.act = { at: now, n };
    return n;
  }

  activeNow(now) {
    const live = this.liveSet(now);
    if (!live.size) return this.sql.exec(L.ACTIVE_SQL, ...L.activeArgs(now)).one().n;
    // (devices with an open socket write nothing while it is open: they are counted by the socket, the others by when they were heard)
    const heard = this.sql.exec(L.ACTIVE_SQL.replace("COUNT(*) AS n", "c"), ...L.activeArgs(now)).toArray();
    let n = live.size;
    for (const r of heard) if (!live.has(r.c)) n++;
    return n;
  }

  async daily() {
    const ch = CFG.channels || {};
    const now = Date.now();
    const evs = this.sql.exec("SELECT s, n, first_at FROM events WHERE first_at > ?", now - 24 * 3600_000).toArray();
    const devs = this.sql.exec("SELECT COUNT(*) AS n FROM seen WHERE at > ?", now - 24 * 3600_000).one().n;
    await this.say(ch.daily_summary, L.dailySummary(evs, devs, now));
    const mv = L.moversText(L.movers(await this.history(), await this.products(), 7, 3));
    if (mv) await this.say(ch.price_movers, mv);
    // reports and product suggestions of the last 24 hours -> the private staff channels (and the owner's digest channel)
    const reps = this.sql.exec("SELECT c, title, note, app, ver, at, text FROM reports WHERE at > ? ORDER BY id", now - 24 * 3600_000).toArray();
    const rd = L.reportDigest(reps, now);
    if (rd) await this.say(ch.staff_reports, rd);
    if (rd && this.env.DIGEST_TOPIC) {
      const file = reps.map(r => `===== ${new Date(r.at).toISOString()} ${r.app} ${r.ver} device ${r.c}\n${r.title}\n${r.note ? "note: " + r.note + "\n" : ""}\n${r.text}`).join("\n\n");
      try { await fetch(`https://ntfy.sh/${this.env.DIGEST_TOPIC}`, { method: "PUT", body: file, headers: { Title: `Restalker reports: ${reps.length}`, Filename: "restalker-reports.txt" } }); }
      catch (e) { this.note("! digest send: " + String(e).slice(0, 80)); }
    }
    const sg = this.sql.exec("SELECT c, a, name FROM suggest WHERE at > ?", now - 24 * 3600_000).toArray();
    const sd = L.suggestionDigest(sg, new Set((await this.products()).map(p => p.a)));
    if (sd) await this.say(ch.staff_suggestions, sd);
    this.sql.exec("DELETE FROM reports WHERE at < ?", now - 30 * 86400_000);
    this.sql.exec("DELETE FROM suggest WHERE at < ?", now - 90 * 86400_000);
    return { restocks: evs.length, devices: devs, movers: !!mv, reports: reps.length, suggestions: sg.length };
  }

  async stats(withLog) {
    const now = Date.now();
    const one = (q, ...a) => this.sql.exec(q, ...a).one().n;
    const prods = await this.products();
    const last = this.sql.exec(`SELECT a, s, p, n, first_at FROM events WHERE n >= ? ORDER BY id DESC LIMIT 5`, L.CONFIRM_DEVICES).toArray()
      .map(e => ({ ...e, name: (prods.find(p => p.a === e.a) || {}).n || e.a }));
    const out = {
      active: this.active(now),                      // (heard in the last 30 minutes: "active now")
      devices12h: one("SELECT COUNT(*) AS n FROM seen WHERE at > ?", now - 12 * 3600_000),
      devicesAll: one("SELECT COUNT(*) AS n FROM seen"),
      sockets: this.state.getWebSockets().length,                                      // devices holding a connection open right now
      lines: one("SELECT COUNT(*) AS n FROM msgs"), syncPerDay: Math.max(this.win.perDay, this.demand(now)), syncNext: L.nextPoll(true, Math.max(this.win.perDay, this.demand(now))),
      // (2026-11-21: counted from what is stored - "syncPerDay" lives in memory and is 0 again whenever the relay has slept)
      linkLines: one("SELECT COUNT(*) AS n FROM msgs WHERE ch LIKE 'rkl-%'"),          // sealed posts of linked devices held right now
      linked1h: one("SELECT COUNT(*) AS n FROM seen WHERE g IS NOT NULL AND g != '' AND at > ?", now - 3600_000),   // linked devices heard in the last hour
      checkedIn1h: one("SELECT COUNT(*) AS n FROM seen WHERE hb IS NOT NULL AND at > ?", now - 3600_000),            // devices that check in themselves (1.0.4+ / v145+)
      restocks24h: one("SELECT COUNT(*) AS n FROM events WHERE first_at > ?", now - 24 * 3600_000),
      confirmed24h: one("SELECT COUNT(*) AS n FROM events WHERE first_at > ? AND n >= ?", now - 24 * 3600_000, L.CONFIRM_DEVICES),
      last, crowdReadAt: Number(this.kvGet("crowd_at") || 0), tickAt: Number(this.kvGet("tick_at") || 0),
      alarm: await this.state.storage.getAlarm(), discord: !!(CFG.channels && CFG.channels.all_restocks),
    };
    if (withLog) out.log = JSON.parse(this.kvGet("log") || "[]");
    return out;
  }
}

// ---------------------------------------------------------------------------------------------------- Discord interactions
const hexBytes = h => new Uint8Array((h.match(/../g) || []).map(b => parseInt(b, 16)));

async function verify(pubHex, sigHex, msg) {
  const data = new TextEncoder().encode(msg);
  for (const alg of [{ name: "Ed25519" }, { name: "NODE-ED25519", namedCurve: "NODE-ED25519" }]) {
    try {
      const key = await crypto.subtle.importKey("raw", hexBytes(pubHex), alg, false, ["verify"]);
      return await crypto.subtle.verify(alg.name === "Ed25519" ? { name: "Ed25519" } : alg, key, hexBytes(sigHex), data);
    } catch (e) { /* try the other name */ }
  }
  return false;
}

const reply = (content, extra = {}) => json({ type: 4, data: { content: content.slice(0, 1990), flags: 64, allowed_mentions: { parse: [] }, ...extra } });

function followMenus(memberRoles) {
  const roles = CFG.roles || {}, sets = roles.sets || {}, stores = roles.stores || {};
  const has = new Set(memberRoles || []);
  const rows = [];
  (roles.setGroups || []).forEach((g, i) => {
    const opts = g.filter(n => sets[n]).map(n => ({ label: n.slice(0, 100), value: sets[n], default: has.has(sets[n]) }));
    if (opts.length) rows.push({ type: 1, components: [{ type: 3, custom_id: "roles:s" + i, min_values: 0, max_values: opts.length,
      placeholder: i === 0 ? "Sets to be pinged about" : "Older sets", options: opts }] });
  });
  const so = Object.entries(stores).map(([n, id]) => ({ label: n, value: id, default: has.has(id) }));
  if (so.length) rows.push({ type: 1, components: [{ type: 3, custom_id: "roles:st", min_values: 0, max_values: so.length,
    placeholder: "Stores to be pinged about", options: so }] });
  return rows;
}

function menuOptions(customId) {
  const roles = CFG.roles || {};
  if (customId === "roles:st") return Object.values(roles.stores || {});
  const i = Number(customId.slice(7));
  return ((roles.setGroups || [])[i] || []).map(n => (roles.sets || {})[n]).filter(Boolean);
}

async function setRole(env, guild, user, role, add) {
  return fetch(`${API}/guilds/${guild}/members/${user}/roles/${role}`, { method: add ? "PUT" : "DELETE",
    headers: { Authorization: "Bot " + env.DISCORD_BOT_TOKEN, "User-Agent": UA, "X-Audit-Log-Reason": "Restalker /follow" } });
}

async function interactions(req, env, ctx) {
  const sig = req.headers.get("x-signature-ed25519"), ts = req.headers.get("x-signature-timestamp");
  const body = await req.text();
  if (!sig || !ts || !env.DISCORD_PUBLIC_KEY || !(await verify(env.DISCORD_PUBLIC_KEY, sig, ts + body))) return new Response("bad signature", { status: 401 });
  const it = JSON.parse(body);
  if (it.type === 1) return json({ type: 1 });                                  // Discord's check of this address
  const member = it.member || {};
  const user = (member.user || it.user || {}).id;
  const name = (it.data || {}).name;

  if (it.type === 4) {                                                          // /price: product names as you type
    const q = String(((it.data.options || []).find(o => o.focused) || {}).value || "").toLowerCase();
    const prods = await products();
    const choices = prods.filter(p => p.n.toLowerCase().includes(q)).slice(0, 25).map(p => ({ name: p.n.slice(0, 100), value: p.a }));
    return json({ type: 8, data: { choices } });
  }
  if (it.type === 2 && name === "follow") return reply("Choose what Restalker pings you about (pick none to stop the pings):", { components: followMenus(member.roles) });
  if (it.type === 2 && name === "status") {
    const s = await (await hub(env).fetch("https://hub/v1/stats")).json();
    s.devices12h = `${s.active} active now · ${s.devices12h}`;
    const last = (s.last || []).map(e => `• ${e.name} - ${e.s} ${L.money(e.p)} (${e.n} devices) <t:${Math.floor(e.first_at / 1000)}:R>`).join("\n") || "none yet";
    return reply(`**Restalker crowd**\nDevices sharing (last 12 h): **${s.devices12h}** · all time: **${s.devicesAll}**\n` +
      `Restocks spotted (24 h): **${s.restocks24h}** · confirmed: **${s.confirmed24h}**\nLatest confirmed:\n${last}`);
  }
  if (it.type === 2 && name === "link") {
    const code = String(((it.data.options || [])[0] || {}).value || "").trim().toUpperCase();
    const uname = (member.user || it.user || {}).username || "";
    const r = await (await hub(env).fetch("https://hub/__link", { method: "POST", body: JSON.stringify({ code, user, uname }) })).json();
    if (!r.ok) return reply("That code is not valid (codes work for 15 minutes) - make a new one in Restalker: Settings, Discord, Connect.");
    const role = (CFG.roles || {}).linked;
    if (role && it.guild_id) await setRole(env, it.guild_id, user, role, true);
    return reply(`Connected - this Discord account now belongs to ${r.devices} Restalker device${r.devices === 1 ? "" : "s"}. Thank you for sharing!`);
  }
  if (it.type === 2 && name === "price") {
    const a = String(((it.data.options || [])[0] || {}).value || "");
    const prods = await products();
    const p = prods.find(x => x.a === a) || prods.find(x => x.n.toLowerCase().includes(a.toLowerCase()));
    if (!p) return reply("That product is not on Restalker's list.");
    const hist = await rawJson("mobile/tcg_history.json");
    return reply(L.priceInfo(p, ((hist || {}).h || {})[p.a]));
  }
  if (it.type === 3) {
    const id = (it.data || {}).custom_id || "";
    if (id === "follow:open") return reply("Choose what Restalker pings you about (pick none to stop the pings):", { components: followMenus(member.roles) });
    if (id.startsWith("roles:") && user && it.guild_id) {
      const opts = menuOptions(id);
      const ch = L.roleChanges(opts, it.data.values || [], member.roles || []);
      const results = await Promise.all([...ch.add.map(r => setRole(env, it.guild_id, user, r, true)), ...ch.remove.map(r => setRole(env, it.guild_id, user, r, false))]);
      const bad = results.filter(r => !r.ok).length;
      const names = Object.fromEntries([...Object.entries((CFG.roles || {}).sets || {}), ...Object.entries((CFG.roles || {}).stores || {})].map(([n, r]) => [r, n]));
      const chosen = (it.data.values || []).map(r => names[r]).filter(Boolean);
      return reply(bad ? "Some of that could not be saved - try again in a minute." :
        (chosen.length ? `Saved - you'll be pinged for: ${chosen.join(", ")}.` : "Saved - no pings from this list."));
    }
  }
  return reply("Restalker did not understand that.");
}

async function rawJson(path) {
  const r = await fetch(RAW + path, { cf: { cacheTtl: 600, cacheEverything: true }, headers: { "User-Agent": "RestalkerRelay" } });
  return r.ok ? r.json() : null;
}
async function products() { return ((await rawJson("mobile/catalog.json")) || {}).products || []; }
