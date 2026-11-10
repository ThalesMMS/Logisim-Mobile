#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./gradlew :core:jvmTest --tests dev.logisim.mobile.core.Sap1IntegrationTest --console=plain
if [ "$#" -gt 0 ]; then
    desktop_jar=$1
    kotlin_stdlib=${KOTLIN_STDLIB_JAR:-$(python3 - <<'PYTHON'
import os
import re
from pathlib import Path
version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', Path("build.gradle.kts").read_text()).group(1)
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
print(next((cache / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" / version).glob("*/*.jar")))
PYTHON
)}
    oracle_classes=$(mktemp -d)
    trap 'rm -rf "$oracle_classes"' EXIT HUP INT TERM
    javac -cp "$desktop_jar" -d "$oracle_classes" scripts/Sap1DesktopOracle.java
    javac -cp "core/build/libs/core-jvm.jar:$kotlin_stdlib" -d "$oracle_classes" scripts/Sap1MobileTrace.java
    mkdir -p outputs/sap1-validation
    java -Djava.awt.headless=true -cp "$oracle_classes:$desktop_jar" Sap1DesktopOracle examples/sap1.circ > outputs/sap1-validation/desktop-trace.tsv
    java -cp "$oracle_classes:core/build/libs/core-jvm.jar:$kotlin_stdlib" Sap1MobileTrace examples/sap1.circ > outputs/sap1-validation/mobile-trace.tsv
    python3 scripts/compare-sap1-traces.py outputs/sap1-validation
fi
