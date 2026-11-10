from pathlib import Path
import datetime
import hashlib
import json
import os
import re
import signal
import subprocess
import sys
import time

ROOT = Path('/Users/thales/GitHub/logisim-mobile-worktrees/canvas-zoomout-retention')
BASE = Path('/Users/thales/GitHub/logisim-mobile-worktrees/android-canvas-input-diagnostic-test-ui')
BUILD = ROOT / 'outputs/android-testapk-e7fb33f8-002'
STANDARD = Path('/Users/thales/GitHub/logisim-mobile-dev/outputs/implementation/artifacts/5c2abc60')
OUT = ROOT / 'outputs/android-zoomout-e7fb33f8-native-5558-001'
ADB = '/Users/thales/Library/Android/sdk/platform-tools/adb'
SERIAL = 'emulator-5558'
SOURCE = 'e7fb33f8d76c6ec8bc7a7046e7c252fb9386d630'
PACKAGES = ['thalesmms.logisim.mobile.e2e', 'thalesmms.logisim.mobile.e2e.test', 'thalesmms.logisim.mobile.snapshot.e2e.test']
DIAGNOSTIC = [BASE / 'outputs/android-canvas-input-diagnostic-b06cefcc/apks/e2e_apk.apk',
              BUILD / 'apks/e2e_test_apk.apk', STANDARD / 'snapshot_apk.apk']
NORMAL = [STANDARD / 'e2e_apk.apk', STANDARD / 'e2e_test_apk.apk', STANDARD / 'snapshot_apk.apk']
DIAGNOSTIC_SHA = ['c12be1b537b1e02744f057fb36372f3669df25e3385e9c2e7e4fda8a8fc1484b',
    '33b888b38368b6ea56bc512bcef7e5c1d20d62fb8ab461e7f8da457800ba962a',
    '504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c']
NORMAL_SHA = ['10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973',
    '19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b', DIAGNOSTIC_SHA[2]]
FINGERPRINT = 'google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.043/12960925:userdebug/dev-keys'
KEYS = [('system', k) for k in ['font_scale', 'user_rotation', 'accelerometer_rotation', 'system_locales']] + [
    ('secure', k) for k in ['accessibility_enabled', 'enabled_accessibility_services', 'touch_exploration_enabled',
                           'default_input_method', 'show_ime_with_hard_keyboard']] + [
    ('global', k) for k in ['window_animation_scale', 'transition_animation_scale', 'animator_duration_scale']]

def utc(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def write(path, value): path.write_text(json.dumps(value, indent=2) + '\n')

OUT.mkdir(parents=True, exist_ok=False)
(OUT / 'raw').mkdir()
argv = [sys.executable, str(ROOT / 'scripts/run-android-mixed-input.py'), '--serial', SERIAL,
    '--apk', str(DIAGNOSTIC[0]), '--test-apk', str(DIAGNOSTIC[1]), '--source-revision', SOURCE,
    '--case', 'pinch', '--canvas-input-trace', '--zoom-out-observations', '--timeout', '360',
    '--output', str(OUT / 'pinch-once'), '--adb', ADB]
report = {'schema': 1, 'status': 'PREPARED_WAITING_GO', 'serial': SERIAL, 'source': SOURCE,
          'appProducer': 'b06cefcc42a5c85a4f55d4ba127d4b93c2c38c5e', 'case': 'pinch', 'argv': argv,
          'nativeCalls': 0, 'nativeQualification': 'NOT_QUALIFIED', 'commands': [], 'startedUtc': utc(),
          'driverSha256': sha(__file__), 'buildManifestSha256': sha(BUILD / 'artifact.json'),
          'diagnosticTriplet': [dict(path=str(p), sha256=s) for p, s in zip(DIAGNOSTIC, DIAGNOSTIC_SHA)],
          'restoreTriplet': [dict(path=str(p), sha256=s) for p, s in zip(NORMAL, NORMAL_SHA)],
          'limits': ['One original pinch case, no scroll, retry, fallback or cache operation.',
                     'If preparation passes, the unchanged case injects the original pinch MotionEvents.',
                     'Standard app/test/helper are restored before returning the device.']}
(OUT / 'driver.py').write_bytes(Path(__file__).read_bytes())
write(OUT / 'unit.json', report)
print(json.dumps({'event': 'PREPARED_WAITING_GO', 'argv': argv, 'driverSha256': report['driverSha256'],
                  'output': str(OUT)}), flush=True)
if input().strip() != 'GO':
    report.update(status='CANCELLED_BEFORE_DEVICE_ACCESS', finishedUtc=utc()); write(OUT / 'unit.json', report)
    raise SystemExit(0)

def interrupted(signum, frame):
    raise KeyboardInterrupt('Received signal ' + str(signum))

for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
    signal.signal(signum, interrupted)

def run(arguments, name, *, binary=False, save=True, timeout=45, check=True):
    start = time.monotonic(); began = utc()
    result = subprocess.run([ADB, '-s', SERIAL, *arguments], stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    suffix = '.bin' if binary else '.txt'
    row = {'argv': [ADB, '-s', SERIAL, *arguments], 'startedUtc': began, 'endedUtc': utc(),
           'elapsedSeconds': time.monotonic() - start, 'exitCode': result.returncode,
           'stdoutBytes': len(result.stdout), 'stdoutSha256': hashlib.sha256(result.stdout).hexdigest()}
    if save:
        path = OUT / 'raw' / (name + suffix); path.write_bytes(result.stdout); row['stdout'] = str(path.relative_to(OUT))
    error_path = OUT / 'raw' / (name + '.stderr.txt'); error_path.write_bytes(result.stderr)
    row['stderr'] = str(error_path.relative_to(OUT)); report['commands'].append(row); write(OUT / 'unit.json', report)
    if check and result.returncode:
        raise RuntimeError(f'{name} exit {result.returncode}: ' + result.stderr.decode(errors='replace'))
    return result.stdout if binary else result.stdout.decode(errors='replace')

def installed(package, prefix):
    output = run(['shell', 'pm', 'path', package], prefix + '-path')
    paths = [line.removeprefix('package:') for line in output.splitlines() if line.startswith('package:')]
    if len(paths) != 1 or not re.fullmatch(r'/data/app/[A-Za-z0-9_~./+=:-]+\.apk', paths[0]):
        raise RuntimeError('Unexpected installed APK path for ' + package)
    value = run(['exec-out', 'cat', paths[0]], prefix + '-apk', binary=True, save=False)
    return {'package': package, 'bytes': len(value), 'sha256': hashlib.sha256(value).hexdigest(), 'devicePath': paths[0]}

def triplet(prefix, expected):
    rows = [installed(package, prefix + '-' + str(i)) for i, package in enumerate(PACKAGES)]
    if [row['sha256'] for row in rows] != expected:
        raise RuntimeError('Installed triplet differs during ' + prefix)
    return rows

def configuration(prefix):
    result = {'settings': {f'{table}/{key}': run(['shell', 'settings', 'get', table, key],
              prefix + '-' + table + '-' + key).strip() for table, key in KEYS}}
    for name, args in [('wmSize', ['shell', 'wm', 'size']), ('wmDensity', ['shell', 'wm', 'density']),
                       ('localeProperty', ['shell', 'getprop', 'persist.sys.locale']),
                       ('localeFallback', ['shell', 'getprop', 'ro.product.locale']),
                       ('fingerprint', ['shell', 'getprop', 'ro.build.fingerprint']),
                       ('sdk', ['shell', 'getprop', 'ro.build.version.sdk'])]:
        result[name] = run(args, prefix + '-' + name).strip()
    access = run(['shell', 'dumpsys', 'accessibility'], prefix + '-accessibility')
    result['touchExplorationOffObserved'] = 'touchExplorationEnabled=false' in access
    result['enabledServicesSettingEmpty'] = result['settings']['secure/enabled_accessibility_services'] in ['', 'null']
    return result

def sources_unchanged():
    if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip() != SOURCE or \
       subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip():
        raise RuntimeError('Source changed')
    if sha(BUILD / 'artifact.json') != 'b37903e7faa3d9c8e8991c791be5dfb9a86f91f86bc2e38367d9cbf7a8791471':
        raise RuntimeError('Build manifest changed')
    if sha(__file__) != report['driverSha256']:
        raise RuntimeError('Native driver changed')
    for path, expected in list(zip(DIAGNOSTIC, DIAGNOSTIC_SHA)) + list(zip(NORMAL, NORMAL_SHA)):
        if sha(path) != expected: raise RuntimeError('A local APK changed')

def stop_owned(prefix):
    for i, package in enumerate(PACKAGES):
        run(['shell', 'am', 'force-stop', package], prefix + '-' + str(i))

changed_install = False
runner = None
primary = None
before = None
anr_before = None
try:
    sources_unchanged()
    if run(['get-state'], 'device-state').strip() != 'device': raise RuntimeError('Reserved device is not ready')
    before = configuration('before')
    report['configurationBefore'] = before
    if before['fingerprint'] != FINGERPRINT or before['sdk'] != '35': raise RuntimeError('Device producer changed')
    if before['settings']['secure/accessibility_enabled'] != '0' or not before['enabledServicesSettingEmpty'] or \
       not before['touchExplorationOffObserved']: raise RuntimeError('Reader must be off before the unit')
    locale = before['settings']['system/system_locales']
    if locale in ['', 'null']: locale = before['localeProperty'] or before['localeFallback']
    if not locale.lower().startswith('en'): raise RuntimeError('The fixture UI requires the original English locale')
    report['standardInstalledBefore'] = triplet('standard-before', NORMAL_SHA)
    run(['shell', 'dumpsys', 'window', '-a'], 'before-windows')
    run(['shell', 'dumpsys', 'activity', 'processes'], 'before-processes')
    anr_before = run(['shell', 'dumpsys', 'activity', 'lastanr'], 'before-lastanr')
    stop_owned('before-stop-owned')
    changed_install = True
    for i in (0, 1): run(['install', '-r', '-t', str(DIAGNOSTIC[i])], 'install-diagnostic-' + str(i), timeout=90)
    report['diagnosticInstalledBefore'] = triplet('diagnostic-before', DIAGNOSTIC_SHA)
    run(['shell', 'pm', 'list', 'instrumentation'], 'registered-instrumentations')
    report['status'] = 'NATIVE_RUNNING'; report['nativeCalls'] = 1; write(OUT / 'unit.json', report)
    with (OUT / 'native-runner.log').open('w') as log:
        runner = subprocess.Popen(argv, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                  start_new_session=True)
        report['runnerPid'] = runner.pid; report['nativeStartedUtc'] = utc(); write(OUT / 'unit.json', report)
        print(json.dumps({'event': 'NATIVE_STARTED', 'pid': runner.pid, 'argv': argv}), flush=True)
        report['runnerExitCode'] = runner.wait()
    report['nativeFinishedUtc'] = utc()
    native_manifest = OUT / 'pinch-once/manifest.json'
    if native_manifest.is_file():
        report['runnerManifestSha256'] = sha(native_manifest)
        report['runnerResult'] = json.loads(native_manifest.read_text())
        events = report['runnerResult'].get('native', {}).get('events', [])
        pinch_motion = [event for event in events if event.get('kind') == 'motion' and event.get('operation') == 'pinch']
        report['injectedPinchMotionEvents'] = len(pinch_motion)
        report['acceptedPinchMotionEvents'] = sum(event.get('accepted') is True for event in pinch_motion)
        report['pinchActionMaskedSequence'] = [event.get('actionMasked') for event in pinch_motion]
        if report['runnerResult'].get('status') == 'PASS' and (not pinch_motion or
                any(event.get('accepted') is not True for event in pinch_motion) or
                not {0, 1, 5, 6}.issubset(set(report['pinchActionMaskedSequence']))):
            raise RuntimeError('A complete accepted native pinch sequence was not retained')
    report['diagnosticInstalledAfter'] = triplet('diagnostic-after', DIAGNOSTIC_SHA)
    report['status'] = 'NATIVE_FINISHED'
except BaseException as error:
    primary = error
    report['primaryError'] = {'class': type(error).__name__, 'message': str(error)}
    report['status'] = 'UNIT_ERROR'
finally:
    cleanup_errors = []
    def cleanup(name, operation):
        try: return operation()
        except BaseException as error:
            cleanup_errors.append({'operation': name, 'class': type(error).__name__, 'message': str(error)})
            return None
    if runner is not None and runner.poll() is None:
        cleanup('stop-runner-group', lambda: os.killpg(runner.pid, signal.SIGTERM))
    if changed_install:
        cleanup('stop-owned-packages', lambda: stop_owned('cleanup-stop-owned'))
        for i in (0, 1):
            cleanup('restore-standard-' + str(i), lambda i=i: run(['install', '-r', '-t', str(NORMAL[i])],
                    'restore-standard-' + str(i), timeout=90))
        report['standardInstalledAfter'] = cleanup('verify-standard-triplet', lambda: triplet('standard-after', NORMAL_SHA))
        cleanup('stop-restored-owned-packages', lambda: stop_owned('restored-stop-owned'))
    if before is not None:
        after = cleanup('configuration-after', lambda: configuration('after'))
        report['configurationAfter'] = after
        report['configurationUnchanged'] = after == before
        if after != before: cleanup_errors.append({'operation': 'configuration-comparison', 'message': 'Configuration differs or could not be read'})
    if anr_before is not None:
        anr_after = cleanup('lastanr-after', lambda: run(['shell', 'dumpsys', 'activity', 'lastanr'], 'after-lastanr'))
        report['lastAnrUnchanged'] = anr_before == anr_after
        if anr_after != anr_before: cleanup_errors.append({'operation': 'lastanr-comparison', 'message': 'ANR differs or could not be read'})
    cleanup('local-source-apk-pins', sources_unchanged)
    report['cleanupErrors'] = cleanup_errors
    report['restorationStatus'] = 'PASS' if changed_install and not cleanup_errors and report.get('standardInstalledAfter') else 'NOT_MUTATED' if not changed_install else 'FAIL'
    report['finishedUtc'] = utc()
    if report.get('runnerResult', {}).get('status') == 'PASS' and not cleanup_errors and primary is None:
        report['status'] = 'DIAGNOSTIC_CASE_PASS_RESTORED'; report['nativeQualification'] = 'PARTIAL_SYNTHESIZED_INPUT'
    elif report['nativeCalls']:
        report['status'] = 'DIAGNOSTIC_CASE_FAIL_RESTORED' if report['restorationStatus'] == 'PASS' else 'DIAGNOSTIC_CASE_FAIL_CLEANUP_INCOMPLETE'
    write(OUT / 'unit.json', report)
    print(json.dumps({k: report.get(k) for k in ['status', 'nativeCalls', 'runnerExitCode', 'nativeQualification',
                    'restorationStatus', 'configurationUnchanged', 'lastAnrUnchanged', 'cleanupErrors']}), flush=True)
