#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../.." && pwd)"
core_jar="${1:-$repo_root/core/build/libs/core-jvm.jar}"
fixture="$repo_root/core/src/jvmTest/resources/fpga/GECKO5_EDUCATION.xml"
case "${LOGISIM_FPGA_BITSTREAM:-0}" in
    0|1) ;;
    *) printf 'LOGISIM_FPGA_BITSTREAM must be 0 or 1.\n' >&2; exit 2 ;;
esac

for artifact in "$core_jar" "$fixture"; do
    if [[ ! -f "$artifact" ]]; then
        printf 'Missing artifact: %s\nRun ./gradlew :core:jvmJar first.\n' "$artifact" >&2
        exit 1
    fi
done

kotlin_stdlib="${KOTLIN_STDLIB_JAR:-}"
if [[ -z "$kotlin_stdlib" ]]; then
    kotlin_stdlib="$(python3 - <<'PY'
import os
from pathlib import Path

gradle_cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
candidates = list((gradle_cache / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib').glob('*/*/kotlin-stdlib-*.jar'))
candidates = [path for path in candidates if not path.name.endswith(('-sources.jar', '-javadoc.jar'))]
def version(path):
    return tuple(int(part) if part.isdigit() else 0 for part in path.parents[1].name.split('.'))
print(max(candidates, key=version) if candidates else '')
PY
)"
fi
if [[ ! -f "$kotlin_stdlib" ]]; then
    printf 'Set KOTLIN_STDLIB_JAR to the Kotlin standard library JAR.\n' >&2
    exit 1
fi

java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
iverilog_bin="${IVERILOG_BIN:-iverilog}"
vvp_bin="${VVP_BIN:-vvp}"
nvc_bin="${NVC_BIN:-nvc}"
for tool in "$java_bin" "$iverilog_bin" "$vvp_bin" "$nvc_bin"; do
    command -v "$tool" >/dev/null || { printf 'Required tool not found: %s\n' "$tool" >&2; exit 1; }
done

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/logisim-fpga-build.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT
cp -- "$core_jar" "$work_dir/mobile-core.jar"
shasum -a 256 "$work_dir/mobile-core.jar" "$fixture"
"$java_bin" --class-path "$work_dir/mobile-core.jar:$kotlin_stdlib" \
    "$script_dir/WrapperSimulation.java" "$fixture" "$work_dir"

for test_case in combinational inout; do
    (
        cd -- "$work_dir/$test_case/verilog"
        "$iverilog_bin" -g2012 -s tb -o simulation.vvp user_top.v logisim_fpga_top.v tb.sv
        "$vvp_bin" simulation.vvp | tee simulation.log
    )
    (
        cd -- "$work_dir/$test_case/vhdl"
        "$nvc_bin" --std=08 -a user_top.vhd logisim_fpga_top.vhd tb.vhd
        "$nvc_bin" --std=08 -e tb
        "$nvc_bin" --std=08 -r tb --exit-severity=failure 2>&1 | tee simulation.log
    )
done
printf 'PASS 4 wrapper simulations: 4 combinational states and 10 inout cases per language.\n'
if [[ "${LOGISIM_FPGA_BITSTREAM:-0}" == 1 ]]; then
    PYTHONDONTWRITEBYTECODE=1 python3 "$script_dir/generated-bitstream.py" \
        "$repo_root" "$work_dir/combinational/verilog" "$work_dir/bitstream-result.json"
fi
python3 - "$work_dir" "$iverilog_bin" "$nvc_bin" "$fixture" <<'PY'
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

work_dir = Path(sys.argv[1])
cases = []
for case, expected_count in (("combinational", 4), ("inout", 10)):
    for language, label in (("verilog", "Verilog"), ("vhdl", "VHDL")):
        log = (work_dir / case / language / 'simulation.log').read_text()
        matches = re.findall(rf'PASS {label} {case}: (\d+) ', log)
        if matches != [str(expected_count)]:
            raise SystemExit(f'Missing or unexpected simulation result for {language} {case}: {matches}')
        cases.append({'case': case, 'language': language, 'checks': int(matches[0])})

def version(tool, argument):
    return subprocess.run([tool, argument], check=True, capture_output=True, text=True).stdout.splitlines()[0]

def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

print(json.dumps({
    'status': 'passed',
    'simulation_runs': len(cases),
    'assertion_cases': sum(case['checks'] for case in cases),
    'cases': cases,
    'tools': {'iverilog': version(sys.argv[2], '-V'), 'nvc': version(sys.argv[3], '--version')},
    'inputs': {'core_sha256': sha256(work_dir / 'mobile-core.jar'), 'board_sha256': sha256(sys.argv[4])},
    'bitstream': json.loads((work_dir / 'bitstream-result.json').read_text())
        if (work_dir / 'bitstream-result.json').is_file() else {'enabled': False},
}, sort_keys=True))
PY
