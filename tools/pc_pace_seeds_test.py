import sys, os, tempfile, random
sys.path.insert(0, sys.argv[1]); os.chdir(tempfile.mkdtemp())
import sweep, time as _t
clock = [1_800_000_000.0]
sweep.time = type("T", (), {"time": staticmethod(lambda: clock[0]), "strftime": _t.strftime, "sleep": staticmethod(lambda s: None), "localtime": _t.localtime, "gmtime": _t.gmtime})
sweep._save_soon = lambda: None; sweep.log = lambda *a, **k: None
def run(refuse, seconds, seed, learned=None):
    rng = random.Random(seed); p = sweep.Pace("target", "main", 6.0, 4.0, 120.0, learned=learned); t_end = clock[0] + seconds; g = b = 0
    while clock[0] < t_end:
        clock[0] = max(clock[0] + p.interval, p.rest_until)
        if refuse(p.interval, rng): p.block("HTTP 435"); b += 1
        else: p.ok(); g += 1
    return p, g, b
res = {"A": [], "A120": [], "B": [], "M": []}
for seed in range(1, 41):
    p, g, b = run(lambda iv, r: r.random() < 0.2, 2 * 3600, seed); res["A"].append(max(p.saved()["safe"], p.saved()["calm"]))
    t0 = clock[0]; p, g, b = run(lambda iv, r: r.random() < 0.2, 3600, seed, {"calm": 120.0, "safe": 120.0, "last_block": t0 - 60, "at": t0}); res["A120"].append(max(p.saved()["safe"], p.saved()["calm"]))
    p, g, b = run(lambda iv, r: iv < 30 and r.random() < 0.9, 6 * 3600, seed); b0 = p.blocks; r0 = p.requests if hasattr(p, "requests") else 0
    end0 = max(p.saved()["safe"], p.saved()["calm"]); rng2 = random.Random(seed + 99); t_end = clock[0] + 6 * 3600; g2 = b2 = 0
    while clock[0] < t_end:
        clock[0] = max(clock[0] + p.interval, p.rest_until)
        if p.interval < 30 and rng2.random() < 0.9: p.block("HTTP 435"); b2 += 1
        else: p.ok(); g2 += 1
    res["B"].append((max(end0, 0), b2 / max(1, g2 + b2)))
    # mixed: 20 % at any pace AND everything refused faster than 20 s
    p, g, b = run(lambda iv, r: r.random() < (0.95 if iv < 20 else 0.2), 6 * 3600, seed); res["M"].append(max(p.saved()["safe"], p.saved()["calm"]))
a = sorted(res["A"]); a2 = sorted(res["A120"]); m = sorted(res["M"]); bi = sorted(x[0] for x in res["B"]); bs = sorted(x[1] for x in res["B"])
ok1 = a[int(len(a) * 0.9) - 1] <= 13.5 and a[-1] <= 60.0; ok2 = a2[int(len(a2) * 0.9) - 1] <= 15.0; ok3 = bi[0] >= 30.0 and bs[-1] < 0.06; ok4 = m[0] >= 20.0 and m[int(len(m) * 0.9) - 1] <= 70.0 and m[-1] <= 121
print(("PASS" if ok1 else "FAIL"), "40 runs, refused 1 in 5 at any pace, 2 h: slowest end pace", a[-1], "middle", a[20])
print(("PASS" if ok2 else "FAIL"), "40 runs starting from the kept 120 s: after 1 h 9 in 10 are at 15 s or faster; middle", a2[20], "slowest", a2[-1])
print(("PASS" if ok3 else "FAIL"), "40 runs, a store that refuses speed under 30 s: never ends faster than 30 s; fastest", bi[0], "slowest", bi[-1], "largest refused share in the 6 hours after it has settled %.3f" % bs[-1])
print(("PASS" if ok4 else "FAIL"), "40 runs, mixed store (1 in 5 always, everything under 20 s): ends between 20 and 70 s in 9 of 10; fastest", m[0], "middle", m[20], "slowest", m[-1])
print("A:", [round(x) for x in a]); print("A120:", [round(x) for x in a2]); print("B:", [round(x) for x in bi]); print("M:", [round(x) for x in m])
sys.exit(0 if ok1 and ok2 and ok3 and ok4 else 1)
