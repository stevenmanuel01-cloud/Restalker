"""v155: the OTHER buying browsers on Amazon (multibuy._try_amazon) against a stand-in Amazon that sends its page in pieces:
looks tied to how far the page has been read (no fixed pauses), and the purchase limit per seller.
usage: python3 others_test.py <program folder> [old]     ("old": a program before v155 - only the two timings are measured)"""
import sys, os, time, ssl, json, threading, tempfile, http.server, socketserver
PROG = os.path.abspath(sys.argv[1]); OLD = len(sys.argv) > 2 and sys.argv[2] == "old"
A = "B0TESTOT01"; TITLE = "Magic: The Gathering Nauctis: The Sunken Realm Secret Lair Bundle"
S = {"mode": "boxfirst", "D1": 0.6, "D2": 2.5, "stock": True, "price": "89.99", "checkout": "unavailable", "reqs": []}
head = '<!doctype html><html><head><title>Amazon.com: Nauctis Bundle</title></head><body><div id="dp" class="a-container">'
def form_open(): return ('<div id="rightCol"><div id="buybox"><form id="addToCart" method="post" action="/checkout/standin"><input type="hidden" name="ASIN" value="%s"><input type="hidden" id="ASIN" value="%s">'
    '<input type="hidden" id="merchantID" name="merchantID" value="ATVPDKIKX0DER"><span id="submit.buy-now"><input id="buy-now-button" name="submit.buy-now" type="submit" value="Buy Now"></span>' % (A, A))
form_end = '<input type="hidden" name="offerListingID" value="LASTFIELD"></form></div></div>'
def center(): return ('<div id="centerCol"><span id="productTitle">%s</span><div id="corePrice_feature_div"><span class="a-price"><span class="a-offscreen">$%s</span></span></div>'
    '<div id="availability"><span>In Stock</span></div><div id="merchantInfoFeature_feature_div"><span class="offer-display-feature-text-message">Amazon.com</span></div>' % (TITLE, S["price"]))
tail = '</div><div id="feature-bullets">the rest of the page %s</div></div></body></html>' % ("x" * 2000)
sold = '<div id="centerCol"><span id="productTitle">%s</span><div id="availability"><span>Currently unavailable.</span></div>' % TITLE
def pieces():
    if not S["stock"]: return [(0, head + sold), (S["D2"], tail)]
    if S["mode"] == "boxfirst": return [(0, head + form_open()), (S["D1"], form_end + center()), (S["D2"], tail)]
    return [(0, head + form_open() + form_end + center() + tail)]
class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"
    def log_message(self, *a): pass
    def do_GET(self):
        p = self.path.split("?")[0]
        if p.startswith("/dp/" + A):
            rec = {"t": time.time(), "m": "GET"}; S["reqs"].append(rec)
            self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8"); self.send_header("Cache-Control", "no-store"); self.end_headers()
            try:
                for wait, text in pieces():
                    time.sleep(wait); self.wfile.write(text.encode()); self.wfile.flush()
                rec["end"] = time.time()
            except Exception:
                rec["cut"] = time.time()
            return
        self.send_response(200); self.send_header("Content-Type", "text/html"); self.end_headers(); self.wfile.write(b"<html><body>stand-in</body></html>")
    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0); body = self.rfile.read(n).decode("utf-8", "replace")
        S["reqs"].append({"t": time.time(), "m": "POST", "body": body})
        says = ("There was a problem with some of the items in your order (see below for more information): Sorry, you’ve reached the purchase limit for this item. Please remove the item to continue."
                if S["checkout"] == "limit" else "This item is currently unavailable. It has been removed from your order.")
        html = ('<!doctype html><html><head><title>Checkout</title></head><body><div id="checkout-main"><h1>Review your order</h1><div data-asin="%s"><span>%s</span>'
                '<div class="a-alert-content">%s</div></div></div></body></html>' % (A, TITLE, says))
        self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8"); self.end_headers(); self.wfile.write(html.encode())
class TS(socketserver.ThreadingMixIn, http.server.HTTPServer): daemon_threads = True
srv = TS(("127.0.0.1", 0), H)
cx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); cx.load_cert_chain("/home/claude/pctests/engine/cert/cert.pem", "/home/claude/pctests/engine/cert/key.pem")
srv.socket = cx.wrap_socket(srv.socket, server_side=True); PORT = srv.server_address[1]
threading.Thread(target=srv.serve_forever, daemon=True).start()
os.environ["RK_BUYER_EXE"] = "/opt/pw-browsers/chromium-1243/chrome-linux64/chrome"
os.environ["RK_EXTRA_BUYER_ARGS"] = json.dumps(["--ignore-certificate-errors", "--no-proxy-server", "--host-resolver-rules=MAP www.amazon.com 127.0.0.1:%d, MAP amazon.com 127.0.0.1:%d" % (PORT, PORT)])
for k in ("https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY"): os.environ.pop(k, None)
sys.path.insert(0, PROG); os.chdir(tempfile.mkdtemp())
import multibuy, amazon_buy as ab
out = []
def say(n, ok, more=""):
    out.append(bool(ok)); print(("PASS " if ok else "FAIL ") + n + (("  | " + str(more)[:230]) if more else ""))
steps, results, limits_told, seen = [], [], [], []
multibuy._log = lambda m: None
multibuy._note = lambda kind, key, s=None, **kw: steps.append((time.time(), s or kw))
multibuy._still_wanted = lambda job, **k: True
multibuy._amz_done = lambda *a, **k: None
ab._save = lambda: None; ab._event = lambda *a, **k: None; ab._engine_client = lambda: None
real_limit = ab.amazon_limit
ab.amazon_limit = lambda u, why="", nm=None, store="Amazon", seller=None, seller_name="": limits_told.append((u, seller, seller_name))
if hasattr(ab, "limit_seen"): ab.limit_seen = lambda u, key=None: seen.append((u, key))
multibuy.Worker._account = lambda self, store: None
def _buy(self, job):
    t0 = time.time()
    try:
        results.append(("ok", self._try_amazon(job), t0, time.time()))
    except multibuy.Stop as s:
        results.append((s.kind, str(s), t0, time.time()))
    except Exception as e:
        results.append(("error", repr(e)[:200], t0, time.time()))
multibuy.Worker._buy = _buy
w = multibuy.Worker("chromium")
t0 = time.time()
while time.time() - t0 < 60 and w.ctx is None and w.state == "starting": time.sleep(0.2)
P = {"id": "custom-asin-product-9", "asin": A, "url": "https://www.amazon.com/dp/" + A, "name": TITLE, "tokens": ["nauctis", "sunken", "realm", "bundle"], "forbid": [], "customProduct": True, "cap": 90}
def run(**kw):
    S.update(kw); n0, r0 = len(S["reqs"]), len(results)
    with multibuy._lock: w.pending["k"] = 1
    w.q.put({"key": A, "store": "amazon", "url": P["url"], "cap": 90, "product": dict(P), "detected": time.time(), "name": TITLE, "pid": P["id"]})
    t = time.time()
    while len(results) == r0 and time.time() - t < 60: time.sleep(0.05)
    rq = S["reqs"][n0:]; g = next((r for r in rq if r["m"] == "GET"), None); p = next((r for r in rq if r["m"] == "POST"), None)
    res = results[-1] if len(results) > r0 else ("none", "no result in 60 s", t, time.time())
    return {"press": round((p["t"] - g["t"]) * 1000) if p and g else None, "body": p["body"] if p else "", "kind": res[0], "why": res[1],
            "took": round((res[3] - (g["t"] if g else res[2])) * 1000), "posts": sum(1 for r in rq if r["m"] == "POST")}
say("0 the browser started with the stand-in Amazon", w.ctx is not None, w.state + " " + w.detail)
# 1. Amazon's order (buy box, then title and price, then a long rest): pressed the moment the buy box is complete
a = run(mode="boxfirst", D1=0.6, D2=2.5, stock=True, checkout="unavailable")
print("MEASURED buy box complete at 600 ms, page end at 3100 ms -> Buy Now pressed %s ms after the page request (%s: %s)" % (a["press"], a["kind"], a["why"][:70]))
# 2. a slow page: the buy box arrives 2.6 s after the page began
b = run(mode="boxfirst", D1=2.6, D2=1.0, stock=True)
print("MEASURED slow page, buy box complete at 2600 ms -> Buy Now pressed %s ms after the page request (%s: %s)" % (b["press"], b["kind"], b["why"][:70]))
if OLD:
    os._exit(0)
say("1 Buy Now is pressed as soon as the buy box is complete, long before the page has been read to its end", a["press"] is not None and 600 <= a["press"] < 1100, "%s ms" % a["press"])
say("1b the whole form went to Amazon", "offerListingID=LASTFIELD" in a["body"] and "merchantID=ATVPDKIKX0DER" in a["body"])
say("2 a SLOW page is waited for (until v154 it was given up as 'not buyable' after three looks) and bought the moment its buy box is complete", b["press"] is not None and 2600 <= b["press"] < 3100, "%s ms" % b["press"])
# 3. a sold-out page: 'not buyable' is said once the page has been read - not earlier, and without a long wait after it
c = run(stock=False, D2=1.5)
say("3 a sold-out page: nothing is pressed; 'not buyable' is said when the page has been read (not before, not long after)", c["posts"] == 0 and c["kind"] == "soldout" and 1500 <= c["took"] < 2300, "%s: %s after %s ms (page end 1500 ms)" % (c["kind"], c["why"][:50], c["took"]))
# 4. over the cap while the page is still arriving: said only when the page has been read
d = run(mode="boxfirst", D1=0.5, D2=1.2, stock=True, price="129.99")
say("4 a price over the cap is never pressed, and it is said only once the page has been read", d["posts"] == 0 and d["kind"] == "overcap" and 1700 <= d["took"] < 2500, "%s: %s after %s ms" % (d["kind"], d["why"][:50], d["took"]))
# 5. Amazon's checkout says "purchase limit": the program is told WHICH seller
e = run(mode="boxfirst", D1=0.3, D2=0.5, stock=True, price="89.99", checkout="limit")
say("5 Amazon's checkout says 'purchase limit': the program is told the listing and the seller of that offer", e["posts"] == 1 and e["kind"] == "soldout" and limits_told == [(A, "ATVPDKIKX0DER", "Amazon.com")], "%s | %s" % (limits_told, e["why"][:80]))
# 6. the limit is kept for that seller: its offer is looked at, not pressed
ab._settings["limit"] = {A: {"ATVPDKIKX0DER": {"until": time.time() + 3600, "name": "Amazon.com", "at": time.time()}}}
f = run(mode="boxfirst", D1=0.3, D2=0.5, stock=True, checkout="unavailable")
say("6 the limited seller's offer: looked at, NOT pressed (no checkout opened), and said so", f["posts"] == 0 and f["kind"] == "soldout" and "purchase limit holds for the seller" in f["why"] and "Amazon.com" in f["why"] and len(seen) == 1, "%s: %s" % (f["kind"], f["why"][:110]))
# 7. the limit is for ANOTHER seller: this offer is bought
ab._settings["limit"] = {A: {"A9OTHERSELLER9": {"until": time.time() + 3600, "name": "Other Shop", "at": time.time()}}}
g = run(mode="boxfirst", D1=0.3, D2=0.5, stock=True)
say("7 a limit for ANOTHER seller does not stop this offer: it is pressed", g["posts"] == 1 and g["press"] is not None, "%s ms; %s" % (g["press"], g["kind"]))
# 8. a page in one piece
ab._settings["limit"] = {}
h = run(mode="whole", stock=True)
say("8 a page that comes in one piece is bought as always", h["posts"] == 1 and h["press"] is not None and h["press"] < 700, "%s ms" % h["press"])
bad = out.count(False); print(f"SOME FAILED: {bad} of {len(out)}" if bad else f"ALL PASS ({len(out)})"); os._exit(1 if bad else 0)
