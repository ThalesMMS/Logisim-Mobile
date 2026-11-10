#!/bin/sh
# #321: opens the files Mobile saved (fixtures/evolution-names/mobile-*.circ, written by EvolutionNamesEvolutionTest) in
# Evolution twice and after a re-save, checks names.tsv against Evolution's rename and entry functions, and records the
# outcome in fixtures/evolution-names/evolution-load.tsv.
set -eu
cd "$(dirname "$0")/../../.."
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /path/to/logisim-evolution-all.jar" >&2
    exit 2
fi
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
javac -cp "$1" -d "$classes" scripts/oracle/evolution-names/EvolutionNamesOracle.java
status=0
java -Djava.awt.headless=true '-Djava.util.prefs.PreferencesFactory=EvolutionNamesOracle$MemoryPreferencesFactory' \
    -cp "$classes:$1" EvolutionNamesOracle fixtures/evolution-names > "$classes/out.tsv" || status=1
mv "$classes/out.tsv" fixtures/evolution-names/evolution-load.tsv
exit $status
