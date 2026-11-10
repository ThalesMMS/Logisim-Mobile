#!/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../../.." && pwd)
desktop_jar=${1:?Usage: run.sh desktop-all.jar [mobile-core.jar]}
core_jar=${2:-$repo_root/core/build/libs/core-jvm.jar}
kotlin_stdlib=${KOTLIN_STDLIB_JAR:-$(python3 - <<'PY'
from pathlib import Path
paths = list((Path.home()/'.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib').glob('*/*/kotlin-stdlib-*.jar'))
print(sorted(paths, key=lambda p: tuple(map(int, p.parents[1].name.split('.'))))[-1])
PY
)}
shasum -a 256 "$desktop_jar" "$core_jar"
java -Djava.awt.headless=true --class-path "$desktop_jar:$core_jar:$kotlin_stdlib" "$script_dir/ArithmeticOracle.java"
java -Djava.awt.headless=true --class-path "$desktop_jar" "$script_dir/ArithmeticFixtureOracle.java" \
    "$repo_root/core/src/jvmTest/resources/arithmetic-components.circ"
