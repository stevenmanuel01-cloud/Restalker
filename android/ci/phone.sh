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

echo "=== the phone's reports, read back from outside (the way Claude reads them)"
code=$(adb exec-out run-as $P cat files/shots/t14_report.txt 2>/dev/null | sed -n 's/^code \(rkr-[a-z0-9]\{20\}\).*/\1/p' | head -1)
if [ -z "$code" ]; then echo "no report code from the test"; fail=1; else
  got=""; for i in 1 2 3 4 5 6; do got=$(curl -s -A "Restalker-CI" "https://ntfy.sh/$code/json?poll=1&since=all" || true)
    echo "$got" | grep -q '"sent by you' && echo "$got" | grep -q 'automatic: Sign in to Amazon' && break; sleep 5; done
  if echo "$got" | grep -q '"sent by you' && echo "$got" | grep -q 'automatic: Sign in to Amazon'; then
    url=$(echo "$got" | grep 'automatic: Sign in' | grep -o '"url":"[^"]*"' | tail -1 | cut -d'"' -f4)
    body=$(curl -s -A "Restalker-CI" "$url" || true)
    if echo "$body" | grep -q "Restalker Mobile v" && echo "$body" | grep -q "Amazon asked to sign in"; then echo "reports read back: OK (button + automatic, file readable)"
    else echo "REPORT FILE NOT READABLE"; fail=1; fi
  else echo "REPORTS NOT FOUND ON THE REPORT SERVER"; echo "$got" | cut -c1-300; fail=1; fi
fi

echo "=== big text, small screen"
adb shell settings put system font_scale 1.3; adb shell wm size 720x1280; adb shell wm density 320; sleep 3
adb shell am instrument -w -r -e debug false -e class $P.ScreenTests -e shotTag _big $P.test/androidx.test.runner.AndroidJUnitRunner > instrument_big.txt 2>&1
grep -E "^OK|FAILURES|Tests run" instrument_big.txt | tail -3
awk '/INSTRUMENTATION_STATUS: stack=/{s=$0; getline n; s=s" | "n} /INSTRUMENTATION_STATUS: test=/{t=$0} /INSTRUMENTATION_STATUS_CODE: -2/{print t" || "s}' instrument_big.txt | cut -c1-600
grep -E "ScreenTests.kt:[0-9]+" instrument_big.txt | head -10
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
