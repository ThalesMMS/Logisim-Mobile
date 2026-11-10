"""Synthetic parser/oracle checks only. These are not iOS execution receipts."""
import json
from pathlib import Path
import tempfile
import unittest
import uuid

from ios_io44_case_postflight import (DEFAULT_REPOSITORY, METHODS, NotReady,
                                     expected_checkpoints, original_name, run_postflight)


class CaseScopedPostflightTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.run = Path(self.temporary.name) / 'run'
        (self.run / 'attachments').mkdir(parents=True)
        self.output = Path(self.temporary.name) / 'postflight'
        self.contract = json.loads((DEFAULT_REPOSITORY / 'fixtures/user-scenarios/io-actions-v1.json').read_text())
        self.fixture = (DEFAULT_REPOSITORY / self.contract['fixture']).read_bytes()
        self.groups = []
        self.log = []
        (self.run / 'results.json').write_text(json.dumps({'status': 'FAIL', 'finished': 'terminal-synthetic', 'execution': {'status': 'FAIL'}}))

    def attachment(self, group, name, contents):
        stem, extension = name.rsplit('.', 1)
        exported = str(uuid.uuid4()).upper() + ('.xml' if extension == 'circ' else '.json')
        suggestion = stem + '_0_' + str(uuid.uuid4()).upper() + '.' + extension
        (self.run / 'attachments' / exported).write_bytes(contents)
        result = {'exportedFileName': exported, 'suggestedHumanReadableName': suggestion}
        group['attachments'].append(result)
        return result

    def method(self, method, status='passed', count=2):
        group = {'testIdentifier': 'IoEndToEndTests/' + method + '()',
                 'testIdentifierURL': 'test://com.apple.xcode/Harness/EditorEndToEndTests/IoEndToEndTests/' + method,
                 'attachments': []}
        self.groups.append(group)
        self.log += [f"Test Case '-[EditorEndToEndTests.IoEndToEndTests {method}]' started.",
                     f"Test Case '-[EditorEndToEndTests.IoEndToEndTests {method}]' {status} (1.0 seconds)."]
        for document, expected in expected_checkpoints(method, self.contract)[:count]:
            assert expected == {}, 'Synthetic positive fixtures cover unchanged export stages only'
            self.attachment(group, document, self.fixture)
            metadata = {'schemaVersion': 1, 'scope': 'native-mounted-editor', 'document': document,
                        'expectedAttributes': expected, 'postflightRequired': True}
            self.attachment(group, document.removesuffix('.circ') + '.io-checkpoint.json', json.dumps(metadata).encode())
        return group

    def execute(self):
        (self.run / 'attachments/manifest.json').write_text(json.dumps(self.groups))
        (self.run / 'test.log').write_text('\n'.join(self.log))
        return run_postflight(self.run, DEFAULT_REPOSITORY, self.output)

    def test_repeated_basename_in_two_cases_uses_each_cases_own_xml(self):
        self.method(METHODS[0]); self.method(METHODS[2])
        result = self.execute()
        self.assertEqual(4, result['summary']['pairedDocumentsPassed'])
        complete = [case for case in result['cases'] if case['postflightStatus'] == 'passed']
        self.assertEqual(2, len(complete))
        first = [case['checkpointResults'][0]['documentExportedFile'] for case in complete]
        self.assertEqual(2, len(set(first)))
        self.assertEqual('not-qualified', result['issue44'])
        self.assertTrue(all(case['nativeMethodStatus'] == 'not_run' for case in result['cases'] if case not in complete))

    def test_xml_in_a_different_case_cannot_satisfy_missing_local_xml(self):
        group = self.method(METHODS[0]); other = self.method(METHODS[2])
        group['attachments'].remove(group['attachments'][0])
        result = self.execute()
        display = result['cases'][0]
        self.assertEqual('failed', display['postflightStatus'])
        self.assertTrue(any('found 0' in error for error in display['errors']))
        self.assertEqual('passed', result['cases'][2]['postflightStatus'])

    def test_duplicate_original_xml_names_within_one_case_are_rejected(self):
        group = self.method(METHODS[0])
        self.attachment(group, '1-io-fixture-opened.circ', self.fixture)
        result = self.execute()
        self.assertEqual('failed', result['cases'][0]['postflightStatus'])
        self.assertTrue(any('found 2' in error for error in result['cases'][0]['errors']))

    def test_duplicate_metadata_is_not_resolved_by_first_match(self):
        group = self.method(METHODS[0])
        metadata = json.loads((self.run / 'attachments' / group['attachments'][1]['exportedFileName']).read_text())
        self.attachment(group, '1-io-fixture-opened.io-checkpoint.json', json.dumps(metadata).encode())
        result = self.execute()
        self.assertEqual('failed', result['cases'][0]['postflightStatus'])
        self.assertTrue(any('Ambiguous metadata' in error for error in result['cases'][0]['errors']))

    def test_failed_method_keeps_available_document_check_and_missing_stage_not_run(self):
        self.method(METHODS[0], 'failed', count=1)
        result = self.execute()
        case = result['cases'][0]
        self.assertEqual('failed', case['nativeMethodStatus'])
        self.assertEqual('partial', case['postflightStatus'])
        self.assertEqual([{'document': '2-io-display-transitions.circ', 'status': 'NOT_REACHED'}], case['missingCheckpoints'])

    def test_xml_without_metadata_is_an_unpaired_attachment_not_a_pass(self):
        group = self.method(METHODS[1], 'failed', count=0)
        self.attachment(group, '1-io-fixture-opened.circ', self.fixture)
        result = self.execute()
        case = result['cases'][1]
        self.assertEqual(0, case['pairedDocumentCount'])
        self.assertEqual('INCOMPLETE_AFTER_XML_CAPTURE', case['postflightStatus'])
        self.assertEqual(12, len(case['missingCheckpoints']))
        self.assertEqual('INCOMPLETE_AFTER_XML_CAPTURE', case['missingCheckpoints'][0]['status'])
        self.assertEqual('METADATA_MISSING', case['missingCheckpoints'][0]['reason'])
        self.assertTrue(all(stage['status'] == 'NOT_REACHED' for stage in case['missingCheckpoints'][1:]))
        self.assertEqual('unpaired-not-qualified', case['orphanDocumentAttachments'][0]['status'])

    def test_running_owner_report_is_rejected_before_any_output_write(self):
        self.method(METHODS[0])
        (self.run / 'results.json').write_text(json.dumps({'status': 'RUNNING', 'execution': {'status': 'RUNNING'}}))
        with self.assertRaises(NotReady):
            self.execute()
        self.assertFalse(self.output.exists())

    def test_metadata_and_original_filename_must_agree(self):
        group = self.method(METHODS[0])
        path = self.run / 'attachments' / group['attachments'][1]['exportedFileName']
        metadata = json.loads(path.read_text()); metadata['document'] = '2-io-display-transitions.circ'
        path.write_text(json.dumps(metadata))
        result = self.execute()
        self.assertTrue(any('Original metadata name' in error for error in result['cases'][0]['errors']))

    def test_document_oracle_still_rejects_disconnected_xml(self):
        import xml.etree.ElementTree as ET
        group = self.method(METHODS[0])
        path = self.run / 'attachments' / group['attachments'][0]['exportedFileName']
        project = ET.fromstring(path.read_bytes()); circuit = project.find('circuit')
        for wire in list(circuit.findall('wire')):circuit.remove(wire)
        path.write_bytes(ET.tostring(project))
        result = self.execute()
        self.assertEqual('failed', result['cases'][0]['postflightStatus'])
        self.assertTrue(any('oracle rejected' in error for error in result['cases'][0]['errors']))

    def test_exporter_suffix_keeps_multidot_metadata_name(self):
        self.assertEqual('1-io-fixture-opened.io-checkpoint.json', original_name(
            '1-io-fixture-opened.io-checkpoint_0_588B6581-A2FC-48A1-9459-D9DD49F89BB3.json'))


if __name__ == '__main__':
    unittest.main()
