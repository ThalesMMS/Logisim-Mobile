#!/bin/sh
# Menu actions against Evolution. Usage: run.sh EVOLUTION_JAR [OUT] [--no-build]
# 1. Mobile: MenuActionsParityExportTest drives EditorState and writes each result (Gradle, under the shared lock).
# 2. Evolution: MenuActionsOracle applies the same menu actions headless; the Mobile documents are reopened and
#    rewritten by Evolution (canon); compare.py diffs both, with the accepted differences of expect.json.
# 3. BoardEditorOracle (FPGA > Board Editor) and VerilogImportOracle (Project > Import Verilog, after the
#    hdl-import oracle has imported the same sources through the Mobile path).
set -eu
cd "$(dirname "$0")/../../.."
. scripts/java-home.sh
export PATH="$JAVA_HOME/bin:$PATH"
jar=${1:?Pass the Logisim Evolution JAR}
out=${2:-outputs/menu-actions}
build=1
[ "${3:-}" = "--no-build" ] && build=0
here=scripts/oracle/menus
rm -rf "$out"; mkdir -p "$out/logs"
if [ $build = 1 ]; then
    until mkdir /tmp/logisim-build.lock 2>/dev/null; do sleep 20; done
    trap 'rmdir /tmp/logisim-build.lock 2>/dev/null || true' EXIT
    ./gradlew :core:jvmJar :composeApp:testAndroidHostTest --tests 'dev.logisim.mobile.MenuActionsParityExportTest' \
        --rerun --console=plain > "$out/logs/gradle.txt" 2>&1
    rmdir /tmp/logisim-build.lock; trap - EXIT
fi
mobile=composeApp/build/menu-parity/mobile
$here/run-evolution.sh "$jar" evolution $here/fixtures "$out/evolution" > "$out/logs/evolution.txt" 2>&1
$here/run-evolution.sh "$jar" canon "$mobile" "$out/mobile-canon" $here/fixtures > "$out/logs/canon.txt" 2>&1
cp -R "$mobile" "$out/mobile"
status=0
python3 $here/compare.py "$out/evolution" "$out/mobile-canon" "$mobile" --expect $here/expect.json \
    --report "$out/menu-actions.json" > "$out/logs/compare.txt" 2>&1 || status=1

. $here/classpath.sh "$jar"
classes=$(mktemp -d)
javac -nowarn -cp "$MENUS_CP" -d "$classes" $here/BoardEditorOracle.java $here/VerilogImportOracle.java $here/MenuActionsOracle.java
java -Djava.awt.headless=true -cp "$classes:$MENUS_CP" BoardEditorOracle "$out/boards" \
    core/src/jvmTest/resources/fpga/GECKO5_EDUCATION.xml core/src/jvmTest/resources/fpga/BASYS3.xml \
    > "$out/logs/board-editor.txt" 2>&1 || true
python3 scripts/oracle/hdl-import/run.py --mobile-jar core/build/libs/core-jvm.jar \
    --mobile-sha256 "$(shasum -a 256 core/build/libs/core-jvm.jar | cut -d' ' -f1)" \
    --desktop-jar "$jar" --desktop-sha256 "$(shasum -a 256 "$jar" | cut -d' ' -f1)" \
    --output "$out/hdl-import" > "$out/logs/hdl-import.txt" 2>&1 || status=1
java -Djava.awt.headless=true '-Djava.util.prefs.PreferencesFactory=MenuActionsOracle$MemoryPreferencesFactory' \
    -cp "$classes:$MENUS_CP" VerilogImportOracle "$(command -v yosys)" "$out/hdl-import" \
    hierarchy:hierarchy gates:gates macros:macros:MASK=2 > "$out/logs/verilog-import.txt" 2>&1 || status=1
rm -rf "$classes"
cat "$out/logs/compare.txt" | grep -v '^    ' ; grep '^SAME\|^DIFF\|^INFO' "$out/logs/board-editor.txt" "$out/logs/verilog-import.txt" | cut -c1-200
shasum -a 256 "$jar" core/build/libs/core-jvm.jar
exit $status
