#!/bin/bash
# Compares Evolution and Mobile event times for one generated circuit per catalog component.
set -euo pipefail
cd "$(dirname "$0")/../../.."
variants=0
extra=()
while [ "$#" -gt 0 ]; do
    case "$1" in
        --variants) variants=1; shift ;;
        --negative) extra+=(--negative); shift ;;
        --pairs) variants=1; extra+=(--pairs "$2"); shift 2 ;;
        *) break ;;
    esac
done
if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "Usage: $0 [--variants] [--negative] [--pairs N] /path/to/logisim-evolution.jar output-directory [mobile-core.jar]" >&2
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
mkdir -p "$output/fixtures" "$output/desktop" "$output/mobile" "$output/classes"
javac -cp "$desktop_jar" -d "$output/classes" scripts/oracle/TemporalDesktopOracle.java \
    scripts/oracle/temporal-kinds/AttributeOptionsDesktop.java
options=()
if [ "$variants" = 1 ]; then
    java -Djava.awt.headless=true -cp "$output/classes:$desktop_jar" com.cburch.logisim.data.AttributeOptionsDesktop \
        > "$output/attribute-options.tsv"
    options=("$output/attribute-options.tsv")
fi
java -cp "$mobile_cp" dev.logisim.mobile.core.TemporalKindSweep "$output/fixtures" ${options[@]+"${options[@]}"} ${extra[@]+"${extra[@]}"}
# FIXTURES=REGEX keeps only the matching fixture IDs (for example FIXTURES='blif_circuit(__.*)?').
if [ -n "${FIXTURES:-}" ]; then
    grep -E "^(${FIXTURES})\$" "$output/fixtures/index.txt" > "$output/fixtures/index.filtered" || true
    mv "$output/fixtures/index.filtered" "$output/fixtures/index.txt"
fi
export desktop_jar mobile_cp output
compare() {
    set -o pipefail
    id=$1
    fixture="$output/fixtures/$id"
    desktop="$output/desktop/$id.tsv"
    if java -Djava.awt.headless=true -cp "$output/classes:$desktop_jar" com.cburch.logisim.circuit.TemporalDesktopOracle \
        "$fixture.circ" "$fixture.ops.tsv" 2> "$output/desktop/$id.err" | grep -v '^M' > "$desktop"; then
        desktop_ok=1
    else
        desktop_ok=0
    fi
    result="$id"
    for mode in settle step; do
        mobile="$output/mobile/$id.$mode.tsv"
        if [ "$desktop_ok" = 0 ]; then status=DESKTOP_ERROR
        elif ! java -cp "$mobile_cp" dev.logisim.mobile.core.TemporalCorpusCli "$fixture.circ" "$fixture.ops.tsv" \
            $([ "$mode" = step ] && echo step) > "$mobile" 2> "$output/mobile/$id.$mode.err"; then status=MOBILE_ERROR
        elif cmp -s "$desktop" "$mobile"; then status=SAME
        elif [ "$(cut -f2,4,5 "$desktop")" = "$(cut -f2,4,5 "$mobile")" ]; then status=TIME
        else status=VALUE; fi
        result="$result	$status"
    done
    echo "$result"
}
export -f compare
# Telnet fixtures listen on the same TCP ports, so they run one at a time.
{
    { grep -v '^telnet' "$output/fixtures/index.txt" || true; } | xargs -P "${JOBS:-4}" -I{} bash -c 'compare "$@"' _ {}
    { grep '^telnet' "$output/fixtures/index.txt" || true; } | xargs -P 1 -I{} bash -c 'compare "$@"' _ {}
} | sort > "$output/summary.tsv"
cut -f2,3 "$output/summary.tsv" | sort | uniq -c
