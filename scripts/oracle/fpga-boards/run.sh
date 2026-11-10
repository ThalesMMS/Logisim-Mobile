#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../.." && pwd)"
desktop_jar="${1:?Usage: run.sh /path/to/logisim-evolution-all.jar [mobile-core.jar [board.xml...]]}"
core_jar="${2:-$repo_root/core/build/libs/core-jvm.jar}"
fixtures=(
    "$repo_root/core/src/jvmTest/resources/fpga/GECKO5_EDUCATION.xml"
    "$repo_root/core/src/jvmTest/resources/fpga/BASYS3.xml"
)
if (( $# > 2 )); then fixtures=("${@:3}"); fi

for artifact in "$desktop_jar" "$core_jar" "${fixtures[@]}"; do
    if [[ ! -f "$artifact" ]]; then
        printf 'Missing artifact: %s\nBuild the desktop fat JAR and run ./gradlew :core:jvmJar first.\n' "$artifact" >&2
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

java_bin=java
if [[ -n "${JAVA_HOME:-}" ]]; then java_bin="$JAVA_HOME/bin/java"; fi
printf 'Desktop JAR: %s\nMobile JAR: %s\n' "$desktop_jar" "$core_jar"
shasum -a 256 "$desktop_jar" "$core_jar" "${fixtures[@]}"
"$java_bin" -Djava.awt.headless=true --class-path "$desktop_jar:$core_jar:$kotlin_stdlib" \
    "$script_dir/BoardImportOracle.java" "${fixtures[@]}"
