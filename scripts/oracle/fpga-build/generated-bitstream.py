"""Run the companion ECP5 build with the mobile generator's unchanged HDL and LPF."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tool_path(variable, name, preferred):
    value = os.environ.get(variable)
    if not value:
        value = str(preferred) if preferred.is_file() else shutil.which(name)
    resolved = shutil.which(value) if value else None
    if not resolved:
        raise SystemExit(f'Missing tool {name}; set {variable} to its executable path.')
    return str(Path(resolved).resolve())


def version(executable, argument):
    result = subprocess.run([executable, argument], check=True, capture_output=True, text=True)
    return (result.stdout.strip() or result.stderr.strip()).splitlines()[0]


def main():
    if len(sys.argv) != 4:
        raise SystemExit('Usage: generated-bitstream.py repo-root generated-verilog-directory result.json')
    repo, workdir, result_path = (Path(value).resolve() for value in sys.argv[1:])
    sys.path.insert(0, str(repo))
    from companion.api import JobContext, JobFailure
    from companion.fpga import synthesize

    suite = repo / 'outputs/toolchains/oss-cad-suite-fpga/oss-cad-suite/bin'
    tools = {
        'yosys': tool_path('YOSYS_BIN', 'yosys', Path('/opt/homebrew/bin/yosys')),
        'nextpnr-ecp5': tool_path('NEXTPNR_ECP5', 'nextpnr-ecp5', suite / 'nextpnr-ecp5'),
        'ecppack': tool_path('ECPPACK', 'ecppack', suite / 'ecppack'),
    }
    names = ('user_top.v', 'logisim_fpga_top.v', 'pins.lpf')
    inputs = {name: workdir / name for name in names}
    for name, path in inputs.items():
        if not path.is_file():
            raise SystemExit(f'Missing generated input: {name}')
    input_hashes = {name: sha256(path) for name, path in inputs.items()}
    lpf = inputs['pins.lpf'].read_text()
    for directive in ('BLOCK RESETPATHS;', 'BLOCK ASYNCPATHS;',
                      'PULLMODE=UP IO_TYPE=LVCMOS18', 'PULLMODE=NONE IO_TYPE=LVCMOS33 DRIVE=4'):
        if directive not in lpf:
            raise SystemExit(f'The generated GECKO5 LPF omitted {directive}')
    events = []

    def emit(stream, text):
        events.append((stream, text))
        if stream == 'stage':
            print(f'Generated ECP5 project: {text}', flush=True)

    context = JobContext(workdir, inputs, tools, threading.Event(), timeout_seconds=180,
                         max_output_bytes=16 * 1024 * 1024, emit=emit)
    try:
        result = synthesize(context, {
            'flow': 'ecp5', 'part': '85k', 'package': 'CABGA381', 'top': 'logisim_fpga_top',
            'sources': ['user_top.v', 'logisim_fpga_top.v'], 'constraint': 'pins.lpf',
            'language': 'verilog', 'goal': 'bitstream',
        })
    except JobFailure as error:
        print(json.dumps({'status': 'failed', 'error': error.as_dict(),
                          'inputs': input_hashes, 'generated_lpf': lpf}, sort_keys=True), file=sys.stderr)
        return 1
    stages = [text for stream, text in events if stream == 'stage']
    if stages != ['synthesize', 'place-route', 'pack']:
        raise SystemExit(f'Unexpected build stages: {stages}')
    if any(result[name] is not True for name in ('synthesized', 'placedAndRouted', 'bitstreamGenerated')):
        raise SystemExit('The companion did not complete all ECP5 build stages.')
    if result['programmed'] is not False or result['hardwareVerified'] is not False:
        raise SystemExit('This check must not program or claim to verify hardware.')
    if {artifact['name'] for artifact in result['artifacts']} != {'synthesized.json', 'routed.config', 'design.bit'}:
        raise SystemExit('The companion omitted an ECP5 build artifact.')
    artifacts = []
    for artifact in result['artifacts']:
        path = context.output_path(artifact['name'])
        if path.stat().st_size != artifact['size'] or sha256(path) != artifact['sha256']:
            raise SystemExit(f'Artifact metadata mismatch: {artifact["name"]}')
        artifacts.append({key: artifact[key] for key in ('name', 'size', 'sha256')})
    if context.output_path('design.bit').stat().st_size <= 1000:
        raise SystemExit('The ECP5 bitstream is unexpectedly small.')
    if b'.device LFE5U-85F' not in context.output_path('routed.config').read_bytes():
        raise SystemExit('The routed configuration does not identify the GECKO5 ECP5 device.')
    if input_hashes != {name: sha256(path) for name, path in inputs.items()}:
        raise SystemExit('The build changed a generated input.')
    warnings = [line.strip() for stream, text in events if stream in ('stdout', 'stderr')
                for line in text.splitlines() if 'warning' in line.lower()]
    summary = {
        'enabled': True, 'status': 'passed', 'flow': result['flow'], 'top': result['top'],
        'synthesized': result['synthesized'], 'placedAndRouted': result['placedAndRouted'],
        'bitstreamGenerated': result['bitstreamGenerated'], 'programmed': result['programmed'],
        'hardwareVerified': result['hardwareVerified'], 'stages': stages, 'artifacts': artifacts,
        'inputs': input_hashes, 'generated_lpf': lpf, 'warnings': warnings,
        'tools': {name: version(path, '-V' if name == 'yosys' else '--version')
                  for name, path in tools.items()},
        'companion_sha256': sha256(repo / 'companion/fpga.py'),
    }
    result_path.write_text(json.dumps(summary, sort_keys=True))
    bitstream = next(artifact for artifact in artifacts if artifact['name'] == 'design.bit')
    print(f'PASS generated ECP5 LPF: design.bit {bitstream["size"]} bytes, SHA-256 {bitstream["sha256"]}', flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
