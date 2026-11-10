#!/bin/sh
set -eu
cd "$(dirname "$0")/../../.."
desktop_jar=${1:?Usage: run-default-text.sh /path/to/logisim-evolution-all.jar}
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
javac -cp "$desktop_jar" -d "$classes" scripts/oracle/annotations/DefaultTextAnchorOracle.java
java -Djava.awt.headless=true -cp "$classes:$desktop_jar" DefaultTextAnchorOracle examples/compatibility/appearance-default-text.circ
