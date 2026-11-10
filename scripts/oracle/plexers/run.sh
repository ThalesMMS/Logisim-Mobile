#!/bin/sh
set -eu
cd "$(dirname "$0")/../../.."
desktop_jar=${1:?Usage: scripts/oracle/plexers/run.sh desktop-all.jar}
kotlin_stdlib=${KOTLIN_STDLIB_JAR:-$(python3 - <<'PY'
import os, re
from pathlib import Path
version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', Path('build.gradle.kts').read_text()).group(1)
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
print(next((cache / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib' / version).glob('*/*.jar')))
PY
)}
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
classpath="$desktop_jar:core/build/libs/core-jvm.jar:$kotlin_stdlib"
javac -cp "$classpath" -d "$classes" scripts/oracle/plexers/PlexersOracle.java
java -Djava.awt.headless=true -cp "$classes:$classpath" PlexersOracle
