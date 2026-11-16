#!/bin/bash
# Prints what the phone tests left (their notes and screenshots) into the job log. Never fails the job.
P=com.restalker.mobile
mkdir -p shots
adb exec-out run-as $P tar -c -C files shots 2>/dev/null | tar -x -C . 2>/dev/null || true
for f in shots/*.txt; do [ -f "$f" ] || continue; echo "=== FILE $f"; cat "$f"; echo "=== END"; done
for f in shots/*.png; do [ -f "$f" ] || continue; convert "$f" -resize 45% "$f.small.png" 2>/dev/null || cp "$f" "$f.small.png"; echo "=== SHOT $f $(base64 -w0 "$f.small.png")"; done
exit 0
