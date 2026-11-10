#!/usr/bin/env python3
"""Replay one frozen Files failure; never run Android, Git or a network client."""
import ast
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

BASE = Path(__file__).resolve().parent
SOURCE = 'abc480b3c7e6a67442c5ca33e22c5014d2e29f68'
APP_SOURCE = '3a0a01f1af05dd3fea75d5a1499c762d039c478a'
SNAPSHOT_MANIFEST_SHA = '8e874a1e80ea99eb559daf027d11d469eb1faa2d869f3ab1069cdb773318bf32'
COPY_RECEIPT_SHA = '29f4a1da338c3a1a8fb29133b607e6d3099844238ff7efe21d85248cc84696be'
DRIVER_SHA = 'fad8b094f096b14268161b13b39383352bf5a99750a493338d2df426201a9d63'
PACKAGE = 'thalesmms.logisim.mobile.e2e'
APKS = {PACKAGE: '10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973',
        PACKAGE + '.test': '19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b',
        'thalesmms.logisim.mobile.snapshot.e2e.test': '504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c'}
CASES = ['incoming-share-imports-a-copy', 'saf-local-import-preserves-source',
         'cancel-open-preserves-project', 'cancel-export-preserves-project', 'cancel-share-preserves-project',
         'external-receiver-reads-share-grant', 'invalid-and-incompatible-files-preserve-project',
         'save-as-local-document', 'recover-create-document-after-process-death']
ERROR = 'The status_file_opened control did not appear within 30s'
STAGED = 'logisim-e2e-source-android35-files-c3d7-5558-001.circ'
PRIVATE_STEMS = ('saf-local-import-preserves-source', 'native-file-workflow', 'failure')
READINESS = ('cancel-open-native-presented', 'cancel-export-native-presented', 'cancel-share-native-presented')
TOKENS = ('565fd4848f2d45b7a5eec196ccf17d74', 'b2b4a0bc78294733a7e619e401c09609', 'aa7bea2d4d7441a899115cc43f3f3db0')


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path):
    return json.loads(path.read_text())


def safe_files(folder):
    require(folder.is_dir() and not folder.is_symlink(), 'Missing or linked evidence directory')
    entries = list(folder.rglob('*'))
    require(not any(path.is_symlink() for path in entries), 'Evidence must not contain links')
    return [path for path in entries if path.is_file()]


def authenticate(base):
    require(base.is_dir() and not base.is_symlink(), 'The packet must be a physical directory')
    actual = {str(path.relative_to(base)): path for name in ('raw', 'control', 'oracle-snapshot', 'private')
              for path in safe_files(base / name)}
    for name in ('copy-receipt.json', 'local-tar-references.json'):
        path = base / name
        require(path.is_file() and not path.is_symlink(), 'Missing or linked provenance file')
        actual[name] = path
    inventory = read_json(base / 'inventory.json')
    require(set(actual) == set(inventory['files']) and len(actual) == inventory['fileCount'], 'Evidence inventory differs')
    require(sum(path.stat().st_size for path in actual.values()) == inventory['bytes'], 'Evidence byte count differs')
    for name, path in actual.items():
        require(inventory['files'][name] == {'bytes': path.stat().st_size, 'sha256': digest(path)}, 'Evidence changed: ' + name)
    snapshot = base / 'oracle-snapshot'
    require(digest(snapshot / 'manifest.json') == SNAPSHOT_MANIFEST_SHA, 'Historical source manifest changed')
    source = read_json(snapshot / 'manifest.json')
    require(source['source'] == SOURCE, 'Wrong historical source')
    require({str(p.relative_to(snapshot)) for p in safe_files(snapshot)} == set(source['files']) | {'manifest.json'}, 'Historical source inventory differs')
    for name, expected in source['files'].items():
        path = snapshot / name
        require({'bytes': path.stat().st_size, 'sha256': digest(path)} == expected, 'Historical source changed: ' + name)
    return snapshot, source


def load_oracle(snapshot):
    spec = importlib.util.spec_from_file_location('historical_files_sap1_oracle', snapshot / 'scripts/user_scenario_contract.py')
    oracle = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(oracle)
    return oracle


def results_partition(results):
    expected = [{'scenario': CASES[0], 'status': 'passed', 'seconds': 63.21},
                {'scenario': CASES[1], 'status': 'failed', 'error': ERROR},
                {'scenario': 'native-file-workflow', 'status': 'failed', 'error': ERROR}]
    require(results == expected, 'Original Files PASS/FAIL prefix differs')
    return {case: 'PASS' if index == 0 else 'FAIL' if index == 1 else 'NOT_RUN' for index, case in enumerate(CASES)}


def verify(base=BASE):
    base = Path(base)
    snapshot, source = authenticate(base)  # Authenticate all historical Python before import/AST execution.
    oracle = load_oracle(snapshot)
    contract = oracle.load_contract(snapshot / 'fixtures/user-scenarios/editor-actions-v1.json')
    raw, control = base / 'raw', base / 'control'
    claim = read_json(snapshot / 'docs/validation/android35-files-c3d7-preparation.json')
    unit, manifest = read_json(control / 'unit.json'), read_json(raw / 'manifest.json')
    require(unit['status'] == 'FAIL' and unit['nativeExitCode'] == 1 and unit['nativeInvocations'] == 1 and unit['preflightPassed'], 'Original unit outcome differs')
    require(manifest['gitHead'] == APP_SOURCE and manifest['harnessGitHead'] == unit['source'] == SOURCE and manifest['sourceChanges'] == [], 'Producer identity differs')
    require(manifest['scenario'] == unit['scenario'] == 'files' and manifest['serial'] == unit['serial'] == 'emulator-5558' and manifest['package'] == unit['package'] == PACKAGE, 'Wrong Files cohort')
    require(manifest['deviceApi'] == '35' and manifest['appLanguage'] == 'en', 'Wrong API/language')
    require(digest(control / 'driver.py') == unit['driverSHA256'] == DRIVER_SHA, 'Controller source differs')
    require(unit['argv'].count('--scenario') == 1 and unit['argv'][unit['argv'].index('--scenario') + 1] == 'files', 'Wrong controller selection')
    require(unit['sourceFilesBefore'] == unit['sourceFilesAfter'] == claim['sourceFiles'] and len(claim['sourceFiles']) == 71, 'Source pre/post pins differ')
    for name, expected in claim['sourceFiles'].items():
        require(source['files'][name] == expected, 'Historical source differs from prepared pin: ' + name)
    require(unit['artifactFilesAfter'] == claim['localArtifacts'] | claim['appBuildReceipt'] and len(unit['artifactFilesAfter']) == 4, 'Local artifact/receipt pins changed')
    require(digest(raw / 'build-receipt.json') == next(iter(claim['appBuildReceipt'].values()))['sha256'], 'App build receipt changed')
    for name, expected in manifest['scenarioSources'].items():
        require(digest(raw / ('source-' + name)) == expected == source['files']['scripts/' + name]['sha256'], 'Retained executor changed: ' + name)
    require(digest(snapshot / 'fixtures/user-scenarios/editor-actions-v1.json') == manifest['scenarioContractSha256'], 'Wrong historical document contract')
    for field, package in (('appSha256', PACKAGE), ('testSha256', PACKAGE + '.test'), ('snapshotSha256', 'thalesmms.logisim.mobile.snapshot.e2e.test')):
        require(manifest[field] == APKS[package], 'Supplied APK identity differs')
    require({row['package']: row['sha256'] for row in manifest['installedArtifactsBefore']} == APKS, 'Runner installed APK triplet differs')
    require('installedArtifactsAfter' not in manifest, 'Failure manifest incorrectly contains a completed runner postflight')
    reader = next(node for node in ast.parse((control / 'driver.py').read_text()).body if isinstance(node, ast.FunctionDef) and node.name == 'assert_reader_off')
    namespace = {'re': re}
    exec(compile(ast.Module(body=[reader], type_ignores=[]), '<authenticated-reader-check>', 'exec'), namespace)
    for phase in ('before', 'after'):
        values = unit[phase]
        require(read_json(control / phase / 'inventory.json') == values, 'Controller census differs from unit: ' + phase)
        require({row['package']: row['sha256'] for row in values['installed']} == APKS, 'Controller installed APK triplet differs')
        for key, value in values.items():
            if isinstance(value, str):
                require((control / phase / (key + '.stdout')).read_text().strip() == value and not (control / phase / (key + '.stderr')).read_bytes(), 'Raw getter differs: ' + phase + '/' + key)
        namespace['assert_reader_off'](values)
    keys = ('wm-size', 'wm-density', 'font-scale', 'accelerometer-rotation', 'user-rotation', 'accessibility-enabled', 'enabled-services', 'locale', 'persist-locale', 'product-locale')
    require(unit['settingsDifferences'] == {} and all(unit['before'][key] == unit['after'][key] for key in keys), 'Settings changed')
    require(unit['anrChanged'] is False and unit['before']['last-anr'] == unit['after']['last-anr'], 'Recorded last-ANR changed')
    require(unit['cleanup']['pidAfter'] == '' and unit['after']['pid-test'] == '17772' and unit['after']['pid-helper'] == '', 'Owned cleanup or sampled test/helper PID differs')
    require(not any(key in unit for key in ('readbackError', 'sourceReadbackError', 'cleanupError')), 'Controller postflight failed')
    partition = results_partition(read_json(raw / 'results.json'))
    executor = ast.parse((snapshot / 'scripts/e2e-android.py').read_text())
    files = next(node for node in ast.walk(executor) if isinstance(node, ast.FunctionDef) and node.name == 'files')
    names = [node.value.args[0].value for node in files.body if isinstance(node, ast.Expr) and isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Attribute) and node.value.func.attr == 'run']
    require(names == CASES == claim['requiredOrderedCases'], 'Original nine-case order changed')
    public = ('incoming-sap1.circ', 'incoming-share.circ', STAGED)
    counts = {}
    for index, name in enumerate(public):
        data = (raw / name).read_bytes()
        counts[name] = oracle.assert_sap1(data, contract)
        require(ET.fromstring(data).get('e2e-import-token') == TOKENS[index], 'Observed import token differs: ' + name)
    require(len(set(TOKENS)) == 3 and all(re.fullmatch('[0-9a-f]{32}', value) for value in TOKENS), 'Tokens are not three distinct observed UUID hex values')
    require((raw / 'last-document.circ').read_bytes() == (raw / 'incoming-share.circ').read_bytes(), 'Last public Copy XML differs from SEND')
    marker = next(node for node in executor.body if isinstance(node, ast.FunctionDef) and node.name == 'mark_import_source')
    namespace = {'ET': ET}
    exec(compile(ast.Module(body=[marker], type_ignores=[]), '<authenticated-mark-import>', 'exec'), namespace)
    require((raw / STAGED).read_bytes() == namespace['mark_import_source']((snapshot / 'examples/sap1.circ').read_bytes(), TOKENS[2]), 'Staged SAF source differs from marked frozen SAP1')
    for index, action in ((1, 'VIEW'), (2, 'SEND')):
        opened = read_json(raw / f'incoming-open-{index}.json')
        require(opened['action'] == 'android.intent.action.' + action and re.fullmatch(r'e2e-[0-9a-f]{32}\.circ', opened['expectedName']), 'Incoming route/name differs')
        require(opened['uri'] == f'content://{PACKAGE}.test.documents/document/' + opened['expectedName'] and opened['observedStatus'] == 'File ' + opened['expectedName'] + ' opened.', 'Incoming public status differs')
        require(f'Activity: {PACKAGE}.test/dev.logisim.mobile.documents.DocumentFixtureLauncherActivity' in (raw / f'incoming-launch-{index}.txt').read_text(), 'External fixture Activity launch differs')
    for name in READINESS:
        require(not (raw / (name + '.xml')).exists() and not (raw / (name + '.json')).exists(), 'NOT_RUN cancellation has fabricated readiness evidence')
    require(not any((raw / name).exists() for name in ('local-import.circ', 'before-share.circ', 'after-share.circ', 'share-receiver.json', 'before-picker-export.circ', 'before-picker-recovery.circ', 'invalid-xml.circ')), 'Unreached Files case has a public document')
    trace = (raw / 'failure-error.txt').read_text()
    require('local_import' in trace and 'self.wait_key("status_file_opened", name=name)' in trace and trace.rstrip().endswith('AssertionError: ' + ERROR), 'Primary failure boundary differs')
    report = ET.fromstring((raw / 'import-report-4-ui.xml').read_bytes())
    texts = [node.get('text', '') for node in report.iter('node')]
    require('Document: ' + STAGED in texts and 'Source version: 3.8.0. Circuits: 7.' in texts, 'Retained SAF import report differs')
    workspace = (base / 'private/final-saf-checkpoint.workspace').read_text()
    private_xml = workspace[workspace.index('<?xml'):workspace.index('</project>') + len('</project>')].encode()
    require(ET.fromstring(private_xml).get('e2e-import-token') == TOKENS[2], 'Private checkpoint no longer has the SAF token')
    oracle.assert_sap1(private_xml, contract)
    for stem in PRIVATE_STEMS:
        root = ET.fromstring((raw / (stem + '-ui.xml')).read_bytes())
        require(sum(node.get('text') == 'Operation cancelled.' for node in root.iter('node')) == 1, 'Retained failure status differs')
        require(len([node for node in root.iter('node') if node.get('content-desc', '').startswith('Circuit area.')]) == 1, 'Failure Canvas census differs')
        for number in (9, 10, 11):
            require((raw / f'{stem}-checkpoint-{number}.circ').read_bytes() == private_xml, 'Private SAF checkpoint differs')
        metadata = read_json(raw / (stem + '-checkpoint-documents.json'))
        require(len(metadata) == 11 and metadata[-1]['archive_member'] == 'files/workspace/project-cca8c3d338676ae4fc821bf5361fae06-3.workspace', 'Private checkpoint provenance differs')
    refs = read_json(base / 'local-tar-references.json')
    require(len(refs) == 3 and {Path(row['path']).name for row in refs} == {stem + '-private-files.tar' for stem in PRIVATE_STEMS}, 'Local TAR reference census differs')
    for row in refs:
        require(row['bytes'] == 500282 and row['sha256'] == '981931af1fa5ca58c1e9c62f60d7c5a1068c3a40b90cbc40ff15e6b010c3e5bf', 'Original local TAR pin differs')
        require(row['memberBytes'] == (base / row['retainedMember']).stat().st_size and row['memberSHA256'] == digest(base / row['retainedMember']), 'Retained private member differs')
    require(digest(base / 'copy-receipt.json') == COPY_RECEIPT_SHA, 'Original byte-copy receipt changed')
    copy = read_json(base / 'copy-receipt.json')
    for side in ('raw', 'control'):
        for name, expected in copy['inventory'][side].items():
            if name.endswith('.tar'):
                require(any(Path(row['path']).name == name and {'bytes': row['bytes'], 'sha256': row['sha256']} == expected for row in refs), 'Excluded TAR differs from original mirror')
            else:
                require({'bytes': (base / side / name).stat().st_size, 'sha256': digest(base / side / name)} == expected, 'Original copy changed: ' + side + '/' + name)
    requests = [json.loads(line) for line in (control / 'adb-command-requests.jsonl').read_text().splitlines()]
    require(len(requests) == 301 and all(row['argv'][1:3] == ['-s', 'emulator-5558'] for row in requests), 'Controller request census differs')
    require(sum('prepareCopy' in row['argv'] for row in requests) == 2, 'Two public Copy XML request preparations differ')
    require(not list(raw.glob('focus-snapshot-*.json')), 'Original CLI cohort acquired unexpected SDK snapshots')
    return {'status': 'PASS', 'qualification': 'ARCHIVE_REPLAY_ONLY', 'originalNativeStatus': 'FAIL', 'nativeReplay': 'NOT_RUN',
            'originalNativeInvocations': 1, 'nativeExitCode': 1, 'cases': partition,
            'viewInitialPublicCopy': True, 'lastPublicCopy': 'incoming-share.circ', 'sap1Counts': counts,
            'privateSafDocumentObserved': True, 'privateIsPublicExport': False, 'settingsDifferences': {},
            'sourcePins': 71, 'localArtifactPins': 4, 'controllerRequestsOnly': len(requests),
            'recovery': 'NOT_RUN', 'sdkSnapshots': 0, 'testPackagePidBeforeOwnedCleanup': '17772',
            'helperPidBeforeOwnedCleanup': '', 'ownedMainPidAfterForceStop': '',
            'scope': 'One Files failure after VIEW and SEND. Seven later cases NOT_RUN; no whole-issue35 completion or callback/IME cause inferred.'}


if __name__ == '__main__':
    print(json.dumps(verify(), indent=2))
