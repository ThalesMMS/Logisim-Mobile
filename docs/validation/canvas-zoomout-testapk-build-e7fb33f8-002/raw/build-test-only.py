from pathlib import Path
import argparse
import datetime
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

ROOT = Path('/Users/thales/GitHub/logisim-mobile-worktrees/canvas-zoomout-retention')
BASE = Path('/Users/thales/GitHub/logisim-mobile-worktrees/android-canvas-input-diagnostic-test-ui')
OUT = ROOT / 'outputs/android-testapk-e7fb33f8-002'
PREVIOUS = ROOT / 'outputs/android-testapk-e7fb33f8-001'
MARKER = ':androidApp:bundleE2eClassesToCompileJar'
SOURCE = 'e7fb33f8d76c6ec8bc7a7046e7c252fb9386d630'
PRODUCER = 'b06cefcc42a5c85a4f55d4ba127d4b93c2c38c5e'
INIT = Path('/tmp/logisim-e7-test-only-guard-v2.gradle')
PREFLIGHT = ROOT / 'outputs/android-testapk-e7fb33f8-graph-001/reuse-preflight.json'
MANIFEST = OUT / 'artifact.json'
MODULES = ('androidApp', 'composeApp', 'core')

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def git(*args, root=ROOT):
    return subprocess.check_output(['git', *args], cwd=root, text=True).strip()

def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')

def inventory(path):
    files = []
    for entry in sorted(path.rglob('*')):
        if entry.is_symlink():
            raise RuntimeError('A frozen build path is a symlink: ' + str(entry))
        if entry.is_file():
            files.append({'path': str(entry.relative_to(path)), 'bytes': entry.stat().st_size, 'sha256': sha(entry)})
    return files

def producer_inventory(root):
    return {module: [row for row in inventory(root / module / 'build')
            if module != 'androidApp' or 'androidtest' not in row['path'].lower()] for module in MODULES}

def source_inventory():
    old = json.loads((BASE / 'outputs/android-canvas-input-diagnostic-b06cefcc/production-inputs.json').read_text())
    rows = []
    for record in old['files']:
        path = ROOT / record['path']
        data = path.read_bytes()
        blob = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        if blob != record['blob'] or data != (BASE / record['path']).read_bytes():
            raise RuntimeError('A production input differs from b06: ' + record['path'])
        rows.append({'path': record['path'], 'bytes': len(data), 'sha256': sha(path), 'gitBlob': blob})
    return rows

def run(command, name):
    env = os.environ.copy()
    env['ANDROID_HOME'] = '/Users/thales/Library/Android/sdk'
    start = time.monotonic()
    with (OUT / (name + '.log')).open('w') as log:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    return {'argv': command, 'exitCode': result.returncode, 'elapsedSeconds': time.monotonic() - start,
            'log': name + '.log', 'logSha256': sha(OUT / (name + '.log'))}

def command(phase, exclusions):
    args = ['./gradlew', ':androidApp:assembleE2eAndroidTest', '-PandroidTestBuildType=e2e',
            '-Pkotlin.incremental=false', '--console=plain', '--no-configuration-cache',
            '-I', str(INIT), '-De7.artifact.output=' + str(OUT), '-De7.phase=' + phase]
    for task in exclusions:
        args += ['-x', task]
    return args + (['--dry-run'] if phase == 'dry-run' else ['--rerun-tasks'])

def verify_common(manifest):
    if inventory(PREVIOUS) != manifest['previousFailedOutputBefore']:
        raise RuntimeError('The first failed artifact changed')
    if git('rev-parse', 'HEAD') != SOURCE or git('status', '--porcelain'):
        raise RuntimeError('The source checkout changed or is dirty')
    if source_inventory() != manifest['productionInputsBefore']:
        raise RuntimeError('Production source inputs changed')
    if inventory(ROOT / 'androidApp/src/androidTest') != manifest['testInputsBefore']:
        raise RuntimeError('Test source inputs changed')
    if sha(__file__) != manifest['driverSha256'] or sha('/tmp/logisim-gradle-serial.py') != manifest['serializationWrapperSha256']:
        raise RuntimeError('The reviewed build driver or serialization wrapper changed')
    for row in manifest['reusedArtifacts']:
        if sha(row['path']) != row['sha256']:
            raise RuntimeError('A frozen reused APK changed')

def stage():
    OUT.mkdir(parents=True, exist_ok=False)
    if git('rev-parse', 'HEAD') != SOURCE or git('status', '--porcelain') or git('rev-parse', 'HEAD', root=BASE) != PRODUCER:
        raise RuntimeError('Unexpected source checkout')
    previous = json.loads((PREVIOUS / 'artifact.json').read_text())
    if previous['status'] != 'FAILED_BUILD' or previous['source'] != SOURCE:
        raise RuntimeError('The original Provider failure is required')
    original_before = json.loads((PREVIOUS / 'frozen-b06-builds-before.json').read_text())
    for module in MODULES:
        if inventory(BASE / module / 'build') != original_before[module]:
            raise RuntimeError('An original b06 build changed')
    producer_before = producer_inventory(ROOT)
    if producer_before != json.loads((PREVIOUS / 'reused-producer-inputs-after.json').read_text()):
        raise RuntimeError('Existing producer copies differ from the frozen inputs')
    for module, rows in producer_before.items():
        for row in rows:
            original, copied = BASE / module / 'build' / row['path'], ROOT / module / 'build' / row['path']
            a, b = original.stat(), copied.stat()
            if (a.st_dev, a.st_ino) == (b.st_dev, b.st_ino) or b.st_nlink != 1:
                raise RuntimeError('A frozen copied producer input shares an inode')
    marker_jar = ROOT / 'androidApp/build/intermediates/compile_app_classes_jar/e2e/bundleE2eClassesToCompileJar/classes.jar'
    original_jar = BASE / 'androidApp/build/intermediates/compile_app_classes_jar/e2e/bundleE2eClassesToCompileJar/classes.jar'
    if sha(marker_jar) != sha(original_jar) or sha(marker_jar) != '2b34b6c3fc3eddf347456d9c77cbd30114a7af0771f475416a84d9edd4c64656':
        raise RuntimeError('The disabled marker JAR differs from b06')
    excluded = [task for task in previous['excludedProducerTasks'] if task != MARKER]
    allowed = previous['allowedTestTasks']
    if len(excluded) != 62 or len(allowed) != 37:
        raise RuntimeError('The revised task admission differs from 38/62')
    manifest = {'schema': 1, 'status': 'STAGING_REVISED_ADMISSION', 'source': SOURCE, 'frozenProducer': PRODUCER,
        'scope': 'Test APK only, frozen b06 producer inputs and one disabled producer completion marker',
        'startedUtc': utc(), 'productionInputsBefore': source_inventory(), 'testInputsBefore': inventory(ROOT / 'androidApp/src/androidTest'),
        'reusedArtifacts': previous['reusedArtifacts'], 'catalog': previous['catalog'],
        'excludedProducerTasks': excluded, 'allowedTestTasks': allowed, 'allowedGraphTasks': allowed + [MARKER],
        'disabledMarker': {'task': MARKER, 'jar': str(marker_jar), 'bytes': marker_jar.stat().st_size, 'sha256': sha(marker_jar),
                           'requiredEnabled': False, 'requiredActions': 0},
        'copyMode': 'Reuse the physically separate producer copies from 001; no second copy or app build',
        'reusedCopyOrigin': str(PREVIOUS), 'priorFailureCommit': '1412dc22a5afee6a92ca68aad72ef8bd5878ecf4',
        'producerCopyFilesRevalidated': sum(map(len, producer_before.values())),
        'previousFailedOutputBefore': inventory(PREVIOUS), 'removedCopiedTestApk': previous['removedCopiedTestApk'],
        'previousCopiedTestApkAlreadyRemoved': True, 'nativeStatus': 'NOT_RUN', 'appRebuilt': False, 'helperRebuilt': False,
        'driverSha256': sha(__file__), 'serializationWrapperSha256': sha('/tmp/logisim-gradle-serial.py'), 'commands': []}
    if Path(manifest['removedCopiedTestApk']['path']).exists():
        raise RuntimeError('A test APK already exists')
    write(MANIFEST, manifest)
    write(OUT / 'frozen-b06-builds-before.json', original_before)
    write(OUT / 'reused-producer-inputs-before.json', producer_before)
    shutil.copyfile(INIT, OUT / 'test-only-guard.gradle')
    shutil.copyfile(__file__, OUT / 'build-test-only.py')
    manifest['guardSha256'] = sha(INIT)
    phase = run(command('dry-run', excluded), 'excluded-task-graph')
    manifest['commands'].append(phase)
    write(MANIFEST, manifest)
    if phase['exitCode']:
        raise RuntimeError('Revised dry-run failed; no build executed')
    graph = json.loads((OUT / 'guard-dry-run-task-graph.json').read_text())
    if graph['status'] != 'ADMITTED' or set(graph['tasks']) != set(manifest['allowedGraphTasks']):
        raise RuntimeError('The revised graph is not exactly 37 test tasks and the disabled marker')
    verify_common(manifest)
    for module in MODULES:
        if inventory(BASE / module / 'build') != original_before[module]:
            raise RuntimeError('An original b06 build changed')
    if producer_inventory(ROOT) != producer_before:
        raise RuntimeError('Revised dry-run changed producer inputs')
    manifest['status'] = 'EXCLUDED_DRY_RUN_PASS_BUILD_NOT_RUN'
    write(MANIFEST, manifest)
    print(json.dumps({'status': manifest['status'], 'producerCopies': manifest['producerCopyFilesRevalidated'],
                      'tasks': len(graph['tasks']), 'excluded': len(excluded)}))

def build():
    manifest = json.loads(MANIFEST.read_text())
    if manifest['status'] != 'EXCLUDED_DRY_RUN_PASS_BUILD_NOT_RUN' or sha(INIT) != manifest['guardSha256']:
        raise RuntimeError('The excluded dry-run and immutable guard are required')
    verify_common(manifest)
    if Path(manifest['removedCopiedTestApk']['path']).exists():
        raise RuntimeError('The output test APK already exists')
    manifest.update(status='TEST_APK_BUILD_RUNNING', buildStartedUtc=utc(),
                    plannedBuildArgv=command('build', manifest['excludedProducerTasks']))
    write(MANIFEST, manifest)
    phase = run(manifest['plannedBuildArgv'], 'test-apk-build')
    manifest['commands'].append(phase)
    manifest['buildProcessExitCode'] = phase['exitCode']
    write(MANIFEST, manifest)
    before = json.loads((OUT / 'frozen-b06-builds-before.json').read_text())
    manifest['frozenOriginalBuildsUnchanged'] = all(inventory(BASE / module / 'build') == before[module] for module in MODULES)
    producer_after = producer_inventory(ROOT)
    write(OUT / 'reused-producer-inputs-after.json', producer_after)
    manifest['reusedProducerInputsUnchanged'] = producer_after == json.loads((OUT / 'reused-producer-inputs-before.json').read_text())
    verify_common(manifest)
    if phase['exitCode'] == 0:
        marker_path = OUT / 'guard-build-disabled-producer-marker-state.json'
        marker = json.loads(marker_path.read_text())
        if not (marker['executed'] is True and marker['skipped'] is True and marker['didWork'] is False
                and marker['failure'] is None and marker['jarBeforeSha256'] == marker['jarAfterSha256'] == manifest['disabledMarker']['sha256']
                and marker['enabled'] is False and marker['actions'] == 0):
            raise RuntimeError('The producer completion marker was not a frozen no-op')
        manifest['disabledMarkerResult'] = marker
        write(MANIFEST, manifest)
    if phase['exitCode'] or not manifest['frozenOriginalBuildsUnchanged'] or not manifest['reusedProducerInputsUnchanged']:
        manifest['status'] = 'TEST_APK_BUILD_FAIL'
        write(MANIFEST, manifest)
        raise RuntimeError('Build failed or frozen input changed; no fallback was attempted')
    apk = Path(manifest['removedCopiedTestApk']['path'])
    if not apk.is_file() or sha(apk) == manifest['removedCopiedTestApk']['sha256']:
        raise RuntimeError('A fresh distinct test APK was not produced')
    tools = Path('/Users/thales/Library/Android/sdk/build-tools/36.0.0')
    for cmd, name in (([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], 'test-apk-signature'),
                      ([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', manifest['reusedArtifacts'][0]['path']], 'reused-app-signature'),
                      ([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], 'test-apk-badging'),
                      ([str(tools / 'aapt2'), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'], 'test-apk-manifest')):
        manifest['commands'].append(run(cmd, name))
        write(MANIFEST, manifest)
    if any(item['exitCode'] for item in manifest['commands']):
        raise RuntimeError('An APK verification command failed')
    badging = (OUT / 'test-apk-badging.log').read_text()
    tree = (OUT / 'test-apk-manifest.log').read_text()
    sys.path.insert(0, str(ROOT / 'scripts'))
    spec = importlib.util.spec_from_file_location('e7_manifest_support', ROOT / 'scripts/e2e-android.py')
    support = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(support)
    identity = support.parse_apk_manifest(tree)
    expected = {'package': 'thalesmms.logisim.mobile.e2e.test', 'instrumentations': [
        {'name': 'dev.logisim.mobile.documents.DocumentIntegrationInstrumentation',
         'targetPackage': 'thalesmms.logisim.mobile.e2e'}]}
    if "package: name='thalesmms.logisim.mobile.e2e.test'" not in badging or identity != expected:
        raise RuntimeError('The test APK package or instrumentation target is unexpected')
    manifest['testIdentity'] = identity
    certs = [re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$',
                       (OUT / name).read_text(), re.M) for name in ('test-apk-signature.log', 'reused-app-signature.log')]
    if len(certs[0]) != 1 or certs[0] != certs[1]:
        raise RuntimeError('The test and reused app signing certificates differ')
    manifest['testAndReusedAppSignerSha256'] = certs[0][0]
    required = [b'Ldev/logisim/mobile/input/NativeZoomOutObservations;', b'Ldev/logisim/mobile/input/ZoomOutObservationCapture;', b'zoomOutObservations']
    with zipfile.ZipFile(apk) as archive:
        dex = b''.join(archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
    if not all(value in dex for value in required):
        raise RuntimeError('The new collector descriptors are absent from the test APK')
    dst = OUT / 'apks/e2e_test_apk.apk'
    dst.parent.mkdir()
    shutil.copyfile(apk, dst)
    manifest['testArtifact'] = {'path': str(dst), 'bytes': dst.stat().st_size, 'sha256': sha(dst),
        'producerSource': SOURCE, 'requiredDexDescriptors': [value.decode() for value in required]}
    new_classes = {}
    for name, relative in (('kotlin', 'androidApp/build/intermediates/built_in_kotlinc/e2eAndroidTest/compileE2eAndroidTestKotlin/classes'),
                           ('java', 'androidApp/build/intermediates/javac/e2eAndroidTest/compileE2eAndroidTestJavaWithJavac/classes')):
        directory = ROOT / relative
        if not directory.is_dir():
            raise RuntimeError('A test class output is missing')
        new_classes[name] = inventory(directory)
    write(OUT / 'new-test-classes.json', new_classes)
    manifest['testClassInventory'] = {'file': 'new-test-classes.json', 'sha256': sha(OUT / 'new-test-classes.json'),
                                    'counts': {name: len(rows) for name, rows in new_classes.items()}}
    manifest.update(status='TEST_APK_BUILD_PASS', qualification='BUILD_ONLY_FROZEN_PRODUCTION_INPUTS', endedUtc=utc())
    write(MANIFEST, manifest)
    print(json.dumps({'status': manifest['status'], 'testArtifact': manifest['testArtifact'], 'producerUnchanged': manifest['reusedProducerInputsUnchanged'], 'nativeStatus': manifest['nativeStatus']}))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=('stage', 'build'))
    args = parser.parse_args()
    try:
        stage() if args.phase == 'stage' else build()
    except BaseException as error:
        if MANIFEST.is_file():
            manifest = json.loads(MANIFEST.read_text())
            manifest.update(status='FAILED_' + args.phase.upper(),
                            error={'class': type(error).__name__, 'message': str(error)}, endedUtc=utc())
            write(MANIFEST, manifest)
        raise
