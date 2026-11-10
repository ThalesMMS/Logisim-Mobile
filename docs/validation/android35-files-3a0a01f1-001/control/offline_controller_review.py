#!/usr/bin/env python3
"""Read-only Files controller review; never imports/runs the controller or ADB."""
from pathlib import Path
import ast
import hashlib
import json
import re
import tempfile
import traceback
from types import SimpleNamespace

CONTROL = Path(__file__).resolve().parent
ROOT = CONTROL.parents[1]
DRIVER = CONTROL / 'driver.py'
PREPARATION = ROOT / 'docs/validation/android35-files-c3d7-preparation.json'
PRIOR = Path('/Users/thales/GitHub/logisim-mobile-worktrees/android35-inspector-close/outputs/android-editing-3a0a01f1-1bb15545-5558-r2-001-control/before')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fragment(nodes, namespace, label):
    # Execute only the selected AST fragment with fake getters/ADB; never top-level setup.
    exec(compile(ast.Module(body=nodes, type_ignores=[]), label, 'exec'), namespace)


def review():
    before = {'driver': sha(DRIVER), 'preparation': sha(PREPARATION)}
    tree = ast.parse(DRIVER.read_text())
    claim = json.loads(PREPARATION.read_text())
    checked = 0
    for section in ('sourceFiles', 'localArtifacts', 'appBuildReceipt'):
        for name, expected in claim[section].items():
            path = ROOT / name if section == 'sourceFiles' else Path(name)
            observed = {'bytes': path.stat().st_size, 'sha256': sha(path)}
            assert observed == expected, (section, name, observed)
            checked += 1

    argv = [node.value for node in ast.walk(tree) if isinstance(node, ast.Assign) and
            any(isinstance(target, ast.Name) and target.id == 'argv' for target in node.targets)]
    assert len(argv) == 1 and isinstance(argv[0], ast.List), 'Expected one native argv assignment'
    literals = [node.value if isinstance(node, ast.Constant) else None for node in argv[0].elts]
    assert literals.count('--scenario') == 1 and literals[literals.index('--scenario') + 1] == 'files', literals
    assert 'all' not in literals and 'editing' not in literals, literals
    scenario_source = ast.parse((ROOT / 'scripts/e2e-android.py').read_text())
    files = next(node for node in ast.walk(scenario_source) if isinstance(node, ast.FunctionDef) and node.name == 'files')
    ordered = [node.value.args[0].value for node in files.body if isinstance(node, ast.Expr) and
               isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Attribute) and node.value.func.attr == 'run']
    assert ordered == claim['requiredOrderedCases'] and len(ordered) == 9, ordered

    reader = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'assert_reader_off')
    namespace = {'re': re}
    fragment([reader], namespace, '<reader-off-only>')
    actual = {key: (PRIOR / (key + '.stdout')).read_text().strip()
              for key in ('accessibility-enabled', 'enabled-services', 'accessibility')}
    assert (PRIOR / 'sdk.stdout').read_text().strip() == '35', 'Historical reader fixture is not API35'
    namespace['assert_reader_off'](actual)
    negatives = []
    for key, value in (('accessibility-enabled', '1'),
                       ('enabled-services', 'com.google.android.marvin.talkback/.TalkBackService'),
                       ('accessibility', actual['accessibility'].replace('touchExplorationEnabled=false', 'touchExplorationEnabled=true'))):
        assert value != actual[key], 'Negative control did not mutate its input'
        altered = dict(actual)
        altered[key] = value
        try:
            namespace['assert_reader_off'](altered)
        except AssertionError:
            negatives.append(key)
        else:
            raise AssertionError('Reader mutation accepted: ' + key)

    outer = next(node for node in tree.body if isinstance(node, ast.Try))
    tries = [node for node in outer.finalbody if isinstance(node, ast.Try)]
    assert len(tries) == 3, 'Review the new finally shape before running fragments'
    readback, sourceback, cleanup = tries
    branches = []
    for mode in ('readback-fail', 'source-readback-fail', 'cleanup-pid-fail', 'original-fail-cleanup-error'):
        with tempfile.TemporaryDirectory() as directory:
            settings = {key: 'same' for key in ('wm-size', 'wm-density', 'font-scale', 'accelerometer-rotation',
                                               'user-rotation', 'locale', 'persist-locale', 'product-locale', 'last-anr')}
            settings.update(actual)
            record = {'status': 'FAIL' if mode == 'original-fail-cleanup-error' else 'PASS',
                      'nativeInvocations': 1, 'before': settings, 'sourceFilesAfter': {}}
            calls = []

            def fake_adb(*arguments, **keywords):
                calls.append(arguments)
                if mode == 'original-fail-cleanup-error' and arguments[1:3] == ('am', 'force-stop'):
                    raise RuntimeError('fake force-stop failure')
                return SimpleNamespace(stdout='still-running' if mode == 'cleanup-pid-fail' and arguments[1] == 'pidof' else '')

            environment = {'record': record, 'CONTROL': Path(directory), 'ROOT': ROOT,
                           'CLAIM': {'sourceFiles': {'app.properties': {'bytes': 357, 'sha256': 'wrong'}}},
                           'sha': lambda path: 'different', 'PACKAGE': 'thalesmms.logisim.mobile.e2e',
                           'adb': fake_adb, 'now': lambda: 'offline', 'traceback': traceback,
                           'assert_reader_off': namespace['assert_reader_off'],
                           'capture': lambda phase: dict(settings, locale='changed')}
            if mode == 'readback-fail':
                fragment([readback], environment, '<readback-only>')
            if mode == 'source-readback-fail':
                fragment([sourceback], environment, '<source-readback-only>')
            fragment([cleanup], environment, '<cleanup-only>')
            expected = {'readback-fail': 'READBACK_FAIL', 'source-readback-fail': 'SOURCE_READBACK_FAIL',
                        'cleanup-pid-fail': 'CLEANUP_FAIL', 'original-fail-cleanup-error': 'FAIL'}[mode]
            assert record['status'] == expected, (mode, record)
            assert calls[0] == ('shell', 'am', 'force-stop', 'thalesmms.logisim.mobile.e2e'), calls
            assert len(calls) == (1 if mode == 'original-fail-cleanup-error' else 2), calls
            if len(calls) == 2:
                assert calls[1] == ('shell', 'pidof', 'thalesmms.logisim.mobile.e2e'), calls
            branches.append({'mode': mode, 'status': record['status'], 'mockAdbCalls': len(calls)})

    after = {'driver': sha(DRIVER), 'preparation': sha(PREPARATION)}
    assert before == after, 'Review changed its driver or preparation inputs'
    return {'status': 'PASS', 'qualification': 'OFFLINE_CONTROLLER_REVIEW_ONLY', 'realAdbCalls': 0,
            'controllerWholeExecuted': False, 'inputHashesBefore': before, 'inputHashesAfter': after,
            'driverMatchesPreparationBeforeHeadFreeze': before['driver'] == claim['controller']['sha256BeforeHeadFreeze'],
            'checkedSourceAndArtifactEntries': checked, 'nativeArgvScenario': 'files', 'nativeArgvAssignments': 1,
            'orderedNineCasesMatch': True, 'orderedCases': ordered,
            'historicalReaderFixture': str(PRIOR),
            'historicalReaderHashes': {key: sha(PRIOR / (key + '.stdout')) for key in (*actual, 'sdk')},
            'readerOffActualR2Dump': 'PASS', 'readerRejectsMutations': negatives, 'cleanupOfflineBranches': branches}


if __name__ == '__main__':
    print(json.dumps(review(), indent=2))
