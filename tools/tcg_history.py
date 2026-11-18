"""Restalker 2.8 (owner, 2026-11-17 11:10 PM: "include the tcg price graph for each product under each product page ... just reposting
this information is sufficient, and does not need to be refreshed more than once per day during the off peak hours").
Once a day (GitHub, ~2 AM Pacific) this reads TCGplayer's own market-price history of every central-list product (mobile/catalog.json,
its TCGplayer product number) - one product every 3 seconds - and writes mobile/tcg_history.json: {asin: [[day, market price], ...]}
(day = days since 1970-01-01) - 3 months ("h") and 1 year ("y"), each point [day, market price, items sold]. Phones read that one small file once a day; nobody's phone asks TCGplayer itself."""
import json, re, sys, time, datetime, urllib.request

CAT = "mobile/catalog.json"
OUT = "mobile/tcg_history.json"
URL = "https://infinite-api.tcgplayer.com/price/history/{pid}/detailed?range={rng}"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json", "Origin": "https://www.tcgplayer.com",
                                               "Referer": "https://www.tcgplayer.com/"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read(3_000_000).decode("utf-8", "replace")


def day(s):
    d = datetime.date.fromisoformat(str(s)[:10])
    return (d - datetime.date(1970, 1, 1)).days


def points(body):
    """TCGplayer's answer -> [[day, market price, items sold], ...] (oldest first) of the sealed product's main line (the variant with the
    most sales) - the line and the bars of TCGplayer's own "Market Price History" chart."""
    d = json.loads(body)
    rows = d.get("result") or []
    if isinstance(rows, dict):
        rows = [rows]
    best = None
    for r in rows:
        bs = r.get("buckets") or []
        sold = sum(float(b.get("quantitySold") or 0) for b in bs)
        if bs and (best is None or sold > best[0]):
            best = (sold, bs)
    out = {}
    for b in (best[1] if best else []):
        try:
            p = float(b.get("marketPrice") or 0)
            q = int(float(b.get("quantitySold") or 0))
            if 0 < p < 100000:
                out[day(b.get("bucketStartDate"))] = [round(p, 2), max(0, min(q, 1_000_000))]
        except Exception:
            pass
    return [[k] + out[k] for k in sorted(out)]


def pid_of(p):
    for k in ("tp",):
        if str(p.get(k) or "").isdigit():
            return str(p[k])
    m = re.search(r"/product/(\d+)", str(p.get("i") or ""))
    return m.group(1) if m else None


def main():
    probe = "--probe" in sys.argv
    cat = json.load(open(CAT, encoding="utf-8"))
    hist, year, bad = {}, {}, []
    for n, p in enumerate(cat.get("products") or []):
        pid = pid_of(p)
        if not pid:
            continue
        for rng, into in (("quarter", hist), ("annual", year)):     # (3 months: a point every ~3 days; 1 year: a point a week)
            try:
                body = get(URL.format(pid=pid, rng=rng))
                if probe and n < 2:
                    print("RAW", rng, p["a"], body[:700].replace("\n", " "))
                pts = points(body)
                if len(pts) >= 2:
                    into[p["a"]] = pts
                else:
                    bad.append(f"{p['a']} ({pid}, {rng}): {len(pts)} point(s)")
            except Exception as e:
                bad.append(f"{p['a']} ({pid}, {rng}): {str(e)[:80]}")
            time.sleep(3)
    print(f"tcg history: {len(hist)} product(s) with 3 months, {len(year)} with 1 year" + (f"; none for {len(bad)}: " + "; ".join(bad) if bad else ""))
    for a, pts in list(hist.items())[:3]:
        print("  ", a, len(pts), "points", pts[0], "...", pts[-1])
    if not hist:
        print("NOTHING READ - the old file is kept"); return 1
    json.dump({"v": 2, "made": datetime.datetime.utcnow().strftime("%Y-%m-%d"), "h": hist, "y": year}, open(OUT, "w"), separators=(",", ":"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
