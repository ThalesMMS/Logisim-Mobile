#!/bin/bash
# Compares BLIF circuits in Evolution and Mobile: event values and times, and the Evolution round trip.
set -euo pipefail
cd "$(dirname "$0")/../../.."
if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "Usage: $0 /path/to/logisim-evolution.jar output-directory [mobile-core.jar]" >&2
    exit 2
fi
desktop_jar=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
output=$2
core_jar=$(cd "$(dirname "${3:-core/build/libs/core-jvm.jar}")" && pwd)/$(basename "${3:-core/build/libs/core-jvm.jar}")
for artifact in "$desktop_jar" "$core_jar"; do
    [ -f "$artifact" ] || { echo "Missing $artifact; build the desktop JAR and ./gradlew :core:jvmJar." >&2; exit 1; }
done
kotlin_stdlib=${KOTLIN_STDLIB_JAR:-$(python3 - <<'PY'
from pathlib import Path
import os, re
version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', Path('build.gradle.kts').read_text()).group(1)
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
print(next(p for p in (cache / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib' / version).glob('*/*.jar')
           if not p.name.endswith(('-sources.jar', '-javadoc.jar'))))
PY
)}
mobile_cp="$core_jar:$kotlin_stdlib"
rm -rf "$output"
mkdir -p "$output/fixtures" "$output/desktop" "$output/mobile" "$output/classes"
python3 scripts/oracle/blif-circuit/make-fixtures.py "$output/fixtures"
javac -cp "$desktop_jar" -d "$output/classes" scripts/oracle/TemporalDesktopOracle.java \
    scripts/oracle/temporal-kinds/ResaveDesktop.java
java -Djava.awt.headless=true -cp "$output/classes:$desktop_jar" ResaveDesktop "$output/fixtures" 2> "$output/resave.err"
# summary.tsv: settle and step against the Evolution trace. roundtrip.tsv: Mobile replays the file Evolution saved.
compare() {
    if [ ! -f "$1" ]; then echo DESKTOP_ERROR
    elif ! java -cp "$mobile_cp" dev.logisim.mobile.core.TemporalCorpusCli "$1" "$2" ${4:+$4} > "$3" 2> "$3.err"; then echo MOBILE_ERROR
    elif cmp -s "$desktop" "$3"; then echo SAME
    elif [ "$(cut -f2,4,5 "$desktop")" = "$(cut -f2,4,5 "$3")" ]; then echo TIME
    else echo VALUE; fi
}
: > "$output/summary.tsv"
: > "$output/roundtrip.tsv"
while read -r id; do
    fixture="$output/fixtures/$id"
    desktop="$output/desktop/$id.tsv"
    java -Djava.awt.headless=true -cp "$output/classes:$desktop_jar" com.cburch.logisim.circuit.TemporalDesktopOracle \
        "$fixture.circ" "$fixture.ops.tsv" 2> "$output/desktop/$id.err" | grep -v '^M' > "$desktop"
    settle=$(compare "$fixture.circ" "$fixture.ops.tsv" "$output/mobile/$id.settle.tsv")
    step=$(compare "$fixture.circ" "$fixture.ops.tsv" "$output/mobile/$id.step.tsv" step)
    echo "$id	$settle	$step" >> "$output/summary.tsv"
    echo "$id	$(compare "$fixture.desktop.circ" "$fixture.ops.tsv" "$output/mobile/$id.roundtrip.tsv")" >> "$output/roundtrip.tsv"
done < "$output/fixtures/index.txt"
cat "$output/summary.tsv" "$output/roundtrip.tsv"
