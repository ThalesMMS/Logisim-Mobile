#!/bin/sh
set -eu
cd "$(dirname "$0")/../../.."
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /path/to/logisim-evolution-all.jar" >&2
    exit 2
fi
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
javac -cp "$1" -d "$classes" scripts/oracle/gates/GatesDesktopOracle.java
mkdir -p core/src/jvmTest/resources
java -Djava.awt.headless=true -cp "$classes:$1" GatesDesktopOracle > core/src/jvmTest/resources/gates-desktop.tsv
