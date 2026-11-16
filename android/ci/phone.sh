#!/bin/bash
# Runs on the virtual phone (GitHub). 1) all app tests  2) the screen tests again with big text on a small screen
# 3) 3000 random taps with the internet OFF (no real store is ever reached)  4) prints the notes and screenshots.
set -u
P=com.restalker.mobile
fail=0
adb install -r -g apk/outputs/apk/debug/app-debug.apk
adb install -r -g apk/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e debug false $P.test/androidx.test.runner.AndroidJUnitRunner > instrument.txt 2>&1
grep -E "^OK|FAILURES|Tests run" instrument.txt
echo "--- failures (name + reason) ---"
awk '/INSTRUMENTATION_STATUS: stack=/{s=$0; getline n; s=s" | "n} /INSTRUMENTATION_STATUS: test=/{t=$0} /INSTRUMENTATION_STATUS_CODE: -2/{print t" || "s}' instrument.txt | cut -c1-600
sed -n '/^There w/,/^FAILURES/p' instrument.txt | head -60
grep -q "^OK (" instrument.txt && ! grep -q "FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed" instrument.txt || fail=1

echo "=== big text, small screen"
adb shell settings put system font_scale 1.3; adb shell wm size 720x1280; adb shell wm density 320; sleep 3
adb shell am instrument -w -r -e debug false -e class $P.ScreenTests -e shotTag _big $P.test/androidx.test.runner.AndroidJUnitRunner > instrument_big.txt 2>&1
grep -E "^OK|FAILURES|Tests run|AssertionError" instrument_big.txt | tail -10
grep -q "^OK (" instrument_big.txt || fail=1
adb shell settings put system font_scale 1.0; adb shell wm size reset; adb shell wm density reset

echo "=== random taps with the internet off"
adb shell svc wifi disable; adb shell svc data disable; adb shell cmd connectivity airplane-mode enable 2>/dev/null || true; sleep 5
if adb shell ping -c 1 -W 3 8.8.8.8 >/dev/null 2>&1; then
  echo "internet still on - random taps skipped (no real store may be reached)"
else
  adb shell monkey -p $P --pct-syskeys 0 --pct-appswitch 5 --throttle 120 -s 4242 -v 3000 > monkey.txt 2>&1
  tail -5 monkey.txt
  if grep -q "CRASH\|ANR in\|Monkey aborted" monkey.txt; then echo "RANDOM TAPS FOUND A CRASH"; grep -A30 "CRASH\|ANR in" monkey.txt | head -60; fail=1; else echo "random taps: no crash, no freeze"; fi
fi
adb shell cmd connectivity airplane-mode disable 2>/dev/null || true; adb shell svc wifi enable; adb shell svc data enable

adb logcat -d > logcat.txt || true
grep -E "FATAL EXCEPTION|AndroidRuntime" -A12 logcat.txt | head -60
bash android/ci/evidence.sh || true
exit $fail
