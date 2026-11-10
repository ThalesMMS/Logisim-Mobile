#!/bin/sh
# Compare project options gateUndefined, simlimit and simrand with Logisim Evolution (#331).
# Usage: scripts/oracle/project-options/run.sh EVOLUTION_JAR [OUTPUT_DIR]
# Runs Evolution `-tty table` and Mobile `--tty-evolution -tty table` on the same fixtures and
# compares stdout, the oscillation/halt message and the exit code. Needs core/build/libs/core-jvm.jar.
set -eu
cd "$(dirname "$0")/../../.."
[ "$#" -ge 1 ] || { echo "Usage: $0 EVOLUTION_JAR [OUTPUT_DIR]" >&2; exit 2; }
desktop_jar=$1
out=${2:-outputs/project-options-oracle}
. scripts/java-home.sh
PATH=$JAVA_HOME/bin:$PATH
mobile_jar=core/build/libs/core-jvm.jar
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
rm -rf "$out"; mkdir -p "$out"
javac -d "$temporary/classes" scripts/oracle/project-options/MemoryPreferencesFactory.java
python3 scripts/oracle/project-options/generate.py "$out/fixtures" > "$out/fixtures.txt"
export LOGISIM_MOBILE_CLI_PROFILE="$temporary/cli.properties"
echo "mobile_jar_sha256=$(shasum -a 256 "$mobile_jar" | cut -d' ' -f1)" > "$out/inputs.txt"
echo "desktop_jar_sha256=$(shasum -a 256 "$desktop_jar" | cut -d' ' -f1)" >> "$out/inputs.txt"
java -version 2>&1 | head -1 >> "$out/inputs.txt"
# Per-process limit (seconds); macOS has no coreutils timeout. The watchdog forks the command and
# sends SIGKILL when the limit expires (a bare alarm+exec does not reliably stop a JVM); exit 124.
with_timeout() {
    perl -e 'my $limit = shift; my $pid = fork() // die "fork: $!";
        if ($pid == 0) { exec @ARGV or die "exec: $!" }
        local $SIG{ALRM} = sub { kill "KILL", $pid; waitpid $pid, 0; exit 124 };
        alarm $limit; waitpid $pid, 0; alarm 0; exit($? & 127 ? 128 + ($? & 127) : $? >> 8)' \
        "${ORACLE_TIMEOUT:-60}" "$@"
}
failures=0
total=0
# Evolution with simrand > 0 draws from an unseeded Random; runs repeat to show the settled table is stable.
repeat=${SIMRAND_REPEAT:-5}
while read -r name; do
    total=$((total + 1))
    fixture="$out/fixtures/$name.circ"
    runs=1
    formats=table
    case "$name" in *-halt-*) formats=table,halt ;; esac
    case "$name" in simrand-*-32) runs=$repeat ;; esac
    i=0
    while [ "$i" -lt "$runs" ]; do
        i=$((i + 1))
        set +e
        with_timeout java -Djava.awt.headless=true -Djava.util.prefs.PreferencesFactory=MemoryPreferencesFactory \
            -cp "$temporary/classes:$desktop_jar" com.cburch.logisim.Main -tty "$formats" "$fixture" \
            > "$out/$name.desktop.$i.out" 2> "$out/$name.desktop.$i.err"
        rc=$?
        set -e
        if [ "$rc" -eq 124 ]; then
            # Evolution's -tty has no tick limit and no oscillation cutoff for some rings: it keeps
            # propagating. That is Evolution's behaviour, recorded as such, not a hang of the oracle.
            echo "evolution did not terminate within ${ORACLE_TIMEOUT:-60}s" > "$out/$name.desktop.$i.out"
        fi
        echo "exit=$rc" >> "$out/$name.desktop.$i.out"
        if [ "$i" -gt 1 ] && ! cmp -s "$out/$name.desktop.1.out" "$out/$name.desktop.$i.out"; then
            echo "UNSTABLE desktop $name run $i"; failures=$((failures + 1))
        fi
    done
    set +e
    with_timeout java -cp "$mobile_jar:$kotlin_stdlib" dev.logisim.mobile.core.HeadlessCircuitCli "$fixture" --tty-evolution -tty "$formats" \
        > "$out/$name.mobile.out" 2> "$out/$name.mobile.err"
    echo "exit=$?" >> "$out/$name.mobile.out"
    set -e
    # Mobile writes the halt reason on stdout; Evolution logs it on stderr. Compare the trace without it
    # and the reason separately (the stream difference is recorded in the receipt).
    grep -v '^halted due to ' "$out/$name.mobile.out" > "$out/$name.mobile.trace" || true
    grep -v '^halted due to ' "$out/$name.desktop.1.out" > "$out/$name.desktop.trace" || true
    # Evolution logs the halt reason through SLF4J on stderr; keep only the message text.
    desktop_reason=$(grep -o 'halted due to [a-z ]*' "$out/$name.desktop.1.err" || true)
    mobile_reason=$(grep -o 'halted due to [a-z ]*' "$out/$name.mobile.err" "$out/$name.mobile.out" | sed 's/^[^:]*://' || true)
    if grep -q '^exit=124$' "$out/$name.desktop.1.out"; then
        # Bounded comparison: Mobile must also report the circuit as non-settling (oscillation, exit 1).
        if grep -q '^exit=1$' "$out/$name.mobile.out" && [ "$mobile_reason" = "halted due to detected oscillation" ]; then
            echo "EVOLUTION-NONTERMINATING $name (Mobile stops with detected oscillation)"
        else
            echo "DIFF $name (Evolution does not terminate; Mobile reason='$mobile_reason')"
            failures=$((failures + 1))
        fi
    elif cmp -s "$out/$name.desktop.trace" "$out/$name.mobile.trace" && [ "$desktop_reason" = "$mobile_reason" ]; then
        echo "SAME $name"
    else
        echo "DIFF $name (reason: desktop='$desktop_reason' mobile='$mobile_reason')"
        diff -u "$out/$name.desktop.trace" "$out/$name.mobile.trace" | head -20 || true
        failures=$((failures + 1))
    fi
done < "$out/fixtures.txt"
# Order-dependent race (informative): Evolution draws random delays from an unseeded Random and
# has no tick limit, so each run is bounded by RACE_TIMEOUT seconds; Mobile uses --ticks.
race_runs=${RACE_RUNS:-5}
for rand in 0 32; do
    fixture="$out/fixtures/race/simrand-race-$rand.circ"
    i=0
    while [ "$i" -lt "$race_runs" ]; do
        i=$((i + 1))
        set +e
        d=$(ORACLE_TIMEOUT=${RACE_TIMEOUT:-10} with_timeout java -Djava.awt.headless=true -Djava.util.prefs.PreferencesFactory=MemoryPreferencesFactory \
            -cp "$temporary/classes:$desktop_jar" com.cburch.logisim.Main -tty halt,speed "$fixture" 2>&1 | grep -o 'halted due to [a-z ]*\|([0-9]* ticks' | tr '\n' ' ')
        m=$(with_timeout java -cp "$mobile_jar:$kotlin_stdlib" dev.logisim.mobile.core.HeadlessCircuitCli "$fixture" -tty halt,speed --ticks 5000 2>&1 | grep -o 'halted due to [a-z ]*\|([0-9]* ticks' | tr '\n' ' ')
        set -e
        echo "RACE simrand=$rand run=$i desktop=[${d:-no halt within ${RACE_TIMEOUT:-10}s}] mobile=[$m]"
    done
done
if [ "$failures" -ne 0 ]; then echo "FAIL: $failures of $total fixtures differ"; exit 1; fi
echo "PASS: $total project-option fixtures match Evolution"
