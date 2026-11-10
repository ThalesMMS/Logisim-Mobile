#!/usr/bin/env bash
# Compares Buzzer, Digital Oscilloscope, RGB Video, RealTimeClock and SocVga with Evolution.
# Usage: run.sh /path/to/logisim-evolution-all.jar [mobile-core.jar] [section...]
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../.." && pwd)"
desktop_jar="${1:?Usage: run.sh /path/to/logisim-evolution-all.jar [mobile-core.jar] [section...]}"
shift
core_jar="$repo_root/core/build/libs/core-jvm.jar"
if [[ $# -gt 0 && "$1" == *.jar ]]; then core_jar="$1"; shift; fi

for artifact in "$desktop_jar" "$core_jar"; do
    if [[ ! -f "$artifact" ]]; then
        printf 'Missing JAR: %s\nBuild the desktop fat JAR and run ./gradlew :core:jvmJar first.\n' "$artifact" >&2
        exit 1
    fi
done

kotlin_stdlib="${KOTLIN_STDLIB_JAR:-}"
if [[ -z "$kotlin_stdlib" ]]; then
    kotlin_stdlib="$(python3 - <<'PY'
import os
from pathlib import Path

gradle_cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
candidates = list((gradle_cache / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib').glob('*/*/kotlin-stdlib-*.jar'))
candidates = [path for path in candidates if not path.name.endswith(('-sources.jar', '-javadoc.jar'))]
def version(path):
    return tuple(int(part) if part.isdigit() else 0 for part in path.parents[1].name.split('.'))
print(max(candidates, key=version) if candidates else '')
PY
)"
fi
if [[ ! -f "$kotlin_stdlib" ]]; then
    printf 'Kotlin standard library not found. Set KOTLIN_STDLIB_JAR to its JAR path.\n' >&2
    exit 1
fi

java_bin=java javac_bin=javac
if [[ -n "${JAVA_HOME:-}" ]]; then java_bin="$JAVA_HOME/bin/java"; javac_bin="$JAVA_HOME/bin/javac"; fi
work="$(mktemp -d "${TMPDIR:-/tmp}/output-devices-oracle.XXXXXX")"
trap 'rm -rf "$work"' EXIT
classpath="$desktop_jar:$core_jar:$kotlin_stdlib"
"$javac_bin" -nowarn -cp "$classpath" -d "$work" "$script_dir"/*.java
# Evolution's Buzzer plays through javax.sound; this service records its clips instead.
mkdir -p "$work/META-INF/services"
printf 'OracleMixerProvider\n' > "$work/META-INF/services/javax.sound.sampled.spi.MixerProvider"

printf 'Desktop JAR: %s\nMobile JAR: %s\n' "$desktop_jar" "$core_jar"
shasum -a 256 "$desktop_jar" "$core_jar"
# The SoC bus builds a Swing dialog, so the oracle needs a graphics environment (not headless).
"$java_bin" -Djavax.sound.sampled.Clip=OracleMixerProvider -cp "$work:$classpath" OutputDevicesOracle "$@"
