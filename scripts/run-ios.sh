#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if ! command -v xcodegen >/dev/null; then
    echo "Instale XcodeGen para gerar o projeto definido em iosApp/project.yml." >&2
    exit 1
fi
. scripts/java-home.sh
simulator_id="${1:-$(xcrun simctl list devices available -j | python3 -c 'import json,sys; d=json.load(sys.stdin); ds=[v for k,vs in d["devices"].items() if "iOS" in k for v in vs]; preferred=next((v for v in ds if "iPad" in v["name"]), ds[0] if ds else None); print(preferred["udid"] if preferred else "")')}"
if [ -z "$simulator_id" ]; then
    echo "Instale um runtime iOS Simulator pelo Xcode." >&2
    exit 1
fi
python3 tools/generate_app_identity.py --check
python3 tools/generate_app_icons.py --check
xcodegen generate --spec iosApp/project.yml
xcodebuild -project iosApp/LogisimMobile.xcodeproj -scheme LogisimMobile \
    -configuration Debug -destination "platform=iOS Simulator,id=$simulator_id" \
    -derivedDataPath iosApp/DerivedData CODE_SIGNING_ALLOWED=NO build
device_state=$(xcrun simctl list devices -j | python3 -c 'import json,sys; ds=[v for vs in json.load(sys.stdin)["devices"].values() for v in vs]; print(next(v["state"] for v in ds if v["udid"]==sys.argv[1]))' "$simulator_id")
if [ "$device_state" != Booted ]; then xcrun simctl boot "$simulator_id"; fi
xcrun simctl bootstatus "$simulator_id" -b
xcrun simctl install "$simulator_id" iosApp/DerivedData/Build/Products/Debug-iphonesimulator/LogisimMobile.app
xcrun simctl launch "$simulator_id" thalesmms.logisim.mobile
simulator_app="$(xcode-select -p)/Applications/Simulator.app"
if [ -d "$simulator_app" ]; then open -a "$simulator_app"; fi
