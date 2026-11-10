#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /path/to/logisim-evolution-all.jar" >&2
    exit 2
fi
desktop_jar=$1
./gradlew :core:jvmJar --console=plain
stdlib=$(python3 - <<'PY'
import os,re
from pathlib import Path
version=re.search(r'kotlin\("multiplatform"\) version "([^"]+)"',Path('build.gradle.kts').read_text()).group(1)
print(next((Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))/'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib'/version).glob('*/*.jar')))
PY
)
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
classpath="core/build/libs/core-jvm.jar:$stdlib:$desktop_jar"
javac -cp "$classpath" -d "$classes" scripts/CircDesktopCompatibility.java
java -Djava.awt.headless=true -cp "$classes:$classpath" CircDesktopCompatibility examples/compatibility/*.circ examples/sap1.circ
