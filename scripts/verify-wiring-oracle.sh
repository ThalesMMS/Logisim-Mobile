#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
desktop_jar=${1:?Pass the desktop Logisim Evolution JAR}
kotlin_stdlib=${KOTLIN_STDLIB_JAR:-$(python3 - <<'PY'
import os
from pathlib import Path
root = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
print(next((root / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.21').glob('*/*.jar')))
PY
)}
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
javac -cp "$desktop_jar:core/build/libs/core-jvm.jar:$kotlin_stdlib" -d "$classes" scripts/WiringOracle.java
javac -cp "$desktop_jar:core/build/libs/core-jvm.jar:$kotlin_stdlib" -d "$classes" scripts/WiringGeometryOracle.java
java -Djava.awt.headless=true -cp "$classes:$desktop_jar:core/build/libs/core-jvm.jar:$kotlin_stdlib" WiringOracle
java -Djava.awt.headless=true -cp "$classes:$desktop_jar:core/build/libs/core-jvm.jar:$kotlin_stdlib" WiringGeometryOracle
