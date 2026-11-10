#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
python3 scripts/generate-localization.py
./gradlew :core:jvmTest --tests '*HdlExportTest' :core:jvmJar --console=plain
kotlin_stdlib=${KOTLIN_STDLIB_JAR:-$(python3 - <<'PYTHON'
import os
import re
from pathlib import Path
version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', Path("build.gradle.kts").read_text()).group(1)
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
print(next((cache / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" / version).glob("*/*.jar")))
PYTHON
)}
destination=${1:-outputs/hdl-export-validation}
# Both values of Evolution's "VHDL keywords in upper case" preference.
for keywords in upper lower; do
    java -cp "core/build/libs/core-jvm.jar:core/build/classes/kotlin/jvm/test:$kotlin_stdlib" \
        dev.logisim.mobile.core.HdlExportOracleMainKt "$destination/$keywords" "$keywords"
    python3 scripts/oracle/hdl-export/verify.py "$destination/$keywords"
done
