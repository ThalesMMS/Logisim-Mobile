import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

BASE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('files_failure_verifier', BASE / 'verify.py')
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


class FrozenFilesFailureTests(unittest.TestCase):
    def packet(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / 'packet'
        shutil.copytree(BASE, path)
        return path

    def rehash(self, packet):
        paths = {str(path.relative_to(packet)): path for folder in ('raw', 'control', 'oracle-snapshot', 'private')
                 for path in verifier.safe_files(packet / folder)}
        for name in ('copy-receipt.json', 'local-tar-references.json'):
            paths[name] = packet / name
        value = {'fileCount': len(paths), 'bytes': sum(path.stat().st_size for path in paths.values()),
                 'files': {name: {'bytes': path.stat().st_size, 'sha256': verifier.digest(path)} for name, path in paths.items()}}
        (packet / 'inventory.json').write_text(json.dumps(value, indent=2) + '\n')

    def change_json(self, path, mutate):
        value = json.loads(path.read_text())
        mutate(value)
        path.write_text(json.dumps(value, indent=2) + '\n')

    def test_original_fail_is_replayed_without_native_execution(self):
        result = verifier.verify()
        self.assertEqual('FAIL', result['originalNativeStatus'])
        self.assertEqual('NOT_RUN', result['nativeReplay'])
        self.assertEqual(7, list(result['cases'].values()).count('NOT_RUN'))
        self.assertFalse(result['privateIsPublicExport'])

    def test_rehashed_missing_public_sap1_wire_fails_historical_count_oracle(self):
        packet = self.packet()
        path = packet / 'raw/incoming-sap1.circ'
        project = ET.fromstring(path.read_bytes())
        circuit = next(item for item in project.findall('circuit') if item.find('wire') is not None)
        circuit.remove(circuit.find('wire'))
        path.write_bytes(ET.tostring(project))
        self.rehash(packet)
        with self.assertRaisesRegex(AssertionError, 'sap1'):
            verifier.verify(packet)

    def test_rehashed_last_public_copy_cannot_be_replaced_by_private_saf(self):
        packet = self.packet()
        (packet / 'raw/last-document.circ').write_bytes((packet / 'raw/failure-checkpoint-11.circ').read_bytes())
        self.rehash(packet)
        with self.assertRaisesRegex(AssertionError, 'Last public Copy XML'):
            verifier.verify(packet)

    def test_rehashed_same_count_staged_source_change_is_rejected(self):
        packet = self.packet()
        path = packet / 'raw' / verifier.STAGED
        project = ET.fromstring(path.read_bytes())
        project.find('circuit/comp').set('name', 'InventedGate')
        path.write_bytes(ET.tostring(project, encoding='utf-8', xml_declaration=True))
        self.rehash(packet)
        with self.assertRaisesRegex(AssertionError, 'Staged SAF source differs'):
            verifier.verify(packet)

    def test_rehashed_failed_saf_cannot_be_promoted_to_pass(self):
        packet = self.packet()
        self.change_json(packet / 'raw/results.json', lambda value: value[1].update(status='passed'))
        self.rehash(packet)
        with self.assertRaisesRegex(AssertionError, 'PASS/FAIL prefix'):
            verifier.verify(packet)

    def test_rehashed_fabricated_public_local_import_is_rejected(self):
        packet = self.packet()
        shutil.copyfile(packet / 'raw/failure-checkpoint-11.circ', packet / 'raw/local-import.circ')
        self.rehash(packet)
        with self.assertRaisesRegex(AssertionError, 'Unreached Files case'):
            verifier.verify(packet)

    def test_rehashed_wrong_saf_report_name_is_rejected(self):
        packet = self.packet()
        path = packet / 'raw/import-report-4-ui.xml'
        path.write_bytes(path.read_bytes().replace(verifier.STAGED.encode(), b'wrong.circ'))
        self.rehash(packet)
        with self.assertRaisesRegex(AssertionError, 'SAF import report'):
            verifier.verify(packet)

    def test_rehashed_failure_status_change_is_rejected(self):
        packet = self.packet()
        path = packet / 'raw/failure-ui.xml'
        path.write_bytes(path.read_bytes().replace(b'Operation cancelled.', b'Operation completed.'))
        self.rehash(packet)
        with self.assertRaisesRegex(AssertionError, 'failure status'):
            verifier.verify(packet)

    def test_rehashed_matching_getter_and_census_still_reject_changed_settings(self):
        packet = self.packet()
        (packet / 'control/after/locale.stdout').write_text('en-US\n')
        self.change_json(packet / 'control/after/inventory.json', lambda value: value.update(locale='en-US'))
        self.change_json(packet / 'control/unit.json', lambda value: value['after'].update(locale='en-US'))
        self.rehash(packet)
        with self.assertRaisesRegex(AssertionError, 'Settings changed'):
            verifier.verify(packet)

    def test_rehashed_historical_oracle_is_rejected_before_import(self):
        packet = self.packet()
        path = packet / 'oracle-snapshot/scripts/user_scenario_contract.py'
        path.write_text(path.read_text() + '\nraise AssertionError("UNAUTHENTICATED_IMPORT_EXECUTED")\n')
        self.change_json(packet / 'oracle-snapshot/manifest.json', lambda value: value['files']['scripts/user_scenario_contract.py'].update(bytes=path.stat().st_size, sha256=verifier.digest(path)))
        self.rehash(packet)
        with self.assertRaisesRegex(AssertionError, 'Historical source manifest changed'):
            verifier.verify(packet)

    def test_linked_evidence_is_rejected(self):
        packet = self.packet()
        path = packet / 'raw/last-document.circ'
        path.unlink()
        path.symlink_to('incoming-share.circ')
        with self.assertRaisesRegex(AssertionError, 'links'):
            verifier.verify(packet)

    def test_packet_runs_after_relocation_outside_checkout(self):
        packet = self.packet()
        result = subprocess.run([sys.executable, '-B', str(packet / 'verify.py')], cwd=packet.parent,
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        value = json.loads(result.stdout)
        self.assertEqual('ARCHIVE_REPLAY_ONLY', value['qualification'])
        self.assertEqual('FAIL', value['originalNativeStatus'])


if __name__ == '__main__':
    unittest.main()
