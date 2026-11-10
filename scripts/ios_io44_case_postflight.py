"""Correlate retained I/O checkpoints by XCTest case and original attachment name.

Only local files and the existing pure Python document postflight are used.
"""
import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RUN = ROOT / 'docs/validation/io44-ios-3a0a01f1-failed/run'
DEFAULT_REPOSITORY = DEFAULT_RUN.parent / 'oracle'
METHODS = ('testIoDisplaySignalsAndNativeDrawing', 'testIoDimensionChangesRemainExportable',
           'testIoMomentaryControlsAndJoystick', 'testIoKeyboardTtyAndCapacity')
UUID = r'[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}'


class NotReady(Exception):
    pass


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def original_name(suggestion):
    match = re.fullmatch(r'(.+)_\d+_' + UUID + r'(\.[A-Za-z0-9-]+)?', suggestion)
    return match.group(1) + (match.group(2) or '') if match else suggestion


def expected_checkpoints(method, contract):
    checkpoints = [('1-io-fixture-opened.circ', {})]
    if method == 'testIoDimensionChangesRemainExportable':
        cumulative = {}
        for edit in contract['dimensionEdits']:
            suffix = edit['label'].lower() + '-' + edit['name']
            for phase, value in (('dimension', edit['value']), ('restored', edit['restore'])):
                cumulative.setdefault(edit['label'], {})[edit['name']] = value
                checkpoints.append((f'{len(checkpoints)+1}-io-{phase}-{suffix}.circ', deepcopy(cumulative)))
        checkpoints.append((f'{len(checkpoints)+1}-io-dimensions-restored.circ', deepcopy(cumulative)))
    else:
        suffix, attributes = {
            METHODS[0]: ('io-display-transitions', {}),
            METHODS[2]: ('io-pointer-controls', {}),
            METHODS[3]: ('io-keyboard-capacity', {'IO_KEYBOARD': {'buflen': '4'}}),
        }[method]
        checkpoints.append(('2-' + suffix + '.circ', attributes))
    return checkpoints


def native_status(test_log, method):
    name = 'EditorEndToEndTests.IoEndToEndTests ' + method
    prefix = "Test Case '-[" + name + "]' "
    terminal = re.findall(re.escape(prefix) + r'(passed|failed|skipped) \(', test_log)
    assert len(terminal) <= 1, f'Ambiguous terminal XCTest records for {method}'
    if terminal:
        return terminal[0]
    return 'incomplete' if prefix + 'started.' in test_log else 'not_run'


def attachment_path(root, attachment):
    name = attachment['exportedFileName']
    assert name == Path(name).name and name not in ('.', '..'), 'Invalid exported attachment path'
    path = root / name
    assert path.is_file(), f'Missing exported attachment: {name}'
    return path


def run_postflight(run=DEFAULT_RUN, repository=DEFAULT_REPOSITORY, output=None):
    run, repository = Path(run), Path(repository)
    report_path, manifest_path = run / 'results.json', run / 'attachments/manifest.json'
    if not report_path.is_file() or not manifest_path.is_file():
        raise NotReady('The terminal runner report and exported attachment manifest must exist')
    report = json.loads(report_path.read_text())
    if not report.get('finished') or report.get('status') not in ('PASS', 'FAIL') or report.get('execution', {}).get('status') not in ('PASS', 'FAIL'):
        raise NotReady('The owner execution has not finished; no postflight was run')
    log_path = run / 'test.log'
    if not log_path.is_file():
        raise NotReady('The terminal test log must exist for case-specific outcomes')
    manifest = json.loads(manifest_path.read_text())
    assert isinstance(manifest, list), 'Expected xcresult export attachment groups'
    contract_path = repository / 'fixtures/user-scenarios/io-actions-v1.json'
    contract = json.loads(contract_path.read_text())
    test_log = log_path.read_text()
    output = Path(output) if output else run / 'io44-document-postflight'
    output.mkdir(parents=True, exist_ok=True)
    result = {'schemaVersion': 1, 'scope': 'case-scoped-document-postflight',
              'nativeExecution': 'not-inferred-by-the-document-oracle', 'issue44': 'not-qualified',
              'runStatus': report['status'], 'finished': report['finished'],
              'sourceSha256': {'runnerReport': sha(report_path), 'attachmentManifest': sha(manifest_path),
                               'testLog': sha(log_path), 'ioContract': sha(contract_path),
                               'documentOracle': sha(repository / 'scripts/io_showcase_contract.py')}, 'cases': []}
    identities = [group.get('testIdentifier') for group in manifest]
    assert len(identities) == len(set(identities)), 'Ambiguous duplicate testcase attachment groups'
    for method in METHODS:
        testcase = 'IoEndToEndTests/' + method + '()'
        groups = [group for group in manifest if group.get('testIdentifier') == testcase]
        native = native_status(test_log, method)
        expected = dict(expected_checkpoints(method, contract))
        item = {'testCase': testcase, 'nativeMethodStatus': native,
                'expectedCheckpointCount': len(expected), 'metadataAttachments': 0,
                'pairedDocumentCount': 0, 'checkpointResults': [], 'missingCheckpoints': [],
                'orphanDocumentAttachments': [], 'errors': []}
        result['cases'].append(item)
        if not groups:
            item['postflightStatus'] = 'NOT_RUN'
            item['missingCheckpoints'] = [{'document': name, 'status': 'NOT_REACHED'} for name in expected]
            if native == 'passed':
                item['errors'].append('Native method passed but its scoped attachment group is missing')
                item['postflightStatus'] = 'failed'
            continue
        group = groups[0]
        assert group.get('testIdentifierURL', '').endswith('/IoEndToEndTests/' + method), 'Test identifier and URL disagree'
        case_dir = output / method
        case_dir.mkdir(exist_ok=True)
        (case_dir / 'attachment-manifest.json').write_text(json.dumps(group, indent=2) + '\n')
        by_name = {}
        for attachment in group['attachments']:
            name = original_name(attachment['suggestedHumanReadableName'])
            by_name.setdefault(name, []).append(attachment)
        paired = set()
        for meta_name, candidates in by_name.items():
            if not meta_name.endswith('.io-checkpoint.json'):
                continue
            item['metadataAttachments'] += len(candidates)
            checkpoint = {'metadataAttachmentName': meta_name, 'status': 'failed'}
            item['checkpointResults'].append(checkpoint)
            try:
                assert len(candidates) == 1, f'Ambiguous metadata attachments within {testcase}: {meta_name}'
                metadata_path = attachment_path(run / 'attachments', candidates[0])
                metadata = json.loads(metadata_path.read_text())
                document = metadata['document']
                checkpoint['documentAttachmentName'] = document
                assert document in expected, f'Unexpected checkpoint in {testcase}: {document}'
                assert meta_name == document.removesuffix('.circ') + '.io-checkpoint.json', 'Original metadata name disagrees with its document'
                assert document not in paired, f'Duplicate metadata for {document}'
                assert metadata['expectedAttributes'] == expected[document], 'Checkpoint attributes differ from this method contract stage'
                documents = by_name.get(document, [])
                assert len(documents) == 1, f'Expected one XML named {document} in {testcase}, found {len(documents)}'
                document_path = attachment_path(run / 'attachments', documents[0])
                destination = case_dir / (document + '.postflight.json')
                command = [sys.executable, str(repository / 'scripts/io_showcase_contract.py'),
                           '--metadata', str(metadata_path), '--document', str(document_path),
                           '--document-attachment-name', document, '--contract', str(contract_path),
                           '--targets', str(repository / 'examples/io-showcase.targets.json'), '--output', str(destination)]
                checked = subprocess.run(command, capture_output=True, text=True, check=False)
                checkpoint.update(metadataExportedFile=candidates[0]['exportedFileName'],
                                  documentExportedFile=documents[0]['exportedFileName'],
                                  metadataSha256=sha(metadata_path), documentSha256=sha(document_path),
                                  oracleExitCode=checked.returncode, oracleRecord=str(destination))
                assert checked.returncode == 0, 'Existing document oracle rejected checkpoint: ' + checked.stdout[-1000:]
                oracle = json.loads(destination.read_text())
                assert oracle['status'] == 'passed' and oracle['nativeExecution'] == 'not-inferred'
                checkpoint['status'] = 'passed'
                paired.add(document)
            except (AssertionError, KeyError, ValueError, OSError) as error:
                checkpoint['error'] = str(error)
                item['errors'].append(str(error))
        item['pairedDocumentCount'] = len(paired)
        for name, candidates in by_name.items():
            if name.endswith('.circ') and name not in paired:
                for attachment in candidates:
                    path = attachment_path(run / 'attachments', attachment)
                    item['orphanDocumentAttachments'].append({'originalName': name, 'exportedFileName': attachment['exportedFileName'],
                        'sha256': sha(path), 'status': 'unpaired-not-qualified',
                        'reason': 'No successful metadata/XML pair from this testcase'})
        captured = {entry['originalName'] for entry in item['orphanDocumentAttachments']}
        attempted = {entry.get('documentAttachmentName') for entry in item['checkpointResults']}
        for name in expected:
            if name in paired:
                continue
            if name in captured:
                item['missingCheckpoints'].append({'document': name, 'status': 'INCOMPLETE_AFTER_XML_CAPTURE',
                    'reason': 'METADATA_MISSING' if name not in attempted else 'METADATA_OR_PAIR_INVALID'})
            else:
                item['missingCheckpoints'].append({'document': name, 'status': 'NOT_REACHED'})
        item['postflightStatus'] = ('failed' if item['errors'] or (native == 'passed' and item['missingCheckpoints']) else
                                    'passed' if native == 'passed' and len(paired) == len(expected) else
                                    'partial' if paired else 'INCOMPLETE_AFTER_XML_CAPTURE' if captured else 'NOT_RUN')
    result['summary'] = {'pairedDocumentsPassed': sum(item['pairedDocumentCount'] for item in result['cases']),
                         'metadataAttachments': sum(item['metadataAttachments'] for item in result['cases']),
                         'expectedDocumentsIfAllFourMethodsComplete': sum(item['expectedCheckpointCount'] for item in result['cases']),
                         'orphanDocumentAttachments': sum(len(item['orphanDocumentAttachments']) for item in result['cases']),
                         'nativeMethodFailures': [item['testCase'] for item in result['cases'] if item['nativeMethodStatus'] == 'failed'],
                         'unqualifiedDocumentStages': sum(len(item['missingCheckpoints']) for item in result['cases']),
                         'incompleteAfterXmlCapture': sum(stage['status'] == 'INCOMPLETE_AFTER_XML_CAPTURE' for item in result['cases'] for stage in item['missingCheckpoints']),
                         'notReachedDocumentStages': sum(stage['status'] == 'NOT_REACHED' for item in result['cases'] for stage in item['missingCheckpoints']),
                         'status': 'document-checks-only-no-native-or-issue-qualification'}
    (output / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, default=DEFAULT_RUN)
    parser.add_argument('--repository', type=Path, default=DEFAULT_REPOSITORY)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        result = run_postflight(args.run, args.repository, args.output)
    except NotReady as error:
        print(json.dumps({'status': 'NOT_RUN', 'reason': str(error)}))
        return 2
    print(json.dumps(result['summary'], indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
