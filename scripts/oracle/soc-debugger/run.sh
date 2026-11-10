#!/usr/bin/env bash
# Compares the SoC debugger model (ELF load, trace, registers, breakpoints, RV32 assembler) with Evolution.
# Usage: run.sh /path/to/logisim-evolution-all.jar [OUTPUT_DIR] [mobile-core.jar]
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../.." && pwd)"
desktop_jar="${1:?Usage: run.sh /path/to/logisim-evolution-all.jar [OUTPUT_DIR] [mobile-core.jar]}"
out="${2:-$repo_root/outputs/soc-debugger-oracle}"
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
work="$(mktemp -d "${TMPDIR:-/tmp}/soc-debugger-oracle.XXXXXX")"
trap 'rm -rf "$work"' EXIT
classpath="$desktop_jar:$core_jar:$kotlin_stdlib"
printf 'Desktop JAR: %s\nMobile JAR: %s\n' "$desktop_jar" "$core_jar"
shasum -a 256 "$desktop_jar" "$core_jar"
"$JAVA_HOME/bin/javac" -nowarn -cp "$classpath" -d "$work" "$script_dir/SocDebuggerOracle.java" \
    "$repo_root/scripts/oracle/project-options/MemoryPreferencesFactory.java"
# Like scripts/SocProgramDesktopOracle.java, the SoC bus builds a Swing dialog; no window is shown.
headless="${SOC_ORACLE_HEADLESS:-false}"
perl -e 'alarm 180; exec @ARGV' "$JAVA_HOME/bin/java" -Djava.awt.headless="$headless" -Dapple.awt.UIElement=true \
    -Djava.util.prefs.PreferencesFactory=MemoryPreferencesFactory -cp "$work:$classpath" SocDebuggerOracle "$repo_root" "$out"
