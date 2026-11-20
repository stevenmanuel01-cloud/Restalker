#!/usr/bin/env python3
"""The text of a phone-app release page on GitHub.

Owner, 2026-11-19: "there are way too many release notes so it's nearly impossible to find the actual download. Is there a way to
force this to be at the top of the page" - and a new Samsung phone refused the file ("blocked", although installing was allowed):
that is Samsung's Auto Blocker, which no app can switch off itself.

So the page starts with the download link, then how to install (the Samsung step included), then ONLY this version's notes
(the page used to carry every version's notes, with the file under all of them).

    release_body.py VERSION [RELEASE_NOTES.md]   -> prints the page text
"""
import re, sys

REPO = "https://github.com/stevenmanuel01-cloud/Restalker"


def section(notes: str, ver: str) -> str:
    """This version's part of the notes (without its heading); the newest part when the version has none."""
    parts = re.split(r"(?m)^# ", notes)
    parts = [p for p in parts if p.strip()]
    for p in parts:
        if re.match(r"Restalker Mobile %s(\s|$)" % re.escape(ver), p):
            return p.split("\n", 1)[1].strip() if "\n" in p else ""
    return parts[0].split("\n", 1)[1].strip() if parts and "\n" in parts[0] else ""


def body(ver: str, notes: str) -> str:
    apk = "%s/releases/download/mobile-v%s/restalker-mobile-%s.apk" % (REPO, ver, ver)
    new = section(notes, ver)
    out = [
        "## [⬇ Download Restalker Mobile %s - tap here](%s)" % (ver, apk),
        "",
        "**To install (Android):**",
        "1. Tap the download link above.",
        "2. Open the downloaded file and tap **Install**. If the phone asks, allow your browser to install apps.",
        "3. Open Restalker. After this it updates itself.",
        "",
        "**A Samsung phone says it is blocked?** Newer Samsung phones block apps from outside the store even after you allow them. "
        "Go to **Settings > Security and privacy > Auto Blocker**, switch it **off**, install Restalker, then switch it back on.",
    ]
    if new:
        out += ["", "---", "", "### What is new in %s" % ver, "", new]
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    v = sys.argv[1]
    if not re.fullmatch(r"[0-9]{1,3}(\.[0-9]{1,3}){0,2}", v):
        sys.exit("not a version: %r" % v)
    path = sys.argv[2] if len(sys.argv) > 2 else "android/RELEASE_NOTES.md"
    sys.stdout.write(body(v, open(path, encoding="utf-8").read()))
