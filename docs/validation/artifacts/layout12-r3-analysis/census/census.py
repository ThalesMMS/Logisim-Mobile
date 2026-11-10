"""Reproduce the R3 artifact census using local files only.

No process, network, Android, emulator or Simulator API is invoked. File mtimes
are host write-completion metadata of the supplied directory, not device capture
timestamps. Use the original producer directory to reproduce its chronology;
copying an archive without preserving metadata can change those timestamps.
"""

import argparse
from collections import Counter
import csv
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import tarfile
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True

DEFAULT_PRODUCER = Path('/Users/thales/GitHub/logisim-mobile-worktrees/layout12-export-preparation/outputs/layout12-small-phone-font1-5558-003')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def utc(value):
    return datetime.fromtimestamp(value, timezone.utc).isoformat()


def load(path):
    return json.loads(path.read_text())


def file_info(path, base):
    data = path.read_bytes()
    return {'path': path.relative_to(base).as_posix(), 'bytes': len(data), 'sha256': sha(data),
            'hostWriteCompletionUTC': utc(path.stat().st_mtime)}


def focus(text):
    return re.findall(r'(?m)^\s*mCurrentFocus=Window\{([^\n}]+)\}', text)


def own_settings(value):
    return {key: value[key] for key in ('wm_size', 'wm_density', 'system', 'services')}


def source_lines(text, needle):
    return [{'line': index + 1, 'text': line.strip()} for index, line in enumerate(text.splitlines()) if needle in line]


def census(producer, output):
    run = producer / 'run'
    output.mkdir(parents=True, exist_ok=True)
    all_paths = [path for path in sorted(producer.rglob('*')) if path.is_file()]
    analysis_paths = [path for path in all_paths if '__pycache__' in path.relative_to(producer).parts or path.suffix in ('.pyc', '.pyo')]
    files = [file_info(path, producer) for path in all_paths if path not in analysis_paths]
    analysis_files = [file_info(path, producer) for path in analysis_paths]
    by_path = {item['path']: item for item in files}
    manifest = load(run / 'manifest.json')
    observations = load(run / 'layout12-observations.json')
    cleanup = load(producer / 'cleanup.json')
    frozen_ui = (run / 'source-android_ui.py').read_text()
    frozen_main = (run / 'source-e2e-android.py').read_text()
    frozen_layout = (run / 'source-android_layout12_scenarios.py').read_text()
    stages = {
        'import-report-1-ui.xml': 'Retained startup report before the selected layout unit configured its display.',
        'layout12-001-window-before-layout-ui.xml': 'Retained editor before cell configuration; the default AND document is visible, not the layout fixture.',
        'import-report-2-ui.xml': 'Retained default-document report at the simulated display size, before the incoming layout fixture.',
        'import-report-3-ui.xml': 'Retained incoming layout-fixture report before baseline export.',
        'layout12-small-phone-ui.xml': 'Post-failure collector, after the inner restoration routine.',
        'failure-ui.xml': 'Later post-failure collector, after the inner restoration routine.',
    }
    roots = []
    for path in sorted(run.glob('*.xml'), key=lambda p: p.stat().st_mtime):
        root = ET.parse(path).getroot()
        nodes = list(root.iter('node'))
        canvas = [node.attrib for node in nodes if node.get('resource-id') == 'editor-canvas']
        roots.append({**by_path[path.relative_to(producer).as_posix()], 'rootAttributes': root.attrib,
            'nodeCount': len(nodes), 'packageCounts': dict(Counter(n.get('package') for n in nodes)),
            'topLevelWindows': [node.attrib for node in root if node.tag == 'node'],
            'tags': [n.get('resource-id') for n in nodes if n.get('resource-id')],
            'editableNodes': [n.attrib for n in nodes if n.get('class', '').endswith('EditText')],
            'focusedNodes': [n.attrib for n in nodes if n.get('focused') == 'true'],
            'canvasNodes': canvas,
            'text': [n.get('text') for n in nodes if n.get('text')],
            'stage': stages.get(path.name, 'Unclassified retained capture; do not infer its stage.'),
            'observer': 'CLI capture under the frozen pre-input execution path; no SDK request artifacts exist.',
            'limit': 'This file is a retained capture, not a log of every root returned by screen().'})

    images = []
    for path in sorted(run.glob('*.png'), key=lambda p: p.stat().st_mtime):
        data = path.read_bytes()
        assert data[:8] == b'\x89PNG\r\n\x1a\n' and data[12:16] == b'IHDR'
        images.append({**by_path[path.relative_to(producer).as_posix()], 'pixels': list(struct.unpack('>II', data[16:24])),
                       'correspondingXml': path.stem + '-ui.xml'})

    sources = []
    for name, expected in manifest['scenarioSources'].items():
        filename = 'source-layout12-' + Path(name).name if name.startswith('fixtures/') else 'source-' + name
        info = by_path['run/' + filename]
        assert info['sha256'] == expected, name
        sources.append({'source': name, 'snapshot': filename, 'sha256': expected})

    primary = next(record for record in observations['records'] if record['name'] == 'scenario-failure')
    sequences = re.findall(r'/sdcard/logisim-e2e-([a-f0-9]+)-(\d+)\.xml', primary['error'])
    remote_sequences = sorted({int(number) for _, number in sequences})
    sdk_files = [item['path'] for item in files if Path(item['path']).name.startswith('focus-snapshot-')]
    initial = next(record for record in observations['records'] if record['name'] == 'window-before-layout')
    applied = [record for record in observations['records'] if record['name'] == 'applied-configuration']
    snapshots = {}
    for name in ('inventory-before-ui.json', 'inventory-after-run.json', 'inventory-final.json'):
        value = load(producer / name)
        snapshots[name] = {'publicSettings': own_settings(value),
                           'windowManagerCurrentFocus': focus(value['window_displays']),
                           'capturedAt': value['captured_at']}

    private_archives = []
    for path in sorted(run.glob('*private-files.tar')):
        status = load(path.with_name(path.stem + '-status.json'))
        with tarfile.open(path) as stream:
            members = [member.name for member in stream.getmembers() if member.isfile()]
        private_archives.append({'path': path.relative_to(producer).as_posix(), 'package': status['package'],
                                 'clipboardPayloadMembers': [name for name in members if name.endswith('clipboard-project.circ')],
                                 'limit': 'The archive was collected with run-as of the editor package. It does not sample the separate .test receiver files.'})

    pid_files = []
    for path in sorted(run.glob('*pid-*.txt')):
        pid_files.append({'path': path.relative_to(producer).as_posix(), 'literal': path.read_text().strip()})
    logcat = (run / 'failure-logcat.txt').read_text()
    log_pids = sorted(set(re.findall(r'(?m)^\d\d-\d\d\s+\S+\s+(\d+)\s+\d+\s+[VDIWEF]\s', logcat)))
    trace = (run / 'failure-error.txt').read_text()
    log_lines = logcat.splitlines()
    summary = {
        'producerDirectory': str(producer),
        'scope': 'Read-only census of retained R3 files. No subprocess, Android/device, network, build or source modification.',
        'manifest': {key: manifest.get(key) for key in ('utc', 'gitHead', 'harnessGitHead', 'sourceChanges', 'serial', 'deviceApi', 'layout12Unit', 'layout12Font')},
        'sources': sources,
        'fileInventory': {'nativeFilesIncludingFrozenApks': len(files), 'currentFilesystemTotal': len(all_paths),
                'analysisFilesExcluded': analysis_files,
                'policy': 'Host __pycache__ and .pyc/.pyo analysis products are identified separately and never counted as Native evidence.',
                'previousCensusCorrection': load(output / 'analysis-exclusions.json') if (output / 'analysis-exclusions.json').exists() else None},
        'nativeResult': load(run / 'results.json'),
        'cleanup': {key: cleanup[key] for key in ('unitAttempts', 'nativeExitCode', 'nativeStatus', 'restorationStatus', 'elapsedSeconds', 'finalDifferences')},
        'recordOrder': [{'name': record['name'], 'status': record.get('status')} for record in observations['records']],
        'primaryFailure': primary,
        'primaryBoundary': {'function': 'Scenarios.export_xml -> wait_editor after Read copied XML tap',
                'sourceReferences': source_lines(frozen_main, 'self.ui.click(text="Read copied XML")') + source_lines(frozen_main, 'def wait_editor('),
                'traceArtifact': 'run/failure-error.txt',
                'readinessGateReached': 'line471 post-Read wait_editor, before receiver payload file read',
                'wholeWaitBudgetSecondsByFrozenSource': 30,
                'lastDumpRemainingSecondsLiteral': re.findall(r'No fresh Android UI hierarchy within ([0-9.]+)s', primary['error']),
                'exactFailureWallClockTime': 'NOT_RETAINED. The error-file mtime is after inner restoration.',
                'observedInTrace': 'line 471, in export_xml' in trace},
        'retainedRoots': roots,
        'retainedRootCounts': {'captures': len(roots), 'distinctXmlHashes': len({root['sha256'] for root in roots})},
        'sdk': {'artifactFiles': sdk_files, 'completeResponsesRecorded': 0, 'typedRootErrorsRecorded': 0,
                'lastSuccess': None,
                'sourceActivationSites': source_lines(frozen_ui, '_focused_snapshot_active = True'),
                'sourceInitialFalse': source_lines(frozen_ui, '_focused_snapshot_active = False')[:1],
                'inference': 'The frozen path activates SDK polling only through input_text. R3 failed before either layout text-input case. No SDK request was reached by that path or retained.',
                'limit': 'NOT_INVOKED is not an SDK availability or ROOT-state success verdict.'},
        'cli': {'primaryLastRemoteSequences': remote_sequences, 'retainedXmlCaptures': len(roots),
                'perQueryReturnedRoots': 'NOT_RETAINED', 'allAttemptOutcomeCounts': 'NOT_RETAINED',
                'sequenceLimit': 'The counter increments per CLI dump attempt in one driver instance. Sequence36 identifies that primary failing attempt; it is not 36 successful roots or the final total after failure collectors.',
                'lastSavedRootBeforePrimaryFailure': next(root['path'] for root in roots if root['path'].endswith('import-report-3-ui.xml')),
                'knownLaterSuccessfulLookup': 'Source/trace show Read copied XML lookup returned and its tap was sent before the failing wait_editor. That returned root, package, coordinates, activity and exact time were not retained.',
                'lastRootDuringFailingWait': 'NOT_RETAINED'},
        'sourceRouteCensus': [
                {'stage': 'Launch and initial wait_editor', 'retainedEvidence': ['run/import-report-1-ui.xml', 'run/layout12-001-window-before-layout-ui.xml'], 'limit': 'Other query returns are not retained.'},
                {'stage': 'Configure simulated display and wait_editor', 'retainedEvidence': ['applied-configuration record', 'run/import-report-2-ui.xml'], 'limit': 'wait_display samples PNG/settings, not an SDK root.'},
                {'stage': 'Public incoming fixture launcher and editor import acknowledgement', 'retainedEvidence': ['run/incoming-launch-1.txt', 'run/incoming-open-1.json', 'run/import-report-3-ui.xml'], 'limit': 'The launcher button query root is not retained.'},
                {'stage': 'ClipboardReader Prepare XML copy lookup/tap; return wait_editor; XML/Copy/Close lookup/taps', 'retainedEvidence': ['source-e2e-android.py control path and final trace'], 'limit': 'Those individual returned query roots, actions and foreground/activity metadata are not retained.'},
                {'stage': 'ClipboardReader Read copied XML lookup/tap', 'retainedEvidence': ['source-e2e-android.py control path reached by final trace'], 'limit': 'Lookup/tap completion is source inference; callback payload and foreground return are not established.'},
                {'stage': 'Post-Read wait_editor', 'retainedEvidence': ['run/failure-error.txt', 'scenario-failure record', 'run/results.json'], 'limit': 'The last failed CLI attempt is sequence36. Intermediate returned roots and actual activity/PID are not retained.'},
                {'stage': 'Inner settings restoration and failure collectors', 'retainedEvidence': ['restoration record', 'run/layout12-small-phone-ui.xml', 'run/failure-ui.xml'], 'limit': 'Import report roots in this stage cannot be assigned to the earlier failing wait.'}],
        'foregroundAndPid': {'initialParsedWindow': initial['window'], 'initialPidFiles': pid_files,
                'failureLogcatPids': log_pids, 'inventories': snapshots,
                'expectedReceiverActivity': 'thalesmms.logisim.mobile.e2e.test/dev.logisim.mobile.documents.DocumentClipboardReaderActivity',
                'observedActivityAfterReadDuringFailingWait': 'NOT_RETAINED',
                'receiverPid': 'NOT_RETAINED',
                'limit': 'CLI screen() has no per-query foreground/PID assertion. Initial WM/PID and post-restoration inventories cannot be relabeled as observations during the failing wait.'},
        'screenshots': images,
        'configuration': {'applied': applied,
                'postRestorationSettingsEqualOriginal': all(snapshots[name]['publicSettings'] == snapshots['inventory-before-ui.json']['publicSettings'] for name in ('inventory-after-run.json', 'inventory-final.json'))},
        'viewport': {'retainedCanvasFrames': [{'path': root['path'], 'canvasNodes': root['canvasNodes'], 'stage': root['stage']} for root in roots if root['canvasNodes']],
                'fixturePaintedViewportRecords': [record for record in observations['records'] if 'paintedViewport' in record],
                'limit': 'The only retained canvas belongs to the pre-cell default AND document. No fixture viewport transform, selected-ID or focus scenario was reached.'},
        'clipboard': {'baselineFiles': [path.relative_to(producer).as_posix() for path in run.glob('*baseline.circ')],
                'privateArchives': private_archives,
                'payloadResult': 'NOT_SAMPLED. The .test receiver payload read in export_xml follows the failing wait_editor, so it was never copied to the run directory.',
                'limit': 'Reaching the Read copied XML tap does not prove its callback wrote the expected payload or that MainActivity resumed.'},
        'capturedLogcat': {'artifact': 'run/failure-logcat.txt', 'lineCountIncludingHeader': len(log_lines),
                'threadtimeLineCount': sum(bool(re.match(r'^\d\d-\d\d\s', line)) for line in log_lines), 'pids': log_pids,
                'firstDeviceLocalTimestamp': next((line[:18] for line in log_lines if re.match(r'^\d\d-\d\d\s', line)), None),
                'lastDeviceLocalTimestamp': next((line[:18] for line in reversed(log_lines) if re.match(r'^\d\d-\d\d\s', line)), None),
                'fatalOrAnrLines': [line for line in log_lines if re.search(r'FATAL EXCEPTION|ANR in |am_anr|am_crash', line)],
                'limit': 'The collector filters app PID logs. It is not a system activity-transition trace, a receiver PID trace, or proof of the foreground during every CLI query. Device-local timestamps are not converted to host UTC.'},
        'chronologyLimits': ['File mtimes are host write-completion metadata, not device event timestamps or an atomic XML/WM/PNG capture.',
                'The two failure screenshots and roots were collected after the inner configuration restoration. Their Import report modal does not establish the modal during the failing wait.',
                'The wait had a 30-second deadline. Its final reported acquisition failed with 1.148 s remaining; intermediate returned roots were not retained. No claim is made that the entire 30-second interval lacked fresh roots.',
                'No SDK-vs-CLI causal or product/environment verdict is inferred from a backend that was not invoked.'],
        'nextPlan': ['Preserve the archived failure and immutable producer identities; no immediate retry, adapter or timeout change.',
                'If Root authorizes a later diagnostic, retain each existing CLI command result, budget/elapsed time and every returned root around receiver Prepare and Read transitions without changing the acceptance gate.',
                'Record the receiver payload and actual foreground/activity at the blocked boundary only under that separate authorization. Keep unknown activity/PID/roots as unknown until observed.',
                'Compare with the separate keyboard run only as a different executed backend/input path. It cannot fill R3 missing observations.']}
    (output / 'census.json').write_text(json.dumps(summary, indent=2) + '\n')
    with (output / 'files.csv').open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=('path', 'bytes', 'sha256', 'hostWriteCompletionUTC'))
        writer.writeheader(); writer.writerows(files)
    with (output / 'roots.csv').open('w', newline='') as stream:
        fields = ('path', 'sha256', 'hostWriteCompletionUTC', 'rotation', 'nodes', 'packages', 'tags', 'stage')
        writer = csv.DictWriter(stream, fieldnames=fields); writer.writeheader()
        for root in roots:
            writer.writerow({'path': root['path'], 'sha256': root['sha256'], 'hostWriteCompletionUTC': root['hostWriteCompletionUTC'],
                'rotation': root['rootAttributes'].get('rotation'), 'nodes': root['nodeCount'],
                'packages': json.dumps(root['packageCounts']), 'tags': json.dumps(root['tags']), 'stage': root['stage']})
    with (output / 'query-route.csv').open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=('basis', 'stage', 'retainedEvidence', 'limit'))
        writer.writeheader()
        for row in summary['sourceRouteCensus']:
            writer.writerow({'basis': 'SOURCE_ROUTE_WITH_RETAINED_FILES; not a complete per-query event log',
                'stage': row['stage'], 'retainedEvidence': json.dumps(row['retainedEvidence']), 'limit': row['limit']})
    print(json.dumps({'census': str(output / 'census.json'), 'nativeFilesIncludingFrozenApks': len(files),
                      'excludedAnalysisFiles': len(analysis_files), 'currentFilesystemTotal': len(all_paths), 'retainedRoots': len(roots),
                      'SDKArtifacts': len(sdk_files), 'lastPrimaryCliSequence': remote_sequences,
                      'native': cleanup['nativeStatus'], 'restoration': cleanup['restorationStatus']}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--producer', type=Path, default=DEFAULT_PRODUCER)
    parser.add_argument('--output', type=Path, default=Path('/tmp/layout12-r3-census'))
    args = parser.parse_args()
    census(args.producer.resolve(), args.output.resolve())
