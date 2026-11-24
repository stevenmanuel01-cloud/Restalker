# the program's own Hidden Chrome start (multibuy.Worker("stealth"), started by its own thread as in real use) in a place where Chrome
# cannot start with its sandbox (this test machine runs as root): it must fall back, start, say so, and be usable
import os, sys, tempfile, time
os.environ["RK_CHROME_EXE"] = "/opt/pw-browsers/chromium-1243/chrome-linux64/chrome"
os.environ.setdefault("DISPLAY", ":99")
sys.path.insert(0, sys.argv[1]); os.chdir(tempfile.mkdtemp())
import multibuy
logs = []
multibuy._log = lambda m: logs.append(m)
n = 0
def ok(c, t, extra=""):
    global n; n += 1; print(("PASS " if c else "FAIL ") + str(n), t, ("| " + str(extra)[:110]) if extra else ""); return c
w = multibuy.Worker("stealth")
t0 = time.time()
while time.time() - t0 < 60 and w.ctx is None and w.state == "starting":
    time.sleep(0.3)
took = time.time() - t0
good = ok(w.ctx is not None, "Hidden Chrome starts even where Chrome's sandbox cannot be used", f"state={w.state} {w.detail} after {took:.1f} s")
good &= ok(multibuy.SANDBOX.get("stealth", "").startswith("off ("), "it says honestly that the sandbox is off here and why", multibuy.SANDBOX.get("stealth"))
good &= ok(sum("did not start with Chrome's sandbox" in l for l in logs) == 1, "the fallback is written to the log, once")
good &= ok(took < 25, "the fallback costs little time at the start", f"{took:.1f} s for both tries")
rep = multibuy.report(); rep = "\n".join(rep) if isinstance(rep, list) else str(rep)
good &= ok("Hidden Chrome = off (" in rep and "store-reading browsers" in rep, "the diagnostics carry the line")
import subprocess
ps = subprocess.run(["ps", "-eo", "args"], capture_output=True, text=True).stdout
mine = [l for l in ps.splitlines() if "buyer_profile_stealth" in l and "--type=" not in l]
good &= ok(len(mine) == 1, "exactly one Hidden Chrome is running afterwards (the failed try left nothing behind)", len(mine))
print("ALL PASS (%d)" % n if good else "SOME FAILED")
os._exit(0)
