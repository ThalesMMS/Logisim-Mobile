#!/bin/sh
set -eu
cd "$(dirname "$0")/../../.."
if [ "$#" != 1 ]; then
    echo "Usage: $0 /path/logisim-evolution-all.jar" >&2
    exit 2
fi
desktop_jar=$1
if [ "${LOGISIM_SKIP_BUILD:-0}" != 1 ]; then
    ./gradlew :core:jvmJar --console=plain
fi
kotlin_stdlib=${KOTLIN_STDLIB_JAR:-$(python3 - <<'PYTHON'
import os, re
from pathlib import Path
version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', Path('build.gradle.kts').read_text()).group(1)
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
print(next((cache / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib' / version).glob('*/*.jar')))
PYTHON
)}
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT HUP INT TERM
mobile_jar=core/build/libs/core-jvm.jar
javac -cp "$desktop_jar" -d "$temporary" scripts/oracle/vectors/VectorDesktopOracle.java
javac -cp "$mobile_jar:$kotlin_stdlib" -d "$temporary" scripts/oracle/vectors/VectorMobileOracle.java
java -cp "$temporary:$mobile_jar:$kotlin_stdlib" VectorMobileOracle --fixtures "$temporary"
mkdir -p outputs/vector-oracle
for fixture in and flip; do
    case "$fixture" in
        and) circuit='AND' ;;
        flip) circuit='Flip' ;;
    esac
    java -Djava.awt.headless=true -cp "$temporary:$desktop_jar" VectorDesktopOracle "$temporary/$fixture.circ" "scripts/oracle/vectors/$fixture.txt" "$circuit" > "outputs/vector-oracle/$fixture-desktop.tsv"
    java -cp "$temporary:$mobile_jar:$kotlin_stdlib" VectorMobileOracle "$temporary/$fixture.circ" "scripts/oracle/vectors/$fixture.txt" "$circuit" > "outputs/vector-oracle/$fixture-mobile.tsv"
    diff -u "outputs/vector-oracle/$fixture-desktop.tsv" "outputs/vector-oracle/$fixture-mobile.tsv"
done
echo 'PASS: 14 combinational and sequential test-vector rows match the desktop'
