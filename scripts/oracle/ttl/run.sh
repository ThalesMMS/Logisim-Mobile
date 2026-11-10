#!/bin/sh
set -eu
cd "$(dirname "$0")/../../.."
if [ "$#" -lt 1 ]; then
    echo "Usage: $0 desktop-logisim.jar [--no-build]" >&2
    exit 2
fi
desktop_jar=$1
if [ "${2:-}" != --no-build ]; then ./gradlew :core:jvmJar --console=plain; fi
kotlin_stdlib=${KOTLIN_STDLIB_JAR:-$(python3 - <<'PY'
import os,re
from pathlib import Path
version=re.search(r'kotlin\("multiplatform"\) version "([^"]+)"',Path('build.gradle.kts').read_text()).group(1)
root=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))
print(next((root/'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib'/version).glob('*/*.jar')))
PY
)}
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
out=outputs/ttl-validation
mkdir -p "$out"
javac -cp "$desktop_jar" -d "$classes" scripts/oracle/ttl/TtlDesktopOracle.java
javac -cp "core/build/libs/core-jvm.jar:$kotlin_stdlib" -d "$classes" scripts/oracle/ttl/TtlMobileCompare.java
java -Djava.awt.headless=true -Djava.util.prefs.userRoot="$classes/prefs" -cp "$classes:$desktop_jar" TtlDesktopOracle metadata scripts/oracle/ttl/models.txt > "$out/metadata.tsv"
java -Djava.awt.headless=true -Djava.util.prefs.userRoot="$classes/prefs" -cp "$classes:$desktop_jar" TtlDesktopOracle vectors scripts/oracle/ttl/combinational-models.txt > "$out/combinational.tsv"
java -Djava.awt.headless=true -Djava.util.prefs.userRoot="$classes/prefs" -cp "$classes:$desktop_jar" TtlDesktopOracle sequence scripts/oracle/ttl/sequential-models.txt > "$out/sequential.tsv"
java -cp "$classes:core/build/libs/core-jvm.jar:$kotlin_stdlib" TtlMobileCompare "$out/combinational.tsv" > "$out/combinational-result.txt"
cat "$out/combinational-result.txt"
java -cp "$classes:core/build/libs/core-jvm.jar:$kotlin_stdlib" TtlMobileCompare "$out/sequential.tsv" sequence > "$out/sequential-result.txt"
cat "$out/sequential-result.txt"
