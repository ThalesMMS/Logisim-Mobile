#!/bin/sh
# Compare the Base Poke, Text and Menu tools with Logisim Evolution at the model level (#331), with the
# SoC, PLA ROM and Image menu extenders and the Image menu effects (#359).
# Usage: scripts/oracle/base-tools/run.sh EVOLUTION_JAR [OUTPUT_DIR]
# Needs core/build/libs/core-jvm.jar (TextToolOracle calls the Mobile core).
set -eu
cd "$(dirname "$0")/../../.."
[ "$#" -ge 1 ] || { echo "Usage: $0 EVOLUTION_JAR [OUTPUT_DIR]" >&2; exit 2; }
desktop_jar=$1
out=${2:-outputs/base-tools-oracle}
. scripts/java-home.sh
PATH=$JAVA_HOME/bin:$PATH
kotlin_stdlib=${KOTLIN_STDLIB_JAR:-$(python3 - <<'PYTHON'
import os, re
from pathlib import Path
version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', Path('build.gradle.kts').read_text()).group(1)
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
print(next((cache / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib' / version).glob('*/*.jar')))
PYTHON
)}
classpath="core/build/libs/core-jvm.jar:$kotlin_stdlib:$desktop_jar"
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT HUP INT TERM
rm -rf "$out"; mkdir -p "$out"
# Per-process limit (seconds): the watchdog forks the JVM and sends SIGKILL at the limit (exit 124).
with_timeout() {
    perl -e 'my $limit = shift; my $pid = fork() // die "fork: $!";
        if ($pid == 0) { exec @ARGV or die "exec: $!" }
        local $SIG{ALRM} = sub { kill "KILL", $pid; waitpid $pid, 0; exit 124 };
        alarm $limit; waitpid $pid, 0; alarm 0; exit($? & 127 ? 128 + ($? & 127) : $? >> 8)' \
        "${ORACLE_TIMEOUT:-60}" "$@"
}
javac -cp "$classpath" -d "$temporary" scripts/oracle/project-options/MemoryPreferencesFactory.java \
    scripts/oracle/base-tools/PokeDesktopOracle.java scripts/oracle/base-tools/TextToolOracle.java \
    scripts/oracle/base-tools/MenuDesktopOracle.java scripts/oracle/base-tools/BaseToolsMobileOracle.java \
    scripts/oracle/base-tools/ImageMenuDesktopOracle.java scripts/oracle/base-tools/ImageMenuCases.java
run() { with_timeout java -Duser.language=en -Djava.awt.headless="${headless:-true}" -Dapple.awt.UIElement=true -Djava.util.prefs.PreferencesFactory=MemoryPreferencesFactory \
    -cp "$temporary:$classpath" "$@" 2>&1 | grep -v ' INFO \| WARN ' ; }
run PokeDesktopOracle scripts/oracle/base-tools/poke-pins.circ > "$out/poke-desktop.tsv"
# The SoC bus builds a Swing dialog, so the menu oracle needs a graphics environment (not headless).
loaded="fixtures/soc/program-rv32.circ=fixtures/soc/sum-rv32.elf fixtures/soc/program-nios2.circ=fixtures/soc/sum-nios2.elf"
headless=false run MenuDesktopOracle scripts/oracle/base-tools/menu-tool.circ $loaded > "$out/menu-desktop.tsv"
images="scripts/oracle/base-tools/badge.png scripts/oracle/base-tools/photo.png"
run ImageMenuDesktopOracle "$out/image-desktop" $images > "$out/image-desktop.tsv"
run BaseToolsMobileOracle poke scripts/oracle/base-tools/poke-pins.circ > "$out/poke-mobile.tsv"
run BaseToolsMobileOracle menu scripts/oracle/base-tools/menu-tool.circ $loaded > "$out/menu-mobile.tsv"
run BaseToolsMobileOracle image "$out/image-mobile" $images > "$out/image-mobile.tsv"
set +e
run TextToolOracle scripts/oracle/base-tools/text-tool.circ "$out/text-tool" > "$out/text-tool.log"
run ImageMenuCases "$out/image-desktop" "$out/image-mobile" > "$out/image-pixels.log"
set -e
cat "$out/poke-desktop.tsv" "$out/menu-desktop.tsv" "$out/image-desktop.tsv" "$out/image-pixels.log" "$out/text-tool.log"
failures=0
for tool in poke menu image; do
    if cmp -s "$out/$tool-desktop.tsv" "$out/$tool-mobile.tsv"; then echo "SAME $tool"
    else echo "DIFF $tool"; diff "$out/$tool-desktop.tsv" "$out/$tool-mobile.tsv" || true; failures=$((failures + 1)); fi
done
grep -q '^SAME text-tool' "$out/text-tool.log" || failures=$((failures + 1))
grep -q '^SAME image-menu' "$out/image-pixels.log" || failures=$((failures + 1))
exit "$failures"
