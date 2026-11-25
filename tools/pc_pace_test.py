"""v159: a slowed pace must pay (sweep.Pace). usage: python3 pace_test.py <program folder>
A simulated clock and simulated stores: (A) refusals do not depend on the pace (Target, diagnostics 100), (B) refusals come from speed,
(C) no refusals at all, (D) a store that refuses everything for a while."""
import sys, os, tempfile, random
sys.path.insert(0, sys.argv[1]); os.chdir(tempfile.mkdtemp())
import sweep, time as _t
out = []
def say(n, ok, more=""):
    out.append(bool(ok)); print(("PASS " if ok else "FAIL ") + n + (("  | " + str(more)[:260]) if more else ""))
clock = [1_800_000_000.0]
sweep.time = type("T", (), {"time": staticmethod(lambda: clock[0]), "strftime": _t.strftime, "sleep": staticmethod(lambda s: None), "localtime": _t.localtime, "gmtime": _t.gmtime})
sweep._save_soon = lambda: None; sweep.log = lambda *a, **k: None

def run(refuse, seconds, start=6.0, floor=4.0, ceil=120.0, seed=1, pace=None):
    """refuse(interval, rng, now) -> True when the store refuses this request. -> the pace, good answers, refusals."""
    rng = random.Random(seed)
    p = pace or sweep.Pace("target", "main", start, floor, ceil)
    t_end = clock[0] + seconds; good = bad = 0
    while clock[0] < t_end:
        clock[0] = max(clock[0] + p.interval, p.rest_until)
        if refuse(p.interval, rng, clock[0]): p.block("HTTP 435"); bad += 1
        else: p.ok(); good += 1
    return p, good, bad

# the OLD rule for comparison (v158): every refusal 1.5 x slower, 10 % back per 30 min without refusal
def old(refuse, seconds, start=6.0, floor=4.0, ceil=120.0, seed=1):
    rng = random.Random(seed); calm = start; safe = 0.0; last = clock[0]; t = clock[0]; t_end = t + seconds; good = 0
    while t < t_end:
        t += max(calm, safe)
        if refuse(max(calm, safe), rng, t): safe = min(ceil, max(safe, max(calm, safe) * 1.5)); calm = max(calm, safe); last = t; t += 45
        else:
            good += 1
            if safe and t - last > 1800: safe = safe * 0.9 if safe * 0.9 > floor else 0.0; calm = max(start, safe); last = t
    return max(calm, safe), good

A = lambda iv, rng, now: rng.random() < 0.20                      # refused 1 in 5 whatever the pace (Target)
p, good, bad = run(A, 4 * 3600)
iv_old, good_old = old(A, 4 * 3600)
say("A1 a store that refuses 1 in 5 at ANY pace: after 4 hours the pace is the normal one again, not 120 s", p.interval <= 13.5, f"new: one request every {p.interval:.1f} s; old rule: every {iv_old:.0f} s")
say("A2 ... and it got several times the good answers of the old rule", good > 3 * good_old, f"good answers in 4 h: new {good}, old rule {good_old}")
clock[0] += 10
t0 = clock[0]; p2 = sweep.Pace("target", "main", 6.0, 4.0, 120.0, learned={"calm": 120.0, "safe": 120.0, "last_block": t0 - 60, "at": t0})
say("A3 a PC that comes with the old 120 s pace kept in its file (the owner's): it starts there ...", abs(p2.interval - 120.0) < 0.01)
p2, g2, b2 = run(A, 3600, pace=p2)
say("A4 ... and is back under 15 s within the hour", p2.interval <= 15.0, f"{p2.interval:.1f} s after 1 h; {g2} good, {b2} refused; trials paid {p2.trials[0]}, not {p2.trials[1]}")
B = lambda iv, rng, now: iv < 30.0 and rng.random() < 0.9          # refuses (nearly) everything faster than one request per 30 s
p, good, bad = run(B, 6 * 3600)
say("B1 a store that really refuses speed: the pace settles at 30 s or slower and stays", 30.0 <= p.interval <= 60.0, f"one request every {p.interval:.1f} s")
tail_p, tg, tb = run(B, 3 * 3600, pace=p)
say("B2 ... its faster tries get rarer (each one that did not pay doubles the wait) - few refusals in the next 3 hours", tb <= 0.12 * (tg + tb) and p.trial_wait >= 40, f"{tb} refused of {tg + tb}; next try after {p.trial_wait} answers")
say("B3 ... and the wait between tries has an end (never stuck: at most every 160 answers)", p.trial_wait <= sweep.TRIAL_WAIT_MAX)
C = lambda iv, rng, now: False
p, good, bad = run(C, 3600)
say("C a store that refuses nothing: nothing is slowed, nothing is tried", p.safe == 0 and p.trials == [0, 0] and p.interval <= 6.0, f"{p.interval:.1f} s")
edge = [clock[0] + 1800, clock[0] + 5400]
D = lambda iv, rng, now: edge[0] <= now < edge[1]                # an hour of refusing everything, then fine again
p, good, bad = run(D, 5 * 3600)
say("D an hour of refusing everything, then fine: back at the normal pace by itself", p.interval <= 13.5, f"{p.interval:.1f} s; refused {bad}")
say("D2 during the hour of refusals no more than 1 request a minute was sent (rests are kept)", bad <= 75, f"{bad} requests in the hour")
# a try under way is not what is written to the file
p3 = sweep.Pace("target", "main", 6.0, 4.0, 120.0); 
for _ in range(3): clock[0] += 200; p3.block("HTTP 435")
for _ in range(12):
    if p3.trial is None: clock[0] = max(clock[0] + p3.interval, p3.rest_until); p3.ok()
say("E a faster step that is only being tried is not saved as learned (a restart in the middle keeps the proven pace)", p3.trial is not None and p3.saved()["safe"] == p3.trial["back"] and p3.saved()["safe"] > p3.safe, f"trying {p3.interval:.1f} s, saved {p3.saved()['safe']:.1f} s")
print(f"\n{sum(out)} of {len(out)} passed"); sys.exit(0 if all(out) else 1)
