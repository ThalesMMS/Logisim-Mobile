#!/bin/sh
# usage: run31.sh OUTDIR
A=/tmp/g363/a; O=$1
D=/data/user_de/0/com.android.talkback/shared_prefs; F=$D/com.android.talkback_preferences.xml
$A shell settings put secure enabled_accessibility_services null; sleep 2
$A shell am force-stop com.android.talkback
$A shell am force-stop thalesmms.logisim.mobile.e2e
$A shell pm clear thalesmms.logisim.mobile.e2e
$A shell pm grant com.android.talkback android.permission.READ_PHONE_STATE
$A shell "run-as com.android.talkback sh -c 'sed -i -E \"/name=\\\"(first_time_user|has_training_exit|pref_update_welcome_14_1_shown_key|pref_update_multi_finger_gestures_shown_key)\\\"/d\" $F; sed -i \"s#</map>#<boolean name=\\\"first_time_user\\\" value=\\\"false\\\"/><boolean name=\\\"has_training_exit\\\" value=\\\"true\\\"/><boolean name=\\\"pref_update_welcome_14_1_shown_key\\\" value=\\\"true\\\"/><boolean name=\\\"pref_update_multi_finger_gestures_shown_key\\\" value=\\\"true\\\"/></map>#\" $F; grep -E \"first_time_user|has_training_exit|14_1_shown|multi_finger_gestures_shown\" $F'"
$A logcat -c
$A shell settings put secure enabled_accessibility_services com.android.talkback/com.google.android.marvin.talkback.TalkBackService
$A shell settings put secure accessibility_enabled 1
$A shell settings put secure touch_exploration_enabled 1
sleep 8
$A shell am start -W -n thalesmms.logisim.mobile.e2e/dev.logisim.mobile.MainActivity | tail -1
sleep 6
echo "tutorial starts: $($A logcat -d | grep -c 'START u0.*TrainingActivity')"
perl -e 'alarm 900; exec @ARGV' /tmp/tbvenv/bin/python -I /tmp/g363/flow.py 5562 8562 $O > $O.log 2>&1; echo flow_rc=$?
cat $O.log | tail -20
echo "tutorial starts end: $($A logcat -d | grep -c 'START u0.*TrainingActivity')"
$A logcat -d > $O.logcat.txt
$A shell settings put secure enabled_accessibility_services null; $A shell settings put secure accessibility_enabled 0; $A shell settings put secure touch_exploration_enabled 0
