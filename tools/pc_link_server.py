"""Restalker's real engine link (amazon_buy) without browsers, for an end-to-end test with the real engine in Chromium.
usage: python3 link_server.py <program folder> <seconds>.  Touch /tmp/rk_pin_<ASIN> to send a pin for that listing."""
import sys, time, os, glob, tempfile
sys.path.insert(0, sys.argv[1]); os.chdir(tempfile.mkdtemp())
import amazon_buy as ab
ab.start(log_fn=lambda m: print("LOG", str(m)[:160], flush=True), launch=False)
print("LINK_FILE", ab._write_link(), flush=True)
end = time.time() + float(sys.argv[2]); told = set()
while time.time() < end:
    time.sleep(0.25)
    for f in glob.glob("/tmp/rk_pin_*"):
        asin = f.rsplit("_", 1)[1]
        os.remove(f)
        print("PIN", asin, ab.pin(asin, 12), flush=True)
    n = len(ab._timing)
    if n and n not in told:
        told.add(n); print("TIMING_ROWS", n, flush=True)
print("FINAL_TIMING_ROWS", len(ab._timing), flush=True)
print(ab.timing_text(), flush=True)
