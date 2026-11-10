"""Offline controls for the four-failure historical receipt. No iOS execution."""
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from ios_io44_case_postflight import sha
from verify_ios_io44_failed_receipt import DEFAULT_BUNDLE, verify_receipt


class FailedIoReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.bundle = Path(self.temporary.name) / 'portable-copy'
        shutil.copytree(DEFAULT_BUNDLE, self.bundle)

    def update_json(self, relative, change):
        path = self.bundle / relative
        value = json.loads(path.read_text()); change(value)
        path.write_text(json.dumps(value))
        if relative != 'receipt.json':
            self.rehash(relative)

    def rehash(self, relative):
        path = self.bundle / 'receipt.json'
        receipt = json.loads(path.read_text())
        receipt['artifacts'][relative]['sha256'] = sha(self.bundle / relative)
        path.write_text(json.dumps(receipt))

    def test_portable_copy_uses_frozen_oracle_and_preserves_failed_qualification(self):
        result = verify_receipt(self.bundle)
        self.assertEqual('verified-failed-native-run', result['status'])
        self.assertEqual('four-failed', result['nativeMethods'])
        self.assertEqual(0, result['qualifiedCheckpointPairs'])
        self.assertEqual({'incompleteAfterXmlCapture': 1, 'reason': 'METADATA_MISSING', 'notReached': 17}, result['documentStages'])
        self.assertEqual('not-qualified', result['issue44'])
        dimensions = result['caseObservations'][0]
        self.assertEqual([False], dimensions['match19'])
        self.assertTrue(all(case['match19'] == [True, True] for case in result['caseObservations'][1:]))

    def test_captured_xml_cannot_be_relabelled_not_reached(self):
        self.update_json('receipt.json', lambda value: value['claim'].update(firstDimensionsCheckpoint='NOT_REACHED'))
        with self.assertRaisesRegex(AssertionError, 'Four FAILs'):
            verify_receipt(self.bundle)

    def test_zero_metadata_cannot_be_promoted_to_a_qualified_checkpoint(self):
        self.update_json('receipt.json', lambda value: value['claim'].update(qualifiedCheckpointPairs=1))
        with self.assertRaisesRegex(AssertionError, 'Four FAILs'):
            verify_receipt(self.bundle)

    def test_successful_cleanup_cannot_be_promoted_to_native_pass(self):
        self.update_json('run/results.json', lambda value: value.update(status='PASS'))
        with self.assertRaises(AssertionError):
            verify_receipt(self.bundle)

    def test_synthesized_event_is_not_a_delivery_receipt(self):
        self.update_json('receipt.json', lambda value: value['claim'].update(closeEventDelivery='delivered'))
        with self.assertRaisesRegex(AssertionError, 'Four FAILs'):
            verify_receipt(self.bundle)

    def test_modified_decoded_event_is_rejected_against_the_original_archive(self):
        self.update_json('diagnostics/close/close-synthesized-event-decoded.json', lambda value: value.update(targetProcessID=77115))
        with self.assertRaises(AssertionError):
            verify_receipt(self.bundle)

    def test_unknown_initial_dimension_probe_is_rejected_even_after_rehashing(self):
        relative = 'run/attachments/4F609B0E-156B-4071-B95F-6DF5536AF64F.txt'
        path = self.bundle / relative
        text = path.read_text(); self.assertIn('OUT_KEYBOARD_DATA: 0000000', text)
        path.write_text(text.replace('OUT_KEYBOARD_DATA: 0000000', 'OUT_KEYBOARD_DATA: ???????'))
        self.rehash(relative)
        with self.assertRaisesRegex(AssertionError, 'OUT_KEYBOARD_DATA'):
            verify_receipt(self.bundle)

    def test_empty_wait_dump_cannot_gain_a_window_without_rejection(self):
        receipt = json.loads((self.bundle / 'receipt.json').read_text())
        relative = 'run/attachments/' + receipt['emptyWaitSnapshots'][0]
        path = self.bundle / relative; path.write_text(path.read_text() + '\nWindow (Main), 0x123, {{0.0, 0.0}, {834.0, 1210.0}}\n')
        self.rehash(relative)
        with self.assertRaises(AssertionError):
            verify_receipt(self.bundle)


if __name__ == '__main__':
    unittest.main()
