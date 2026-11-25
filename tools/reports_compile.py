"""Compiles the reports people sent (opened with owner_sign.py open ...) into one short list to work from: what was reported how
often, by how many different senders (anonymous tags), on which versions - and for each report the lines that say what was wrong.
Nothing identifies a device or a person. usage: python3 tools/reports_compile.py <reports.json> [out.md]"""
import json, re, sys, time, collections

KEY = re.compile(r"^(Restalker (Mobile )?v|Phone:|Data used|Data by activity|Settings:|Desktop link:|Watching service:|Network:|Notifications allowed|Battery now|"
                 r"Amazon:|Target:|Walmart:|Best Buy:|GameStop:|phone link|price-drop|When Buy Now|Who buys|Trackers on this phone)", re.I)
BAD = re.compile(r"NOT THIS PRODUCT|NOT BOUGHT|STOPPED|FAILED|refused|human check|sign(ed)? ?out|did not load|Traceback|error", re.I)


def main(a):
    d = json.load(open(a[0], encoding="utf-8"))
    reps = d.get("reports") or []
    by = collections.OrderedDict()
    for r in reps:
        k = re.sub(r"[0-9]+(\.[0-9]+)?", "#", (r.get("title") or "(no title)"))[:90]
        by.setdefault(k, []).append(r)
    out = [f"# Reports: {len(reps)} from {len(set(r.get('who') for r in reps))} sender(s)", ""]
    for k, l in sorted(by.items(), key=lambda x: -len(x[1])):
        out.append(f"## {len(l)}x {k}  ({len(set(r.get('who') for r in l))} sender(s); versions: {', '.join(sorted(set(str(r.get('app')) + ' ' + str(r.get('ver')) for r in l)))})")
        for r in l:
            text = r.get("text") or ""
            lines = text.splitlines()
            out.append(f"- {time.strftime('%m-%d %H:%M', time.gmtime((r.get('at') or 0) / 1000))} UTC, sender {r.get('who')}, {r.get('app')} {r.get('ver')}"
                       + (f" - note: \"{(r.get('note') or '')[:200]}\"" if r.get("note") else ""))
            for ln in [x for x in lines if KEY.search(x)][:14]:
                out.append("    " + ln[:260])
            bad = collections.Counter(re.sub(r"\(?\d{4}-\d\d-\d\d[ T]\d\d:\d\d(:\d\d)?\)?|\$[0-9.,]+|B0[0-9A-Z]{8}", "", m.group(0) + x[m.end():m.end() + 70])[:90].strip()
                                      for x in lines for m in [BAD.search(x)] if m)
            for w, n in bad.most_common(6):
                out.append(f"    ! {n}x {w}")
        out.append("")
    text = "\n".join(out)
    if len(a) > 1:
        open(a[1], "w", encoding="utf-8").write(text)
    print(text)


if __name__ == "__main__":
    main(sys.argv[1:])
