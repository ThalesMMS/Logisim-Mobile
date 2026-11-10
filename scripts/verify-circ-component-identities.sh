#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "Usage: $0 /path/to/logisim-evolution-all.jar /path/to/core-jvm.jar [/path/to/mobile-classes]" >&2
    echo "Uses existing build artifacts; does not invoke Gradle." >&2
    exit 2
fi
desktop_jar=$1
core_jar=$2
mobile_classes=${3:-$core_jar}
stdlib=${KOTLIN_STDLIB_JAR:-$(python3 - <<'PY'
import os,re
from pathlib import Path
version=re.search(r'kotlin\("multiplatform"\) version "([^"]+)"',Path('build.gradle.kts').read_text()).group(1)
print(next((Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))/'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib'/version).glob('*/*.jar')))
PY
)}
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
classpath="$mobile_classes:$core_jar:$stdlib:$desktop_jar"
javac -encoding UTF-8 -cp "$classpath" -d "$classes" scripts/CircComponentIdentityOracle.java
java -Djava.awt.headless=true -cp "$classes:$classpath" CircComponentIdentityOracle
