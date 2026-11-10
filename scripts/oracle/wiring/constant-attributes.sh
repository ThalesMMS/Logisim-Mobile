#!/bin/sh
set -eu
cd "$(dirname "$0")/../../.."
if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    echo "Usage: $0 /path/to/logisim-evolution.jar" >&2
    exit 2
fi
desktop_jar=$1
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
javac -cp "$desktop_jar" -d "$classes" scripts/oracle/wiring/ConstantAttributeOracle.java
java -Djava.awt.headless=true -cp "$classes:$desktop_jar" ConstantAttributeOracle
