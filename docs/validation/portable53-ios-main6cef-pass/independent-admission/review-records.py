from pathlib import Path
import base64
import datetime
import hashlib
import json
import re
import subprocess

REPO = Path(__file__).resolve().parents[2]
RAW = Path('/Users/thales/GitHub/logisim-mobile-worktrees/portable-native-evidence/outputs/ios53-final-6cef-native')
PRODUCER = '6cef05429cf66be23569aaebc3c72950cf3fe53a'
VALIDATION = '85da031e4ed11474c2e763976392729810ef142d'
HARNESS = '177ced4e87146a75b3ca2e80a80da7c654cb019d'
TREE = 'd987d43d6587c0139ff0ecddca2d4ae95c9bad66ba6cd19d29c6452214be3080'
EXE = '198a06dc847cc0fd8717b7c05b06f334b8a1e701aaf63aed5a6012699be5b946'
DYLIB = 'db2c909500c95f345362fdf06f5fd17c3647af9800863fc53d0b6a9bf31647cf'
CATALOG = '83a6f69e2a6d530a8afff326250dcd2c09416d4deeb35187d03117b2b5f6c6ca'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text())


def git(*args):
    return subprocess.check_output(['git', *args], cwd=REPO)


def fingerprint(value):
    return value['bundle_tree_sha256'] == TREE and value['executable_sha256'] == EXE and value['bundle_id'] == 'thalesmms.logisim.mobile'


def window_links(directory):
    result = {}
    for path in directory.glob('window-*.workspace-link'):
        text = path.read_text()
        assert text.startswith('LOGISIM-LINK\n1\n')
        body = text.split('\n', 4)[4]
        values = []
        for _ in range(2):
            count, body = body.split(':', 1)
            size = int(count)
            value, body = body[:size], body[size:]
            assert all(ord(char) < 128 for char in value)
            values.append(value)
        assert not body and values[0] not in result
        result[values[0]] = values[1]
    return result


def source_provenance():
    before_path = Path('/Users/thales/GitHub/logisim-mobile-worktrees/portable-native-evidence/outputs/ios53-main6cef-preflight/artifact-before.json')
    before = read(before_path)
    assert fingerprint(before['observedArtifact']) and before['dylibSha256'] == DYLIB
    inputs_path = Path('/Users/thales/GitHub/logisim-mobile-worktrees/ios-final-runtime-6cef0542/outputs/ios-final-runtime-6cef0542/production-inputs.json')
    inputs = read(inputs_path)
    assert inputs['file_count'] == 301 and inputs['source_dirty'] is False
    expected = {row['path']: row['blob'] for row in inputs['files']}
    for revision in [PRODUCER, VALIDATION]:
        rows = git('ls-tree', '-r', revision).decode().splitlines()
        actual = {row.partition('\t')[2]: row.partition('\t')[0].split()[2] for row in rows}
        assert all(actual.get(path) == blob for path, blob in expected.items())
    digest = hashlib.sha256()
    for path, blob in sorted(expected.items()):
        digest.update(path.encode() + b'\0' + blob.encode() + b'\n')
    assert digest.hexdigest() == inputs['production_input_digest'] == 'fe8c7e9337359543a49fb38d9a6c6f003866436a16cb327acbb76abc8c80c9e2'
    delta = git('diff', '--name-only', PRODUCER, VALIDATION).decode().splitlines()
    assert delta == ['composeApp/src/androidHostTest/kotlin/dev/logisim/mobile/RuntimeSimulationRequestTest.kt']
    storage = git('show', PRODUCER + ':composeApp/src/iosMain/kotlin/dev/logisim/mobile/IosCatalogStorage.kt').decode()
    assert 'flock(descriptor, LOCK_EX)' in storage and '$directory/catalog.lock' in storage
    return {'producer': PRODUCER, 'validation': VALIDATION, 'productionBlobCount': 301,
            'productionDigest': digest.hexdigest(), 'onlyDelta': delta,
            'productionManifest': str(inputs_path), 'productionManifestSha256': sha(inputs_path),
            'sourceAppBefore': str(before_path), 'sourceAppBeforeSha256': sha(before_path),
            'expectedTree': TREE, 'expectedExecutable': EXE, 'expectedDylib': DYLIB}


def review_case(mode, selector, seconds):
    case = RAW / mode
    result = read(case / 'results.json')
    configuration = read(case / 'harness/Run.json')
    evidence = case / 'documents' / ('portable-' + mode + '-evidence')
    receipt = read(evidence / 'ui-receipt.json')
    after = read(case / 'installed-after.json')
    selected = 'EditorEndToEndTests/PortableProjectEndToEndTests/' + selector
    assert (result['status'], result['native_execution_gate'], result['qualification_gate']) == ('PASS', 'NATIVE_PASS', 'PARTIAL')
    assert result['alpha'] is False and result['physical_device'] is False
    assert result['selected_tests'] == [selected]
    assert result['derived_data'] == '/Users/thales/GitHub/logisim-mobile-worktrees/portable-native-evidence/outputs/ios53-final-6cef-derived'
    assert configuration == result['configuration']
    assert configuration['simulatorUDID'] == 'B25F12A5-62D8-4E9E-964E-96519DB6FB69'
    assert configuration['visualExport']['sourcePin'] == PRODUCER and configuration['visualExport']['buildSHA256'] == TREE
    assert 'prepare-only' not in configuration['documents']
    assert configuration['documents'].endswith('/Documents/LogisimE2E/' + configuration['run_id'])
    assert receipt['status'] == 'PASSED' and receipt['executedViaUi'] is True
    assert receipt['sourcePin'] == PRODUCER and receipt['appArtifactSha256'] == TREE
    assert fingerprint(result['artifact']) and fingerprint(result['main_app_installation']['installed']) and fingerprint(after['observed'])
    assert after['debugDylibSha256'] == DYLIB
    if mode == 'folder':
        assert fingerprint(after['sourceArtifactAfter'])
    assert sha(case / 'harness/Catalog.json') == result['catalog']['sha256'] == CATALOG
    assert len(read(case / 'harness/Catalog.json')) == 2496
    assert len(result['harness_sources']) == 17
    for name, digest in result['harness_sources'].items():
        if name == 'AccessibilityUITests.swift':
            folder = 'scripts/ios_accessibility_ui'
        elif name in ['NativeUIHarness.swift', 'NativeTextReplacement.swift']:
            folder = 'scripts/ios_native_ui'
        else:
            folder = 'scripts/ios_e2e_ui'
        expected = hashlib.sha256(git('show', HARNESS + ':' + folder + '/' + name)).hexdigest()
        assert sha(case / 'harness' / name) == digest == expected
    collector = (case / 'harness/PortableWorkspaceEvidence.swift').read_text()
    assert 'O_RDONLY | O_NOFOLLOW' in collector and 'LOCK_SH | LOCK_NB' in collector and 'return try withReadLock {' in collector
    stages = ['before', 'imported', 'restarted', 'reopened']
    assert set(receipt['workspaceSnapshots']) == set(stages)
    snapshots, archives, manifests = {}, {}, {}
    for stage in stages:
        directory = evidence / 'snapshots' / stage / 'workspace'
        meta_path = directory.parent / 'snapshot.json'
        meta = read(meta_path)
        observed = read(directory.parent / 'observation.json')
        assert not directory.is_symlink()
        files = list(directory.iterdir())
        assert all(path.is_file() and not path.is_symlink() for path in files)
        assert set(meta['files']) == {path.name for path in files}
        assert all(sha(directory / name) == digest for name, digest in meta['files'].items())
        assert meta['status'] == 'LOCKED_READ_ONLY_SNAPSHOT'
        assert meta['coordination'] == 'flock LOCK_SH on existing catalog.lock; production commits use LOCK_EX'
        assert meta['observation'] == observed and meta['sourceWorkspace'] == configuration['workspace']
        assert meta['snapshotWorkspace'] == configuration['documents'] + '/portable-' + mode + '-evidence/snapshots/' + stage + '/workspace'
        assert window_links(directory) == observed['windows']
        if stage != 'before':
            assert not (directory / ('external-' + observed['activeProjectId'] + '.workspace-link')).exists()
            descriptor = observed['sourceDescriptor']
            encoded = (directory / ('attachment-' + descriptor['sha256'] + '.workspace')).read_text()
            prefix = 'LOGISIM-WORKSPACE-ATTACHMENT\n1\n'
            assert encoded.startswith(prefix)
            body = encoded[len(prefix):]
            archive = base64.b64decode(body, validate=True)
            assert base64.b64encode(archive).decode() == body
            assert len(archive) == int(descriptor['bytes'])
            assert hashlib.sha256(archive).hexdigest() == descriptor['sha256'] == observed['sourceArchiveSha256']
            assert archive == (directory.parent / 'source.lsebdl').read_bytes()
            archives[stage] = archive
        snapshots[stage] = observed
        manifests[stage] = {'sha256': sha(meta_path), 'filesVerified': len(files)}
    a, b, c, d = [snapshots[stage] for stage in stages]
    imported, reopened = b['activeProjectId'], d['activeProjectId']
    assert imported == receipt['importedProjectId'] == receipt['restartedProjectId'] == c['activeProjectId']
    assert reopened == receipt['reopenedProjectId']
    assert imported not in a['catalogProjectIds']
    assert reopened not in set(a['catalogProjectIds'] + b['catalogProjectIds'] + c['catalogProjectIds'])
    assert all(set(x['catalogProjectIds']).issubset(y['catalogProjectIds']) for x, y in [(a, b), (b, c), (c, d)])
    window = receipt['observedWindowBeforeRestart']
    assert receipt['observedWindowAfterRestart'] == window
    assert b['windows'][window] == c['windows'][window] == imported and d['windows'][window] == reopened
    assert all(b['windows'].get(key) == value for key, value in a['windows'].items() if key != window)
    assert b['sourceDescriptor'] == c['sourceDescriptor'] and archives['imported'] == archives['restarted']
    assert b['projectIdentity'] == c['projectIdentity'] == d['projectIdentity']
    exports = receipt['exports']
    assert len(exports) == 2 and {row['afterRestart'] for row in exports} == {False, True}
    paths = [(evidence / row['path']).resolve() for row in exports]
    assert len(set(paths)) == 2
    for path, row in zip(paths, exports):
        assert path.is_file() and not path.is_symlink() and path.stat().st_size == row['bytes'] and sha(path) == row['sha256']
    second = next(evidence / row['path'] for row in exports if row['afterRestart'])
    assert archives['reopened'] == second.read_bytes()
    assert all((evidence / name).is_file() and not (evidence / name).is_symlink() for name in receipt['evidence'])
    restart = receipt['restart']
    assert restart['beforeProcessId'] > 0 and restart['afterProcessId'] > 0 and restart['beforeProcessId'] != restart['afterProcessId']
    assert restart['terminationObserved'] == 'notRunning' and restart['relaunchObserved'] == 'runningForeground'
    pid_pattern = r'\bApplication,\s+(?:0x[0-9A-Fa-f]+,\s+)?pid:\s*([0-9]+)'
    for needle, expected in [('before-process-termination', restart['beforeProcessId']), ('new-process-foreground-observed', restart['afterProcessId'])]:
        matches = [evidence / name for name in receipt['evidence'] if needle in name and name.endswith('.txt')]
        assert len(matches) == 1 and {int(value) for value in re.findall(pid_pattern, matches[0].read_text())} == {expected}
    canvases = [evidence / name for name in receipt['evidence'] if 'independent-portable-outputs-observed' in name and name.endswith('.txt')]
    assert len(canvases) == 3
    assert all(re.search(r'OUT_FIRST53:\s*00001000', path.read_text()) and re.search(r'OUT_SECOND53:\s*00000011', path.read_text()) for path in canvases)
    test_log = (case / 'test.log').read_text()
    assert re.search(re.escape(selector) + r"\]' passed \(" + re.escape(seconds) + r" seconds\)\.", test_log)
    assert 'ios-e2e-alpha-002' not in test_log and 'ios-e2e-alpha-002' not in (case / 'build.log').read_text()
    assert read(evidence / 'input-files-before.json') == read(evidence / 'input-files-after.json')
    return {'status': 'ADMITTED_RECORDED_NATIVE_PASS', 'seconds': float(seconds), 'runId': configuration['run_id'],
            'nativeGate': result['native_execution_gate'], 'generalGate': result['qualification_gate'], 'selectedTest': selected,
            'resultsSha256': sha(case / 'results.json'), 'nativeReceiptSha256': sha(evidence / 'ui-receipt.json'),
            'installedAfterSha256': sha(case / 'installed-after.json'), 'runSha256': sha(case / 'harness/Run.json'),
            'catalogSha256': sha(case / 'harness/Catalog.json'), 'swiftSourcesVerified': 17,
            'collectorSha256': sha(case / 'harness/PortableWorkspaceEvidence.swift'), 'snapshotManifests': manifests,
            'importedProjectId': imported, 'restoredProjectId': c['activeProjectId'], 'reopenedProjectId': reopened,
            'observedWindow': window, 'pidRestart': restart, 'exports': exports,
            'sourceArchivePreservedAcrossRestart': True, 'reopenedSourceEqualsActualSecondExport': True,
            'nativeEditableAssociationAbsent': True, 'freshImportedAndReopenedWorkspaces': True,
            'allReceiptEvidenceFilesPresent': len(receipt['evidence']), 'literalCanvasObservations8and3': 3,
            'installationStatus': result['main_app_installation']['status'],
            'installedBeforeWasFinal': fingerprint(result['main_app_installation']['before']),
            'actualRunUsed': True, 'offlinePlaceholderUsedAsNativeProof': False}


review = {'schema': 1, 'status': 'IN_PROGRESS', 'findings': [], 'cases': {},
          'scope': 'Independent admission/provenance review of copied MAIN6cef native records and Git objects only.',
          'recordedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'nativeOracleRerun': False,
          'appFrameworkDerivedDataRead': False, 'deviceAccess': False}
try:
    review['productionProvenance'] = source_provenance()
    review['cases']['bundle'] = review_case('bundle', 'testBundleImportExportsAndRestoresItsSourceArchive', '356.118')
    review['cases']['folder'] = review_case('folder', 'testFolderImportExportsAndRestoresItsSourceArchive', '318.815')
    review['status'] = 'ADMITTED_NO_FINDINGS'
except Exception as error:
    import traceback
    review['status'] = 'REVIEW_FINDING'
    review['findings'].append({'type': type(error).__name__, 'message': str(error), 'traceback': traceback.format_exc()})
review['limits'] = [
    'No app binaries, Frameworks, DerivedData, devices, builds, Gradle or native/desktop/mobile oracles were accessed or executed. Fingerprint provenance is reviewed from recorded files.',
    'The separate six fresh oracle PASS reports belong to android_bundle_oracle; this review did not rerun them.',
    'Scope is two issue53 native cases on one iPad Simulator. General runner gate remains PARTIAL. No physical-device, Android, full iOS suite, SystemOpen, A4 or epic-wide qualification is claimed.',
    'All-zero UUID PREPARED contracts remain preparation only and are not used as native evidence.'
]
destination = Path(__file__).parent / 'admission-review.json'
destination.write_text(json.dumps(review, indent=2) + '\n')
print(json.dumps({'review': str(destination), 'status': review['status'], 'findings': review['findings'],
                  'cases': {key: {'status': value['status'], 'seconds': value['seconds']} for key, value in review['cases'].items()}}, indent=2))
