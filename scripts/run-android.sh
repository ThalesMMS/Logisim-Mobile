#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
adb="$android_sdk/platform-tools/adb"
if [ ! -x "$adb" ]; then
    echo "Defina ANDROID_HOME para o Android SDK instalado." >&2
    exit 1
fi
"$adb" get-state >/dev/null
./gradlew :androidApp:assembleDebug --console=plain
"$adb" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
"$adb" shell am start -n thalesmms.logisim.mobile/dev.logisim.mobile.MainActivity
