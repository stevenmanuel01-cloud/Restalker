"""v151: the PC's held-open connection (relay.py + wsclient.py) against a local relay.
usage: python3 ws_pc_test.py <program folder> <http://127.0.0.1:PORT>"""
import sys, time, types, json, random, urllib.request, threading
sys.path.insert(0, sys.argv[1]); base = sys.argv[2]
out = []
def say(name, ok, more=""):
    out.append(bool(ok)); print(("PASS " if ok else "FAIL ") + name + (("  | " + str(more)) if more else ""))
rnd = lambda n, abc="0123456789abcdef": "".join(random.choice(abc) for _ in range(n))
G, CH, ME, OTHER = rnd(32), "rkl-" + rnd(24, "abcdefghijklmnopqrstuvwxyz0123456789"), rnd(12), rnd(12)
sealed = lambda t: "rk1:" + (t.encode().hex() + "A" * 60)[:80]
# stand-ins for the two parts of the program the connection hands its lines to
pl = types.ModuleType("phonelink"); cr = types.ModuleType("crowd")
state = {"linked": True, "crowd": True, "rsince": "", "gsince": "", "csince": "", "go": [], "woke": 0, "crowd_lines": [], "presence": []}
pl.channels = lambda: [CH, CH + "-go"] if state["linked"] else []
pl.cursors = lambda: [state["rsince"], state["gsince"]]
def _ws_line(ch, m):
    if not ch.endswith("-go"): return False
    state["go"].append((time.time(), m)); return "quiet"
pl.ws_line = _ws_line
pl.wake_now = lambda: state.__setitem__("woke", state["woke"] + 1)
pl.who = lambda: sealed("the pc's sign of life")
pl.linked = lambda: state["linked"]
pl.load = lambda: {"code": "RK1.x.y"}; pl.parse_code = lambda c: (CH, "sec"); pl.lock_group = lambda sec: G if state["linked"] else None
pl.take_presence = lambda beats: (state["presence"].append(beats), len(beats))[1]
cr.on = lambda: state["crowd"]; cr.my_id = lambda: ME; cr.relay_since = lambda: state["csince"]
def _take(lines, now=None):
    state["crowd_lines"] += [(time.time(), x) for x in lines]; state["csince"] = str(lines[-1]["id"]); return len(lines)
cr.relay_take = _take
sys.modules["phonelink"], sys.modules["crowd"] = pl, cr
import relay
relay.BASE = base                                   # (never the real relay)
relay.WS_URL = base.replace("http", "ws"); relay.WS_HERE = 2.0; relay.WS_PING = 1.0
def http(method, path, body=None, raw=None):
    data = raw.encode() if raw is not None else json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method, headers={"Content-Type": "text/plain" if raw is not None else "application/json"})
    with urllib.request.urlopen(req, timeout=8) as r: return json.loads(r.read().decode() or "{}")
def until(f, secs=5.0):
    end = time.time() + secs
    while time.time() < end:
        if f(): return True
        time.sleep(0.01)
    return False

# before: the newest line on the relay, so this test's own lines can be told from older ones
relay.ws_start()
say("1 the PC opens the connection and the relay says hello", until(lambda: relay.ws_up(), 8), relay.ws_text())
say("1b the active count came with it", relay._st.get("active", -1) >= 1, relay._st.get("active"))
time.sleep(0.5); state["crowd_lines"].clear()
# 2. another linked device posts (the old way): pushed to this PC, the link round is woken, the round gets it without asking the relay
t0 = time.time(); r = http("POST", "/v1/ch/" + CH, raw=sealed("phone says hi"))
ok = until(lambda: state["woke"] >= 1, 3); lat = time.time() - t0
got = relay.sync({CH: "", CH + "-go": ""})
say("2a a linked device's post wakes the link round at once", ok and lat < 0.5, f"{lat * 1000:.0f} ms after it was posted")
say("2b the round gets that post from the open connection (no request to the relay)", got is not None and [m.get("id") for m in got.get(CH, [])] == [r["id"]] and relay._st.get("syncs", 0) == 0, got and len(got.get(CH, [])))
say("2c it is handed out once", relay.sync({CH: r["id"]}).get(CH) == [])
# 3. the buy-now doorbell: taken on the spot
t0 = time.time(); http("POST", "/v1/ch/" + CH + "-go", raw=sealed("go: buy now"))
ok = until(lambda: len(state["go"]) >= 1, 3)
woke3 = state["woke"]
say("3 a phone's heartbeat on the fast channel reaches the PC at once", ok and state["go"][0][0] - t0 < 0.5, f"{(state['go'][0][0] - t0) * 1000:.0f} ms" if ok else "never")
time.sleep(0.3)
g3 = relay.sync({CH: r["id"], CH + "-go": ""})
say("3b ... it starts no extra round, and the next round still gets it (nothing is lost)", state["woke"] == woke3 and len(g3.get(CH + "-go") or []) == 1, f"woke {state['woke'] - woke3}, lines {len(g3.get(CH + '-go') or [])}")
relay._ws_buf.setdefault(CH, []).extend([{"id": "5", "message": "old"}, {"id": str(int(r["id"]) + 1000), "message": "new"}, {"id": str(int(r["id"]) + 1000), "message": "new again"}])
g3 = relay.sync({CH: r["id"]})
say("3c a line at or before the reader's place, or twice, is never handed out", [m["message"] for m in g3[CH]] == ["new"], [m["message"] for m in g3[CH]])
# 4. a crowd sighting from someone else reaches crowd.py at once
st = http("GET", "/v1/stats"); prod = (st.get("last") or [{}])[0].get("a") or "B0TESTPC01"
import wsclient
other = wsclient.WS(relay.WS_URL + "/v1/ws?c=" + OTHER, timeout=10)
while True:
    m = other.recv(1.0)
    if m is None or '"hello"' in m: break
t0 = time.time(); other.send(json.dumps({"t": "s", "l": [{"v": 1, "k": "s", "a": prod, "s": "Walmart", "st": "in", "t": time.time(), "p": 55.5}]}))
ok = until(lambda: any(prod in json.dumps(x) for _, x in state["crowd_lines"]), 3)
say("4 another device's sighting reaches this PC's crowd reader at once", ok, f"{(state['crowd_lines'][-1][0] - t0) * 1000:.0f} ms" if ok else "never")
# 5. this PC's own post and sighting go up the connection and are answered
sent0 = relay._ws["sent"]
say("5a this PC's sealed post goes up the connection and is kept", relay.post_ch(CH, sealed("pc state")) is True and relay._ws["sent"] > sent0)
ok5 = relay.share([{"v": 1, "k": "s", "a": prod, "s": "Target", "st": "in", "t": time.time(), "p": 44.0}])
say("5b this PC's sighting goes up the connection and is taken", ok5 is True)
got_other = []
while True:
    m = other.recv(0.5)
    if m is None: break
    got_other.append(m)
say("5c ... and the other device hears that sighting", any("Target" in x for x in got_other), len(got_other))
# 6. "I am here" goes up by itself and the linked devices' signs of life come back
say("6 'I am here' is sent by itself; the linked devices come back with it", until(lambda: len(state["presence"]) >= 1, 6), state["presence"][-1:] )
# 7. the connection drops: asking the old way takes over; it comes back by itself and nothing is lost or doubled
relay._ws["ws"].sock.close()
say("7a a dropped connection is noticed", until(lambda: not relay.ws_up(), 8))
r7 = http("POST", "/v1/ch/" + CH, raw=sealed("posted while the pc was away"))
state["rsince"] = r["id"]
say("7b it is opened again by itself", until(lambda: relay.ws_up(), 12), relay.ws_text())
until(lambda: relay._ws_buf.get(CH), 3)
got = relay.sync({CH: state["rsince"]})
ids = [m.get("id") for m in (got or {}).get(CH, [])]
say("7c what was posted meanwhile arrives after it is back", r7["id"] in ids, ids)
# 8. unlinked and sharing off: no connection is kept
state["linked"] = False; state["crowd"] = False
say("8 nothing linked and sharing off: the connection is given up", until(lambda: not relay._ws["up"], 30), relay.ws_text())
relay._ws["stop"] = True
try: other.close()
except Exception: pass
bad = out.count(False); print(f"SOME FAILED: {bad} of {len(out)}" if bad else f"ALL PASS ({len(out)})"); sys.exit(1 if bad else 0)
