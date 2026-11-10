#!/usr/bin/env python3
"""Replay existing b06 pure geometry; never run Android, Gradle, or a device."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import subprocess


HERE = Path(__file__).resolve().parent
BASE = Path('/Users/thales/GitHub/logisim-mobile-worktrees/android-canvas-input-diagnostic-test-ui')
E7 = Path('/Users/thales/GitHub/logisim-mobile-worktrees/canvas-zoomout-retention')
RAW = E7 / 'outputs/android-zoomout-e7fb33f8-native-5558-002/pinch-once/native'
STDLIB = Path('/Users/thales/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.21/c343e135bd1e7dd94b7ed76b4159068d888d09db/kotlin-stdlib-2.4.21.jar')
SOURCE_FILES = ['composeApp/src/commonMain/kotlin/dev/logisim/mobile/CircuitSemanticOverlay.kt',
                'core/src/commonMain/kotlin/dev/logisim/mobile/core/ComponentGeometry.kt',
                'core/src/commonMain/kotlin/dev/logisim/mobile/core/CircuitDocument.kt']
EXPECTED_STATES = [(58, 1.1855534, 402.47583, 586.02747),
                   (63, 0.94844276, 429.98065, 616.022),
                   (220, 1.896886, 258.4614, 521.0438)]


def identity(path):
    value = path.read_bytes()
    return {'bytes': len(value), 'sha256': hashlib.sha256(value).hexdigest()}


def intersect(a, b):
    value = [max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])]
    return value if value[0] < value[2] and value[1] < value[3] else None


def subtract(a, b):
    cut = intersect(a, b)
    if cut is None:
        return [a]
    candidates = [[a[0], a[1], a[2], cut[1]], [a[0], cut[3], a[2], a[3]],
                  [a[0], cut[1], cut[0], cut[3]], [cut[2], cut[1], a[2], cut[3]]]
    return [v for v in candidates if v[0] < v[2] and v[1] < v[3]]


def adjusted_model(boxes):
    # Model of the pinned SemanticsOwner region algorithm, not execution of Android Region.
    unaccounted = [[0, 0, 1080, 1472]]
    result = {}
    for name in ('mi-output', 'mi-not', 'mi-input'):
        box = boxes[name]['placed']
        pieces = [v for space in unaccounted if (v := intersect(box, space)) is not None]
        assert pieces, name
        result[name] = [min(v[0] for v in pieces), min(v[1] for v in pieces),
                        max(v[2] for v in pieces), max(v[3] for v in pieces)]
        unaccounted = [v for space in unaccounted for v in subtract(space, box)]
    return result


def cx(bounds):
    return (bounds[0] + bounds[2]) / 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='New output directory outside this packet')
    args = parser.parse_args()
    output = args.output.resolve()
    if output == HERE or HERE in output.parents or output.exists():
        parser.error('Use a new directory outside the packet')
    output.mkdir(parents=True)
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=BASE, text=True).strip() == 'b06cefcc42a5c85a4f55d4ba127d4b93c2c38c5e'
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=E7, text=True).strip() == 'e7fb33f8d76c6ec8bc7a7046e7c252fb9386d630'
    classdirs = [BASE / 'core/build/classes/kotlin/android/main', BASE / 'composeApp/build/classes/kotlin/android/main']
    inputs = [STDLIB, HERE / 'BoundsReplay.java', HERE / 'run.py', *(BASE / p for p in SOURCE_FILES),
              E7 / 'androidApp/src/androidTest/kotlin/dev/logisim/mobile/input/MixedInputScenarios.kt']
    inputs += [RAW / name for name in ('0-fixture.circ', 'canvas-input-trace.json', 'zoom-out-observations.jsonl',
                                       'pinch-failure-tree.txt', 'result.json')]
    for directory in classdirs:
        inputs += sorted(p for p in directory.rglob('*') if p.is_file())
    before = {str(p): identity(p) for p in inputs}
    report = {'scope': 'OFFLINE_PURE_FUNCTION_REPLAY', 'nativeStatus': 'NOT_RUN',
              'producer': 'b06cefcc42a5c85a4f55d4ba127d4b93c2c38c5e', 'inputsBefore': before, 'commands': []}
    cp = ':'.join(map(str, [*classdirs, STDLIB]))
    commands = [['javac', '--release', '17', '-classpath', cp, '-d', str(output), str(HERE / 'BoundsReplay.java')],
                ['java', '-classpath', str(output) + ':' + cp, 'BoundsReplay', str(RAW / '0-fixture.circ')]]
    stdout = ''
    for index, command in enumerate(commands):
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (output / f'command-{index + 1}.log').write_text(result.stdout)
        report['commands'].append({'argv': command, 'exitCode': result.returncode,
                                   'log': f'command-{index + 1}.log', 'logIdentity': identity(output / f'command-{index + 1}.log')})
        assert result.returncode == 0, result.stdout
        stdout = result.stdout
    states = {}
    current = None
    for line in stdout.splitlines():
        state = re.match(r'STATE (\d+) ', line)
        if state:
            current = int(state[1]); states[current] = {}
        elif line.startswith('mi-'):
            name = line.split()[0]
            fields = {key: [float(v) for v in values.split(',')]
                      for key, values in re.findall(r'(world|raw|target|placed|screen)=\[([^]]+)\]', line)}
            fields['placed'] = [int(v) for v in fields['placed']]
            states[current][name] = fields
    assert set(states) == {0, 1, 2}
    trace = json.loads((RAW / 'canvas-input-trace.json').read_bytes())
    for sequence, scale, pan_x, pan_y in EXPECTED_STATES:
        matches = [e['record'] for e in trace['state']['entries'] if e['sequence'] == sequence]
        assert len(matches) == 1 and matches[0]['stage'] == 'composed'
        assert (matches[0]['after']['scale'], matches[0]['after']['panX'], matches[0]['after']['panY']) == (scale, pan_x, pan_y)
    model = {str(i): adjusted_model(boxes) for i, boxes in states.items()}
    rows = [json.loads(line) for line in (RAW / 'zoom-out-observations.jsonl').read_text().splitlines()]
    previous = next(r for r in rows if r.get('z') == 4 and r.get('k') == 'P' and r.get('n') == 101)
    prepared = next(r for r in rows if r.get('z') == 4 and r.get('k') == 'P' and r.get('n') == 102)
    observed = {'zoom3-last-old-poll': {}, 'zoom4-first-ready-poll': {}, 'final-tree': {}}
    for name, row, state in [('zoom3-last-old-poll', previous, '0'), ('zoom4-first-ready-poll', prepared, '1')]:
        for component, key in [('mi-input', 'i'), ('mi-output', 'o')]:
            actual = row[key][11:15]
            expected = model[state][component]
            expected = [expected[0], expected[1] + 647, expected[2], expected[3] + 647]
            assert actual == expected, (name, component, actual, expected)
            observed[name][component] = actual
    for line in (RAW / 'pinch-failure-tree.txt').read_text().splitlines():
        node = json.loads(line)
        resource = node.get('resource-id') or ''
        for component in ('mi-input', 'mi-not', 'mi-output'):
            if resource.endswith('canvas-component:' + component):
                actual = [int(v) for v in re.findall(r'-?\d+', node['bounds'])]
                predicted = model['2'][component]
                predicted = [predicted[0], predicted[1] + 647, predicted[2], predicted[3] + 647]
                assert actual == predicted, (component, actual, predicted)
                observed['final-tree'][component] = actual
    result = json.loads((RAW / 'result.json').read_bytes())
    assert result['status'] == 'FAIL' and 'Touch pinch did not preserve the injected span ratio' in result['error']
    motions = [r for r in result['events'] if r.get('kind') == 'motion' and r.get('operation') == 'pinch']
    assert len(motions) == 17 and all(r['accepted'] is True for r in motions)
    down = next(r for r in motions if r['actionMasked'] == 5)
    injected_center = sum(p['x'] for p in down['pointers']) / len(down['pointers'])
    assert injected_center == cx(model['1']['mi-not']) == 601.5
    before_span = cx(model['1']['mi-output']) - cx(model['1']['mi-input'])
    after_span = cx(model['2']['mi-output']) - cx(model['2']['mi-input'])
    before_geometric = cx(states[1]['mi-not']['target'])
    after_geometric = cx(states[2]['mi-not']['target'])
    summary = {'status': 'OFFLINE_MODEL_MATCHES_RECORDED_BOUNDS', 'nativeResultPreserved': 'FAIL',
               'states': states, 'adjustedIntegerModel': model, 'observedBounds': observed,
               'actualAcceptedMotionEvents': len(motions), 'injectedCenterX': injected_center,
               'modelAxSpanBefore': before_span, 'modelAxSpanAfter': after_span, 'modelAxSpanRatio': after_span / before_span,
               'recordedScaleRatio': EXPECTED_STATES[2][1] / EXPECTED_STATES[1][1],
               'notBeforeGeometricCenterX': before_geometric, 'notAfterGeometricCenterX': after_geometric,
               'notBeforeAdjustedCenterX': cx(model['1']['mi-not']), 'notAfterAdjustedCenterX': cx(model['2']['mi-not']),
               'modelAdjustedAnchorShift': cx(model['2']['mi-not']) - cx(model['1']['mi-not']),
               'geometricBodyCenterShift': after_geometric - before_geometric, 'originalAnchorTolerancePixels': 4,
               'limits': ['The region subtraction is an offline model of the pinned Compose source, not execution of Android Region.',
                          'The last ready ZoomOut poll and final failure tree do not retain the exact later beforeDistance and ratio-assert operands.',
                          'The counterfactual anchor check did not execute because the ratio assertion failed first.',
                          'The prior AX geometry persisted for multiple polls after a newer composition, but no cache operation or cause isolation occurred.',
                          'The earlier state trace dropped six records; the three referenced compositions and final gesture states remain retained.']}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    report['inputsAfter'] = {str(p): identity(p) for p in inputs}
    report['inputsUnchanged'] = report['inputsAfter'] == before
    assert report['inputsUnchanged']
    report['status'] = 'PASS_OFFLINE_ONLY'
    data = (json.dumps(report, indent=2) + '\n').encode()
    (output / 'report.json').write_bytes(data)
    (output / 'report.json.gz').write_bytes(gzip.compress(data, mtime=0))
    print(json.dumps({'status': report['status'], 'inputFiles': len(inputs),
                      'summary': str(output / 'summary.json'), 'nativeResultPreserved': 'FAIL'}))


if __name__ == '__main__':
    main()
