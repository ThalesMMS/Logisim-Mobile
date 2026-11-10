. /tmp/g367/env.sh; cd $WT; . scripts/java-home.sh
A=/tmp/g363/a
nohup emulator -avd logisim31play -port 5562 -grpc 8562 -grpc-use-token -gpu host -no-boot-anim -no-audio -no-snapshot-load > $G/emu-tb31.log 2>&1 &
EP=$!
perl -e 'alarm 300; exec @ARGV' sh /tmp/g243/boot-wait.sh emulator-5562 || { echo BOOT_TIMEOUT; kill $EP; exit 5; }
sleep 25
echo "api=$($A shell getprop ro.build.version.sdk)"; $A shell dumpsys package com.android.talkback | grep -m1 versionName
$A install -r -t $G/apk/androidApp-e2e.apk | tail -1
O=$G/android/tb31; mkdir -p $O
D=/data/user_de/0/com.android.talkback/shared_prefs; F=$D/com.android.talkback_preferences.xml
SVC=com.android.talkback/com.google.android.marvin.talkback.TalkBackService
run() { name=$1; flow=$2
  $A shell settings put secure enabled_accessibility_services null; sleep 2
  $A shell am force-stop com.android.talkback
  $A shell am force-stop thalesmms.logisim.mobile.e2e
  $A shell pm clear thalesmms.logisim.mobile.e2e
  $A shell pm grant com.android.talkback android.permission.READ_PHONE_STATE
  $A shell "run-as com.android.talkback sh -c 'sed -i -E \"/name=\\\"(first_time_user|has_training_exit|pref_update_welcome_14_1_shown_key|pref_update_multi_finger_gestures_shown_key)\\\"/d\" $F; sed -i \"s#</map>#<boolean name=\\\"first_time_user\\\" value=\\\"false\\\"/><boolean name=\\\"has_training_exit\\\" value=\\\"true\\\"/><boolean name=\\\"pref_update_welcome_14_1_shown_key\\\" value=\\\"true\\\"/><boolean name=\\\"pref_update_multi_finger_gestures_shown_key\\\" value=\\\"true\\\"/></map>#\" $F; grep -E \"first_time_user|has_training_exit|14_1_shown|multi_finger_gestures_shown\" $F'"
  $A logcat -c
  $A shell settings put secure enabled_accessibility_services $SVC
  $A shell settings put secure accessibility_enabled 1
  $A shell settings put secure touch_exploration_enabled 1
  sleep 8
  $A shell am start -W -n thalesmms.logisim.mobile.e2e/dev.logisim.mobile.MainActivity | tail -1
  sleep 6
  echo "$name tutorial starts: $($A logcat -d | grep -c 'START u0.*TrainingActivity')"
  perl -e 'alarm 900; exec @ARGV' /tmp/tbvenv/bin/python -I $flow 5562 8562 $O/$name > $O/$name.log 2>&1; echo "$name rc=$?"
  tail -30 $O/$name.log | cut -c1-200
  echo "$name tutorial starts end: $($A logcat -d | grep -c 'START u0.*TrainingActivity')"
  $A shell settings put secure enabled_accessibility_services null; sleep 2
}
run base $G/flows/talkback-flow.py
run f365 $G/flows/talkback-flow-365.py
$A shell settings put secure accessibility_enabled 0; $A shell settings put secure touch_exploration_enabled 0
$A emu kill; sleep 6; kill $EP 2>/dev/null
echo TB31_DONE
