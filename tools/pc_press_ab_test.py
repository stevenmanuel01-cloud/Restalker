"""v157: the diagnostics of pages without the product and of how Buy Now was pressed (amazon_buy). usage: python3 ab_test.py <program folder>"""
import sys, os, tempfile, time
sys.path.insert(0, sys.argv[1]); os.chdir(tempfile.mkdtemp())
import amazon_buy as ab
out = []
def say(n, ok, more=""):
    out.append(bool(ok)); print(("PASS " if ok else "FAIL ") + n + (("  | " + str(more)[:220]) if more else ""))
t0 = ab.press_ab_text()
say("1 before anything is counted the diagnostics say so", "nothing counted yet" in t0)
C = type("C", (), {"trusted": True, "path": ab.PATH, "hello": "ok", "kind": "buyer"})()
ab._on_message(C, {"type": "press_ab", "mode": "read", "early": {"presses": 0, "junk": 0}, "read": {"presses": 7, "junk": 1}, "junkPages": 3, "decided": "",
                   "examples": [{"at": time.time() * 1000, "title": "Amazon.com", "heads": ["Click the button below to continue shopping"], "buttons": ["Continue shopping"],
                                 "chars": 61, "html": 261, "code": 200, "len": 2100, "after": "read"}]})
t1 = ab.press_ab_text()
say("2 the engine's count reaches the diagnostics: how it presses now, both ways' numbers, and what such a page showed",
    "when the page has been read" in t1 and "7 times" in t1 and "followed within 2 minutes by a page without the product: 1" in t1 and "Click the button below" in t1
    and "answer number 200" in t1 and "Continue shopping" in t1 and "such pages in all: 3" in t1, t1[-260:])
ab._on_message(C, {"type": "press_ab", "mode": "early", "early": {"presses": 2, "junk": 0}, "read": {"presses": 9, "junk": 3}, "junkPages": 6, "decided": "pages without the product follow presses made when the page was read too - early pressing is not the cause; it is on again", "examples": []})
t2 = ab.press_ab_text()
say("3 a decision the engine made by its numbers is said in plain words", "decided by the numbers" in t2 and "still arriving (early)" in t2)
say("4 it is part of the diagnostics file", ab.press_ab_text().splitlines()[0] in ab.amazon_limit_text())
# the very fast pages in the timing rows
now = time.time() * 1000
for i in range(6): ab._on_timing({"kind": "product", "asin": "B0X", "path": "/dp/B0X", "first": 150, "got": 200, "at": now, "marks": {"engine": 160, "dom": 250, "load": 300, "idle": 260}})
for i in range(4): ab._on_timing({"kind": "product", "asin": "B0X", "path": "/dp/B0X", "first": 2000, "got": 3000, "at": now, "marks": {"engine": 2010, "buyNow": 2300, "price": 2300, "title": 2350, "availability": 2300, "dom": 3000, "idle": 3400, "load": 4800}})
t3 = ab.timing_text()
say("5 the timing report counts the product-page loads that were complete in under 1 s and what was in them", "complete in under 1 s" in t3 and "6 of 10" in t3 and "with a product name 0" in t3, [l for l in t3.splitlines() if "under 1 s" in l][:1])
bad = out.count(False); print(f"SOME FAILED: {bad} of {len(out)}" if bad else f"ALL PASS ({len(out)})"); sys.exit(1 if bad else 0)
