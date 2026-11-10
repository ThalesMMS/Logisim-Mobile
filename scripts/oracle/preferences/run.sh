#!/bin/sh
# Preferences campaign (#236, #243): records what Evolution d58a61e0 does with each user preference in
# fixtures/preferences/evolution.tsv. EditorPreferencesEvolutionTest writes Mobile's rows (mobile.tsv) and the DRC
# fixtures; compare.py joins both files per parity-matrix row.
# Usage: run.sh /path/to/logisim-evolution-all.jar [/path/to/logisim-evolution-checkout]
set -eu
cd "$(dirname "$0")/../../.."
if [ "$#" -lt 1 ]; then
    echo "Usage: $0 /path/to/logisim-evolution-all.jar [evolution-source-checkout]" >&2
    exit 2
fi
jar=$1
source=${2:-$(cd "$(dirname "$jar")/../.." && pwd)}
. scripts/java-home.sh
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
"$JAVA_HOME/bin/javac" -nowarn -cp "$jar" -d "$classes" scripts/oracle/preferences/PreferencesOracle.java 2>/dev/null
run() {
    "$JAVA_HOME/bin/java" -Djava.awt.headless=true "$@" '-Djava.util.prefs.PreferencesFactory=PreferencesOracle$MemoryPreferencesFactory' \
        -cp "$classes:$jar" PreferencesOracle fixtures/preferences "$mode"
}
out=fixtures/preferences/evolution.tsv
mode=all; run -Duser.language=en -Duser.country=US > "$out.tmp"
mode=gate-shape; run -Duser.language=ru -Duser.country=RU >> "$out.tmp"
mode=gate-shape; run -Duser.language=pt -Duser.country=BR >> "$out.tmp"
# Preferences each panel's source edits (the running panels hold some of them only in listeners).
for panel in TemplateOptions IntlOptions WindowOptions LayoutOptions SimOptions HotkeyOptions; do
    file="$source/src/main/java/com/cburch/logisim/gui/prefs/$panel.java"
    printf 'panel-source\t%s\tmembers\t%s\n' "$panel" "$(grep -o 'AppPreferences\.[A-Za-z_0-9]*' "$file" | sed 's/AppPreferences\.//' | sort -u | tr '\n' ',' | sed 's/,$//')" >> "$out.tmp"
done
file="$source/src/main/java/com/cburch/logisim/fpga/prefs/FpgaOptions.java"
printf 'panel-source\t%s\tmembers\t%s\n' FpgaOptions "$(grep -o 'AppPreferences\.[A-Za-z_0-9]*' "$file" | sed 's/AppPreferences\.//' | sort -u | tr '\n' ',' | sed 's/,$//')" >> "$out.tmp"
printf 'source\trevision\tgit\t%s\n' "$(git -C "$source" rev-parse HEAD)" >> "$out.tmp"
mv "$out.tmp" "$out"
