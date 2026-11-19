#!/usr/bin/env python3
"""Restalker Discord server setup (owner, 2026-11-19: server "Restalker" and "Restalker Bot" made and invited). Safe to run again and
again: it finds what is already there by name and only adds what is missing. It never deletes anything a person made.

  python3 discord_setup.py <catalog.json> <out discord.json> [worker base url]

Reads DISCORD_BOT_TOKEN and DISCORD_APP_ID from the environment. Writes the channel / role ids the relay needs (none are secret).
With the worker's url: the slash commands and the interactions address are set too (the worker must already answer there).
"""
import json, os, sys, time, urllib.request, urllib.error

API = "https://discord.com/api/v10"
TOKEN = os.environ["DISCORD_BOT_TOKEN"].strip()
APP = os.environ["DISCORD_APP_ID"].strip()
UA = "DiscordBot (https://github.com/stevenmanuel01-cloud/Restalker, 1)"

VIEW, SEND, HISTORY, THREADS_PUB, SEND_IN_THREADS, REACT = 1 << 10, 1 << 11, 1 << 16, 1 << 35, 1 << 38, 1 << 6
MANAGE_MESSAGES, KICK, MODERATE = 1 << 13, 1 << 1, 1 << 40
STORES = ["Amazon", "Target", "Walmart", "Best Buy", "GameStop", "TCGplayer"]
STORE_CH = {"Amazon": "amazon", "Target": "target", "Walmart": "walmart", "Best Buy": "best-buy", "GameStop": "gamestop", "TCGplayer": "tcgplayer"}
MARK = "⁣"   # an invisible mark at the end of the bot's own intro messages (so a second run finds them and does not post again)


def call(method, path, body=None, reason="Restalker setup"):
    data = json.dumps(body).encode() if body is not None else None
    for _ in range(6):
        req = urllib.request.Request(API + path, data=data, method=method, headers={
            "Authorization": "Bot " + TOKEN, "User-Agent": UA, "Content-Type": "application/json", "X-Audit-Log-Reason": reason})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                txt = r.read().decode()
                return json.loads(txt) if txt else {}
        except urllib.error.HTTPError as e:
            txt = e.read().decode()
            if e.code == 429:
                time.sleep(min(10, float(json.loads(txt).get("retry_after", 1)) + 0.3)); continue
            raise RuntimeError(f"{method} {path} -> {e.code} {txt[:300]}")
    raise RuntimeError(f"{method} {path} -> still rate limited")


def say(*a): print(*a, flush=True)


def main():
    catalog, out = sys.argv[1], sys.argv[2]
    base = sys.argv[3].rstrip("/") if len(sys.argv) > 3 else ""
    prods = json.load(open(catalog))["products"]

    guilds = [g for g in call("GET", "/users/@me/guilds") if g["name"] == "Restalker"]
    if not guilds: raise SystemExit("The bot is not in a server named Restalker.")
    g = guilds[0]["id"]
    say("server: Restalker")

    # ---- roles: one per set (newest first) and per store; Moderator. All pingable by the bot only (mentionable for it).
    years = {}
    for p in prods:
        if p.get("gs"): years[p["gs"]] = max(years.get(p["gs"], 0), int(p.get("gy") or 0))
    sets = sorted(years, key=lambda s: (-years[s], s))
    have = {r["name"]: r for r in call("GET", f"/guilds/{g}/roles")}
    def role(name, color=0, perms=None, mention=True):
        if name in have: return have[name]["id"]
        body = {"name": name, "color": color, "mentionable": mention, "hoist": False}
        if perms is not None: body["permissions"] = str(perms)
        r = call("POST", f"/guilds/{g}/roles", body); have[name] = r; say("role made:", name); return r["id"]
    set_roles = {s: role(s) for s in sets}
    store_roles = {s: role(s, 0x1F3A5F) for s in STORES}
    mod = role("Moderator", 0xF2B134, MANAGE_MESSAGES | KICK | MODERATE, mention=False)
    everyone = g   # (the @everyone role has the server's id)

    # ---- channels: five groups; alerts and start-here read-only (threads allowed under alerts)
    chans = call("GET", f"/guilds/{g}/channels")
    by = {(c["name"], c.get("parent_id")): c for c in chans}
    cats = {c["name"]: c for c in chans if c["type"] == 4}
    cat_ow = {}     # (a new channel takes its group's permissions)
    def category(name, ro, threads=False):
        if name in cats:
            cat_ow[cats[name]["id"]] = [{k: o[k] for k in ("id", "type", "allow", "deny")} for o in cats[name].get("permission_overwrites", [])]
            return cats[name]["id"]
        ow = []
        if ro:
            allow = VIEW | HISTORY | REACT | ((THREADS_PUB | SEND_IN_THREADS) if threads else 0)
            ow = [{"id": everyone, "type": 0, "allow": str(allow), "deny": str(SEND | (0 if threads else THREADS_PUB))},
                  {"id": mod, "type": 0, "allow": str(SEND | MANAGE_MESSAGES), "deny": "0"}]
        c = call("POST", f"/guilds/{g}/channels", {"name": name, "type": 4, "permission_overwrites": ow}); cats[name] = c; cat_ow[c["id"]] = ow
        say("group made:", name); return c["id"]
    def channel(name, parent, topic="", kind=0, tags=None):
        for c in chans:
            if c["name"] == name and c["type"] in (0, 5, 15): return c["id"]
        body = {"name": name, "type": kind, "parent_id": parent, "topic": topic[:1000], "permission_overwrites": cat_ow.get(parent, [])}
        if kind == 15 and tags: body["available_tags"] = [{"name": t} for t in tags]
        try: c = call("POST", f"/guilds/{g}/channels", body)
        except RuntimeError as e:
            if kind != 15: raise
            say("forum not possible here, text channel instead:", name, str(e)[:80])
            body.pop("available_tags", None); body["type"] = 0; c = call("POST", f"/guilds/{g}/channels", body)
        chans.append(c); say("channel made:", name); return c["id"]

    start = category("START HERE", ro=True)
    alerts = category("RESTOCK ALERTS", ro=True, threads=True)
    market = category("MARKET", ro=True)
    community = category("COMMUNITY", ro=False)
    help_ = category("HELP", ro=False)

    ch = {}
    ch["welcome"] = channel("welcome", start, "What Restalker is and how this server works")
    ch["rules"] = channel("rules", start, "Server rules - power back to the players")
    ch["get_roles"] = channel("get-roles", start, "Choose which sets and stores Restalker pings you about")
    ch["install"] = channel("install-restalker", start, "Download Restalker for your PC and your Android phone")
    ch["all_restocks"] = channel("all-restocks", alerts, "Every restock confirmed by 2+ Restalker devices. Discuss in a thread under the alert.")
    stores = {s: channel(STORE_CH[s], alerts, f"Restocks at {s}, confirmed by 2+ Restalker devices") for s in STORES}
    ch["price_movers"] = channel("price-movers", market, "Daily: the biggest TCG price drops and rises")
    ch["daily_summary"] = channel("daily-summary", market, "Daily: restocks spotted, stores, devices sharing")
    # (the server's own #general goes into COMMUNITY)
    gen = next((c for c in chans if c["name"] == "general" and c["type"] == 0), None)
    if gen and gen.get("parent_id") != community:
        call("PATCH", f"/channels/{gen['id']}", {"parent_id": community}); say("moved #general to COMMUNITY")
    if not gen: channel("general", community, "Talk about anything Magic")
    channel("pulls-and-showcase", community, "Show what you opened")
    channel("deck-building", community, "Decks, ideas, questions")
    channel("local-stock-reports", community, "Seen stock in a shop near you? Say where and what.")
    channel("fair-trades", community, "Trades and sales at or under MSRP only")
    channel("help", help_, "Ask anything - one thread per question", 15, ["Install", "Phone", "PC", "Buying"])
    channel("bug-reports", help_, "Something wrong? One post per problem", 15, ["PC", "Phone", "Discord"])
    channel("feature-requests", help_, "Ideas - upvote the ones you want", 15, ["PC", "Phone", "Discord"])
    ch["release_notes"] = channel("release-notes", help_, "New Restalker versions, posted by the bot")
    # (#release-notes read-only for members)
    rn = next(c for c in chans if c["id"] == ch["release_notes"])
    if not any(o["id"] == everyone and int(o.get("deny", 0)) & SEND for o in rn.get("permission_overwrites", [])):
        call("PUT", f"/channels/{ch['release_notes']}/permissions/{everyone}", {"type": 0, "allow": str(VIEW | HISTORY | REACT), "deny": str(SEND | THREADS_PUB)})

    # ---- the bot's intro messages (once each)
    me = call("GET", "/users/@me")["id"]
    def intro(cid, body, pin=False):
        msgs = call("GET", f"/channels/{cid}/messages?limit=50")
        if any(m["author"]["id"] == me and m.get("content", "").endswith(MARK) for m in msgs): return
        body["content"] = body.get("content", "") + MARK
        body.setdefault("allowed_mentions", {"parse": []})
        m = call("POST", f"/channels/{cid}/messages", body)
        if pin:
            try: call("PUT", f"/channels/{cid}/pins/{m['id']}")
            except RuntimeError: pass
        say("intro posted:", cid)

    ph = json.load(urllib.request.urlopen("https://raw.githubusercontent.com/stevenmanuel01-cloud/Restalker/main/mobile/latest.json", timeout=20))
    pc = json.load(urllib.request.urlopen("https://raw.githubusercontent.com/stevenmanuel01-cloud/Restalker/main/latest.json", timeout=20))
    intro(ch["welcome"], {"content":
        "**Welcome to Restalker.** Sought-after Magic products sell out in minutes and scalpers get there first. Restalker watches the "
        "stores for you on your PC and your phone - and every Restalker that spots a restock tells all the others at once.\n\n"
        f"• <#{ch['all_restocks']}>: every restock that 2 or more Restalker devices saw, with the price, the MSRP and the TCG price.\n"
        f"• <#{ch['get_roles']}>: choose the sets and stores you want to be pinged about.\n"
        f"• <#{ch['install']}>: get Restalker for your PC and your Android phone.\n"
        f"• <#{ch['rules']}>: the few rules here.\n\n"
        "Restalker never buys on the crowd's word: a sighting only makes your own device look, and it buys only what it sees in stock at "
        "or under YOUR price cap. Power back to the players."})
    intro(ch["rules"], {"content":
        "**Rules**\n1. No reselling above MSRP, and no scalper or resale links.\n2. No other bots or scripts posting stock here.\n"
        "3. Alert channels are read-only - talk about a restock in a thread under its alert.\n4. Be kind; no spam, no scams. "
        "Moderators may remove posts and people who break these.\n5. Trades in #fair-trades at or under MSRP only."})
    intro(ch["install"], {"content":
        f"**Restalker for Android** (v{ph.get('version')}): open this on your phone and install the APK - after that it updates itself.\n"
        "https://github.com/stevenmanuel01-cloud/Restalker/releases/latest\n\n"
        f"**Restalker for Windows** ({pc.get('version')}): the PC program that watches Amazon, Best Buy, Walmart, GameStop and Target "
        "and buys under your cap. It is not public yet - ask in the help forum.\n\n"
        "Link your phone to the PC (Settings, Scan the desktop's QR code) and they share products and never buy the same thing twice."})
    intro(ch["get_roles"], {"content":
        "**Choose your pings.** Tap the button and pick the sets and stores you care about - a restock then pings only the people "
        "following that set or store. Pick nothing to stop the pings. (You can also type /follow anywhere.)",
        "components": [{"type": 1, "components": [{"type": 2, "style": 1, "label": "Choose my pings", "custom_id": "follow:open"}]}]}, pin=True)
    intro(ch["all_restocks"], {"content":
        "Restocks appear here the moment 2 or more Restalker devices have seen them, with the price next to the MSRP and the TCG "
        "price. The count goes up as more devices see it. Restalker devices never buy on the crowd's word - each one looks itself."}, pin=True)
    intro(ch["release_notes"], {"content": f"Current versions: PC {pc.get('version')} · Android {ph.get('version')}. New versions are posted here."})

    # ---- AutoMod: block invite and scam links
    rules = call("GET", f"/guilds/{g}/auto-moderation/rules")
    if not any(r["name"] == "Restalker: scam and invite links" for r in rules):
        try:
            call("POST", f"/guilds/{g}/auto-moderation/rules", {"name": "Restalker: scam and invite links", "event_type": 1, "trigger_type": 1,
                "trigger_metadata": {"keyword_filter": ["*discord.gg/*", "*free nitro*", "*steamcommunity.com/gift*", "*nitro gift*"]},
                "actions": [{"type": 1, "metadata": {"custom_message": "Links like that are not allowed here (Restalker rules)."}}], "enabled": True})
            say("AutoMod rule made")
        except RuntimeError as e: say("AutoMod rule not made:", str(e)[:120])

    cfg = {"guild": g, "channels": {**ch, "stores": stores},
           "roles": {"sets": set_roles, "stores": store_roles, "setGroups": [sets[i:i + 25] for i in range(0, len(sets), 25)], "moderator": mod}}
    json.dump(cfg, open(out, "w"), indent=1)
    say("config written:", len(set_roles), "set roles,", len(store_roles), "store roles,", len(ch) + len(stores), "channels")

    if base:
        cmds = [{"name": "follow", "description": "Choose the sets and stores Restalker pings you about"},
                {"name": "status", "description": "The Restalker crowd: devices sharing and the latest restocks"},
                {"name": "price", "description": "A product's MSRP and TCG price (now, 3-month low and high)",
                 "options": [{"type": 3, "name": "product", "description": "Product", "required": True, "autocomplete": True}]}]
        call("PUT", f"/applications/{APP}/guilds/{g}/commands", cmds); say("slash commands set: /follow /status /price")
        try:
            call("PATCH", "/applications/@me", {"interactions_endpoint_url": base + "/discord"}); say("interactions address set:", base + "/discord")
        except RuntimeError as e: say("! interactions address NOT set:", str(e)[:200]); raise


if __name__ == "__main__":
    main()
