"""Replay retained reader-on bytes. This test makes no device or network calls."""
from pathlib import Path
import json
import unittest

from check_binding import BindingError, FULL, check_cross_evidence


CAPTURE = Path(__file__).resolve().parent.with_name("android-console-precondition-5556-001")
PID_CAPTURED_BEFORE_AND_AFTER = 582


def captured(label):
    return (
        (CAPTURE / (label + "-ams.txt")).read_text(),
        (CAPTURE / (label + "-a11y.txt")).read_text(),
    )


class CapturedBindingTests(unittest.TestCase):
    def check_field(self, field):
        ams, accessibility = captured("baseline-after-sdk")
        return check_cross_evidence(ams, accessibility.replace("eventTypes=TYPES_ALL_MASK", field), system_server_pid=PID_CAPTURED_BEFORE_AND_AFTER)

    def test_before_sdk_captured_state_is_parsed_without_reader_acceptance(self):
        result = check_cross_evidence(*captured("baseline-before-sdk"), system_server_pid=PID_CAPTURED_BEFORE_AND_AFTER)
        self.assertEqual(result["result"], "PASS_AMS_BINDING_AND_A11Y_STATE_ONLY")
        self.assertEqual(result["ams"]["component"], FULL)
        self.assertEqual(result["ams"]["service_process_pid"], 29352)
        self.assertEqual(result["accessibility"]["bound_capabilities"], 251)
        self.assertIsNone(result["accessibility"]["component_in_bound_dump"])
        self.assertIn("CORRELATION_ONLY", result["identity_join"])

    def test_after_sdk_captured_state_is_parsed_without_reader_acceptance(self):
        result = check_cross_evidence(*captured("baseline-after-sdk"), system_server_pid=PID_CAPTURED_BEFORE_AND_AFTER)
        self.assertEqual(result["result"], "PASS_AMS_BINDING_AND_A11Y_STATE_ONLY")
        self.assertEqual(result["ams"]["system_server_pid"], 582)
        self.assertEqual(result["accessibility"]["bound_label_observed_only"], "TalkBack")
        self.assertEqual(result["accessibility"]["bound_record_count"], 1)

    def test_captured_state_without_touch_exploration_is_rejected(self):
        ams, accessibility = captured("baseline-after-sdk")
        with self.assertRaises(BindingError) as failure:
            check_cross_evidence(ams, accessibility.replace("touchExplorationEnabled=true", "touchExplorationEnabled=false"), system_server_pid=PID_CAPTURED_BEFORE_AND_AFTER)
        self.assertEqual(failure.exception.code, "touch_exploration_not_confirmed")

    def test_source_defined_zero_all_single_multiple_and_unknown_bits(self):
        ams, accessibility = captured("baseline-after-sdk")
        for value in ("", "TYPES_ALL_MASK", "TYPE_VIEW_CLICKED",
                      "[TYPE_VIEW_CLICKED, TYPE_VIEW_LONG_CLICKED]",
                      "8000000", "80000000", "[TYPE_VIEW_CLICKED, 80000000]"):
            with self.subTest(event_field=value):
                result = check_cross_evidence(ams, accessibility.replace("eventTypes=TYPES_ALL_MASK", "eventTypes=" + value), system_server_pid=PID_CAPTURED_BEFORE_AND_AFTER)
                self.assertEqual(result["result"], "PASS_AMS_BINDING_AND_A11Y_STATE_ONLY")
                self.assertEqual(result["accessibility"]["bound_capabilities"], 251)
                self.assertIsNone(result["accessibility"]["component_in_bound_dump"])

    def test_noncanonical_or_wrong_formatter_outputs_are_rejected(self):
        ams, accessibility = captured("baseline-after-sdk")
        for value in ("[]", "0", "3", "ffffffff", "0x80000000", "08000000", "TYPE_UNKNOWN",
                      "[TYPE_VIEW_CLICKED]", "[TYPES_ALL_MASK]", "[TYPE_VIEW_CLICKED,TYPE_VIEW_LONG_CLICKED]",
                      "[TYPE_VIEW_LONG_CLICKED, TYPE_VIEW_CLICKED]", "[TYPE_VIEW_CLICKED, TYPE_VIEW_CLICKED]",
                      "[80000000, TYPE_VIEW_CLICKED]", "[TYPE_VIEW_CLICKED, ]", "TYPE_VIEW_CLICKED "):
            with self.subTest(event_field=value), self.assertRaises(BindingError) as failure:
                check_cross_evidence(ams, accessibility.replace("eventTypes=TYPES_ALL_MASK", "eventTypes=" + value), system_server_pid=PID_CAPTURED_BEFORE_AND_AFTER)
            self.assertEqual(failure.exception.code, "unknown_event_type_format")

    def test_missing_equals_and_serviceinfo_colon_are_rejected(self):
        ams, accessibility = captured("baseline-after-sdk")
        for field in ("eventTypes[TYPE_VIEW_CLICKED]", "eventTypes:[TYPE_VIEW_CLICKED]", "eventTypesTYPES_ALL_MASK"):
            with self.subTest(event_field=field), self.assertRaises(BindingError) as failure:
                check_cross_evidence(ams, accessibility.replace("eventTypes=TYPES_ALL_MASK", field), system_server_pid=PID_CAPTURED_BEFORE_AND_AFTER)
            self.assertEqual(failure.exception.code, "unknown_bound_format")

    def test_correct_format_does_not_replace_spoken_feedback_or_touch_capability(self):
        ams, accessibility = captured("baseline-after-sdk")
        for text in (accessibility.replace("FEEDBACK_SPOKEN, ", ""),
                     accessibility.replace("capabilities=251", "capabilities=249")):
            with self.subTest(accessibility=text), self.assertRaises(BindingError) as failure:
                check_cross_evidence(ams, text, system_server_pid=PID_CAPTURED_BEFORE_AND_AFTER)
            self.assertEqual(failure.exception.code, "bound_record_not_spoken_touch_capable")

    def test_independent_source_cases_keep_their_stated_limits(self):
        cases = json.loads(Path(__file__).with_name("canonical-cases.json").read_text())
        self.assertEqual(cases["status"], "SOURCE_DERIVED_EXPECTATIONS_ONLY_NOT_EXECUTED")
        for case in cases["valid_public_formatter_cases"]:
            with self.subTest(field=case["bound_field"]):
                self.assertEqual(self.check_field(case["bound_field"])["result"], "PASS_AMS_BINDING_AND_A11Y_STATE_ONLY")
        for value in cases["invalid_bound_field_values"]:
            with self.subTest(value=value), self.assertRaises(BindingError):
                self.check_field("eventTypes=" + value)
        for field in cases["invalid_bound_prefix_cases"]:
            with self.subTest(field=field), self.assertRaises(BindingError):
                self.check_field(field)

    def test_all_32_source_atoms_and_their_order(self):
        source = json.loads(Path(__file__).with_name("canonical-atoms.json").read_text())
        atoms = source["ordered_atoms"]
        self.assertEqual([item["bit"] for item in atoms], list(range(32)))
        for item in atoms:
            with self.subTest(bit=item["bit"]):
                self.assertEqual(self.check_field("eventTypes=" + item["atom"])["result"], "PASS_AMS_BINDING_AND_A11Y_STATE_ONLY")
        for first, second in zip(atoms, atoms[1:]):
            with self.subTest(first=first["bit"], second=second["bit"]):
                self.assertEqual(self.check_field("eventTypes=[" + first["atom"] + ", " + second["atom"] + "]")["result"], "PASS_AMS_BINDING_AND_A11Y_STATE_ONLY")
                with self.assertRaises(BindingError):
                    self.check_field("eventTypes=[" + second["atom"] + ", " + first["atom"] + "]")


if __name__ == "__main__":
    unittest.main()
