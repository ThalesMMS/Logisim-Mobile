#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../.." && pwd)"
desktop_jar="${1:?Usage: run.sh /path/to/logisim-evolution-all.jar [mobile-core.jar]}"
core_jar="${2:-$repo_root/core/build/libs/core-jvm.jar}"
for artifact in "$desktop_jar" "$core_jar"; do
    if [[ ! -f "$artifact" ]]; then
        printf 'Missing JAR: %s\nBuild the desktop fat JAR and the mobile core first.\n' "$artifact" >&2
        exit 1
    fi
done
kotlin_stdlib="${KOTLIN_STDLIB_JAR:-}"
if [[ -z "$kotlin_stdlib" ]]; then
    kotlin_stdlib="$(python3 - "$repo_root" <<'PY'
import os
import re
import sys
from pathlib import Path
root = Path(sys.argv[1])
version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', (root / 'build.gradle.kts').read_text()).group(1)
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
paths = sorted((cache / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib' / version).glob('*/*.jar'))
print(paths[0] if paths else '')
PY
)"
fi
if [[ ! -f "$kotlin_stdlib" ]]; then
    printf 'Set KOTLIN_STDLIB_JAR to the matching Kotlin standard library JAR.\n' >&2
    exit 1
fi
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
classes_dir="$(mktemp -d)"
trap 'rm -rf "$classes_dir"' EXIT
classpath="$desktop_jar:$core_jar:$kotlin_stdlib"
printf 'Desktop JAR: %s\nMobile JAR: %s\n' "$desktop_jar" "$core_jar"
shasum -a 256 "$desktop_jar" "$core_jar"
"$javac_bin" -cp "$classpath" -d "$classes_dir" "$script_dir/MemoryImageFormatOracle.java"
"$java_bin" -Djava.awt.headless=true -cp "$classes_dir:$classpath" MemoryImageFormatOracle \
    "$repo_root/outputs/memory-image-oracle"
