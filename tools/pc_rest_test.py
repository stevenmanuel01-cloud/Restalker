"""v159: the rest after checkouts that did not buy (amazon_buy.py). usage: python3 rest_test.py <program folder>"""
import sys, time, os, tempfile
sys.path.insert(0, sys.argv[1]); os.chdir(tempfile.mkdtemp())
import amazon_buy as ab
out = []
def say(n, ok, more=""):
    out.append(bool(ok)); print(("PASS " if ok else "FAIL ") + n + (("  | " + str(more)[:240]) if more else ""))
events = []
ab._event = lambda asin, nm, kind, title, body, phone=True, situation=None, extra=None: events.append((asin, kind, title, body, phone))
ab._save = lambda: None; ab._log = lambda *a, **k: None
ab._name = lambda u, p=None, t=None: "Edge of Eternities Bundle"
A = "B0REST0001"
ab._settings.update(armed=[A], rest={}, limit={}, limit_at={}, enabled=True)
ab.enabled = lambda: True
ab.DESCRIBE = lambda tid: {"url": "https://www.amazon.com/dp/" + A, "cap": 100.0, "product": "Edge of Eternities Bundle"}
class Eng:
    def __init__(self): self.sent = []; self.catalog = {A: {"armed": True, "monitor": True}}; self.bb = {}
    def send(self, m): self.sent.append(m); return True
eng = Eng(); ab._engine_client = lambda: eng
sig = []; ab._send_signal = lambda rec: (sig.append(rec), True)[1]
multi = []; ab._multi_go_amazon = lambda *a, **k: multi.append(a)
ab._burst_note = lambda *a, **k: None; ab._episode_note = lambda *a, **k: None
phone = []; ab._phone_go = lambda tid, price: phone.append((tid, price))
def fails(n, why="ORDER NOT PLACED - the Place your order button was not found"):
    for _ in range(n): ab._failed(A, why)

# 1. ten failed checkouts -> the first rest is 30 s
r0 = ab._signal_one("row1", "Restalker", 89.99); ab._last_sent.clear()
fails(9); say("1 nine failed checkouts in a row: no rest yet", ab._resting(A) == 0)
fails(1)
say("1b the tenth: the listing rests 30 SECONDS (until v158: 5 minutes)", 29 <= ab._resting(A) <= 30 and events and "30 s" in events[-1][2], events[-1][2] if events else "")
say("1c the message says what ends it", "new" in events[-1][3] and "restock" in events[-1][3] and "price" in events[-1][3] and events[-1][4] is False, events[-1][3])
# 2. the same standing offer waits
n0 = len(sig)
say("2 a reminder of the SAME standing offer at the same price waits for the rest", ab._signal_one("row1", "in stock now", 89.99) == "resting" and len(sig) == n0)
say("2b a retry waits too", ab._signal_one("row1", "retry (failed)", 89.99) == "resting" and len(sig) == n0)
# 3. new evidence ends the rest at once
r = ab._signal_one("row1", "Restalker", 89.99)
say("3 a NEW restock (the page went from sold out to in stock) ends the rest at once - the checkout starts in the same call", r == "sent" and len(sig) == n0 + 1 and ab._resting(A) == 0 and len(multi) >= 1, f"{r}; other browsers started: {len(multi)}")
ab._last_sent.clear(); fails(10)
say("3b ten more failures soon after: the next rest is 1 minute (it grows only while the failures go on)", 59 <= ab._resting(A) <= 60, ab._resting(A))
r = ab._signal_one("row1", "tracker: TYPA", 89.99)
say("3c a tracker's alert ends it at once", r == "sent" and ab._resting(A) == 0, r)
ab._last_sent.clear(); fails(10)
say("3d ... 2 minutes", 119 <= ab._resting(A) <= 120, ab._resting(A))
r = ab._signal_one("row1", "in stock now", 84.99)
say("3e another PRICE (another offer holds the buy box) ends it at once - also from a reminder", r == "sent" and ab._resting(A) == 0, r)
ab._last_sent.clear(); fails(10); a5 = ab._resting(A); fails(10) if False else None
say("3f ... 5 minutes", 299 <= a5 <= 300, a5)
ab._settings["rest"].pop(A); ab._rest_info[A]["until"] = 0; fails(10)
say("3g ... and never longer than 5 minutes", 299 <= ab._resting(A) <= 300, ab._resting(A))
say("3h how often new evidence ended a rest is counted for the diagnostics", ab._rest_ended["n"] == 3 and "price" in ab._rest_ended["last"], ab._rest_ended)
# 4. half an hour without such a rest: it starts at 30 s again
ab._settings["rest"].pop(A); ab._rest_info[A]["at"] = time.time() - ab.REST_FORGET - 5; fails(10)
say("4 half an hour without such a rest: the next one is 30 s again (nothing stays slow)", 29 <= ab._resting(A) <= 30, ab._resting(A))
# 5. a checkout that lost the race for stock is not a failure
ab._settings["rest"].pop(A); ab._fails.clear()
fails(30, "ORDER NOT PLACED - sold out before the order was placed")
say("5 'sold out under us' is not counted (as before): no rest after 30 of them", ab._resting(A) == 0 and not ab._fails.get(A))
# 6. Amazon's purchase limit with an unreadable seller rests the whole listing for 6 h - a restock does NOT end that one
ab._settings.setdefault("rest", {})[A] = time.time() + ab.LIMIT_REST
r = ab._signal_one("row1", "Restalker", 89.99)
say("6 the 6-hour rest of Amazon's purchase limit (seller not readable) is NOT ended by a restock (it is not this rule's rest)", r == "resting" and ab._resting(A) > 6 * 3600 - 5, r)
ab._settings["rest"].pop(A)
# 7. a rest this program does not know (kept in the settings from an older version) is left alone, and ends by itself
ab._rest_info.clear(); ab._settings["rest"][A] = time.time() + 200
say("7 a rest kept from an older version is left as it is (at most 5 minutes, it ends by itself)", ab._signal_one("row1", "Restalker", 89.99) == "resting")
ab._settings["rest"].pop(A)
# 8. the diagnostics line
ab._last_sent.clear(); fails(10)
t = ab.amazon_limit_text()
say("8 the diagnostics say it in plain words", "rests after checkouts that did not buy (v159" in t and "resting now: " + A in t, [l for l in t.splitlines() if "rests after" in l][:1])
# 9. the 20-second "in stock now" reminders: the linked phone hears every new try (hard rule: every device starts at once)
ab._settings["rest"].pop(A, None); ab._rest_info.clear(); ab._fails.clear(); ab._last_sent.clear(); ab._instock_try.clear(); phone.clear()
row = {"id": "row1", "url": "https://www.amazon.com/dp/" + A}
ab._amazon_rows = lambda: [row]; ab._row_cap = lambda t: 100.0
st = {"status": "IN_STOCK", "price": 89.99, "checked_at": time.time()}
ab.STATUS = lambda tid: dict(st, checked_at=time.time())
n0 = len(sig); ab._instock_amazon()
say("9 a reminder (for sale under the cap right now): this PC's browsers are signalled AND the linked phone is told in the same call", len(sig) == n0 + 1 and phone == [("row1", 89.99)], phone)
ab._instock_amazon()
say("9b not more often than every 20 s per listing (as before)", len(sig) == n0 + 1 and len(phone) == 1)
ab._instock_try.clear(); ab._last_sent.clear(); fails(10); phone.clear(); n0 = len(sig); ab._instock_amazon()
say("9c while the same standing offer rests (same price): no signal, and the phone is not rung for it", len(sig) == n0 and phone == [] and ab._resting(A) > 0)
st["price"] = 79.99; ab._instock_amazon()
say("9d another price while resting: the rest ends, this PC and the phone start at once", len(sig) == n0 + 1 and phone == [("row1", 79.99)] and ab._resting(A) == 0, phone)
print(f"\n{sum(out)} of {len(out)} passed"); sys.exit(0 if all(out) else 1)
