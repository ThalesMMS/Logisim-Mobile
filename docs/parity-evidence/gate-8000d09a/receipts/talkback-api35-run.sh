. /tmp/g367/env.sh; cd $WT; . scripts/java-home.sh
A="adb -s emulator-5556"
nohup emulator -avd logisim35play -port 5556 -grpc 8556 -gpu host -no-boot-anim -no-audio -no-snapshot-load > $G/emu-tb35.log 2>&1 &
EP=$!
perl -e 'alarm 300; exec @ARGV' sh /tmp/g243/boot-wait.sh emulator-5556 || { echo BOOT_TIMEOUT; kill $EP; exit 5; }
sleep 25
echo "api=$($A shell getprop ro.build.version.sdk)"; $A shell dumpsys package com.google.android.marvin.talkback | grep -m1 versionName
$A install -r -t $G/apk/androidApp-e2e.apk | tail -1
O=$G/android/tb35; mkdir -p $O
SVC=com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService
run() { name=$1; flow=$2
  $A shell pm clear thalesmms.logisim.mobile.e2e
  $A shell settings put secure enabled_accessibility_services $SVC
  $A shell settings put secure accessibility_enabled 1; $A shell settings put secure touch_exploration_enabled 1
  sleep 8
  $A shell am start -W -n thalesmms.logisim.mobile.e2e/dev.logisim.mobile.MainActivity | tail -1
  sleep 6
  perl -e 'alarm 900; exec @ARGV' /tmp/tbvenv/bin/python $flow 5556 8556 $O/$name > $O/$name.log 2>&1; echo "$name rc=$?"
  tail -30 $O/$name.log | cut -c1-200
  $A shell settings put secure enabled_accessibility_services null; sleep 2
}
run base $G/flows/talkback-flow.py
run f365 $G/flows/talkback-flow-365.py
$A shell settings put secure accessibility_enabled 0; $A shell settings put secure touch_exploration_enabled 0
$A emu kill; sleep 6; kill $EP 2>/dev/null
echo TB35_DONE
