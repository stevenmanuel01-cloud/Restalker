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
      if (u.pathname === "/v1/stats") return json({ ok: true, ...(await this.stats(u.searchParams.has("log"))) });
      return json({ ok: false, error: "not found" }, 404);
    } catch (e) {
      this.note("! " + u.pathname + ": " + String(e).slice(0, 120));
      return json({ ok: false, error: String(e).slice(0, 200) }, 500);
    }
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
      if (this.rateHit("dev:" + s.c, L.RATE_DEVICE)) { dropped++; continue; }
      this.sql.exec("UPDATE seen SET sightings = sightings + 1 WHERE c = ?", s.c);
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
  }

  async releases() {
    const ch = (CFG.channels || {}).release_notes;
    const pc = await this.cached("pcrel", "latest.json", 60_000);
    const ph = await this.cached("phrel", "mobile/latest.json", 60_000);
    const out = {};
    if (pc && pc.version) {
      const prev = this.kvGet("rel_pc");
      if (prev && prev !== pc.version && ch) {
        await this.say(ch, `**Restalker PC ${pc.version} is out.** It installs itself.\n${pc.notes || ""}`);
        out.pc = pc.version;
      }
      if (prev !== pc.version) this.kvSet("rel_pc", pc.version);
    }
    if (ph && ph.version) {
      const prev = this.kvGet("rel_phone");
      if (prev && prev !== ph.version && ch) {
        const notes = L.newestSection(await this.cached("phnotes", "android/RELEASE_NOTES.md", 60_000, true));
        await this.say(ch, `**Restalker Mobile ${ph.version} is out.** Phones and tablets update by themselves.\n${notes}`);
        out.phone = ph.version;
      }
      if (prev !== ph.version) this.kvSet("rel_phone", ph.version);
    }
    return out;
  }

  async daily() {
    const ch = CFG.channels || {};
    const now = Date.now();
    const evs = this.sql.exec("SELECT s, n, first_at FROM events WHERE first_at > ?", now - 24 * 3600_000).toArray();
    const devs = this.sql.exec("SELECT COUNT(*) AS n FROM seen WHERE at > ?", now - 24 * 3600_000).one().n;
    await this.say(ch.daily_summary, L.dailySummary(evs, devs, now));
    const mv = L.moversText(L.movers(await this.history(), await this.products(), 7, 3));
    if (mv) await this.say(ch.price_movers, mv);
    return { restocks: evs.length, devices: devs, movers: !!mv };
  }

  async stats(withLog) {
    const now = Date.now();
    const one = (q, ...a) => this.sql.exec(q, ...a).one().n;
    const prods = await this.products();
    const last = this.sql.exec(`SELECT a, s, p, n, first_at FROM events WHERE n >= ? ORDER BY id DESC LIMIT 5`, L.CONFIRM_DEVICES).toArray()
      .map(e => ({ ...e, name: (prods.find(p => p.a === e.a) || {}).n || e.a }));
    const out = {
      devices12h: one("SELECT COUNT(*) AS n FROM seen WHERE at > ?", now - 12 * 3600_000),
      devicesAll: one("SELECT COUNT(*) AS n FROM seen"),
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
    const last = (s.last || []).map(e => `• ${e.name} - ${e.s} ${L.money(e.p)} (${e.n} devices) <t:${Math.floor(e.first_at / 1000)}:R>`).join("\n") || "none yet";
    return reply(`**Restalker crowd**\nDevices sharing (last 12 h): **${s.devices12h}** · all time: **${s.devicesAll}**\n` +
      `Restocks spotted (24 h): **${s.restocks24h}** · confirmed: **${s.confirmed24h}**\nLatest confirmed:\n${last}`);
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
