#!/usr/bin/env bash
# Compares Evolution's Telnet component with the Mobile companion and JVM Telnet paths.
# Usage: run.sh /path/to/logisim-evolution-all.jar [OUTPUT_DIR] [mobile-core.jar]
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../.." && pwd)"
desktop_jar="${1:?Usage: run.sh /path/to/logisim-evolution-all.jar [OUTPUT_DIR] [mobile-core.jar]}"
out="${2:-$repo_root/outputs/telnet-oracle}"
core_jar="${3:-$repo_root/core/build/libs/core-jvm.jar}"
. "$repo_root/scripts/java-home.sh"
kotlin_stdlib="${KOTLIN_STDLIB_JAR:-$(python3 -I - "$repo_root" <<'PY'
import os, re, sys
from pathlib import Path
root = Path(sys.argv[1])
version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', (root / 'build.gradle.kts').read_text()).group(1)
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
print(next(p for p in (cache / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib' / version).glob('*/*.jar')
           if not p.name.endswith(('-sources.jar', '-javadoc.jar'))))
PY
)}"
work="$(mktemp -d "${TMPDIR:-/tmp}/telnet-oracle.XXXXXX")"
trap 'rm -rf "$work"' EXIT
classpath="$desktop_jar:$core_jar:$kotlin_stdlib"
printf 'Desktop JAR: %s\nMobile JAR: %s\n' "$desktop_jar" "$core_jar"
shasum -a 256 "$desktop_jar" "$core_jar"
"$JAVA_HOME/bin/javac" -nowarn -cp "$classpath" -d "$work" "$repo_root/scripts/oracle/output-devices/DesktopCircuit.java" "$script_dir/TelnetOracle.java" \
    "$repo_root/scripts/oracle/project-options/MemoryPreferencesFactory.java"
perl -e 'alarm 1200; exec @ARGV' "$JAVA_HOME/bin/java" -Djava.awt.headless=true -Djava.util.prefs.PreferencesFactory=MemoryPreferencesFactory \
    -cp "$work:$classpath" TelnetOracle "$repo_root" "$out"
