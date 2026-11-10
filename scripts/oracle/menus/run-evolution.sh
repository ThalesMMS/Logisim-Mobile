#!/bin/sh
# Usage: run-evolution.sh EVOLUTION_JAR MODE ARGS...   (MODE: evolution FIXTURES OUT [cases] | canon IN OUT FIXTURES)
set -eu
cd "$(dirname "$0")/../../.."
. scripts/java-home.sh
jar=$1; shift
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
"$JAVA_HOME/bin/javac" -nowarn -cp "$jar" -d "$classes" scripts/oracle/menus/MenuActionsOracle.java
"$JAVA_HOME/bin/java" -Djava.awt.headless=true -Duser.language=en -Duser.country=US \
  '-Djava.util.prefs.PreferencesFactory=MenuActionsOracle$MemoryPreferencesFactory' \
  -cp "$classes:$jar" MenuActionsOracle "$@"
