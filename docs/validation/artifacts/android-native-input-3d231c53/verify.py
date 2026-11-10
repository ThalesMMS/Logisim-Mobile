#!/usr/bin/env python3
"""Check the archived Native results and complete circuit documents without a device."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
RECEIPT = HERE.parent.parent / 'android-native-input-3d231c53.json'
APP = '6c094eb693758117fd19ab5b54c0a87718cec5046d34e4ef8566dfe5128f3556'
TESTS = {
    'cancel': '31d6e6cad94ac10f6a0bf21b5bd9c675b2ea80cbbfb71f7917f554f394b97f70',
    'context': '31d6e6cad94ac10f6a0bf21b5bd9c675b2ea80cbbfb71f7917f554f394b97f70',
    'mixed': 'f5bca14d490db09c2045700f525251138568b961d918532ecfa23a20b56db267',
}
BASE_WIRES = [('(100,100)', '(170,100)'), ('(200,100)', '(300,100)')]
KEY_WIRES = [('(100,100)', '(180,100)'), ('(210,100)', '(300,100)')]
MOVE_WIRES = [('(100,100)', '(220,100)'), ('(220,100)', '(220,120)'),
              ('(250,120)', '(300,120)'), ('(300,100)', '(300,120)')]
STYLUS_WIRES = [('(100,100)', '(230,100)'), ('(230,100)', '(230,120)'),
                ('(260,120)', '(300,120)'), ('(300,100)', '(300,120)')]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def tree_model(element):
    return (element.tag, sorted(element.attrib.items()), (element.text or '').strip(),
            [tree_model(child) for child in element])


def without_components_and_wires(root):
    root = deepcopy(root)
    for circuit in root.findall('circuit'):
        for child in list(circuit):
            if child.tag in ('comp', 'wire'):
                circuit.remove(child)
    return tree_model(root)


def check_document(path, baseline, loc='(200,100)', wires=BASE_WIRES, duplicate=False):
    root = ET.parse(path).getroot()
    require(without_components_and_wires(root) == without_components_and_wires(baseline),
            f'{path.name}: project metadata differs')
    actual = {c.get('logisim-mobile-id'): c for c in root.findall('.//comp')}
    expected = {c.get('logisim-mobile-id'): deepcopy(c) for c in baseline.findall('.//comp')}
    expected['mi-not'].set('loc', loc)
    if duplicate:
        expected['c1'] = deepcopy(expected['mi-not'])
        expected['c1'].set('logisim-mobile-id', 'c1')
        expected['c1'].set('loc', '(220,120)')
    require(len(actual) == len(root.findall('.//comp')), f'{path.name}: duplicate component ID')
    require(set(actual) == set(expected), f'{path.name}: component IDs differ')
    require({k: tree_model(v) for k, v in actual.items()} ==
            {k: tree_model(v) for k, v in expected.items()}, f'{path.name}: component differs')
    actual_wires = sorted((w.get('from'), w.get('to')) for w in root.findall('.//wire'))
    require(actual_wires == sorted(wires), f'{path.name}: complete wire topology differs')


def verify():
    receipt = json.loads(RECEIPT.read_text())
    require(receipt['fullIssueQualification'] == 'NOT_QUALIFIED', 'whole issue was promoted')
    for item in receipt['rawInventory']:
        data = (HERE / item['path']).read_bytes()
        require(len(data) == item['bytes'], f"size mismatch: {item['path']}")
        require(hashlib.sha256(data).hexdigest() == item['sha256'], f"hash mismatch: {item['path']}")
    for name, test_hash in TESTS.items():
        directory = HERE / name
        manifest = json.loads((directory / 'manifest.json').read_text())
        result = json.loads((directory / 'native/result.json').read_text())
        require(manifest['status'] == result['status'] == 'PASS', f'{name}: status')
        require(manifest['qualification'] == result['qualification'] == 'PARTIAL', f'{name}: scope')
        require(manifest['requestedCases'] == name, f'{name}: selected case')
        require(result == manifest['native'], f'{name}: result/manifest differ')
        expected_hashes = {'appSha256': APP, 'testSha256': test_hash}
        require(manifest['artifacts'] == manifest['installedArtifactsAfter'] == expected_hashes,
                f'{name}: installed APKs differ')
        require(result['artifact']['sha256Before'] == result['artifact']['sha256After'] == APP,
                f'{name}: native app hash differs')
        require(manifest['lastAnrUnchanged'] is True, f'{name}: ANR changed')
        require((directory/'lastanr-before.txt').read_bytes() ==
                (directory/'lastanr-after.txt').read_bytes(), f'{name}: ANR records differ')
        require(len(result['cases']) == 1 and result['cases'][0]['case'] == name and
                result['cases'][0]['status'] == 'PASS', f'{name}: native case status')
        require(result['events'][0]['apiLevel'] == 35 and
                result['events'][0]['syntheticEvents'] is True, f'{name}: API/input provenance')
        require(all(e['accepted'] is True for e in result['events'] if 'accepted' in e),
                f'{name}: input not accepted')
        case = result['cases'][0]
        require(case['mode']['selection'] == 'ACTIVE', f'{name}: Select mode not active')
        motions = [e for e in result['events'] if e['kind'] == 'motion']
        baseline_path = directory / 'native/0-fixture.circ'
        baseline = ET.parse(baseline_path).getroot()
        if name == 'cancel':
            for filename in ('1-cancel-before.circ', '2-cancel-after.circ', '4-cancel-one-undo.circ'):
                require((directory/'native'/filename).read_bytes() == baseline_path.read_bytes(),
                        f'{name}: {filename} is not byte-exact')
            check_document(directory/'native/3-cancel-valid-move.circ', baseline, '(250,120)', MOVE_WIRES)
            for operation, ending in ((1, 'ACTION_CANCEL'), (2, 'ACTION_UP')):
                events = [e for e in motions if e['operationId'] == operation]
                require([e['action'] for e in events] == ['ACTION_DOWN'] + ['ACTION_MOVE']*10 + [ending],
                        f'cancel: operation {operation} sequence')
                require(len({e['downTimeMs'] for e in events}) == 1 and
                        all(e['tool'] == 'TOUCH' and e['pointers'][0]['id'] == 0 for e in events),
                        f'cancel: operation {operation} pointer identity')
            proof = case['cancelViewportProof']
            require(proof['complete'] and proof['validated'] and proof['before']['frame'] == proof['after'],
                    'cancel: viewport changed')
        elif name == 'context':
            for filename in ('1-context-before.circ', '3-context-undo.circ'):
                require((directory/'native'/filename).read_bytes() == baseline_path.read_bytes(),
                        f'context: {filename} is not byte-exact')
            check_document(directory/'native/2-context-duplicate.circ', baseline, duplicate=True)
            require(case['contextEditorReturnWindowId'] == case['editorWindowId'] !=
                    case['contextPopupWindowId'], 'context: editor window return')
            require(any(e['buttons'] == 2 and e['tool'] == 'MOUSE' for e in motions),
                    'context: secondary mouse input missing')
        else:
            for filename in ('1-mixed-before.circ', '5-mixed-keyboard-undo.circ'):
                require((directory/'native'/filename).read_bytes() == baseline_path.read_bytes(),
                        f'mixed: {filename} is not byte-exact')
            check_document(directory/'native/2-mixed-keyboard.circ', baseline, '(210,100)', KEY_WIRES)
            check_document(directory/'native/3-mixed-stylus.circ', baseline, '(260,120)', STYLUS_WIRES)
            require((directory/'native/4-mixed-stylus-undo.circ').read_bytes() ==
                    (directory/'native/2-mixed-keyboard.circ').read_bytes(), 'mixed: first undo differs')
            stylus = [e for e in motions if e['tool'] == 'STYLUS']
            require([e['action'] for e in stylus] == ['ACTION_DOWN'] + ['ACTION_MOVE']*10 + ['ACTION_UP'],
                    'mixed: stylus sequence')
            require(any(e['kind'] == 'key' and e['keyCode'] == 22 for e in result['events']),
                    'mixed: right-arrow input missing')
            proof = case['mixedViewportProof']
            require(proof['complete'] and proof['validated'] and proof['before']['frame'] == proof['after']
                    and proof['worldDelta'] == {'x': 10, 'y': 0}, 'mixed: target translation proof')
    return {'status': 'PASS', 'cases': list(TESTS), 'rawFiles': len(receipt['rawInventory']),
            'scope': 'Archived artifacts only. No new runtime execution; issue 27 remains partially qualified.'}


if __name__ == '__main__':
    print(json.dumps(verify(), indent=2))
