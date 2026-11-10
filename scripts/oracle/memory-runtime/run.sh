#!/bin/sh
set -eu
cd "$(dirname "$0")/../../.."
desktop_jar=${1:?Pass the desktop Logisim Evolution all.jar path}
ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export ANDROID_HOME
if [ -f /tmp/logisim-gradle-serial.py ]; then
    python3 /tmp/logisim-gradle-serial.py ./gradlew :core:jvmTestClasses :core:jvmJar --console=plain
else
    ./gradlew :core:jvmTestClasses :core:jvmJar --console=plain
fi
kotlin_stdlib=${KOTLIN_STDLIB_JAR:-$(python3 - <<'PY'
import re
from pathlib import Path
version=re.search(r'kotlin\("multiplatform"\) version "([^"]+)"',Path('build.gradle.kts').read_text()).group(1)
print(next((Path.home()/'.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib'/version).glob('*/*.jar')))
PY
)}
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
mkdir -p outputs/memory-oracle
python3 scripts/oracle/memory-runtime/generate-cases.py > outputs/memory-oracle/cases.tsv
javac -cp "$desktop_jar" -d "$classes" scripts/oracle/memory-runtime/MemoryDesktopOracle.java
java -Djava.awt.headless=true -cp "$classes:$desktop_jar" MemoryDesktopOracle outputs/memory-oracle/cases.tsv > outputs/memory-oracle/desktop.tsv
java -cp "core/build/classes/kotlin/jvm/test:core/build/libs/core-jvm.jar:$kotlin_stdlib" dev.logisim.mobile.core.MemoryRuntimeOracleMainKt outputs/memory-oracle/cases.tsv > outputs/memory-oracle/mobile.tsv
python3 - <<'PY'
from pathlib import Path
import json
directory=Path('outputs/memory-oracle')
desktop=directory.joinpath('desktop.tsv').read_text().splitlines()
mobile=directory.joinpath('mobile.tsv').read_text().splitlines()
assert len(desktop)==len(mobile),(len(desktop),len(mobile))
differences=[{'row':i+1,'desktop':a,'mobile':b} for i,(a,b) in enumerate(zip(desktop,mobile)) if a!=b]
directory.joinpath('comparison.json').write_text(json.dumps({'rows':len(desktop),'differences':len(differences),'first_differences':differences[:20]},indent=2)+'\n')
assert not differences,differences[:5]
print(f'PASS: {len(desktop)} memory geometry and propagation observations match the desktop')
PY
