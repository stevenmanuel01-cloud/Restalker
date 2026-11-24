"""v154: Amazon's purchase limit (amazon_buy.py). usage: python3 limit_test.py <program folder>"""
import sys, time, os, tempfile
sys.path.insert(0, sys.argv[1]); os.chdir(tempfile.mkdtemp())
import amazon_buy as ab
out = []
def say(n, ok, more=""):
    out.append(bool(ok)); print(("PASS " if ok else "FAIL ") + n + (("  | " + str(more)) if more else ""))
events = []
ab._event = lambda asin, nm, kind, title, body, phone=True, situation=None, extra=None: events.append((asin, kind, title, body, phone))
ab._save = lambda: None
ab._name = lambda u, p=None, t=None: "Star Trek Beam Me Up Bundle"
ab._settings.setdefault("armed", []).append("B0H6H8KS14"); ab._settings["rest"] = {}; ab._settings.pop("limit_at", None)
A = "B0H6H8KS14"
retried = []; ab._retry_now = lambda u, t, why: retried.append((u, why)); failed = []; ab._failed = lambda u, why="": failed.append(u)
# 1. the engine reports Amazon's sentence -> the product rests 6 hours, once, with one message to the phone
p = {"status": "BLOCKED — Amazon says you have reached its purchase limit for this item — TERMINAL; MANUAL RE-ARM REQUIRED", "hitAt": 2, "inProgress": False}
prev = {"status": "CHECKOUT RUNNING", "hitAt": 1, "inProgress": True}
ab._notice(A, p, prev, {"id": "amazon_star_trek_bundle"})
left = ab._resting(A)
say("1 Amazon's purchase-limit answer starts a 6-hour rest (set to Buy stays on, nothing is locked)", 6 * 3600 - 5 < left <= 6 * 3600 and ab.wanted(A) and not (ab._settings.get("locked") or {}).get(A), round(left))
say("1b one message, to the phone, in plain words", len(events) == 1 and events[0][4] is True and "purchase limit" in events[0][2] and "6 hours" in events[0][3] and "nothing is locked" in events[0][3], events[0][2] if events else "")
say("1c it is NOT tried again at once and does not count as a program failure", not retried and not failed)
# 2. said again a moment later (another browser): no second rest, no second message
ab._notice(A, dict(p, hitAt=3), dict(p), {"id": "amazon_star_trek_bundle"}); r2 = ab.amazon_limit(A)
say("2 said again by another browser: no second message", len(events) == 1 and r2 is False)
# 3. while it rests: no signal, no other browsers, no re-arm
say("3 while it rests it is not armed again by itself", ab._rearm_wait(A) > 0)
# 4. the diagnostics say it
t = ab.amazon_limit_text()
say("4 the diagnostics name the product and the rest", "Star Trek Beam Me Up Bundle" in t and "resting" in t, t[-150:])
# 5. you choose Buy again: the rest ends at once
ab._co_forget = lambda a: None; ab._multi_hold = lambda v: None; ab.orders_refresh = lambda *a, **k: None
ab._want(A, True)
say("5 choosing Buy again ends the rest at once", ab._resting(A) == 0 and A not in (ab._settings.get("limit_at") or {}) and ab.wanted(A))
# 6. an ordinary "out of stock" answer is handled as before (asked again at once - your rule)
events.clear(); retried.clear()
p6 = {"status": "BLOCKED — Amazon checkout says the product is unavailable / out of stock — TERMINAL; MANUAL RE-ARM REQUIRED", "hitAt": 9, "inProgress": False}
ab._notice(A, p6, {"status": "CHECKOUT RUNNING", "hitAt": 8, "inProgress": True}, {"id": "amazon_star_trek_bundle"})
say("6 an ordinary 'out of stock' answer is still asked again at once, no long rest", ab._resting(A) == 0 and len(retried) == 1, retried)
# 7. a purchase that WAS made is never turned into a rest
events.clear(); ab._settings["rest"] = {}
ab._notice(A, dict(p, purchased=True, hitAt=11), {"status": "x", "hitAt": 10}, {"id": "amazon_star_trek_bundle"})
say("7 a product that was bought is left alone", ab._resting(A) == 0)
bad = out.count(False); print(f"SOME FAILED: {bad} of {len(out)}" if bad else f"ALL PASS ({len(out)})"); sys.exit(1 if bad else 0)
