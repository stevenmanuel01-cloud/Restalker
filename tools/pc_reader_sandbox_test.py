"""v155: the store-reading browsers are started with Chrome's sandbox when that works (browser_fetch.sandboxed).
usage: python3 reader_sandbox_test.py <program folder>   (this test machine runs as root: Chrome cannot use its sandbox here, so the
real start shows the fallback; the 'works' case is shown with a stand-in start)"""
import sys, os, tempfile, time
sys.path.insert(0, sys.argv[1]); os.chdir(tempfile.mkdtemp())
os.environ.setdefault("DISPLAY", ":99")
import browser_fetch as bf
out = []
def say(n, ok, more=""):
    out.append(bool(ok)); print(("PASS " if ok else "FAIL ") + n + (("  | " + str(more)[:200]) if more else ""))
# 1. a start that works with the sandbox: started with it, nothing switched off
calls = []
def good(**kw): calls.append(kw); return "browser"
bf.SANDBOX.update(on=0, off=0, never=False, why="")
r = bf.sandboxed(good, headless=True)
say("1 where the sandbox works the browser is started WITH it (one start, nothing switched off)", r == "browser" and calls == [{"chromium_sandbox": True, "headless": True}] and bf.SANDBOX["on"] == 1 and not bf.SANDBOX["never"])
# 2. a start that fails either way (e.g. the browser is not installed): the error reaches the caller, the sandbox is NOT blamed
def broken(**kw): raise RuntimeError("no such browser")
try:
    bf.sandboxed(broken); raised = False
except RuntimeError:
    raised = True
say("2 a start that fails with and without it: the error is passed on as always, the sandbox stays on for the next start", raised and not bf.SANDBOX["never"])
# 3. the real thing here (root): Chrome refuses the sandbox -> started without it, remembered, said why
from playwright.sync_api import sync_playwright
exe = "/opt/pw-browsers/chromium-1243/chrome-linux64/chrome"
with sync_playwright() as pw:
    t0 = time.time(); br = bf.sandboxed(pw.chromium.launch, executable_path=exe, headless=True); dt = time.time() - t0
    pg = br.new_page(); pg.goto("data:text/html,<title>ok</title>x"); title = pg.title(); br.close()
    say("3 where Chrome will not start with its sandbox, the reader starts without it and works (and it is remembered, with the reason)", title == "ok" and bf.SANDBOX["never"] and bf.SANDBOX["why"] and dt < 20, f"{dt:.1f} s; why: {bf.SANDBOX['why']}")
    n = []
    def count(**kw): n.append(kw); return pw.chromium.launch(**kw)
    br = bf.sandboxed(count, executable_path=exe, headless=True); br.close()
    say("4 after that the next starts go straight to the way that works (no second failed try)", len(n) == 1 and "chromium_sandbox" not in n[0])
    d = tempfile.mkdtemp(); bf.SANDBOX.update(never=False)
    ctx = bf.sandboxed(pw.chromium.launch_persistent_context, d, user_data_dir=d, executable_path=exe, headless=True)
    pg = ctx.pages[0] if ctx.pages else ctx.new_page(); pg.goto("data:text/html,<title>ok2</title>x"); t2 = pg.title(); ctx.close()
    say("5 a reader with its own folder: the failed try leaves the folder usable for the start that works", t2 == "ok2")
import multibuy
t = multibuy._reader_sandbox_text()
say("6 the diagnostics say how the readers were started", "store-reading browsers" in t and "without it" in t and "stays off for this run" in t, t)
bad = out.count(False); print(f"SOME FAILED: {bad} of {len(out)}" if bad else f"ALL PASS ({len(out)})"); os._exit(1 if bad else 0)
