"""v160: price-drop alerts, notices, the buttons of the app's notifications. usage: python3 v160_test.py <program folder>"""
import sys, os, tempfile, time, json
sys.path.insert(0, sys.argv[1]); os.chdir(tempfile.mkdtemp())
out = []
def say(n, ok, more=""):
    out.append(bool(ok)); print(("PASS " if ok else "FAIL ") + n + (("  | " + str(more)[:250]) if more else ""))
import price_drop as pd
now = [1_800_000_000.0]
st = {}
rows = [{"id": "amz_ff", "url": "https://www.amazon.com/dp/B0TEST0001", "max_price": 100}, {"id": "bb_x", "url": "https://www.bestbuy.com/site/x/1.p"}]
status = lambda tid: st.get(tid)
buy = {"amz_ff": False}
alerts = []
run = lambda: pd.scan(rows, status, now=now[0], alert=lambda t, was, pr, state: alerts.append((t["id"], was, pr, state)), buys=lambda t: buy.get(t["id"], False))
def read(tid, status_, price, high=False): st[tid] = {"status": status_, "price": price, "checked_at": now[0], "high_price": high}
# 1. for sale over the limit: the first price is the mark; a small drop says nothing; a clear one alerts once
read("amz_ff", "OUT_OF_STOCK", 150.0, True); run()
say("1 the first price seen is the mark - no alert", alerts == [] and pd._st["marks"]["amz_ff"]["ref"] == 150.0)
now[0] += 60; read("amz_ff", "OUT_OF_STOCK", 146.0, True); run()
say("1b $4 cheaper (under $5): no alert", alerts == [])
now[0] += 60; read("amz_ff", "OUT_OF_STOCK", 144.0, True); run()
say("1c $6 cheaper but under 5 %: no alert", alerts == [])
now[0] += 60; read("amz_ff", "OUT_OF_STOCK", 139.0, True); run()
say("1d at least 5 % AND $5 cheaper: ONE alert, with the price before", alerts == [("amz_ff", 150.0, 139.0, "over")], alerts)
now[0] += 60; run(); now[0] += 60; read("amz_ff", "OUT_OF_STOCK", 139.0, True); run()
say("1e the same price again: no second alert", len(alerts) == 1)
# 2. bouncing back up and down within a day alerts once, not every time
now[0] += 3600; read("amz_ff", "OUT_OF_STOCK", 150.0, True); run(); now[0] += 3600; read("amz_ff", "OUT_OF_STOCK", 139.0, True); run()
say("2 a price that bounces back up and down again within a day: no new alert", len(alerts) == 1)
now[0] += 25 * 3600; read("amz_ff", "OUT_OF_STOCK", 150.0, True); run(); now[0] += 60; read("amz_ff", "OUT_OF_STOCK", 139.0, True); run()
say("2b higher for over a day, then a clear drop: a new alert", len(alerts) == 2 and alerts[-1][1] == 150.0)
# 3. it comes under the limit: that moment is a restock message, not a drop; a further drop alerts (not set to Buy)
now[0] += 60; read("amz_ff", "IN_STOCK", 99.0); run()
say("3 the moment it comes under the price limit is not a drop alert (the restock message says it)", len(alerts) == 2 and pd._st["marks"]["amz_ff"]["st"] == "in")
now[0] += 60; read("amz_ff", "IN_STOCK", 89.0); run()
say("3b a further clear drop under the limit, not set to Buy: an alert", len(alerts) == 3 and alerts[-1] == ("amz_ff", 99.0, 89.0, "in"), alerts[-1])
buy["amz_ff"] = True; now[0] += 60; read("amz_ff", "IN_STOCK", 70.0); run()
say("3c set to Buy and under the limit: no drop alert (it is bought by itself)", len(alerts) == 3)
# 4. sold out, then back: the mark starts afresh; an old reading says nothing; a row that is gone is forgotten
buy["amz_ff"] = False; now[0] += 60; read("amz_ff", "OUT_OF_STOCK", None); run(); now[0] += 60; read("amz_ff", "IN_STOCK", 60.0); run()
say("4 sold out and back in stock cheaper: not a drop (a restock)", len(alerts) == 3)
st["amz_ff"]["checked_at"] = now[0] - 2000; st["amz_ff"]["price"] = 20.0; run()
say("4b a reading older than 15 minutes says nothing about the price now", len(alerts) == 3)
rows2 = rows[1:]; pd.scan(rows2, status, now=now[0], alert=lambda *a: alerts.append(a), buys=lambda t: False)
say("4c a row that is gone from the table is forgotten", "amz_ff" not in pd._st["marks"])
# 5. the switch, and the marks survive a restart
read("bb_x", "OUT_OF_STOCK", 500.0, True); run(); pd.set_on(False); now[0] += 60; read("bb_x", "OUT_OF_STOCK", 400.0, True); n5 = run()
say("5 switched off: the mark moves, no alert", n5 == 0 and len(alerts) == 3 and pd._st["marks"]["bb_x"]["ref"] == 400.0 and pd.is_on() is False)
kept = json.load(open(pd.FILE))
say("5b the switch and the marks are kept in price_drop.json (a restart does not alert again)", kept["on"] is False and kept["marks"]["bb_x"]["ref"] == 400.0)
t, x = pd.words("Amazon", "Final Fantasy Collector Booster Box", 150.0, 139.0, "over", 100.0, False)
t2, x2 = pd.words("Amazon", "Edge Bundle", 99.0, 89.0, "in", 100.0, False)
say("6 the message in plain words", t == "PRICE DROP - Amazon - Final Fantasy Collector Booster Box: $139.00 (was $150.00)" and "Still over your price limit of $100.00 - nothing is bought" in x and "NOT set to Buy" in x2, t + " | " + x2)
say("6b the diagnostics line", "price-drop alerts (v160): OFF" in pd.report_text() and "alerts sent 3" in pd.report_text(), pd.report_text())
# ---- notices
import relay
shown = []; relay.NOTICE = lambda t, m: shown.append((t, m))
a = relay.take_notice({"id": 7, "title": "Update", "text": "A new version is out."}); b = relay.take_notice({"id": 7, "title": "Update", "text": "A new version is out."})
c = relay.take_notice({"id": 6, "title": "Older", "text": "x"}); d = relay.take_notice({"id": 8, "title": "x", "text": "too short a title"}); e = relay.take_notice("nonsense"); f = relay.take_notice(None)
say("7 a notice is shown once; the same one, an older one and a broken one never", a is True and not any([b, c, d, e, f]) and shown == [("Update", "A new version is out.")], shown)
relay._notice["seen"] = None
say("7b its number is kept across a restart", relay.take_notice({"id": 7, "title": "Update", "text": "again"}) is False and relay.take_notice({"id": 9, "title": "Newer one", "text": "y"}) is True and "last: number 9" in relay.notice_text())
relay._ws_take(json.dumps({"t": "notice", "notice": {"id": 11, "title": "Pushed live", "text": "z"}}), time.time()); relay._ws_take(json.dumps({"t": "hello", "next": 600, "notice": {"id": 12, "title": "With hello", "text": "z"}}), time.time())
say("7c it is taken from the open connection (pushed) and from its hello", [x[0] for x in shown][-2:] == ["Pushed live", "With hello"], shown[-2:])
# ---- the buttons of the app's notifications
import phonelink as pl, phone_actions as pa
acts = [{"action": "http", "label": "Approve $6.99 shipping", "url": "https://ntfy.sh/x", "method": "POST", "body": "rk1 AbCdEfGhIjKlMnOpQrStUv", "clear": True},
        {"action": "http", "label": "Do not buy", "url": "https://ntfy.sh/x", "body": "rk1 ZyXwVuTsRqPoNmLkJiHgFe"}, {"action": "view", "label": "Your orders", "url": "https://www.amazon.com/gp/css/order-history"},
        {"action": "view", "label": "Fourth", "url": "https://example.com"}, {"action": "http", "label": "Bad", "body": "delete everything"}, {"action": "view", "label": "Js", "url": "javascript:1"}]
ab_ = pl._app_buttons(acts)
say("8 a message's buttons as the app takes them: at most 3, a ticket or an https address, nothing else", ab_ == [{"l": "Approve $6.99 shipping", "b": "rk1 AbCdEfGhIjKlMnOpQrStUv"}, {"l": "Do not buy", "b": "rk1 ZyXwVuTsRqPoNmLkJiHgFe"}, {"l": "Your orders", "u": "https://www.amazon.com/gp/css/order-history"}] and pl._app_buttons(None) == [], ab_)
done = []; pa.DO = lambda kind, tid: (done.append((kind, tid)), {"ok": True, "text": "shipping approved"})[1]; pa.SEND = lambda *a, **k: None; pa.LOG = lambda m: None
tk = pa._ticket("approve", "amz_ff", "Final Fantasy Box", "Amazon")
s = {"dev": "d-pc"}
o = {"v": 1, "d": "p-7ae0", "r": "phone", "at": time.time(), "act": "rk1 " + tk}
r1 = pl._take_act(s, o); r2 = pl._take_act(s, o); r3 = pl._take_act(s, dict(o, at=o["at"] + 1))
say("9 a press on the app's notification is redeemed like a tap on the ntfy message - once", r1 == "done" and done == [("approve", "amz_ff")] and r2 == "dup" and r3 in ("used", "dup"), f"{r1} {r2} {r3} {done}")
say("9b a made-up ticket does nothing", pl._take_act(s, {"d": "p-7ae0", "r": "phone", "at": time.time(), "act": "rk1 " + "A" * 24}) == "unknown" and len(done) == 1)
sealed = []
import relay as rl
rl.post_ch = lambda ch, body, http=None, timeout=8.0: (sealed.append((ch, body)), True)[1]
pl.linked = lambda s=None: True; pl.app_peers = lambda s=None, now=None: ["p-7ae0"]; code = pl.make_code() if hasattr(pl, "make_code") else None
print(f"\n{sum(out)} of {len(out)} passed"); sys.exit(0 if all(out) else 1)
