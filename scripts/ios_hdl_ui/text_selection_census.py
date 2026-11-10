#!/usr/bin/env python3
"""Read saved HDL UI outputs only. Never emit field values or typed credentials."""
import argparse
import json
import hashlib
from pathlib import Path
import re
import subprocess
from datetime import datetime, timezone

RUNS = ['ios-hdl-eb376-002', 'ios-hdl-e7-hide-001', 'ios-hdl-a046-ime-001',
        'ios-hdl-b90-full-001', 'ios-hdl-b90-full-002']
ACTOR = re.compile(r'^\s*([A-Za-z][A-Za-z0-9 ]*),\s*0x[0-9a-fA-F]+,')
TAP = re.compile(r'^\s*t\s*=\s*([0-9.]+)s\s+Tap\b')
MENU_OBSERVATION = re.compile(r'Check for interrupting elements affecting ["\'](?:Select All|Selecionar Tudo|Selecionar tudo)["\'] MenuItem\b')
DIRECT_MENU_TAP = re.compile(r'\bTap ["\'](?:Select All|Selecionar Tudo|Selecionar tudo)["\'] MenuItem\b')
EDIT_LABELS = {'select_all': ['Select All', 'Selecionar Tudo', 'Selecionar tudo'],
               'cut': ['Cut', 'Cortar', 'Recortar'], 'copy': ['Copy', 'Copiar']}


def input_identity(path):
    return {'path':str(path), 'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}


def actors(text):
    result = []
    for line in text.splitlines():
        match = ACTOR.match(line)
        if match:
            result.append([match.group(1), line])
        elif result:
            result[-1][1] += '\n' + line
    return result


def tree_counts(path):
    blocks = actors(path.read_text(errors='replace'))
    fields = {'address': 0, 'source': 0, 'other': 0}
    focused = dict(fields)
    menu = {key: 0 for key in EDIT_LABELS}
    menu_items = sum(role == 'MenuItem' for role, _ in blocks)
    menus = sum(role == 'Menu' for role, _ in blocks)
    for role, block in blocks:
        if role == 'MenuItem':
            for key, names in EDIT_LABELS.items():
                if any(re.search(r"\blabel:\s*['\"]" + re.escape(name) + r"['\"]", block) for name in names):
                    menu[key] += 1
        if role not in {'TextView', 'TextField', 'SecureTextField'}:
            continue
        category = ('address' if 'HTTPS address or local loopback' in block or 'Endereço HTTPS ou loopback local' in block else
                    'source' if 'hdl-sim-source' in block or 'hdl-import-source' in block or 'HDL-IP source' in block or 'Fonte HDL-IP' in block else 'other')
        fields[category] += 1
        if re.search(r'\bFocused\b', block):
            focused[category] += 1
    return {'actors': {'menu': menus, 'menu_item': menu_items, 'editable_fields': fields,
                       'focused_editable_fields': focused, 'menu_item_labels': menu}}


def action_observations(lines):
    found = {}
    for index, line in enumerate(lines):
        if DIRECT_MENU_TAP.search(line):
            found[index] = {'tap_line': index + 1, 'target_line': index + 1,
                            'observed_target_role': 'MenuItem', 'observed_target_label': 'Select All'}
        if not MENU_OBSERVATION.search(line):
            continue
        # XCTest logs Tap Any(...) followed by resolution to the actual native
        # Select All MenuItem, then Synthesize event. Count that resolved tap,
        # not Find/predicate/Type text containing these words.
        tap_index = next((j for j in range(index - 1, max(-1, index - 9), -1) if TAP.match(lines[j])), None)
        synthesized = next((j for j in range(index + 1, min(len(lines), index + 6)) if 'Synthesize event' in lines[j]), None)
        if tap_index is not None and synthesized is not None:
            found[tap_index] = {'tap_line': tap_index + 1, 'target_line': index + 1,
                                'synthesize_event_line': synthesized + 1,
                                'observed_target_role': 'MenuItem', 'observed_target_label': 'Select All'}
    return list(found.values())


def resolved_commit(root, value):
    if not re.fullmatch(r'[0-9a-fA-F]{7,40}', value or ''):
        return None
    return subprocess.check_output(['git', 'rev-parse', value + '^{commit}'], cwd=root, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--output', type=Path, default=Path('/tmp/hdl-selection-census.json'))
    args = parser.parse_args()
    report = {'created_at': datetime.now(timezone.utc).isoformat(), 'mode': 'saved-data-read-only-no-ui-no-jobs',
              'method': {'native_select_all_actions': 'A Tap action explicitly naming Select All MenuItem, or Tap Any resolved to that MenuItem followed by Synthesize event. Type/typeText/credential/predicate mentions are not counted.',
                         'selection_failure_count': 'Unique test.log error lines requiring native Select All or an editing selection menu.',
                         'tree_census': 'Counts actual actor records in the latest named native-tree attachment per testcase and latest overall; no field values are emitted.',
                         'producer': 'Runner-reported baseline_sha, resolved to full local Git commit; not an independent source-to-binary attestation.',
                         'value_policy': 'Field contents and URL equality are intentionally not emitted or inferred; native debug values may be truncated.'},
              'script':input_identity(Path(__file__).resolve()), 'runs': []}
    for name in RUNS:
        folder = args.root/'outputs'/name
        result = json.loads((folder/'results.json').read_text())
        log = folder/'test.log'
        lines = log.read_text(errors='replace').splitlines()
        actions = action_observations(lines)
        failure_rows = []
        for index, line in enumerate(lines):
            if 'error:' in line and re.search(r'(?:native.*(?:Select All|editing menu)|selection-menu)', line, re.I):
                failure_rows.append({'line': index+1, 'kind': 'native-selection-menu-required'})
        artifact = result.get('artifact', {})
        entry = {'run': name, 'path': str(folder),
                 'inputs': {'results':input_identity(folder/'results.json'), 'test_log':input_identity(log),
                            'attachment_manifest':input_identity(folder/'attachments/manifest.json')},
                 'producer_reported': result.get('baseline_sha'), 'producer_resolved_local': resolved_commit(args.root,result.get('baseline_sha')),
                 'artifact_identity': {key: artifact.get(key) for key in ['bundle_id','version','build']},
                 'status': result.get('status'), 'native_execution_gate': result.get('native_execution_gate'),
                 'qualification_gate': result.get('qualification_gate'), 'alpha': result.get('alpha'),
                 'selected_tests': result.get('selected_tests'),
                 'native_select_all_menuitem_action_count': len(actions), 'native_select_all_actions': actions,
                 'selection_menu_failure_count': len(failure_rows), 'selection_menu_failures': failure_rows,
                 'native_trees': [], 'selection_failure_snapshots': []}
        for group in json.loads((folder/'attachments/manifest.json').read_text()):
            attachments = group['attachments']
            candidates = [a for a in attachments if a['suggestedHumanReadableName'].startswith('native-tree_')]
            if candidates:
                attachment = max(candidates,key=lambda x:x['timestamp'])
                path = folder/'attachments'/attachment['exportedFileName']
                entry['native_trees'].append({'test_identifier':group['testIdentifier'], 'file':str(path),
                                              'timestamp':attachment['timestamp'], 'input':input_identity(path), **tree_counts(path)})
            for attachment in attachments:
                if 'native-text-replacement-selection-menu-missing-tree_' not in attachment['suggestedHumanReadableName']:
                    continue
                path = folder/'attachments'/attachment['exportedFileName']
                entry['selection_failure_snapshots'].append({'test_identifier':group['testIdentifier'],'file':str(path),
                                                             'timestamp':attachment['timestamp'], 'input':input_identity(path), **tree_counts(path)})
        entry['last_native_tree'] = max(entry['native_trees'],key=lambda x:x['timestamp']) if entry['native_trees'] else None
        report['runs'].append(entry)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    for entry in report['runs']:
        actors=entry['last_native_tree']['actors']
        print(json.dumps({'run':entry['run'],'producer':entry['producer_resolved_local'],'status':entry['status'],
                          'select_all_actions':entry['native_select_all_menuitem_action_count'],
                          'selection_menu_failures':entry['selection_menu_failure_count'],
                          'last_tree_focused':actors['focused_editable_fields'],
                          'last_tree_menu_items':actors['menu_item'],'last_tree_edit_menu_labels':actors['menu_item_labels']},ensure_ascii=False))


if __name__ == '__main__':
    main()
