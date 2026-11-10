#!/bin/sh
set -eu
cd "$(dirname "$0")/../../.."
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "Usage: $0 /path/to/logisim-evolution.jar [mobile-core.jar]" >&2
    exit 2
fi
desktop_jar=$1
core_jar=${2:-core/build/libs/core-jvm.jar}
if [ ! -f "$desktop_jar" ] || [ ! -f "$core_jar" ]; then
    echo "Build the desktop JAR and ./gradlew :core:jvmJar before running this comparison." >&2
    exit 1
fi
kotlin_stdlib=${KOTLIN_STDLIB_JAR:-$(python3 - <<'PY'
from pathlib import Path
import os,re
version=re.search(r'kotlin\("multiplatform"\) version "([^"]+)"',Path('build.gradle.kts').read_text()).group(1)
cache=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))
print(next((cache/'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib'/version).glob('*/*.jar')))
PY
)}
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
classpath="$desktop_jar:$core_jar:$kotlin_stdlib"
javac -cp "$classpath" -d "$classes" scripts/oracle/sequential/SequentialDesktopOracle.java
java -Djava.awt.headless=true '-Djava.util.prefs.PreferencesFactory=SequentialDesktopOracle$MemoryPreferencesFactory' -cp "$classes:$classpath" SequentialDesktopOracle
