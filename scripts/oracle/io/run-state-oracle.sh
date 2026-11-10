#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../.." && pwd)"
desktop_jar="${1:?Usage: run-state-oracle.sh /path/to/logisim-evolution-all.jar [mobile-core.jar]}"
core_jar="${2:-$repo_root/core/build/libs/core-jvm.jar}"
cd "$repo_root"
stdlib="${KOTLIN_STDLIB_JAR:-$(python3 - <<'PY'
from pathlib import Path
import os,re
root=Path(os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle'))/'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib'
version=re.search(r'kotlin\("multiplatform"\) version "([^"]+)"',Path('build.gradle.kts').read_text()).group(1)
print(next((root/version).glob('*/*.jar')))
PY
)}"
shasum -a 256 "$desktop_jar" "$core_jar"
java -Djava.awt.headless=true --class-path "$desktop_jar:$core_jar:$stdlib" "$script_dir/IoStateOracle.java"
