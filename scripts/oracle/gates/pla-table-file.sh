#!/bin/sh
# Regenerates the Evolution PLA table file fixtures in fixtures/pla.
set -eu
cd "$(dirname "$0")/../../.."
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /path/to/logisim-evolution-all.jar" >&2
    exit 2
fi
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
javac -cp "$1" -d "$classes" scripts/oracle/gates/PlaTableFileOracle.java
java -Djava.awt.headless=true -cp "$classes:$1" PlaTableFileOracle fixtures/pla > fixtures/pla/evolution-read.tsv
