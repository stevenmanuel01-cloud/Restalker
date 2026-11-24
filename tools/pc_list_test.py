"""v152: Amazon's offers list as the detector (sweep.py) - with stand-in answers from 'Amazon'.
usage: python3 list_test.py <program folder>"""
import sys, time, os, tempfile, types, json
sys.path.insert(0, sys.argv[1]); os.chdir(tempfile.mkdtemp())
import sweep, amazon_buy
out = []
def say(n, ok, more=""):
    out.append(bool(ok)); print(("PASS " if ok else "FAIL ") + n + (("  | " + str(more)) if more else ""))
def offer(seller, price, pinned=False):
    return f'<div id="aod-{"pinned-" if pinned else ""}offer"><div id="aod-offer-heading"><h5>New</h5></div><div id="aod-offer-price"><span class="a-price"><span class="a-offscreen">${price:.2f}</span></span></div><div id="aod-offer-shipsFrom">Ships from Amazon.com</div><div id="aod-offer-soldBy">Sold by {seller}</div></div>'
def aod(*offers):
    return '<div id="aod-container"><div id="aod-offer-list">' + "".join(offers) + '</div></div>'
PAGE_IN = '<html><body><span id="productTitle">Test Box</span><div id="availability"><span>In Stock</span></div><div id="corePrice_feature_div"><span class="a-price"><span class="a-offscreen">$455.88</span></span></div><div id="merchantInfoFeature_feature_div">Sold by Amazon.com</div><input id="add-to-cart-button" value="Add to Cart"><input id="buy-now-button" name="submit.buy-now" value="Buy Now"></body></html>'
PAGE_OUT = '<html><body><span id="productTitle">Test Box</span><div id="availability"><span>Currently unavailable.</span></div><div id="outOfStock">Currently unavailable. We don\'t know when or if this item will be back in stock.</div></body></html>'
answers = {"aod": aod(offer("NNU Store", 699.00, True)), "pin": PAGE_OUT}
fetched = []
def fake_fetch(st, pace, url, timeout=20.0, gone_ok=False, cookies=None):
    fetched.append(url)
    return (answers["pin"] if "m=ATVPDKIKX0DER" in url else answers["aod"]), None
sweep._fetch = fake_fetch
puts = []
real_put = sweep.put
sweep.put = lambda t, status, price=None, src="", **k: puts.append((t["id"], status, price, src))
sweep._claim = lambda store, tid: True; sweep._unclaim = lambda store, tid: None
sweep.helpers_off = lambda store: False
sweep._HELPERS.update({("amazon", "a"): {"running": True}, ("amazon", "b"): {"running": True}})
watch = {"on": True}
sweep._engine_watches = lambda t: watch["on"]
pins = []
amazon_buy.pin = lambda asin, secs=150.0: (pins.append((asin, secs)), True)[1]
page = {"status": "OUT_OF_STOCK", "price": 699.0}
amazon_buy.STATUS = lambda tid: dict(page)
class Pace:
    answered = False
    def note_request(self): pass
    def block(self, why): pass
    def soft(self, why): pass
st = {"store": "amazon", "pace": Pace(), "poked": {}, "each_n": 1, "tpoked": {}, "poke_seen": {}, "unread": {}, "gap": {}, "answered": {}, "answers": 0}
rows = [{"id": "amazon_hobbit", "url": "https://www.amazon.com/dp/B0GXC89N66", "max_price": 485.0},
        {"id": "amazon_other", "url": "https://www.amazon.com/dp/B0AAAAAAA1", "max_price": 100.0},
        {"id": "amazon_third", "url": "https://www.amazon.com/dp/B0AAAAAAA2", "max_price": 100.0}]
H = rows[0]
# 1. the share of list reads
picks = []
for n in range(1, 41):
    st["each_n"] = n
    t = sweep._aod_due(st, rows)
    picks.append(t["id"] if t else None)
    if t: sweep.AOD["last"][t["id"]] = {"at": time.time() + n * 1e-3, "amazon": None, "n": 1, "low_other": None, "page": None, "page_price": None, "ahead": False}
say("1 three of every four own rounds read an offers list (until v151: one)", sum(1 for x in picks if x) == 30, f"{sum(1 for x in picks if x)} of 40")
say("1b every product's list gets its turns", all(sum(1 for x in picks if x == r["id"]) >= 8 for r in rows), {r["id"]: picks.count(r["id"]) for r in rows})
sweep.AOD["last"].clear()
# 2. only resellers in the list: nothing is concluded
sweep._aod_round(st, H)
say("2 a list with resellers only is no sighting", not puts and sweep.list_in("amazon_hobbit") is None and not pins)
# 3. Amazon's own offer at MSRP appears in the list; the ordinary page still shows the reseller
answers["aod"] = aod(offer("NNU Store", 699.00, True), offer("Amazon.com", 455.88))
t0 = time.time(); sweep._aod_round(st, H)
say("3a Amazon's own offer under the cap in the list IS a sighting: it goes in like a page reading", puts == [("amazon_hobbit", "IN_STOCK", 455.88, "Amazon offers list")], puts)
say("3b the seller page was read at that moment; it showed nothing buyable: the buying browser is NOT sent there", any("m=ATVPDKIKX0DER" in u for u in fetched) and not pins, [u[-40:] for u in fetched[-2:]])
say("3c while the list shows it, the page's 'sold out' is held (list_in gives the list's price)", sweep.list_in("amazon_hobbit") == 455.88)
# 4. its list is kept fresh: it is the next list read, ahead of the others, once 12 s are over
st["each_n"] = 3
say("4a at once after reading it, the round is an ordinary one (this 4th round reads a page)", sweep._aod_due(st, rows) is None)
sweep.AOD["last"]["amazon_hobbit"]["at"] -= 13
say("4b 12 s later its list is read again even in a page round", (sweep._aod_due(st, rows) or {}).get("id") == "amazon_hobbit")
# 5. the seller page shows it now (20 s later): the buying browser is sent there
sweep.AOD["pin_at"]["amazon_hobbit"] -= 21; answers["pin"] = PAGE_IN
sweep._aod_round(st, H)
say("5 the seller page shows Amazon's offer buyable under the cap: the buying browser is sent to that page", pins == [("B0GXC89N66", sweep.PIN_FOR)], pins)
# 6. a product the buying browser does not watch: sighting yes, no seller-page read
n_f = len(fetched); watch["on"] = False; sweep.AOD["pin_at"].clear(); pins.clear()
sweep._aod_round(st, H)
say("6 a product you have not armed: the sighting counts, no seller-page request is spent", len(fetched) == n_f + 1 and not pins and puts[-1][1] == "IN_STOCK")
watch["on"] = True
# 7. the page catches up: the lead is kept for the evidence
sweep.page_says("amazon_hobbit", "OUT_OF_STOCK"); sweep.page_says("amazon_hobbit", "IN_STOCK", now=t0 + 46)
row = [r for r in sweep.LIST_FIRST if r[1] == "amazon_hobbit"][-1]
say("7 when a product page shows it, how much later is kept (the evidence)", row[3] == 46.0, row)
# 8. Amazon's offer over the cap: no sighting
answers["aod"] = aod(offer("Amazon.com", 520.00)); puts.clear()
sweep._aod_round(st, H)
say("8 Amazon's own offer OVER your cap is no sighting, and ends the hold", not puts and sweep.list_in("amazon_hobbit") is None)
# 9. a used offer by Amazon ('Amazon Resale') is not Amazon's new offer
answers["aod"] = aod('<div id="aod-offer"><div id="aod-offer-heading"><h5>Used - Like New</h5></div><div id="aod-offer-price"><span class="a-price"><span class="a-offscreen">$300.00</span></span></div><div id="aod-offer-soldBy">Sold by Amazon Resale</div></div>')
sweep._aod_round(st, H)
say("9 a used offer (Amazon Resale) is no sighting", not puts and sweep.list_in("amazon_hobbit") is None)
# 10. the hold ends by itself when the list is not read again
answers["aod"] = aod(offer("Amazon.com", 455.88)); sweep._aod_round(st, H)
sweep.LIST_IN["amazon_hobbit"]["at"] -= sweep.LIST_HOLD + 1
say("10 without a newer list read the hold ends after 75 s (a dead list cannot keep a product 'in stock')", sweep.list_in("amazon_hobbit") is None)
# 11. a tracker / the crowd reports a product: its LIST is read first and again every 8 s for 3 minutes
sweep.LIST_IN.clear(); sweep.AOD["last"].clear(); st["each_n"] = 3
REC = lambda at: {"at": at, "amazon": None, "n": 1, "low_other": None, "page": "OUT_OF_STOCK", "page_price": None, "ahead": False}
for r in rows: sweep.AOD["last"][r["id"]] = REC(time.time() - 5)
say("11a nothing reported: this 4th round reads a page", sweep._aod_due(st, rows) is None)
st["tpoked"]["amazon_third"] = time.time()
say("11b reported a moment ago: its list is read FIRST (before its page)", (sweep._aod_due(st, rows) or {}).get("id") == "amazon_third")
sweep.AOD["last"]["amazon_third"] = REC(time.time())
say("11c ... not twice in a row", sweep._aod_due(st, rows) is None)
sweep.AOD["last"]["amazon_third"]["at"] -= 9
say("11d ... and again after 8 s", (sweep._aod_due(st, rows) or {}).get("id") == "amazon_third")
st["tpoked"]["amazon_third"] = time.time() - 181
say("11e three minutes after the report the extra reads end", sweep._aod_due(st, rows) is None)
# 12. the diagnostics text
txt = sweep.aod_text([])
say("12 the diagnostics show the reads per product, the list-first sightings and what the seller page said", "list reads per product" in txt and "in the list first; a product page showed it 46 s later" in txt and "showed it buyable 2x" in txt, txt[-330:])
bad = out.count(False); print(f"SOME FAILED: {bad} of {len(out)}" if bad else f"ALL PASS ({len(out)})"); sys.exit(1 if bad else 0)
