#!/bin/sh
# #313: builds the creation-defaults scenario in Evolution for each profile, saves fixtures/creation-defaults/evolution-*.circ
# (and, for the empty profile, a new project from the empty template, #339)
# and records in evolution-load.tsv how Evolution loads its own file and Mobile's (written by CreationDefaultsEvolutionTest).
set -eu
cd "$(dirname "$0")/../../.."
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /path/to/logisim-evolution-all.jar" >&2
    exit 2
fi
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
javac -cp "$1" -d "$classes" scripts/oracle/creation-defaults/CreationDefaultsOracle.java
out=fixtures/creation-defaults/evolution-load.tsv
: > "$out.tmp"
status=0
for profile in classic-false-false-true evolution-true-true-false logisim_evolution-true-true-true empty; do
    printf 'profile\t%s\n' "$profile" >> "$out.tmp"
    java -Djava.awt.headless=true '-Djava.util.prefs.PreferencesFactory=CreationDefaultsOracle$MemoryPreferencesFactory' \
        -cp "$classes:$1" CreationDefaultsOracle fixtures/creation-defaults "$profile" \
        > "$classes/out.tsv" || status=1
    awk -v p="$profile" '{ sub(/\t/, "\t" p "\t"); print }' "$classes/out.tsv" >> "$out.tmp"
done
mv "$out.tmp" "$out"
exit $status
