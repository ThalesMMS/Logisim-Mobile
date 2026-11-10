#!/bin/sh
set -eu
cd "$(dirname "$0")/.."

target="${1:-all}"
case "$target" in
    android|ios|ios-intel|all) ;;
    *) echo "Uso: $0 [android|ios|ios-intel|all]" >&2; exit 2 ;;
esac

if [ "$(uname -s)" = Darwin ]; then
    . scripts/java-home.sh
fi
python3 scripts/check-source.py
python3 scripts/generate-localization.py --check
python3 scripts/test-localization.py
python3 tools/generate_app_identity.py --check
python3 tools/generate_app_icons.py --check
python3 -m unittest discover -s tools/tests -p 'test_*.py'
python3 scripts/generate_examples.py --check
python3 scripts/generate-temporal-fixtures.py --check

if [ "$target" = android ] || [ "$target" = all ]; then
    ./gradlew :core:jvmTest :composeApp:testAndroidHostTest :androidApp:assembleDebug --console=plain --stacktrace
fi
if [ "$target" = ios ] || [ "$target" = ios-intel ] || { [ "$target" = all ] && [ "$(uname -s)" = Darwin ]; }; then
    if [ "$(uname -s)" != Darwin ]; then
        echo "A verificação iOS exige macOS e Xcode." >&2
        exit 1
    fi
    if ! command -v xcodegen >/dev/null; then
        echo "Instale XcodeGen 2.46.0 para verificar o host iOS." >&2
        exit 1
    fi
    if [ "$target" = ios-intel ]; then
        if [ "$(uname -m)" != x86_64 ]; then
            echo "ios-intel exige um host macOS x86_64 para executar os testes macosX64." >&2
            exit 1
        fi
        ./gradlew -PenableMacosX64Ci=true :core:macosX64Test :composeApp:linkDebugFrameworkIosSimulatorArm64 --console=plain
    else
        if [ "$(uname -m)" != arm64 ]; then
            echo "ios exige Apple Silicon para executar iosSimulatorArm64Test. Use ios-intel para o fallback documentado." >&2
            exit 1
        fi
        ios_test_device=$(python3 scripts/ci-boot-ios-simulator.py --diagnostics outputs/ci/ios-simulator.json)
        ./gradlew "-PiosTestDevice=$ios_test_device" :core:iosSimulatorArm64Test :composeApp:iosSimulatorArm64Test :composeApp:linkDebugFrameworkIosSimulatorArm64 --console=plain
    fi
    xcodegen generate --spec iosApp/project.yml
    mkdir -p outputs/ci
    result_bundle="outputs/ci/ios-build-$(date -u +%Y%m%dT%H%M%SZ).xcresult"
    xcodebuild -project iosApp/LogisimMobile.xcodeproj -scheme LogisimMobile \
        -configuration Debug -destination 'generic/platform=iOS Simulator' \
        -derivedDataPath outputs/ci/ios-derived -resultBundlePath "$result_bundle" \
        CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build
fi
