"""v150 'watching a restock closely' (amazon_buy.py) with a stand-in buying browser.   usage: python3 burst_test.py <program folder>"""
import sys, time, types
sys.path.insert(0, sys.argv[1])
import amazon_buy as ab
out = []
def say(name, ok, more=""):
    out.append(ok); print(("PASS " if ok else "FAIL ") + name + (("  | " + str(more)) if more else ""))
A = "B0TESTPY01"; logs = []; sent = []
class Client:
    def __init__(s): s.catalog = {A: {"id": "p1", "name": "Nauctis Bundle", "armed": True, "monitor": True, "cap": 90}}; s.bb = {}
    def send(s, ev): sent.append((time.time(), dict(ev))); return True
C = Client(); want = {"on": True}; others = []
ab._engine_client = lambda: C
ab.enabled = lambda: True
ab.wanted = lambda a: want["on"]
ab._phone_buys = lambda: False
ab._log = lambda m: logs.append(m)
ab.DESCRIBE = lambda tid: {"url": "https://www.amazon.com/dp/" + A, "cap": 90.0, "product": "Nauctis Bundle"}
ab._multi_go_amazon = lambda asin, tid, d, t_det, source, quiet=False: (others.append((time.time(), source, quiet)), ab._multi_started.__setitem__("n", ab._multi_started["n"] + 1))
ab._episode_note = lambda *a, **k: None
ab._settings.setdefault("locked", {}); ab._settings.setdefault("rest", {})
ab.BURST_EVERY = 0.25; ab.BURST_FOR = 1.6; ab.BURST_OTHERS_EVERY = 0.6; ab.BURST_MAX = 3.0
def bw(): return [t for t, e in sent if e["eventId"].startswith("bw-")]
def reset():
    with ab._lock: ab._burst.clear(); ab._last_sent.clear(); ab._pending.clear()
    sent.clear(); logs.clear(); others.clear(); C.catalog[A].update(purchased=False, attempted=False, inProgress=False, submissionPending=False)
    ab._settings["locked"].pop(A, None); ab._settings["rest"].pop(A, None); want["on"] = True

# 1. a tracker's alert: one signal at once, then looks every BURST_EVERY until BURST_FOR is over
r = ab._signal_one("amazon_x", "tracker:trackalacker", 89.99)
time.sleep(2.6)
looks = bw(); gaps = [b - a for a, b in zip(looks, looks[1:])]
say("1a the alert itself goes to the buying browser at once", r == "sent" and sent[0][1]["eventId"].startswith("rs-"), r)
say("1b then the buying browser is asked to look again and again", 3 <= len(looks) <= 8, f"{len(looks)} looks")
say("1c never faster than the pace set", all(g >= ab.BURST_EVERY - 0.02 for g in gaps), [round(g, 2) for g in gaps])
say("1d it ends by itself and says what it saw", A not in ab._burst and any("watched closely for" in m and "could not be bought" in m for m in logs), logs[-1][:140] if logs else "")
say("1e the other buying browsers are started at the alert, and again (quietly) while it is watched", len(others) >= 2 and not others[0][2] and all(o[2] for o in others[1:]), len(others))
say("1f every look is a normal restock signal for the exact listing", all(e["type"] == "restock" and e["amazonAsin"] == A and e["store"] == "amazon" for _, e in sent))
# 2. answers to looks: counted, not logged, not asked again; a refused look is not repeated
reset(); ab._signal_one("amazon_x", "Restalker", 89.99); time.sleep(0.5)
with ab._lock: eid = next((k for k in ab._pending if k.startswith("bw-")), None)
n_logs = len(logs)
ab._on_ack(C, {"eventId": eid, "ok": False, "retryable": True, "reason": "fresh Amazon validation deferred/failed"})
say("2 an answer to a look is taken quietly and never asked again", eid is not None and eid not in ab._pending and len(logs) == n_logs)
# 3. while a checkout runs nothing is asked; after it, the looks go on
reset(); ab.BURST_FOR = 4.0; ab._signal_one("amazon_x", "Restalker", 89.99); time.sleep(1.0); C.catalog[A]["inProgress"] = True; time.sleep(0.5); n1 = len(bw()); time.sleep(0.9); n2 = len(bw())
C.catalog[A]["inProgress"] = False; time.sleep(1.0); n3 = len(bw()); ab.BURST_FOR = 1.6
say("3 a running checkout is left alone; the looks go on after it", n1 >= 1 and n2 == n1 and n3 > n2, (n1, n2, n3))
# 4. bought -> ends at once
reset(); ab._signal_one("amazon_x", "Restalker", 89.99); time.sleep(0.4); C.catalog[A]["purchased"] = True; time.sleep(0.7); n1 = len(bw()); time.sleep(0.5)
say("4 bought: it ends at once", A not in ab._burst and len(bw()) == n1 and any("Ended: bought" in m for m in logs))
# 5. Buy switched off -> ends; not set to Buy -> never starts
reset(); ab._signal_one("amazon_x", "Restalker", 89.99); time.sleep(0.4); want["on"] = False; time.sleep(0.7)
say("5a Buy switched off: it ends", A not in ab._burst and any("Buy is off" in m for m in logs))
reset(); want["on"] = False; ab._signal_one("amazon_x", "tracker:trackalacker", 89.99); time.sleep(0.5)
say("5b a listing that is not set to Buy is never watched closely", A not in ab._burst and not bw())
# 6. resting / locked -> ends
reset(); ab._signal_one("amazon_x", "Restalker", 89.99); time.sleep(0.4); ab._settings["rest"][A] = time.time() + 300; time.sleep(0.7)
say("6 a product that rests is left to rest", A not in ab._burst and any("rests" in m for m in logs))
# 7. over the cap -> nothing
reset(); r = ab._signal_one("amazon_x", "tracker:trackalacker", 699.0); time.sleep(0.4)
say("7 a price over the cap starts nothing", r == "over cap" and A not in ab._burst and not sent, r)
# 8. a retry or a reminder does not start it; a new restock during it is not thrown away as a repeat
reset(); ab._signal_one("amazon_x", "retry after: sold out", 89.99); time.sleep(0.3)
say("8a a retry does not start it", A not in ab._burst)
reset(); ab._signal_one("amazon_x", "Restalker", 89.99); time.sleep(0.35); r2 = ab._signal_one("amazon_x", "Restalker", 89.99)
say("8b a new in-stock reading 0.35 s later is passed on (before: ignored for 15 s)", r2 == "sent", r2)
# 9. new signs keep it going, but never longer than BURST_MAX
reset(); t0 = time.time(); ab._signal_one("amazon_x", "Restalker", 89.99)
while time.time() - t0 < 4.2:
    time.sleep(0.3); ab._signal_one("amazon_x", "tracker:trackalacker", 89.99)
    if A not in ab._burst and time.time() - t0 > 3.0: break
dur = time.time() - t0
time.sleep(0.3)
say("9 new signs keep it going, at most BURST_MAX", any("no new sign" in m for m in logs) and 2.8 <= dur <= 4.4, round(dur, 2))
say("10 what is being watched is listed for the diagnostics", isinstance(ab.burst_snapshot(), list))
bad = out.count(False)
print(f"SOME FAILED: {bad} of {len(out)}" if bad else f"ALL PASS ({len(out)})")
sys.exit(1 if bad else 0)
