#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./gradlew :core:jvmJar :core:jvmTestClasses --console=plain
kotlin_stdlib=${KOTLIN_STDLIB_JAR:-$(python3 - <<'PYTHON'
import os
import re
from pathlib import Path
version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', Path("build.gradle.kts").read_text()).group(1)
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
print(next((cache / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" / version).glob("*/*.jar")))
PYTHON
)}
java -Djava.awt.headless=true -cp "core/build/classes/kotlin/jvm/test:core/build/libs/core-jvm.jar:$kotlin_stdlib" dev.logisim.mobile.core.VisualExportProbe outputs/visual-export "${PDFTOPPM:-pdftoppm}"
