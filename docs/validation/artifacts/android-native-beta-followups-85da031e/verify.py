#!/usr/bin/env python3
"""Validate archived Native I/O-release and failed pinch evidence without a device."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
RECEIPT_NAME = 'android-native-beta-followups-85da031e.json'
APP = '67e81d691beb3830cf49325c81ccffca9b66f2bf8aa98fdc46c9ee7d1f9e5e2c'
TEST = '04a4fdecd9dfdae6577ce040a7595e8c96bb62ac821080ee6faa0f1de7cde52f'
REVISION = '85da031e4ed11474c2e763976392729810ef142d'
MILESTONES = (
    'button output starts low', 'held button output is high', 'label owns input focus',
    'focus loss releases button before pointer UP', 'button stays released after pointer UP',
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(path.read_text())


def one(items, predicate, message):
    matches = [item for item in items if predicate(item)]
    require(len(matches) == 1, message)
    return matches[0]


def summaries(result):
    return [item['record'] for item in result['readinessDiagnostics']['waitSummaries']['records']]


def milestone(result, description, outcome='READY'):
    record = one(summaries(result), lambda item: item['description'] == description,
                 f'missing or duplicate milestone: {description}')
    require(record['lastOutcome'] == outcome, f'wrong outcome: {description}')
    outcome_delta = record['lastOutcomeAtMillis'] - record['startedAtMillis']
    require(outcome_delta >= 0 and record['elapsedMillis'] >= outcome_delta,
            f'inconsistent milestone timing: {description}')
    if outcome == 'READY':
        require(record['elapsedMillis'] == outcome_delta,
                f'inconsistent ready milestone timing: {description}')
    return record


def check_identity(directory, name, status):
    manifest = read_json(directory / 'manifest.json')
    result = read_json(directory / 'native/result.json')
    require(manifest['status'] == result['status'] == status, f'{name}: wrong preserved status')
    expected_scope = 'PARTIAL' if status == 'PASS' else 'NOT_QUALIFIED'
    require(manifest['qualification'] == result['qualification'] == expected_scope,
            f'{name}: wrong preserved qualification')
    require(manifest['native'] == result, f'{name}: native result differs from manifest')
    require(manifest['requestedCases'] == name and result['cases'][0]['case'] == name and
            len(result['cases']) == 1 and result['cases'][0]['status'] == status,
            f'{name}: wrong case selection/status')
    require(manifest['declaredSourceRevision'] == result['declaredSourceRevision'] == REVISION,
            f'{name}: wrong product revision')
    require(manifest['package'] == result['packageName'] == 'thalesmms.logisim.mobile.e2e',
            f'{name}: wrong isolated target')
    require(manifest['serial'] == 'emulator-5556', f'{name}: wrong emulator')
    hashes = {'appSha256': APP, 'testSha256': TEST}
    require(manifest['artifacts'] == manifest['installedArtifactsAfter'] == hashes,
            f'{name}: installed APK hashes differ')
    require(result['artifact']['sha256Before'] == APP, f'{name}: wrong native app hash')
    if status == 'PASS':
        require(result['artifact']['sha256After'] == APP, f'{name}: wrong native final app hash')
    require(manifest['lastAnrUnchanged'] is True and
            (directory/'lastanr-before.txt').read_bytes() == (directory/'lastanr-after.txt').read_bytes(),
            f'{name}: ANR record changed')
    terminals = re.findall(r'^INSTRUMENTATION_CODE: (.*)$',
                           (directory/'instrumentation.txt').read_text(), re.MULTILINE)
    require(terminals == (['-1'] if status == 'PASS' else ['0']), f'{name}: unexpected terminal')
    session = one(result['events'], lambda item: item['kind'] == 'input-session',
                  f'{name}: input-session count')
    require(session['apiLevel'] == 35 and session['syntheticEvents'] is True,
            f'{name}: wrong SDK/input provenance')
    require(all(item['accepted'] is True for item in result['events'] if 'accepted' in item),
            f'{name}: event/guard not accepted')
    return manifest, result



def ordered_fragments(text, fragments, message):
    position = -1
    for fragment in fragments:
        next_position = text.find(fragment, position + 1)
        require(next_position >= 0, message)
        position = next_position


def check_hold_source_order(root):
    java = (root/'source/NativeMixedInput.java').read_text()
    hold = java[java.index('public void hold('):java.index('public void secondaryTap(')]
    require('contact("hold", tool, x, y, x, y, FRAME_MS, false, false,' in hold and
            'Objects.requireNonNull(whilePressed, "whilePressed")' in hold,
            'io-release: archived hold does not forward callback to contact')
    contact = java[java.index('private void contact('):java.index('private final class Gesture')]
    ordered_fragments(contact, (
        'gesture.emit(MotionEvent.ACTION_DOWN,', 'whilePressed.run();',
        'verifyTarget(name, gesture.operation, "after-callback");',
        'gesture.emit(cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP,'),
        'io-release: archived injector callback is not between DOWN and UP')
    kotlin = (root/'source/MixedInputScenarios.kt').read_text()
    function = kotlin[kotlin.index('private fun focusLossReleasesTheHeldButton()'):kotlin.index('private fun focusLabel(')]
    ordered_fragments(function, (
        'waitFor("button output starts low")', 'inputs.hold(',
        'waitFor("held button output is high")', 'focusLabel(fixture.buttonLabel)',
        'waitFor("focus loss releases button before pointer UP")',
        'screenshot("io-released-before-up")\n        }\n        waitFor("button stays released after pointer UP")'),
        'io-release: archived source milestone/callback order differs')


def check_io_release(directory):
    manifest, result = check_identity(directory, 'io-release', 'PASS')
    case = result['cases'][0]
    require(case['mode']['key'] == 'action_simulate' and case['mode']['selection'] == 'ACTIVE',
            'io-release: interaction mode not observed active')
    motions = [item for item in result['events'] if item['kind'] == 'motion']
    require([item['action'] for item in motions] == ['ACTION_DOWN', 'ACTION_UP'],
            'io-release: incorrect pointer sequence')
    down, up = motions
    require(down['operation'] == up['operation'] == 'hold' and
            down['operationId'] == up['operationId'] == 1 and
            down['downTimeMs'] == up['downTimeMs'] and
            down['pointers'][0]['id'] == up['pointers'][0]['id'] == 0 and
            down['tool'] == up['tool'] == 'TOUCH', 'io-release: held-pointer identity differs')
    records = [milestone(result, description) for description in MILESTONES]
    low, high, focused, before_up, after_up = [item['lastOutcomeAtMillis'] for item in records]
    require(low < high < focused < before_up < after_up,
            'io-release: elapsedRealtime milestone order differs')
    require(down['eventTimeMs'] < up['eventTimeMs'], 'io-release: uptime event order differs')
    guards = [(index, item) for index, item in enumerate(result['events'])
              if item['kind'] == 'foreground-guard']
    require(len(guards) == 2 and guards[0][1]['phase'] == 'before' and
            guards[1][1]['phase'] == 'after-callback', 'io-release: hold guard sequence differs')
    down_index, up_index = [result['events'].index(item) for item in motions]
    require(guards[0][0] < down_index < guards[1][0] < up_index,
            'io-release: DOWN/callback-return/UP event order differs')
    check_hold_source_order(directory.parent)
    require([item['waitId'] for item in records] == sorted(item['waitId'] for item in records),
            'io-release: milestone wait order differs')
    fixture = directory/'native/0-fixture.circ'
    after = directory/'native/1-io-release-after.circ'
    require(after.read_bytes() == fixture.read_bytes(), 'io-release: full XML is not byte-exact')
    root = ET.parse(after).getroot()
    components = root.findall('.//comp')
    require(len(components) == 2 and
            {(item.get('logisim-mobile-id'), item.get('name'), item.get('loc')) for item in components} ==
            {('mi-button', 'Button', '(100,100)'), ('mi-output', 'Pin', '(300,100)')},
            'io-release: component identity/geometry differs')
    require([(wire.get('from'), wire.get('to')) for wire in root.findall('.//wire')] ==
            [('(100,100)', '(300,100)')], 'io-release: full wire topology differs')
    return {'case': 'io-release', 'status': 'PASS', 'caseMillis': case['elapsedMillis'],
            'wrapperSeconds': manifest['elapsedSeconds'],
            'milestoneTimesElapsedMillis': dict(zip(MILESTONES, [low, high, focused, before_up, after_up])),
            'waitMilestoneClock': 'SystemClock.elapsedRealtime',
            'pointerEventClock': 'SystemClock.uptimeMillis',
            'pointerDownEventTimeUptimeMillis': down['eventTimeMs'],
            'pointerUpEventTimeUptimeMillis': up['eventTimeMs'],
            'crossClockComparison': 'NOT_PERFORMED',
            'beforeUpCheckOrder': 'Archived source callback and DOWN/after-callback/UP record order',
            'fullXmlSha256': hashlib.sha256(after.read_bytes()).hexdigest()}


def check_pinch(directory):
    manifest, result = check_identity(directory, 'pinch', 'FAIL')
    case = result['cases'][0]
    require('Timed out waiting for expanding touch pinch zooms in' in case['error'] and
            result['error'] == case['error'], 'pinch: failure stage differs')
    failed_wait = milestone(result, 'expanding touch pinch zooms in', 'RETURNED_FALSE')
    require(case['elapsedMillis'] == 31023, 'pinch: archived case duration differs')
    motions = [item for item in result['events'] if item['kind'] == 'motion']
    actions = ['ACTION_DOWN', 'ACTION_POINTER_DOWN(1)'] + ['ACTION_MOVE'] * 13 + [
        'ACTION_POINTER_UP(1)', 'ACTION_UP']
    require([item['action'] for item in motions] == actions, 'pinch: injected SDK sequence differs')
    require(len({item['downTimeMs'] for item in motions}) == 1 and
            all(item['tool'] == 'TOUCH' and item['source'] == 4098 for item in motions),
            'pinch: injected SDK pointer identity differs')
    require(sorted(path.name for path in (directory/'native').glob('*.circ')) ==
            ['0-fixture.circ', '1-pinch-before.circ'], 'pinch: unexpected after-XML claim')
    require((directory/'native/0-fixture.circ').read_bytes() ==
            (directory/'native/1-pinch-before.circ').read_bytes(), 'pinch: fixture/before XML differs')
    require(not any('ratio' in key.lower() or 'anchor' in key.lower() for key in case),
            'pinch: ratio or anchor result incorrectly added')
    return {'case': 'pinch', 'status': 'FAIL', 'caseMillis': case['elapsedMillis'],
            'wrapperSeconds': manifest['elapsedSeconds'], 'lastWaitOutcome': failed_wait['lastOutcome'],
            'sdkMotionEvents': len(motions), 'canvasEventReception': 'NOT_OBSERVED',
            'ratioAnchorAndAfterXmlChecks': 'NOT_REACHED'}


def verify(root=HERE, receipt=None):
    root = Path(root)
    receipt = Path(receipt) if receipt else root.parent.parent / RECEIPT_NAME
    record = read_json(receipt)
    require(record['fullIssueQualification'] == 'NOT_QUALIFIED', 'whole issue was promoted')
    for item in record['rawInventory']:
        data = (root/item['path']).read_bytes()
        require(len(data) == item['bytes'], f"size mismatch: {item['path']}")
        require(hashlib.sha256(data).hexdigest() == item['sha256'], f"hash mismatch: {item['path']}")
    return {'status': 'PASS_ARCHIVE_CHECK', 'fullIssueQualification': 'NOT_QUALIFIED',
            'rawFiles': len(record['rawInventory']),
            'results': [check_io_release(root/'io-release'), check_pinch(root/'pinch')],
            'scope': 'Archived evidence only; no new runtime execution or physical-device qualification.'}


def negative_controls(root=HERE, receipt=None):
    root = Path(root)
    receipt = Path(receipt) if receipt else root.parent.parent / RECEIPT_NAME
    results = []
    for mutation in ('release_milestone_reordered', 'held_high_not_ready', 'callback_after_up', 'xml_wire_changed',
                     'pinch_promoted_to_pass', 'pinch_after_xml_added'):
        with tempfile.TemporaryDirectory(prefix='native-beta-receipt-negative-') as temporary:
            copy_root = Path(temporary)/'archive'
            shutil.copytree(root, copy_root)
            copy_receipt = Path(temporary)/'receipt.json'
            inventory = read_json(receipt)
            name = 'pinch' if mutation.startswith('pinch') else 'io-release'
            result_path = copy_root/name/'native/result.json'
            result = read_json(result_path)
            if mutation in ('release_milestone_reordered', 'held_high_not_ready'):
                description = MILESTONES[3] if mutation == 'release_milestone_reordered' else MILESTONES[1]
                selected = one(summaries(result), lambda item: item['description'] == description,
                               'negative fixture milestone missing')
                if mutation == 'release_milestone_reordered':
                    final_low = milestone(result, MILESTONES[4])
                    selected['lastOutcomeAtMillis'] = final_low['lastOutcomeAtMillis'] + 1
                    selected['elapsedMillis'] = selected['lastOutcomeAtMillis'] - selected['startedAtMillis']
                else:
                    selected['lastOutcome'] = 'RETURNED_FALSE'
            elif mutation == 'callback_after_up':
                path = copy_root/'source/NativeMixedInput.java'
                java = path.read_text()
                ending = 'gesture.emit(cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP, 1, 0, false);'
                java = java.replace('whilePressed.run();', '__CALLBACK_PLACEHOLDER__', 1)
                java = java.replace(ending, 'whilePressed.run();', 1)
                java = java.replace('__CALLBACK_PLACEHOLDER__', ending, 1)
                path.write_text(java)
            elif mutation == 'xml_wire_changed':
                path = copy_root/name/'native/1-io-release-after.circ'
                tree = ET.parse(path)
                tree.find('.//wire').set('to', '(310,100)')
                tree.write(path)
            elif mutation == 'pinch_promoted_to_pass':
                result.update(status='PASS', qualification='PARTIAL')
                result['cases'][0]['status'] = 'PASS'
            else:
                path = copy_root/name/'native/2-pinch-after.circ'
                path.write_bytes((copy_root/name/'native/1-pinch-before.circ').read_bytes())
                inventory['rawInventory'].append({'path': str(path.relative_to(copy_root))})
            result_path.write_text(json.dumps(result))
            manifest_path = copy_root/name/'manifest.json'
            manifest = read_json(manifest_path)
            manifest['native'] = result
            if mutation == 'pinch_promoted_to_pass':
                manifest.update(status='PASS', qualification='PARTIAL')
            manifest_path.write_text(json.dumps(manifest))
            for item in inventory['rawInventory']:
                data = (copy_root/item['path']).read_bytes()
                item.update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
            copy_receipt.write_text(json.dumps(inventory))
            try:
                verify(copy_root, copy_receipt)
            except ValueError as error:
                require('hash mismatch' not in str(error) and 'size mismatch' not in str(error),
                        'negative stopped at integrity check instead of semantic condition')
                results.append({'mutation': mutation, 'status': 'REJECTED', 'reason': str(error)})
            else:
                raise ValueError(f'negative control passed: {mutation}')
    return {'status': 'PASS', 'checks': len(results), 'results': results,
            'scope': 'Offline semantic mutations with recomputed payload hashes; no runtime execution.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=HERE)
    parser.add_argument('--receipt', type=Path)
    parser.add_argument('--negative-controls', action='store_true')
    arguments = parser.parse_args()
    check = negative_controls if arguments.negative_controls else verify
    print(json.dumps(check(arguments.root, arguments.receipt), indent=2))
