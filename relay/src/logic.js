// Restalker relay - the pure rules (no storage, no network): tested by relay/test/logic.test.mjs.
// Stage 2 of the growth plan (owner, 2026-11-18: Cloudflare for the relay; 2026-11-19: Discord server and bot set up).
// A sighting is only ever a reason for a device to LOOK; nothing is bought on the crowd's word. Shared data stays product, store,
// price and time - nothing personal.

export const STORES = ["Amazon", "Target", "Walmart", "Best Buy", "GameStop", "TCGplayer"];
export const MERGE_MS = 20 * 60_000;        // sightings of the same product + store within 20 min are one restock
export const CONFIRM_DEVICES = 2;           // posted to Discord once this many different devices saw it
export const MAX_AGE_MS = 30 * 60_000;      // older sightings are history, not news
export const RATE_DEVICE = 20;              // sightings per device per 10 minutes
export const RATE_IP = 60;                  // per network address per 10 minutes (the device code is self-chosen)

const DEVICE = /^[0-9a-f]{12}$/;
const ASIN = /^[A-Z0-9]{10}$/;

/** A crowd message (the app's / PC's own format: {"v":1,"c":"<device>","k":"s"|"h","a","s","st","t","p"}) -> a clean sighting,
 *  {hello}, or null with nothing kept from anything odd. [known]: the central list's product codes. [now] in ms. */
export function clean(msg, known, now) {
  if (!msg || typeof msg !== "object") return null;
  const c = typeof msg.c === "string" ? msg.c : "";
  if (!DEVICE.test(c)) return null;
  if (msg.k === "h") return { hello: true, c };
  if (msg.k !== "s") return null;
  const a = typeof msg.a === "string" ? msg.a : "";
  if (!ASIN.test(a) || (known && !known.has(a))) return null;
  if (!STORES.includes(msg.s)) return null;
  if (msg.st !== "in") return null;
  let t = Number(msg.t);
  if (!Number.isFinite(t)) return null;
  if (t < 1e11) t *= 1000;                   // (seconds -> ms)
  if (t > now + 5 * 60_000 || now - t > MAX_AGE_MS) return null;
  let p = msg.p == null ? null : Number(msg.p);
  if (p != null && !(Number.isFinite(p) && p > 0 && p < 20000)) p = null;
  if (p != null) p = Math.round(p * 100) / 100;
  return { c, a, s: msg.s, t: Math.round(t), p };
}

/** Merge one sighting into the open events: -> { ev (the event, changed or new), isNew, grew (a new device), confirmedNow }. */
export function merge(events, sg) {
  const open = events.find(e => e.a === sg.a && e.s === sg.s && sg.t - e.last_at <= MERGE_MS && sg.t >= e.first_at - MERGE_MS);
  if (!open) {
    const ev = { id: null, a: sg.a, s: sg.s, first_at: sg.t, last_at: sg.t, p: sg.p, devs: [sg.c], n: 1 };
    return { ev, isNew: true, grew: true, confirmedNow: CONFIRM_DEVICES <= 1 };
  }
  const ev = { ...open, devs: [...open.devs] };
  const grew = !ev.devs.includes(sg.c);
  if (grew) ev.devs.push(sg.c);
  ev.n = ev.devs.length;
  ev.last_at = Math.max(ev.last_at, sg.t);
  ev.first_at = Math.min(ev.first_at, sg.t);
  if (sg.p != null) ev.p = sg.p;
  return { ev, isNew: false, grew, confirmedNow: grew && ev.n === CONFIRM_DEVICES };
}

/** The product's link at that store: Amazon by its code, others from the central list (null when not known). */
export function storeLink(prod, store, asin) {
  if (store === "Amazon") return `https://www.amazon.com/dp/${asin}`;
  if (store === "TCGplayer" && prod && prod.tp) return `https://www.tcgplayer.com/product/${prod.tp}`;
  const x = ((prod && prod.sx) || []).find(o => o.s === store && typeof o.u === "string" && o.u.startsWith("https://"));
  return x ? x.u : null;
}

export const money = v => v == null ? "?" : "$" + Number(v).toFixed(2);

/** The Discord message for a confirmed restock: {content, embeds, allowed_mentions}. [ping]: role ids to mention (may be empty). */
export function restockMessage(ev, prod, tcg, ping) {
  const name = (prod && prod.n) || ev.a;
  const msrp = prod && prod.ms;
  let price = money(ev.p);
  const vs = [];
  if (msrp) vs.push(`MSRP ${money(msrp)}`);
  if (tcg) vs.push(`TCG ${money(tcg)}`);
  if (vs.length) price += `  (${vs.join(" · ")})`;
  const link = storeLink(prod, ev.s, ev.a);
  const embed = {
    title: `IN STOCK: ${name}`.slice(0, 250),
    color: 0xF2B134,
    fields: [
      { name: "Store", value: ev.s, inline: true },
      { name: "Price", value: price, inline: true },
      { name: "Seen by", value: `${ev.n} Restalker device${ev.n === 1 ? "" : "s"}`, inline: true },
    ],
    footer: { text: "Restalker crowd · each device looks for itself before buying - nothing is bought on the crowd's word" },
    timestamp: new Date(ev.first_at).toISOString(),
  };
  if (link) embed.url = link;
  // (owner, 2026-11-19: "I like showing the images more than the long URLs. This image should be slightly smaller though. Just
  //  enough for people to be able to see the product") - the central list's 200 px photo, shown as the post's picture; the name is the link
  if (prod && typeof prod.i === "string" && prod.i.startsWith("https://")) embed.image = { url: prod.i };
  const roles = (ping || []).filter(Boolean);
  return {
    content: roles.map(r => `<@&${r}>`).join(" "),
    embeds: [embed],
    allowed_mentions: { parse: [], roles },
  };
}

/** Yesterday in one message: restocks (confirmed ones and all), by store, devices heard. */
export function dailySummary(events, devicesHeard, now) {
  const day = events.filter(e => now - e.first_at <= 24 * 3600_000);
  const conf = day.filter(e => e.n >= CONFIRM_DEVICES);
  const byStore = {};
  for (const e of day) byStore[e.s] = (byStore[e.s] || 0) + 1;
  const stores = Object.entries(byStore).sort((x, y) => y[1] - x[1]).map(([s, n]) => `${s} ${n}`).join(" · ") || "none";
  return `**The last 24 hours**\nRestocks spotted: **${day.length}** (confirmed by 2+ devices: **${conf.length}**)\nBy store: ${stores}\n` +
    `Restalker devices sharing: **${devicesHeard}**`;
}

/** TCG price movers over the last [days] days from the central history ({asin: [[day, price, sold], ...]}): biggest drops and rises,
 *  at least [minPct] %. -> {drops: [{a, name, from, to, pct}], rises: [...]} (3 each at most). */
export function movers(hist, products, days = 7, minPct = 3) {
  const out = [];
  for (const [a, pts] of Object.entries(hist || {})) {
    if (!Array.isArray(pts) || pts.length < 2) continue;
    const last = pts[pts.length - 1];
    const startDay = last[0] - days;
    let from = null;
    for (const p of pts) { if (p[0] <= startDay) from = p; }
    if (!from) from = pts[0];
    if (!(from[1] > 0) || !(last[1] > 0) || from === last) continue;
    const pct = (last[1] / from[1] - 1) * 100;
    if (Math.abs(pct) < minPct) continue;
    const prod = products.find(x => x.a === a);
    out.push({ a, name: prod ? prod.n : a, from: from[1], to: last[1], pct });
  }
  return {
    drops: out.filter(x => x.pct < 0).sort((x, y) => x.pct - y.pct).slice(0, 3),
    rises: out.filter(x => x.pct > 0).sort((x, y) => y.pct - x.pct).slice(0, 3),
  };
}

export function moversText(m, days = 7) {
  const line = x => `${x.pct > 0 ? "▲" : "▼"} ${x.name}: ${money(x.from)} → ${money(x.to)} (${x.pct > 0 ? "+" : ""}${x.pct.toFixed(1)}%)`;
  if (!m.drops.length && !m.rises.length) return null;
  return `**TCG price movers, last ${days} days**\n` + [...m.drops, ...m.rises].map(line).join("\n");
}

/** A release-notes message: the newest section of a notes file ("# Title ... " until the next "# "), at most ~1800 characters. */
export function newestSection(md) {
  const lines = String(md || "").split("\n");
  const start = lines.findIndex(l => l.startsWith("# "));
  if (start < 0) return "";
  let end = lines.findIndex((l, i) => i > start && l.startsWith("# "));
  if (end < 0) end = lines.length;
  let s = lines.slice(start + 1, end).join("\n").trim();
  if (s.length > 1800) s = s.slice(0, 1790) + "…";
  return s;
}

/** The set roles split for Discord's menus (25 options at most each): newest sets first. */
export function setGroups(products) {
  const years = {};
  for (const p of products) { if (p.gs) years[p.gs] = Math.max(years[p.gs] || 0, Number(p.gy) || 0); }
  const sets = Object.keys(years).sort((x, y) => (years[y] - years[x]) || x.localeCompare(y));
  const groups = [];
  for (let i = 0; i < sets.length; i += 25) groups.push(sets.slice(i, i + 25));
  return groups;
}

/** Role changes for one menu: [options] the role ids it offers, [chosen] the ones picked, [has] the member's roles. */
export function roleChanges(options, chosen, has) {
  const want = new Set(chosen.filter(r => options.includes(r)));
  const own = new Set(has);
  return { add: options.filter(r => want.has(r) && !own.has(r)), remove: options.filter(r => !want.has(r) && own.has(r)) };
}

/** /price: the product's TCG picture - now, the low and high of the series, the change. */
export function priceInfo(prod, series) {
  const pts = (series || []).filter(p => p[1] > 0);
  const lines = [`**${prod.n}**`];
  if (prod.ms) lines.push(`MSRP: ${money(prod.ms)}`);
  if (pts.length) {
    const now = pts[pts.length - 1][1];
    const lo = Math.min(...pts.map(p => p[1])), hi = Math.max(...pts.map(p => p[1]));
    const ch = (now / pts[0][1] - 1) * 100;
    lines.push(`TCG market now: ${money(now)}`, `3 months: low ${money(lo)} · high ${money(hi)} · ${ch >= 0 ? "+" : ""}${ch.toFixed(1)}%`);
  } else lines.push("No TCG history yet.");
  if (prod.tp) lines.push(`https://www.tcgplayer.com/product/${prod.tp}`);
  return lines.join("\n");
}

// ------------------------------------------------------------------------------------------------ the shared checkout lock
// (owner, 2026-11-19: "it should be happening simultaneously with the PC. Its a race to checkout, so whichever happens first is the
// winner and should lock the product normally"). One person's linked devices share a group code (made from their link secret - no one
// else can guess it). Right before "Place your order" a device claims the product; only one device of the group holds it at a time.
// A claim runs out by itself (a device that stopped mid-checkout never blocks the others for long); "bought" holds it for a day.
export const LOCK_MS = 10 * 60_000, LOCK_MAX_MS = 15 * 60_000, BOUGHT_HOLD_MS = 24 * 3600_000;
export const GROUP = /^[0-9a-f]{32}$/;
export const ANY_ASIN = /^[A-Z0-9]{10}$|^bb:[a-z0-9_]{1,60}$/;      // (Amazon codes, and the PC's other-store product keys)

/** -> { grant: bool, row: the lock after (or the holder's) }. [row]: the current lock or null. */
export function lockDecide(row, c, now, ttl = LOCK_MS) {
  const t = Math.max(30_000, Math.min(LOCK_MAX_MS, Number(ttl) || LOCK_MS));
  if (!row || row.until < now || row.owner === c) {
    if (row && row.owner === c && row.state === "bought" && row.until >= now) return { grant: true, row };   // (its own purchase stays)
    return { grant: true, row: { owner: c, state: "checkout", since: now, until: now + t } };
  }
  return { grant: false, row };
}

/** A device says how its checkout ended: "bought" keeps the product held for a day; anything else frees it for the others. Only the
 *  holder can. -> the lock after (null = free), or undefined when this device was not the holder (nothing changes). */
export function releaseDecide(row, c, result, now) {
  if (!row || row.owner !== c) return undefined;
  if (result === "bought") return { ...row, state: "bought", until: now + BOUGHT_HOLD_MS };
  return null;
}

// ------------------------------------------------------------------------------------------------ active now
// "Active now" - one number, counted here, the same for every device whatever version it runs (owner, 2026-11-20: "the count will
// persist consistently for all active devices regardless of what version people are running ... all versions should be able to be
// active without requiring updates"):
// - a version that checks in (the app from 2.15, the PC from v141: POST /v1/h every 15 minutes) is active for 30 minutes after it was
//   last heard. The check-in needs only the device's code; nothing a later version adds may ever be required of an older one.
// - a version from before the check-in existed cannot be told to check in. It says hello on the crowd channel every 6 hours while it
//   runs, and that is all this relay hears of it - so it counts as active for 6.5 hours after it was last heard (hb IS NULL: it has
//   never checked in). Its own screen still shows the number it works out itself ("total contributors") until it is updated once.
export const ACTIVE_MS = 30 * 60_000;
export const ACTIVE_OLD_MS = 6.5 * 3600_000;
export const ACTIVE_SQL = "SELECT COUNT(*) AS n FROM seen WHERE at > ? OR (hb IS NULL AND at > ?)";
export const activeArgs = now => [now - ACTIVE_MS, now - ACTIVE_OLD_MS];

// ------------------------------------------------------------------------------------------------ presence of linked devices
// (owner, 2026-11-20: "it's showing old data for my pc and tablet. Both of these devices are currently active ... Confirm the devices can
// see and report active links"). A device's "heard" time was only its last post on the ntfy link channel - and ntfy refuses a home's
// posts once its 250 a day are used, so a running PC looked silent for hours. With its heartbeat (every 15 minutes, as before) a linked
// device now also gives its group code (the same one as for the checkout lock: made from the link secret, no one else can guess it) and a
// few words about itself - its name, version and kind - SEALED with the link secret (the relay sees only noise). It gets back when the
// relay last heard each OTHER device of its group, with that device's sealed words. No message is used; one row per device as before.
export const WHO = /^rk1:[A-Za-z0-9_-]{24,800}$/;
export const PRESENCE_KEEP_MS = 30 * 86400_000;
export const PRESENCE_MAX = 12;

/** A heartbeat's group and sealed words -> { g, w } (null when absent or odd: the device is then in no group). */
export function presenceIn(b) {
  const g = b && typeof b.g === "string" && GROUP.test(b.g) ? b.g : null;
  const w = g && typeof b.w === "string" && WHO.test(b.w) ? b.w : null;
  return { g, w };
}

/** The other devices of a group for the device [me]: [{ c, at, w }] newest first - heard in the last 30 days, at most PRESENCE_MAX. */
export function presenceOut(rows, me, now) {
  return (rows || []).filter(r => r && r.c !== me && Number(r.at) > now - PRESENCE_KEEP_MS && Number(r.at) <= now + 60_000)
    .sort((a, b) => b.at - a.at).slice(0, PRESENCE_MAX).map(r => ({ c: r.c, at: Number(r.at), w: typeof r.w === "string" ? r.w : null }));
}

// ------------------------------------------------------------------------------------------------ reports and suggestions
// (owner, 2026-11-19: "all issues should be reported to discord, which can be sorted, compiled, and sent to me/you for review ...
// addressed no more than once per day") - the daily digest of everyone's reports.
export function reportDigest(reports, now) {
  if (!reports.length) return null;
  const devs = new Set(reports.map(r => r.c)).size;
  const by = {};
  for (const r of reports) { const k = (r.title || "(no title)").replace(/\$[0-9.,]+/g, "$").slice(0, 90); (by[k] = by[k] || []).push(r); }
  const lines = Object.entries(by).sort((x, y) => y[1].length - x[1].length).slice(0, 15)
    .map(([k, l]) => `• ${l.length}× ${k}` + (l.find(r => r.note) ? ` - "${l.find(r => r.note).note.slice(0, 120)}"` : ""));
  return `**Problem reports, last 24 hours: ${reports.length} from ${devs} device${devs === 1 ? "" : "s"}**\n` + lines.join("\n");
}

export function suggestionDigest(sugg, known) {
  const fresh = sugg.filter(x => !known.has(x.a));
  if (!fresh.length) return null;
  const by = {};
  for (const x of fresh) { const e = (by[x.a] = by[x.a] || { a: x.a, name: x.name, devs: new Set() }); e.devs.add(x.c); if (!e.name && x.name) e.name = x.name; }
  const lines = Object.values(by).sort((x, y) => y.devs.size - x.devs.size).slice(0, 20)
    .map(e => `• ${e.name || "(no name)"} - ${e.a} - asked by ${e.devs.size} device${e.devs.size === 1 ? "" : "s"}`);
  return "**Products people asked to add to the central list (last 24 hours)**\n" + lines.join("\n");
}

/** A short code a person types into Discord (/link CODE) to connect a device: no letters that look alike. */
export function linkCode(rand) {
  const A = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  let s = ""; for (let i = 0; i < 6; i++) s += A[Math.floor(rand() * A.length) % A.length];
  return s;
}

/** (owner, 2026-11-19: "Master phone gets it, tests all updates, then approves for all others to get it"): a release note only for a
 *  version NEWER than the last one announced - taking a public version back (latest.json set to an older one) is never announced. */
export function verNewer(a, b) {
  const n = v => String(v || "").replace(/^v/, "").split(".").map(x => parseInt(x, 10) || 0);
  const x = n(a), y = n(b);
  for (let i = 0; i < Math.max(x.length, y.length); i++) { const d = (x[i] || 0) - (y[i] || 0); if (d) return d > 0; }
  return false;
}

/** (owner, 2026-11-19: "we can reset the version number on the next release"): the phone app's numbering started again at 1.0, right
 *  after 2.15 - so by its NAME 1.0 looks older than 2.14. Only its version code (mobile/latest.json "code") keeps counting up: the
 *  phone app's release note goes by the code. While the last announced code is not known yet (0: a relay from before this), by name. */
export function phoneNewer(code, prevCode, ver, prevVer) {
  const c = Number(code) || 0, p = Number(prevCode) || 0;
  if (c > 0 && p > 0) return c > p;
  return verNewer(ver, prevVer);
}

// ------------------------------------------------------------------------------------------------ the devices' own lines (no ntfy)
// (owner, 2026-11-20: "The ntfy messages arent really needed anymore since the app can handle notifications" - the free message
// service allows a home 250 messages a day for all its devices together, and the linked devices' product updates alone used them up;
// then nothing got through, the crowd's sightings included.) What one person's linked devices tell each other, and what the crowd
// shares, now goes through this relay instead:
// - a linked group's channel ("rkl-" + 24 letters, the same name as before; "-go" = its fast channel). Every post is SEALED with the
//   link's secret by the device (encrypted and signed) - the relay stores and hands out noise it cannot read or change. Whoever knows
//   the channel's name can read that noise and nothing else, exactly as on ntfy.
// - "crowd": the sightings every Restalker shares (posted here directly with /v1/s, or read from the old crowd channel for versions
//   that still post there) - product, store, price, time, nothing personal.
// - ONE request does a device's whole round (POST /v1/sync): "I am here" (the active count, its linked devices), and everything new on
//   the channels it reads. The answer says when to ask again ("next") - with more devices than the free plan carries, everyone asks
//   a little less often instead of anyone being refused.
// ntfy stays for what it is good at: a message to a phone WITHOUT the Restalker app (the ntfy app), and a doorbell for "buy now".
export const CH_LINK = /^rkl-[a-z0-9]{24}(-go)?$/;
export const CH_CROWD = "crowd";
export const SEALED = /^rk1:[A-Za-z0-9_-]{40,700000}$/;
export const CH_MAX_BYTES = 512 * 1024;
export const CH_KEEP_MS = 12 * 3600_000, CH_KEEP_N = 80;            // a link channel: 12 hours, the newest 80 posts
export const GO_KEEP_MS = 2 * 3600_000, GO_KEEP_N = 200;            // its fast channel: 2 hours
export const CROWD_KEEP_MS = 2 * 3600_000;
export const CH_RATE = 120;                                         // posts per channel per 10 minutes (a refused post goes over ntfy)
export const SYNC_RATE = 150;                                       // rounds per device per 10 minutes (usual: 10)
export const SYNC_CHANNELS = 4, SYNC_ROWS = 60;
export const SEEN_WRITE_MS = 8 * 60_000;                            // "I am here" is written at most this often per device

export const chOk = ch => typeof ch === "string" && (ch === CH_CROWD || CH_LINK.test(ch));
export const isGo = ch => typeof ch === "string" && ch.endsWith("-go");
export const keepOf = ch => ch === CH_CROWD ? { ms: CROWD_KEEP_MS, n: 2000 } : isGo(ch) ? { ms: GO_KEEP_MS, n: GO_KEEP_N } : { ms: CH_KEEP_MS, n: CH_KEEP_N };
/** What a first read (no mark yet) goes back to: a link 12 hours, its fast channel 30 minutes, the crowd 30 minutes. */
export const firstBackMs = ch => ch === CH_CROWD ? 30 * 60_000 : isGo(ch) ? 30 * 60_000 : CH_KEEP_MS;
/** The mark a device sends: the last id it has (digits) - anything else means "from the start" (null). */
export function sinceId(v) {
  const s = typeof v === "number" ? String(v) : typeof v === "string" ? v : "";
  return /^[0-9]{1,15}$/.test(s) ? Number(s) : null;
}
/** What a device asks to read: {channel: mark} -> [[channel, id or null]] (only real channel names, a few at most). */
export function syncReads(r) {
  if (!r || typeof r !== "object" || Array.isArray(r)) return [];
  return Object.keys(r).filter(chOk).slice(0, SYNC_CHANNELS).map(ch => [ch, sinceId(r[ch])]);
}
/** One stored post, the way ntfy hands a message out (the devices read both the same way). */
export const line = row => ({ id: String(row.id), time: Math.round(row.at / 100) / 10, event: "message", message: row.m });
/** When to ask again (seconds): a linked device every minute, one that only reads the crowd every 3 - stretched for everyone alike
 *  when the day's requests would pass what the free plan carries (never refused, never more than 15 minutes). */
export const SYNC_BASE_LINKED = 60, SYNC_BASE_CROWD = 180, SYNC_BUDGET_DAY = 60_000, SYNC_MAX = 900;
/** 2026-11-24 (the owner's list, item 6): what the devices heard lately would ask for in a day at the normal pace. Counted from what
 *  is STORED (the "seen" list), because the count kept in memory is 0 again each time the relay has slept - the slow-down below never
 *  came on. It does not move when the devices are slowed down, so the slow-down does not swing back and forth. */
export const syncDemand = (linkedDevices, crowdDevices) =>
  Math.round(Math.max(0, linkedDevices) * 86400 / SYNC_BASE_LINKED + Math.max(0, crowdDevices) * 86400 / SYNC_BASE_CROWD);
export function nextPoll(linked, perDay) {
  const base = linked ? SYNC_BASE_LINKED : SYNC_BASE_CROWD;
  const f = perDay > SYNC_BUDGET_DAY ? perDay / SYNC_BUDGET_DAY : 1;
  return Math.min(SYNC_MAX, Math.ceil(base * f / 5) * 5);
}
// ---- held-open connections (2026-11-24, the owner: "prepare everything for a full scale launch ... the capacity to receive thousands
// of users as well as send messages back to them in near zero latency") ----------------------------------------------------------------
// A device keeps ONE WebSocket open to the relay (GET /v1/ws?c=<device>&g=<group>&ch=<its channels>&s=<newest line it has>). The relay
// sleeps while nothing happens (Cloudflare "hibernation": an idle connection costs nothing) and pushes every new line - a crowd
// sighting, a linked device's sealed post, the buy-now doorbell - to the sockets that carry its channel the moment it arrives.
// What the free plan counts is REQUESTS (100,000 a day): opening a socket is one; a message a device sends over its open socket
// counts as 1/20; what the relay sends is free. So a device costs its connects of the day (a phone on a mobile network: tens), not
// 1,440 polls. "I am here" is the open socket itself - nothing is written for it (the plan also counts rows written: 100,000 a day).
export const WS_MAX = 30000;                // sockets on the one hub (Cloudflare allows 32,768 per object)
export const WS_RATE = 40;                  // connects per device per 10 minutes
export const WS_IP_RATE = 3000;             // connects per network address per 10 minutes (a phone company puts thousands of phones behind one address)
export const WS_CHANNELS = 6;               // link channels one device may carry (its group's line and doorbell; room for a second link)
export const WS_CATCHUP = 200;              // lines sent at once when a device comes back
export const WS_SAFETY_S = 6 * 3600;        // with its socket open a device still asks the old way this seldom (a missed line is found then)
export const WS_ACTIVE_EVERY_MS = 300_000;   // the active count goes to every open connection when it changed, and at least this often
export const WS_MSG_MAX = 64;               // messages a socket may send in 10 s before it is closed
export const CROWD_PUSH_GAP_MS = 15_000;    // the same product at the same store is pushed to everyone at most this often
/** The link channels named in a connect request (comma-separated) -> the valid, different ones, at most WS_CHANNELS. */
export function wsChannels(text) {
  const out = [];
  for (const c of String(text || "").split(",")) if (CH_LINK.test(c) && !out.includes(c) && out.length < WS_CHANNELS) out.push(c);
  return out;
}
/** A line as a socket carries it. */
export const wsLine = (ch, row) => JSON.stringify({ t: "m", ch, ...line(row) });
/** The newest line a device says it has (connect: s=<id>) -> a number, or null (then it gets the usual look back). */
export const wsSince = v => { const n = Number(v); return v != null && v !== "" && Number.isInteger(n) && n >= 0 ? n : null; };
/** What a device sent over its socket -> {kind:"post", ch, m} | {kind:"sight", list} | {kind:"here", w} | null (not understood: ignored). */
export function wsIn(text, channels) {
  if (typeof text !== "string" || text.length > CH_MAX_BYTES + 200) return null;
  if (text === "here") return { kind: "here", w: null };
  let o; try { o = JSON.parse(text); } catch (e) { return null; }
  if (!o || typeof o !== "object") return null;
  if (o.t === "p" && typeof o.ch === "string" && CH_LINK.test(o.ch) && (channels || []).includes(o.ch) && typeof o.m === "string" && SEALED.test(o.m)) return { kind: "post", ch: o.ch, m: o.m };
  // "I am here" with this device's sealed sign of life for its linked devices (every 10 minutes: 1/20 of a request)
  if (o.t === "h") return { kind: "here", w: typeof o.w === "string" && WHO.test(o.w) ? o.w : null };
  if (o.t === "s") { const list = Array.isArray(o.l) ? o.l.slice(0, 20) : [o.l]; return list.every(x => x && typeof x === "object") ? { kind: "sight", list } : null; }
  return null;
}
/** How many devices the free plan carries: each costs its connects of a day (+ 1/20 per message it sends). */
export const wsCapacity = (connectsPerDay, sendsPerDay, budget = 100_000) => Math.floor(budget / (connectsPerDay + sendsPerDay / 20));

/** A sighting as the crowd line carries it (the devices' own message shape), and its key (one line per device, product, store, time). */
export const crowdMsg = s => JSON.stringify({ v: 1, c: s.c, k: "s", a: s.a, s: s.s, st: "in", t: Math.round(s.t / 100) / 10, ...(s.p != null ? { p: s.p } : {}) });
export const crowdKey = s => `${s.c}|${s.a}|${s.s}|${Math.round(s.t / 1000)}`;
