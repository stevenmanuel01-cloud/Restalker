"""Writes WHATS_NEW.md - what the app's "What's new - all versions" link shows: the versions that were released to EVERYONE and what
changed in each (owner, 2026-11-25: "only include the ones that have been publicly available, not the intermediate beta versions which
I test"). The changes of the test versions in between are listed under the public version that first carried them.
The list of public versions is kept in mobile/public_versions.txt; each run adds the version mobile/latest.json names.
usage: python3 tools/whats_new.py   (from the repository's top folder; "Release to everyone" runs it)"""
import json, re, os

def num(v):
    return [int(x) for x in re.findall(r"\d+", str(v))]

def main():
    pub_file = "mobile/public_versions.txt"
    pub = [l.strip() for l in open(pub_file).read().split() if l.strip()] if os.path.exists(pub_file) else []
    cur = json.load(open("mobile/latest.json"))["version"]
    if cur not in pub:
        pub.append(cur)
    pub = sorted(set(pub), key=num)
    open(pub_file, "w").write("\n".join(pub) + "\n")
    # the app's notes, by version
    secs, name = {}, None
    for line in open("android/RELEASE_NOTES.md", encoding="utf-8").read().splitlines():
        m = re.match(r"^# Restalker Mobile (\d+\.\d+(?:\.\d+)?)\s*(?:\((\d{4}-\d{2}-\d{2}))?", line)
        if m:
            name = m.group(1); secs[name] = {"date": m.group(2) or "", "lines": []}
        elif name:
            secs[name]["lines"].append(line)
    out = ["# Restalker - what's new", "",
           "Every version that was released to everyone, newest first, and what changed in it. (Test versions that only the owner's devices "
           "ran are not listed on their own: their changes are under the version that first brought them to everyone.)", ""]
    pc = json.load(open("latest.json"))
    out += ["## Restalker for Windows (PC) - " + pc["version"], "", pc.get("notes", "").strip(), "",
            "The PC program updates itself; its title bar shows the version that is running.", ""]
    out += ["## Restalker Mobile (Android)", ""]
    prev = [0, 0, 0]
    blocks = []
    for v in pub:
        if num(v) < [1, 0, 0] or (num(v)[0] >= 2):          # (the numbering started again at 1.0.0 after 2.15: older ones are not listed)
            continue
        inside = sorted([k for k in secs if prev < (num(k) + [0, 0])[:3] <= (num(v) + [0, 0])[:3] and num(k)[0] < 2], key=num, reverse=True)
        lines = []
        for k in inside:
            body = "\n".join(secs[k]["lines"]).strip()
            if body:
                lines.append(body)
        date = (secs.get(v) or {}).get("date", "")
        blocks.append((v, date, "\n".join(lines)))
        prev = (num(v) + [0, 0])[:3]
    for v, date, body in reversed(blocks):
        out += [f"### {v}" + (f" ({date})" if date else ""), "", body or "- Small fixes.", ""]
    open("WHATS_NEW.md", "w", encoding="utf-8").write("\n".join(out).rstrip() + "\n")
    print("WHATS_NEW.md:", ", ".join(v for v, _, _ in reversed(blocks)) or "no public version yet", "| PC", pc["version"])

if __name__ == "__main__":
    main()
