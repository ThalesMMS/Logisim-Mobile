#!/bin/sh
# Compares Print, FPGA Commander and the assembly window with Evolution (#343).
# Usage: run.sh EVOLUTION_JAR [section...]   sections: assembly print fpga (default: all)
set -eu
cd "$(dirname "$0")/../../.."
desktop_jar=${1:?Usage: run.sh EVOLUTION_JAR [assembly|print|fpga...]}
shift
. scripts/java-home.sh
. scripts/oracle/menus/classpath.sh "$desktop_jar"
for artifact in "$desktop_jar" "${MENUS_CORE_JAR:-core/build/libs/core-jvm.jar}"; do
    [ -f "$artifact" ] || { echo "Missing $artifact: build Evolution's fat JAR and ./gradlew :core:jvmJar." >&2; exit 1; }
done
sections=${*:-assembly print fpga}
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
"$JAVA_HOME/bin/javac" -nowarn --add-exports java.desktop/sun.print=ALL-UNNAMED -cp "$MENUS_CP" -d "$classes" scripts/oracle/menu-windows/*.java
shasum -a 256 "$desktop_jar" "${MENUS_CORE_JAR:-core/build/libs/core-jvm.jar}" | sed "s#  .*/#  #"
java="$JAVA_HOME/bin/java --add-exports java.desktop/sun.print=ALL-UNNAMED -Djava.util.prefs.PreferencesFactory=MemoryPreferences\$Factory -cp $classes:$MENUS_CP"
fixtures=scripts/oracle/menu-windows/fixtures
status=0
for section in $sections; do
    echo "== $section"
    case $section in
        # The assembly window is a Swing dialog: it needs a graphics environment, but is never shown.
        assembly) $java AssemblyWindowOracle "$fixtures/assembly-counters.circ" "$fixtures/firmware.lss" 96 || status=1 ;;
        print) $java -Djava.awt.headless=true PrintLayoutOracle "$fixtures" || status=1 ;;
        fpga) python3 scripts/oracle/menu-windows/fpga_commander.py "$classes:$MENUS_CP" || status=1 ;;
        *) echo "Unknown section $section" >&2; exit 2 ;;
    esac
done
exit $status
