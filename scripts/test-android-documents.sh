#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
adb="$sdk/platform-tools/adb"
if [ ! -x "$adb" ]; then
    echo "Set ANDROID_HOME to the Android SDK containing adb." >&2
    exit 1
fi
serial="${ANDROID_SERIAL:-}"
if [ -z "$serial" ]; then
    serials=$("$adb" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    count=$(printf '%s\n' "$serials" | awk 'NF { n++ } END { print n+0 }')
    if [ "$count" != 1 ]; then
        echo "Connect one test device or set ANDROID_SERIAL." >&2
        exit 1
    fi
    serial="$serials"
fi
variant="${ANDROID_BUILD_TYPE:-debug}"
case "$variant" in
    debug|e2e) ;;
    *) echo "ANDROID_BUILD_TYPE must be debug or e2e." >&2; exit 1 ;;
esac
app="androidApp/build/outputs/apk/$variant/androidApp-$variant.apk"
tests="androidApp/build/outputs/apk/androidTest/$variant/androidApp-$variant-androidTest.apk"
application_id=$(python3 - <<'PY'
from pathlib import Path
path = Path('app.properties')
values = dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line and not line.lstrip().startswith('#')) if path.exists() else {}
print(values.get('app.androidId', 'dev.logisim.mobile').strip())
PY
)
if [ "$variant" = e2e ]; then
    application_id="$application_id.e2e"
fi
if [ ! -f "$app" ] || [ ! -f "$tests" ]; then
    echo "Build the $variant application and Android test APKs first." >&2
    exit 1
fi
"$adb" -s "$serial" install -r "$app"
"$adb" -s "$serial" install -r "$tests"
mkdir -p outputs/android-documents
result="outputs/android-documents/instrumentation.txt"
if ! "$adb" -s "$serial" shell am instrument -w -r \
    "$application_id.test/dev.logisim.mobile.documents.DocumentIntegrationInstrumentation" > "$result" 2>&1; then
    cat "$result"
    exit 1
fi
cat "$result"
if ! rg -q '^DOCUMENT_TESTS_PASSED=13$' "$result"; then
    echo "Document provider tests did not report all thirteen passes." >&2
    exit 1
fi
if ! rg -q 'failures=0' "$result"; then
    echo "Document provider tests reported a failure." >&2
    exit 1
fi
