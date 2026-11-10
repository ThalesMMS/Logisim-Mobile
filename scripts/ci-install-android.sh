#!/bin/sh
set -eu

android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$android_sdk" ] || [ ! -d "$android_sdk" ]; then
    echo "Set ANDROID_HOME to the Android SDK directory." >&2
    exit 1
fi

sdkmanager="$android_sdk/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$sdkmanager" ]; then
    for candidate in "$android_sdk"/cmdline-tools/*/bin/sdkmanager; do
        if [ -x "$candidate" ]; then
            sdkmanager="$candidate"
            break
        fi
    done
fi
if [ ! -x "$sdkmanager" ]; then
    echo "Android command-line tools are missing from $android_sdk/cmdline-tools." >&2
    exit 1
fi

exec "$sdkmanager" --sdk_root="$android_sdk" 'platforms;android-37.0' 'platforms;android-37.1' 'build-tools;36.0.0'
