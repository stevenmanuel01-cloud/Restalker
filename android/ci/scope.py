"""Which phone tests a change needs (owner, 2026-11-20: "when we are making a simple aesthetic change ... changing the color of something
shouldnt require full operation and checkout testing as this was previously passed and not affected by a color change").

usage: scope.py <base commit> [commit message]  -> prints one word, and the phones for it:
  look     colors, words, pictures, notes                          -> the screen tests + random taps on 2 phones (oldest + newest Android)
  screens  the screens' own code (ui/) or the screen tests, nothing that watches or buys -> the screen tests + random taps on all 4 phones
  full     anything that watches, buys, checks out, links, updates or reports (or the build itself) -> everything on all 4 phones
"[full tests]" in the commit message always gives full. The build and the core rule tests run every time, whatever the scope.
"""
import subprocess
import sys

LOOK = ("android/app/src/main/java/com/restalker/mobile/ui/Look.kt", "android/app/src/main/res/", "android/RELEASE_NOTES.md",
        "android/README.md", "android/ci/release_body.py",
        "android/ci/scope.py", "mobile/", "README.md")
SCREENS = ("android/app/src/main/java/com/restalker/mobile/ui/", "android/app/src/androidTest/java/com/restalker/mobile/ScreenTests.kt")


def under(f, prefixes):
    return any(f == p or f.startswith(p) for p in prefixes)


def scope(files, message=""):
    if "[full tests]" in message or not files:
        return "full"
    if all(under(f, LOOK) for f in files):
        return "look"
    if all(under(f, LOOK + SCREENS) for f in files):
        return "screens"
    return "full"


# (1.0.7: Android 16 - API 36 - is the one the app is made for now; it is in every run)
PHONES = {"look": "[30, 36]", "screens": "[30, 33, 34, 35, 36]", "full": "[30, 33, 34, 35, 36]"}

if __name__ == "__main__":
    base = sys.argv[1] if len(sys.argv) > 1 else ""
    msg = sys.argv[2] if len(sys.argv) > 2 else ""
    try:
        out = subprocess.run(["git", "diff", "--name-only", base, "HEAD"], capture_output=True, text=True, check=True).stdout.split()
    except Exception:
        out = []
    files = [f for f in out if f.startswith(("android/", "mobile/")) or f == "README.md" or f.startswith(".github/workflows/android")]
    s = scope(files, msg)
    print(s)
    print(PHONES[s])
    print("\n".join(files), file=sys.stderr)
