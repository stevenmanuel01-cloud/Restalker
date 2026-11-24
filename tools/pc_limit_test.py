"""v155: Amazon's purchase limit, kept PER SELLER (amazon_buy.py). usage: python3 limit_test.py <program folder>"""
import sys, time, os, tempfile, json
sys.path.insert(0, sys.argv[1]); os.chdir(tempfile.mkdtemp())
import amazon_buy as ab
out = []
def say(n, ok, more=""):
    out.append(bool(ok)); print(("PASS " if ok else "FAIL ") + n + (("  | " + str(more)[:230]) if more else ""))
events = []
ab._event = lambda asin, nm, kind, title, body, phone=True, situation=None, extra=None: events.append((asin, kind, title, body, phone))
ab._save = lambda: None
ab._name = lambda u, p=None, t=None: "Star Trek Beam Me Up Bundle"
A = "B0H6H8KS14"; AMZ = "ATVPDKIKX0DER"
ab._settings.update(armed=[A], rest={}, limit={}, limit_at={})
retried = []; ab._retry_now = lambda u, t, why: retried.append((u, why)); failed = []; ab._failed = lambda u, why="": failed.append(u)
ab._co_forget = lambda a: None; ab._multi_hold = lambda v: None; ab.orders_refresh = lambda *a, **k: None

class Eng:                      # a stand-in for the connected buying engine
    def __init__(self): self.sent = []; self.catalog = {}; self.bb = {}
    def send(self, m): self.sent.append(m); return True
eng = Eng(); ab._engine_client = lambda: eng

# 1. the engine tells which seller Amazon refused -> kept for that seller only; the listing itself does not rest
ab._on_message(type("C", (), {"trusted": True, "path": ab.PATH, "hello": "ok", "kind": "buyer"})(), {"type": "limit", "asin": A, "seller": AMZ, "name": "Amazon.com", "untilMs": 0})
for _ in range(40):
    if events: break
    time.sleep(0.05)
lim = ab.limits(A)
say("1 the seller Amazon refused is kept for 6 hours - the listing itself does NOT rest (other sellers are bought)", AMZ in lim and abs(lim[AMZ]["until"] / 1000 - time.time() - 6 * 3600) < 10 and ab._resting(A) == 0 and ab.wanted(A) and not (ab._settings.get("locked") or {}).get(A), json.dumps(lim)[:120])
say("1b one message to the phone, in plain words, naming the seller and saying another seller's offer is still bought", len(events) == 1 and events[0][4] is True and "Amazon.com" in events[0][2] and "ANOTHER seller" in events[0][3] and "nothing is locked" in events[0][3], events[0][2] if events else "")
say("1c the buying browser is told at once (its own list of limited sellers)", any(m.get("type") == "limit" and m.get("asin") == A and AMZ in (m.get("sellers") or {}) for m in eng.sent), eng.sent[-1:] )
# 2. the engine's status words arrive a moment later: no second message, no failure counted, no retry at once
p = {"status": "BLOCKED — Amazon says you have reached its purchase limit for this item (seller: Amazon.com) — TERMINAL; MANUAL RE-ARM REQUIRED", "hitAt": 2, "inProgress": False}
ab._notice(A, p, {"status": "CHECKOUT RUNNING", "hitAt": 1, "inProgress": True}, {"id": "amazon_star_trek_bundle"})
r2 = ab.amazon_limit(A, "", None, "Amazon", AMZ, "Amazon.com")
say("2 said again (status words, another browser): no second message, not counted as a failure, not tried again at once", len(events) == 1 and r2 is False and not retried and not failed)
# 3. while the limited seller holds the buy box (the engine's look says so) and nothing changed: no signal goes out
eng.catalog[A] = {"status": "WAITING — seller limit: Amazon's limit applies to Amazon.com; an offer of another seller is bought", "price": 99.99, "lastCheck": time.time() * 1000}
say("3 the limited seller holds the buy box, same price: nothing is sent (no page loads in six browsers every 20 s)", ab.limit_hold(A, 99.99) is True and ab.limit_hold(A) is True)
say("3b a NEW price is looked at at once (another seller may hold the box now)", ab.limit_hold(A, 94.99) is False)
eng.catalog[A]["lastCheck"] = (time.time() - ab.LIMIT_RELOOK - 5) * 1000
say("3c after 10 minutes without a look the page is looked at again", ab.limit_hold(A, 99.99) is False)
eng.catalog[A] = {"status": "WAITING — $99.99, purchase control unavailable", "price": 99.99, "lastCheck": time.time() * 1000}
say("3d when the engine's look no longer shows the limited seller, signals go out as always", ab.limit_hold(A, 99.99) is False)
# 3e. the engine's "limited seller" status is no failure and no retry; a waiting retry it ended is armed again by the hub
woke = []; ab._wake = type("W", (), {"set": lambda self: woke.append(1), "clear": lambda self: None, "wait": lambda self, t=None: None, "is_set": lambda self: False})()
retried.clear(); failed.clear()
ab._notice(A, {"status": "RETRY STOPPED — new Amazon evidence WAITING — seller limit: Amazon's limit applies to Amazon.com; an offer of another seller is bought; MANUAL RE-ARM REQUIRED", "hitAt": 5}, {"status": "RETRY", "hitAt": 4, "inProgress": True}, {"id": "amazon_star_trek_bundle"})
say("3e 'the limited seller holds the buy box' is not a failed try: nothing counted, nothing retried, the listing is armed again", not failed and not retried and len(woke) == 1 and len(events) == 1)
# 4. a second seller refused: its own entry, its own message
ab.amazon_limit(A, "", None, "Amazon", "A2OTHERSELLER1", "Other Shop")
lim = ab.limits(A)
say("4 a second seller Amazon refuses gets its own entry and its own message", set(lim) == {AMZ, "A2OTHERSELLER1"} and len(events) == 2 and "Other Shop" in events[1][2])
# 5. the diagnostics name listing and sellers
t = ab.amazon_limit_text()
say("5 the diagnostics name the listing and each seller", "Star Trek Beam Me Up Bundle" in t and "Amazon.com" in t and "Other Shop" in t and "left alone" in t, t[:200])
# 6. the other browsers get the same list with the product they are asked to buy
say("6 the list the other browsers get has the times in milliseconds and the names", all(v["until"] > time.time() * 1000 and "name" in v for v in ab.limits(A).values()))
# 7. Buy again ends every limit for the listing at once, and the buying browser is told
eng.sent.clear(); ab._want(A, True)
say("7 choosing Buy again ends the limits for it at once (and the buying browser is told)", ab.limits(A) == {} and ab.wanted(A) and any(m.get("type") == "limit" and m.get("sellers") == {} for m in eng.sent), eng.sent)
# 8. the seller could not be read ('*'): the whole listing rests, as in v154
events.clear(); ab.amazon_limit(A, "words", None, "Amazon", None, "")
say("8 seller unknown: the whole listing rests 6 hours (as v154), set to Buy, not locked", "*" in ab.limits(A) and ab._resting(A) > 6 * 3600 - 10 and ab.limit_hold(A) is True and ab.wanted(A) and len(events) == 1 and "could not be read" in events[0][3])
ab._want(A, True)
say("8b ... and Buy again ends that rest too", ab._resting(A) == 0 and ab.limits(A) == {})
# 9. only the status words arrive (no seller message, e.g. an older engine): the seller named in the words is kept
events.clear(); eng.sent.clear()
ab._notice(A, dict(p, hitAt=21), {"status": "CHECKOUT RUNNING", "hitAt": 20, "inProgress": True}, {"id": "amazon_star_trek_bundle"})
say("9 status words alone are enough: the seller they name is kept", "n:amazon.com" in ab.limits(A) and ab._resting(A) == 0 and len(events) == 1)
ab._want(A, True); events.clear()
ab._notice(A, {"status": "BLOCKED — Amazon says you have reached its purchase limit for this item — TERMINAL; MANUAL RE-ARM REQUIRED", "hitAt": 31}, {"status": "CHECKOUT RUNNING", "hitAt": 30, "inProgress": True}, {"id": "amazon_star_trek_bundle"})
say("9b status words without a seller: the whole listing rests", "*" in ab.limits(A) and ab._resting(A) > 0)
ab._want(A, True)
# 10. an expired limit is gone
ab._settings["limit"] = {A: {AMZ: {"until": time.time() - 5, "name": "Amazon.com", "at": time.time() - 7 * 3600}}}
say("10 a limit whose 6 hours are over is no limit", ab.limits(A) == {} and ab.limit_hold(A) is False)
# 11. an ordinary "out of stock" answer is handled as before (asked again at once - your rule)
events.clear(); retried.clear()
p6 = {"status": "BLOCKED — Amazon checkout says the product is unavailable / out of stock — TERMINAL; MANUAL RE-ARM REQUIRED", "hitAt": 49, "inProgress": False}
ab._notice(A, p6, {"status": "CHECKOUT RUNNING", "hitAt": 48, "inProgress": True}, {"id": "amazon_star_trek_bundle"})
say("11 an ordinary 'out of stock' answer is still asked again at once, no limit kept", ab.limits(A) == {} and ab._resting(A) == 0 and len(retried) == 1, retried)
# 12. a purchase that WAS made is never turned into a limit
ab._settings["limit"] = {}; events.clear()
ab._notice(A, dict(p, purchased=True, hitAt=51), {"status": "x", "hitAt": 50}, {"id": "amazon_star_trek_bundle"})
say("12 a product that was bought is left alone", ab.limits(A) == {} and not events)
# 13. settings from v154 (the whole listing rested, seller unknown): that rest ends when v155 starts
json.dump({"on": True, "armed": [A], "rest": {A: time.time() + 5 * 3600, "B0OTHER0001": time.time() + 200}, "limit_at": {A: time.time() - 3600}}, open(ab.SETTINGS_FILE, "w"))
ab._settings.update(rest={}, limit={}, limit_at={}); ab.load_settings()
say("13 a v154 rest of the whole listing ends when v155 starts (the next try learns the seller); other rests are kept", ab._resting(A) == 0 and ab._resting("B0OTHER0001") > 0 and ab.limits(A) == {}, ab._settings.get("rest"))
# 14. v155 settings are kept across a restart
json.dump({"on": True, "armed": [A], "rest": {}, "limit_at": {A: time.time()}, "limit": {A: {AMZ: {"until": time.time() + 3000, "name": "Amazon.com", "at": time.time()}}}}, open(ab.SETTINGS_FILE, "w"))
ab._settings.update(rest={}, limit={}, limit_at={}); ab.load_settings()
say("14 the limits are kept across a restart of Restalker", AMZ in ab.limits(A))
# 15. after its start the buying browser gets the whole list
eng.sent.clear(); ab.push_limits()
say("15 a buying browser that starts is told every limit", any(m.get("asin") == A and AMZ in m.get("sellers", {}) for m in eng.sent))
bad = out.count(False); print(f"SOME FAILED: {bad} of {len(out)}" if bad else f"ALL PASS ({len(out)})"); sys.exit(1 if bad else 0)
