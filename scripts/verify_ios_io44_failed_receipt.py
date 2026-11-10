"""Verify the archived four-failure I/O run using local, frozen evidence only."""
import argparse
import importlib.util
import json
from pathlib import Path
import plistlib
import re
import tempfile

from ios_io44_case_postflight import METHODS, DEFAULT_RUN, original_name, run_postflight, sha

DEFAULT_BUNDLE = DEFAULT_RUN.parent
CLAIM = {'nativeMethods': 'four-failed', 'qualifiedCheckpointPairs': 0, 'issue44': 'not-qualified',
         'firstDimensionsCheckpoint': 'INCOMPLETE_AFTER_XML_CAPTURE', 'firstDimensionsReason': 'METADATA_MISSING',
         'otherDocumentStages': '17-NOT_REACHED', 'closeEventDelivery': 'not-established',
         'baselineAcrossEntireTimeout': 'not-established', 'engineResetInternals': 'not-observed'}
FRAME = re.compile(r'\{\{([-\d.]+), ([-\d.]+)\}, \{([-\d.]+), ([-\d.]+)\}\}')


def decode_archive(data):
    archive = plistlib.loads(data)
    objects = archive['$objects']
    def decode(value, depth=0):
        assert depth < 64
        if isinstance(value, plistlib.UID):
            assert 0 <= value.data < len(objects)
            return decode(objects[value.data], depth + 1)
        if isinstance(value, list):
            return [decode(item, depth + 1) for item in value]
        if isinstance(value, dict):
            if 'NS.keys' in value and 'NS.objects' in value:
                assert len(value['NS.keys']) == len(value['NS.objects'])
                return {str(decode(key, depth + 1)): decode(item, depth + 1)
                        for key, item in zip(value['NS.keys'], value['NS.objects'])}
            if 'NS.objects' in value:
                return [decode(item, depth + 1) for item in value['NS.objects']]
            return {key: decode(item, depth + 1) for key, item in value.items()}
        return value
    return decode(archive['$top']['root'])


def canvas_snapshot(tree, oracle, expected):
    lines = tree.splitlines()
    marker = next(line for line in lines if "identifier: 'editor-canvas'" in line)
    frame = tuple(map(float, FRAME.search(marker).groups()))
    labels = []
    for line in lines:
        if "label: 'Circuit area." not in line:
            continue
        bounds = FRAME.search(line)
        if bounds and tuple(map(float, bounds.groups())) == frame:
            labels.append(re.search(r"label: '(.*)'", line).group(1))
    match19 = []
    for label in labels:
        try:
            oracle.assert_probe_description(label, expected)
        except AssertionError:
            match19.append(False)
        else:
            match19.append(True)
    return {'frame': list(frame), 'summariesAtFirstMarkerFrame': len(labels), 'match19': match19,
            'uniqueSelectorReturnsValue': len(labels) == 1}


def verify_receipt(bundle=DEFAULT_BUNDLE):
    bundle = Path(bundle)
    receipt = json.loads((bundle / 'receipt.json').read_text())
    assert receipt['schemaVersion'] == 1 and receipt['claim'] == CLAIM, 'Four FAILs and observational limits must remain intact'
    for name, entry in receipt['artifacts'].items():
        assert sha(bundle / name) == entry['sha256'], 'Artifact hash mismatch: ' + name
    run = bundle / 'run'
    results = json.loads((run / 'results.json').read_text())
    admission = json.loads((run / 'artifact-admission.json').read_text())
    plan = json.loads((run / 'execution-plan.json').read_text())
    cleanup = json.loads((run / 'post-cleanup.json').read_text())
    assert results['status'] == 'FAIL' and results['execution']['status'] == 'FAIL' and results['execution']['exit_code'] == 65
    assert results['native_execution_gate'] == 'NATIVE_FAIL' and results['qualification_gate'] == 'FAIL'
    assert set(results['selected_tests']) == {'EditorEndToEndTests/IoEndToEndTests/' + method for method in METHODS}
    assert results['artifact']['bundle_tree_sha256'] == admission['bundleTreeSha256'] == plan['appBundleTreeSha256'] == cleanup['bundleTreeSha256']
    assert admission['source'] == plan['appSource'] == receipt['producerPin']
    assert cleanup['testStatus'] == 'FAIL' and cleanup['nativeExecution'] == 'NATIVE_FAIL' and cleanup['runnerExit'] == 65
    assert cleanup['status'] == 'PASS' and cleanup['ownedPidsStillRunning'] is False
    for name, expected in results['harness_sources'].items():
        assert sha(run / 'harness' / name) == expected, 'Staged helper changed: ' + name
    for name, expected in plan['sourceFiles'].items():
        assert sha(bundle / 'source-tree' / name) == expected, 'Planned source changed: ' + name
    oracle_path = bundle / 'oracle/scripts/io_showcase_contract.py'
    spec = importlib.util.spec_from_file_location('frozen_io44_receipt_oracle', oracle_path)
    oracle = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(oracle)
    contract = oracle.load_io_contract(bundle / 'oracle/fixtures/user-scenarios/io-actions-v1.json')
    assert sha(bundle / 'oracle/fixtures/user-scenarios/io-actions-v1.json') == sha(run / 'harness/io-actions-v1.json')
    for name in ('scripts/io_showcase_contract.py', 'fixtures/user-scenarios/io-actions-v1.json', 'examples/io-showcase.circ', 'examples/io-showcase.targets.json'):
        assert sha(bundle / 'oracle' / name) == plan['sourceFiles'][name]
    fixture = bundle / 'oracle/examples/io-showcase.circ'
    fixture_sha = sha(fixture)
    baseline = json.loads((bundle / 'diagnostics/logisim-ios44-baseline-review.json').read_text())
    assert baseline['expected'] == contract['baselineOutputs'] and len(baseline['cases']) == 4
    manifest = json.loads((run / 'attachments/manifest.json').read_text())
    groups = {group['testIdentifier']: group for group in manifest}
    assert len(groups) == 4
    case_observations, pids, nonces = [], [], []
    for case in baseline['cases']:
        group = groups[case['case']]
        names = {attachment['exportedFileName'] for attachment in group['attachments']}
        tree_name = case['treeAttachment']['exportedFileName']
        assert tree_name in names
        tree = (run / 'attachments' / tree_name).read_text()
        assert sha(run / 'attachments' / tree_name) == case['treeSha256']
        pid = int(re.search(r'pid: (\d+)', tree).group(1))
        assert pid == case['appPid']; pids.append(pid)
        observed = canvas_snapshot(tree, oracle, contract['baselineOutputs'])
        dimension = 'testIoDimensionChangesRemainExportable' in case['case']
        assert observed['summariesAtFirstMarkerFrame'] == (1 if dimension else 2)
        assert observed['match19'] == ([False] if dimension else [True, True])
        assert len(case['ownedNonceFixtures']) == 1
        nonce = case['ownedNonceFixtures'][0]['file']; nonces.append(nonce)
        assert sha(run / 'documents' / nonce) == fixture_sha
        assert any(nonce in (run / 'attachments' / source['attachment']).read_text() for source in case['nonceSources'])
        final_screens = [attachment for attachment in group['attachments'] if original_name(attachment['suggestedHumanReadableName']) == 'final-screen.png']
        assert len(final_screens) == 1 and (run / 'attachments' / final_screens[0]['exportedFileName']).is_file()
        assert len(case['nativeIoProbeAttachments']) == (1 if dimension else 0)
        for probe in case['nativeIoProbeAttachments']:
            assert probe['attachment'] in names
            oracle.assert_probe_description((run / 'attachments' / probe['attachment']).read_text(), contract['baselineOutputs'])
        case_observations.append({'testCase': case['case'], 'pid': pid, 'nonce': nonce, **observed})
    assert len(set(pids)) == len(set(nonces)) == 4
    with tempfile.TemporaryDirectory() as temporary:
        postflight = run_postflight(run, bundle / 'oracle', Path(temporary))
    assert all(case['nativeMethodStatus'] == 'failed' for case in postflight['cases'])
    summary = postflight['summary']
    assert summary['pairedDocumentsPassed'] == summary['metadataAttachments'] == 0
    assert summary['orphanDocumentAttachments'] == summary['incompleteAfterXmlCapture'] == 1
    assert summary['notReachedDocumentStages'] == 17 and summary['expectedDocumentsIfAllFourMethodsComplete'] == 18
    dimension = next(case for case in postflight['cases'] if 'DimensionChanges' in case['testCase'])
    assert dimension['missingCheckpoints'][0] == {'document': '1-io-fixture-opened.circ', 'status': 'INCOMPLETE_AFTER_XML_CAPTURE', 'reason': 'METADATA_MISSING'}
    orphan = run / 'attachments' / dimension['orphanDocumentAttachments'][0]['exportedFileName']
    receiver = run / 'attachments/7FE424D3-DB61-42D5-B74E-BE02C9DC591B.circ'
    assert sha(orphan) == sha(receiver) == fixture_sha
    project = oracle.assert_io_document(orphan.read_bytes(), contract)
    assert len(project.findall('circuit/comp')) == 54 and len(project.findall('circuit/wire')) == 64
    raw_event = run / 'attachments/69F75554-83DC-4EA3-8AB7-7A7D7C6EF799'
    event = decode_archive(raw_event.read_bytes())
    assert event == json.loads((bundle / 'diagnostics/close/close-synthesized-event-decoded.json').read_text())
    assert event['targetProcessID'] == 76677 and event['name'] == 'tap'
    points = [(point['coordinate.x'], point['coordinate.y'], point['offset'])
              for path in event['eventPaths'] for point in path['pointerEvents']]
    assert points == [(492.29816714665515, 703.1480597029523, 0.0), (492.29816714665515, 703.1480597029523, 0.05)]
    assert len(receipt['emptyWaitSnapshots']) == 10
    for name in receipt['emptyWaitSnapshots']:
        text = (run / 'attachments' / name).read_text()
        assert 'Application' in text and re.search(r'\bWindow(?:\s+\([^)]*\))?,', text) is None and 'editor-xml-body' not in text
    assert len(receipt['selectedVideoFrames']) == 4
    return {'status': 'verified-failed-native-run', 'nativeMethods': 'four-failed', 'qualifiedCheckpointPairs': 0,
            'documentStages': {'incompleteAfterXmlCapture': 1, 'reason': 'METADATA_MISSING', 'notReached': 17},
            'orphanXmlContent': 'complete-fixture-unpaired-not-qualified', 'caseObservations': case_observations,
            'closeEvent': 'synthesis-verified-delivery-not-established', 'emptyWaitSnapshots': 10,
            'videoEvidence': 'four-retained-extracted-frames-full-stream-not-archived', 'issue44': 'not-qualified'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, default=DEFAULT_BUNDLE)
    args = parser.parse_args()
    print(json.dumps(verify_receipt(args.bundle), indent=2))


if __name__ == '__main__':
    main()
