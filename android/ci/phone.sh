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

echo "=== Amazon's real picture server, from the phone (t18)"
adb exec-out run-as $P cat files/shots/t18_amazon_pictures.txt 2>/dev/null || echo "(no t18 result)"
echo

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

echo "=== the app updates itself (1.7)"
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
vc() { adb shell dumpsys package $P | grep -m1 -o 'versionCode=[0-9]*' | cut -d= -f2; }
put() { adb push "$1" /data/local/tmp/u.apk >/dev/null && adb shell chmod 644 /data/local/tmp/u.apk && adb shell run-as $P cp /data/local/tmp/u.apk cache/u.apk; }
trig() { adb shell am start -n $P/.ui.MainActivity >/dev/null 2>&1; sleep 3; adb shell am broadcast -n $P/.UpdateTestReceiver --es apk cache/u.apk --es sha "$1" --es version "$2" >/dev/null; }
waitvc() { for i in $(seq 1 30); do [ "$(vc)" = "$1" ] && return 0; sleep 2; done; return 1; }
if [ ! -f apk/next/next1.apk ] || [ ! -f apk/next/next2.apk ]; then echo "NO NEWER TEST COPIES BUILT"; fail=1; else
  v0=$(vc); s1=$(sha256sum apk/next/next1.apk | cut -d' ' -f1); s2=$(sha256sum apk/next/next2.apk | cut -d' ' -f1)
  adb shell appops set $P REQUEST_INSTALL_PACKAGES allow
  # A) a file that is not the released one: refused
  put apk/next/next1.apk; trig 0000000000000000000000000000000000000000000000000000000000000000 9.9; sleep 12
  if [ "$(vc)" = "$v0" ]; then echo "wrong file refused: OK"; else echo "WRONG FILE WAS INSTALLED"; fail=1; fi
  # B) the first update by the app itself (the app was installed by something else - your browser / files):
  #    Android 12+ (API 31+) installs it without asking; Android 11 and older ask once - a notice to tap
  put apk/next/next1.apk; trig $s1 1.7.1
  if [ "$api" -ge 31 ]; then
    if waitvc $((v0+1)); then echo "updated by itself, no question: OK (now $(vc))"
    else echo "DID NOT UPDATE BY ITSELF on API $api"; adb shell dumpsys notification --noredact | grep -A1 "Restalker update" | head -4; fail=1; fi
    # C) and the next one too
    adb shell appops set $P REQUEST_INSTALL_PACKAGES allow
    put apk/next/next2.apk; trig $s2 1.7.2
    if waitvc $((v0+2)); then echo "next update by itself too: OK (now $(vc))"; else echo "SECOND SELF-UPDATE FAILED"; fail=1; fi
  else
    sleep 15
    if adb shell dumpsys notification --noredact | grep -q "Restalker update"; then echo "Android $api asks you once (tap Install): OK"
    else echo "NO 'TAP TO INSTALL' NOTICE ON API $api"; fail=1; fi
  fi
  adb shell dumpsys package $P | grep -m1 -i "installerPackageName"
fi

if adb shell run-as $P test -s files/crash.txt 2>/dev/null; then echo "THE APP WROTE A CRASH:"; adb exec-out run-as $P cat files/crash.txt | head -30; fail=1; fi
adb logcat -d > logcat.txt || true
grep -E "FATAL EXCEPTION|AndroidRuntime" -A12 logcat.txt | head -60
bash android/ci/evidence.sh || true
exit $fail
