#!/bin/bash
# Runs on the virtual phone (GitHub). 1) all app tests  2) the screen tests again with big text on a small screen
# 3) 3000 random taps with the internet OFF (no real store is ever reached)  4) prints the notes and screenshots.
set -u
P=com.restalker.mobile
fail=0
up() {   # the virtual phone back and ready (it can drop off for a moment after a screen-size change)
  for i in $(seq 1 3); do
    timeout 150 adb wait-for-device && for k in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && return 0; sleep 2; done
    echo "(virtual phone not answering - reconnecting)"; adb reconnect offline >/dev/null 2>&1; sleep 5
  done
  echo "THE VIRTUAL PHONE STOPPED ANSWERING"; return 1
}
adb install -r -g apk/outputs/apk/debug/app-debug.apk
adb install -r -g apk/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 2.6: the phone's own log while the app tests run (when the virtual phone dies at the end, its last words are shown)
adb logcat -c 2>/dev/null || true
(adb logcat -v time > live_logcat.txt 2>/dev/null &)
# 2.6: the two groups of app tests run one after the other (the phone and link tests, then the screen tests) with a fresh app in
# between - one long run made the API 33 / 34 virtual phones stop answering near its end
adb shell am instrument -w -r -e debug false -e class $P.PhoneTests $P.test/androidx.test.runner.AndroidJUnitRunner > instrument_a.txt 2>&1
adb shell am force-stop $P 2>/dev/null || true; sleep 3
adb shell am instrument -w -r -e debug false -e class $P.ScreenTests $P.test/androidx.test.runner.AndroidJUnitRunner > instrument_b.txt 2>&1
grep -E "^OK|FAILURES|Tests run" instrument_a.txt | sed 's/^/phone+link tests: /'
grep -E "^OK|FAILURES|Tests run" instrument_b.txt | sed 's/^/screen tests: /'
# (one file for the checks below: both must say OK)
if grep -q "^OK (" instrument_a.txt && grep -q "^OK (" instrument_b.txt; then cat instrument_a.txt instrument_b.txt | grep -v "^OK (" > instrument.txt; echo "OK (both groups)" >> instrument.txt
else cat instrument_a.txt instrument_b.txt > instrument.txt; grep -q "^OK (" instrument_a.txt || grep -q "FAILURES" instrument_a.txt || echo "INSTRUMENTATION_FAILED: phone+link tests did not finish" >> instrument.txt
  grep -q "^OK (" instrument_b.txt || grep -q "FAILURES" instrument_b.txt || echo "INSTRUMENTATION_FAILED: screen tests did not finish" >> instrument.txt; fi
echo "--- failures (name + reason) ---"
awk '/INSTRUMENTATION_STATUS: stack=/{s=$0; getline n; s=s" | "n} /INSTRUMENTATION_STATUS: test=/{t=$0} /INSTRUMENTATION_STATUS_CODE: -2/{print t" || "s}' instrument.txt | cut -c1-600
sed -n '/^There w/,/^FAILURES/p' instrument.txt | head -60
# 2.1: a test that failed is run once more on its own; it passes only if it passes then (shown as SECOND TRY - Claude reads these and
# fixes the test's timing). A real fault fails both times. usage: judge <instrument output> [extra instrument args]
judge() {
  local out=$1; shift
  if grep -q "^OK (" "$out" && ! grep -q "FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed" "$out"; then return 0; fi
  if ! grep -q "FAILURES!!!" "$out" || grep -q "INSTRUMENTATION_FAILED\|Process crashed" "$out"; then return 1; fi
  local failed t bad=0
  failed=$(awk '/INSTRUMENTATION_STATUS: class=/{c=substr($0, index($0,"=")+1)} /INSTRUMENTATION_STATUS: test=/{t=substr($0, index($0,"=")+1)} /INSTRUMENTATION_STATUS_CODE: -2/{print c"#"t}' "$out" | sort -u)
  [ -n "$failed" ] || return 1
  for t in $failed; do
    adb shell am instrument -w -r -e debug false -e class "$t" "$@" $P.test/androidx.test.runner.AndroidJUnitRunner > retry.txt 2>&1
    if grep -q "^OK (" retry.txt; then echo "SECOND TRY passed: $t"; else echo "FAILED TWICE: $t"; bad=1; fi
  done
  return $bad
}
judge instrument.txt || fail=1
alive() { timeout 20 adb shell true >/dev/null 2>&1; }
finish() { timeout 300 bash android/ci/evidence.sh || true; exit $fail; }
if ! alive && ! up; then echo "THE VIRTUAL PHONE DROPPED OFF RIGHT AFTER THE APP TESTS (nothing more can run on it)"; fail=1
  echo "--- how far the tests got"; grep -o "INSTRUMENTATION_STATUS: test=[a-zA-Z0-9_]*" instrument.txt | tail -4
  echo "--- the phone's last words"; grep -E "FATAL|ANR in|Watchdog|lowmemorykiller|Low on memory|Killing|system_server|E/AndroidRuntime" live_logcat.txt | tail -30
  tail -40 live_logcat.txt | cut -c1-220
  finish; fi

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

echo "=== random taps with the internet off"
adb shell svc wifi disable; adb shell svc data disable; adb shell cmd connectivity airplane-mode enable 2>/dev/null || true; sleep 5
if adb shell ping -c 1 -W 3 8.8.8.8 >/dev/null 2>&1; then
  echo "internet still on - random taps skipped (no real store may be reached)"
else
  adb shell monkey -p $P --pct-syskeys 0 --pct-appswitch 5 --throttle 120 -s 4242 -v 3000 > monkey.txt 2>&1
  tail -5 monkey.txt
  # 2.1: once on API 33 the virtual phone's own system restarted (graphics errors, no app crash) at tap 2208 - then the same 3000 taps
  # run once more; a restart at the same tap again, or any app crash, still fails.
  if ! grep -q "Monkey finished" monkey.txt && grep -q "System appears to have crashed" monkey.txt && ! grep -q "CRASH\|ANR in" monkey.txt \
     && ! adb shell run-as $P test -s files/crash.txt 2>/dev/null; then
    first=$(grep -o "crashed at event [0-9]*" monkey.txt)
    echo "(the virtual phone's own system restarted during random taps - $first; no app crash - the same taps once more)"
    up; adb shell svc wifi disable; adb shell svc data disable; adb shell cmd connectivity airplane-mode enable 2>/dev/null || true; sleep 5
    adb shell monkey -p $P --pct-syskeys 0 --pct-appswitch 5 --throttle 120 -s 4242 -v 3000 > monkey.txt 2>&1
    tail -3 monkey.txt
    if grep -q "$first" monkey.txt; then echo "THE SYSTEM RESTARTED AT THE SAME TAP AGAIN"; fi
  fi
  if ! grep -q "Monkey finished" monkey.txt; then echo "RANDOM TAPS DID NOT FINISH (the virtual phone dropped off?)"; fail=1
    # 2.3: show why (an app crash / freeze, or the phone's own system) - the evidence file is not readable from outside the run
    grep -n -B3 -A25 "CRASH\|ANR in\|System appears\|Monkey aborted" monkey.txt | head -80
    adb shell run-as $P cat files/crash.txt 2>/dev/null | head -20 | sed 's/^/crash.txt: /'
  elif grep -q "CRASH\|ANR in\|Monkey aborted" monkey.txt; then echo "RANDOM TAPS FOUND A CRASH"; grep -A30 "CRASH\|ANR in" monkey.txt | head -60; fail=1; else echo "random taps: no crash, no freeze"; fi
fi
adb shell cmd connectivity airplane-mode disable 2>/dev/null || true; adb shell svc wifi enable; adb shell svc data enable

echo "=== the app updates itself (1.7)"
if ! up; then fail=1; finish; fi
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
vc() { adb shell dumpsys package $P | grep -m1 -o 'versionCode=[0-9]*' | cut -d= -f2; }
put() { adb push "$1" /data/local/tmp/u.apk >/dev/null && adb shell chmod 644 /data/local/tmp/u.apk && adb shell run-as $P cp /data/local/tmp/u.apk cache/u.apk; }
trig() { adb shell am start -n $P/.ui.MainActivity >/dev/null 2>&1; sleep 3; adb shell am broadcast -n $P/.UpdateTestReceiver --es apk cache/u.apk --es sha "$1" --es version "$2" >/dev/null; }
waitvc() { for i in $(seq 1 30); do [ "$(vc)" = "$1" ] && return 0; sleep 2; done; return 1; }
if [ ! -f apk/next/next1.apk ] || [ ! -f apk/next/next2.apk ]; then echo "NO NEWER TEST COPIES BUILT"; fail=1; else
  v0=$(vc); [ -n "$v0" ] || { echo "VERSION NOT READABLE"; fail=1; }; s1=$(sha256sum apk/next/next1.apk | cut -d' ' -f1); s2=$(sha256sum apk/next/next2.apk | cut -d' ' -f1)
  adb shell appops set $P REQUEST_INSTALL_PACKAGES allow
  # A) a file that is not the released one: refused
  put apk/next/next1.apk; trig 0000000000000000000000000000000000000000000000000000000000000000 9.9; sleep 12
  if [ -n "$v0" ] && [ "$(vc)" = "$v0" ]; then echo "wrong file refused: OK"; else echo "WRONG FILE WAS INSTALLED"; fail=1; fi
  # B) the first update by the app itself (the app was installed by something else - your browser / files):
  #    Android 12+ (API 31+) installs it without asking; Android 11 and older ask once - a notice to tap
  put apk/next/next1.apk; trig $s1 1.7.1
  if [ "$api" -ge 31 ]; then
    if waitvc $((v0+1)); then echo "updated by itself, no question: OK (now $(vc))"
    else echo "DID NOT UPDATE BY ITSELF on API $api"; adb shell dumpsys notification --noredact | grep -A1 "Restalker update" | head -4; fail=1; fi
    # C) and the next one too (Android allows one silent update a minute at most - real updates are hours apart)
    sleep 65; adb shell appops set $P REQUEST_INSTALL_PACKAGES allow
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
timeout 60 adb logcat -d > logcat.txt || true
grep -E "FATAL EXCEPTION|AndroidRuntime" -A12 logcat.txt | head -60
# (last: changing the screen size has made the virtual phone drop off afterwards - nothing after this needs it)
echo "=== big text, small screen"
adb shell settings put system font_scale 1.3; adb shell wm size 720x1280; adb shell wm density 320; sleep 5
if ! up; then echo "(the virtual phone dropped off after the screen change - big-text run not possible on this one; it ran on the others)"
else
adb shell am instrument -w -r -e debug false -e class $P.ScreenTests -e shotTag _big $P.test/androidx.test.runner.AndroidJUnitRunner > instrument_big.txt 2>&1
grep -E "^OK|FAILURES|Tests run" instrument_big.txt | tail -3
awk '/INSTRUMENTATION_STATUS: stack=/{s=$0; getline n; s=s" | "n} /INSTRUMENTATION_STATUS: test=/{t=$0} /INSTRUMENTATION_STATUS_CODE: -2/{print t" || "s}' instrument_big.txt | cut -c1-600
grep -E "ScreenTests.kt:[0-9]+" instrument_big.txt | head -10
# 2.4: what the screen held when a big-text test could not find something (the first failure in full)
grep -m1 -A60 "Semantics of the node" instrument_big.txt | grep -v "^\s*at " | head -60
if grep -q "^OK (" instrument_big.txt; then :; elif grep -q "device offline\|not found\|Process crashed" instrument_big.txt && ! adb shell true 2>/dev/null; then echo "(the virtual phone dropped off during the big-text run)"; else judge instrument_big.txt -e shotTag _big || fail=1; fi
fi

finish
