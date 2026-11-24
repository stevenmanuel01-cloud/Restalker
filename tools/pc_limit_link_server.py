"""Restalker's real engine link (amazon_buy) without browsers, for the per-seller limit over the link (v155).
usage: python3 limit_link_server.py <program folder> <seconds>. Writes what it keeps to /tmp/rk_limits.json; touch /tmp/rk_buyagain_<ASIN>."""
import sys, time, os, glob, tempfile, json
sys.path.insert(0, sys.argv[1]); os.chdir(tempfile.mkdtemp())
import amazon_buy as ab
events = []
ab._event = lambda asin, nm, kind, title, body, phone=True, situation=None, extra=None: events.append([asin, kind, title])
ab._name = lambda u, p=None, t=None: "Test product " + str(u)
ab._co_forget = lambda a: None; ab._multi_hold = lambda v: None; ab.orders_refresh = lambda *a, **k: None
ab._settings["limit"] = {"B0PUSHED01": {"A9SELLERZZZZZZ": {"until": time.time() + 3600, "name": "Kept Seller", "at": time.time()}}}
ab.start(log_fn=lambda m: print("LOG", str(m)[:160], flush=True), launch=False)
print("LINK_FILE", ab._write_link(), flush=True)
end = time.time() + float(sys.argv[2])
while time.time() < end:
    time.sleep(0.2)
    for f in glob.glob("/tmp/rk_buyagain_*"):
        asin = f.rsplit("_", 1)[1]; os.remove(f); ab._want(asin, True)
    json.dump({"limit": {a: ab.limits(a) for a in list((ab._settings.get("limit") or {}).keys())}, "events": events, "early": dict(ab._early_start)}, open("/tmp/rk_limits.json.tmp", "w"))
    os.replace("/tmp/rk_limits.json.tmp", "/tmp/rk_limits.json")
