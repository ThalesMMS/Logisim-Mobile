import re
import unittest
from check_typeb_trace import check_trace, parse_trace, InitialState, TraceError

# Literal kernel text. Repeated slot/Y/pressure fields are already omitted after DOWN.
VALID = """add device 1: /dev/input/event1
  name:     "virtio_input_multi_touch_1"
[     100.000000] 0003 0039 00000001
[     100.000000] 0003 0035 0000238d
[     100.000000] 0003 0036 00002aaa
[     100.000000] 0003 003a 00000040
[     100.000000] 0000 0000 00000000
[     100.020000] 0003 0035 0000297a
[     100.020000] 0000 0000 00000000
[     100.040000] 0003 0035 00002f67
[     100.040000] 0000 0000 00000000
[     100.060000] 0003 0035 00003554
[     100.060000] 0000 0000 00000000
[     100.080000] 0003 0035 00003b41
[     100.080000] 0000 0000 00000000
[     100.100000] 0003 0035 0000412e
[     100.100000] 0000 0000 00000000
[     100.120000] 0003 0035 0000471b
[     100.120000] 0000 0000 00000000
[     100.140000] 0003 0035 00004d08
[     100.140000] 0000 0000 00000000
[     100.160000] 0003 0035 000052f5
[     100.160000] 0000 0000 00000000
[     100.180000] 0003 003a 00000000
[     100.180000] 0003 0039 ffffffff
[     100.180000] 0000 0000 00000000
"""


def retime(text, replacements):
    return re.sub(r"100\.\d{6}", lambda m: replacements.get(m.group(), m.group()), text)


class TraceTests(unittest.TestCase):
    def check(self, text=VALID, initial=InitialState(0)):
        return check_trace(text, initial)

    def rejects(self, code, text=VALID, initial=InitialState(0)):
        with self.assertRaises(TraceError) as caught:
            self.check(text, initial)
        self.assertEqual(caught.exception.code, code)

    def test_literal_complete_sequence_and_measured_timing(self):
        result = self.check()
        self.assertEqual(result["result"], "PASS_KERNEL_SEQUENCE_ONLY")
        self.assertEqual([f["x"] for f in result["frames"]], [9101, 10618, 12135, 13652, 15169, 16686, 18203, 19720, 21237, 21237])
        self.assertEqual([f["y"] for f in result["frames"]], [10922] * 10)
        self.assertEqual([f["kind"] for f in result["frames"]], ["down"] + ["move"] * 8 + ["up"])
        self.assertEqual([f["tracking"] for f in result["frames"]], [1] * 9 + [-1])
        self.assertEqual([f["pressure"] for f in result["frames"]], [64] * 9 + [0])
        self.assertEqual(result["duration_us"], 180000)
        self.assertEqual(result["syn_intervals_us"], [20000] * 9)
        self.assertEqual(result["offset_from_nominal_us"], [0] * 10)
        self.assertEqual(result["inherited_initial_axes"], [])

    def test_explicit_slot_zero_and_redundant_axes(self):
        text = VALID.replace("[     100.000000] 0003 0039", "[     100.000000] 0003 002f 00000000\n[     100.000000] 0003 0039", 1)
        text = text.replace("[     100.020000] 0000", "[     100.020000] 0003 0036 00002aaa\n[     100.020000] 0003 003a 00000040\n[     100.020000] 0000", 1)
        self.assertEqual(self.check(text)["result"], "PASS_KERNEL_SEQUENCE_ONLY")

    def test_first_axes_can_only_come_from_explicit_independent_initial_values(self):
        text = VALID.replace("[     100.000000] 0003 0035 0000238d\n", "").replace("[     100.000000] 0003 0036 00002aaa\n", "").replace("[     100.000000] 0003 003a 00000040\n", "")
        result = self.check(text, InitialState(0, 9101, 10922, 64))
        self.assertEqual(result["inherited_initial_axes"], ["pressure", "x", "y"])
        self.rejects("unknown_axis", text)

    def test_literal_tool_type_finger_zero_is_allowed(self):
        text = VALID.replace("[     100.000000] 0003 0035", "[     100.000000] 0003 0037 00000000\n[     100.000000] 0003 0035", 1)
        self.assertEqual(self.check(text)["result"], "PASS_KERNEL_SEQUENCE_ONLY")

    def test_literal_non_finger_tool_type_is_rejected(self):
        text = VALID.replace("[     100.000000] 0003 0035", "[     100.000000] 0003 0037 00000001\n[     100.000000] 0003 0035", 1)
        self.rejects("wrong_tool_type", text)

    def test_unknown_or_wrong_initial_slot_is_never_defaulted(self):
        for slot in [None, 1, -1, True, "0"]:
            with self.subTest(slot=slot):
                self.rejects("initial_slot_unknown_or_wrong", initial=InitialState(slot))

    def test_invalid_initial_axis(self):
        self.rejects("invalid_initial_axis", initial=InitialState(0, 32768))
        self.rejects("invalid_initial_axis", initial=InitialState(0, pressure=True))

    def test_unknown_first_x_y_pressure_independently(self):
        for line in ["0035 0000238d", "0036 00002aaa", "003a 00000040"]:
            with self.subTest(axis=line):
                self.rejects("unknown_axis", VALID.replace("[     100.000000] 0003 " + line + "\n", ""))

    def test_stale_initial_axes_cannot_override_observed_wrong_position(self):
        self.rejects("wrong_position", VALID.replace("0000238d", "0000238e"), InitialState(0, 9101, 10922, 64))

    def test_missing_down_tracking_is_not_inferred_from_expected_path(self):
        self.rejects("missing_down", VALID.replace("[     100.000000] 0003 0039 00000001\n", ""))

    def test_wrong_raw_tracking_id_including_logical_grpc_id(self):
        for value in ["0000001c", "00000002", "fffffffe"]:
            with self.subTest(value=value):
                self.rejects("wrong_tracking_id", VALID.replace("0039 00000001", "0039 " + value))

    def test_mid_contact_tracking_change(self):
        self.rejects("wrong_tracking_id", VALID.replace("[     100.040000] 0000", "[     100.040000] 0003 0039 00000002\n[     100.040000] 0000", 1))

    def test_other_slot_even_without_observed_other_contact(self):
        self.rejects("other_slot", VALID.replace("[     100.020000] 0003", "[     100.020000] 0003 002f 00000001\n[     100.020000] 0003", 1))

    def test_foreign_device_header_or_event_prefix(self):
        self.rejects("unrecognized_line", VALID.replace("add device 1: /dev/input/event1", "add device 1: /dev/input/event2"))
        self.rejects("unrecognized_line", VALID.replace("] 0003", "] /dev/input/event2: 0003", 1))

    def test_unexpected_or_late_readiness_headers(self):
        self.rejects("unrecognized_line", VALID.replace('virtio_input_multi_touch_1', 'other_device'))
        self.rejects("unexpected_header", VALID + "add device 1: /dev/input/event1\n")
        self.rejects("unexpected_header", VALID.replace('[     100.000000]', '  name:     "virtio_input_multi_touch_1"\n[     100.000000]', 1))

    def test_missing_readiness_and_empty_trace(self):
        self.rejects("missing_readiness", VALID.split('\n', 2)[2])
        self.rejects("empty_trace", VALID.split('[', 1)[0])

    def test_syn_dropped(self):
        self.rejects("syn_dropped", VALID.replace("[     100.080000] 0000 0000", "[     100.080000] 0000 0003", 1))

    def test_type_a_syn_or_nonzero_syn(self):
        self.rejects("unexpected_syn", VALID.replace("0000 0000 00000000", "0000 0002 00000000", 1))
        self.rejects("unexpected_syn", VALID.replace("0000 0000 00000000", "0000 0000 00000001", 1))

    def test_unexpected_legacy_axis_or_key(self):
        self.rejects("unexpected_abs", VALID.replace("0003 0035", "0003 0000", 1))
        self.rejects("unexpected_event_type", VALID.replace("0003 0035", "0001 014a", 1))

    def test_position_y_or_pressure_changed(self):
        self.rejects("wrong_position", VALID.replace("0000297a", "0000297b", 1))
        self.rejects("wrong_position", VALID.replace("[     100.020000] 0000", "[     100.020000] 0003 0036 00002aab\n[     100.020000] 0000", 1))
        self.rejects("wrong_pressure", VALID.replace("[     100.020000] 0000", "[     100.020000] 0003 003a 00000041\n[     100.020000] 0000", 1))

    def test_axis_range(self):
        self.rejects("axis_out_of_range", VALID.replace("0000238d", "00008000", 1))
        self.rejects("axis_out_of_range", VALID.replace("003a 00000040", "003a 00000401", 1))

    def test_missing_move_syn_or_repeated_position(self):
        self.rejects("wrong_position", VALID.replace("[     100.040000] 0003 0035 00002f67\n[     100.040000] 0000 0000 00000000\n", ""))
        self.rejects("wrong_position", VALID.replace("[     100.020000] 0000 0000 00000000\n", ""))
        self.rejects("wrong_position", VALID.replace("0000297a", "0000238d", 1))

    def test_missing_release_pressure_tracking_or_syn(self):
        self.rejects("release_pressure_nonzero", VALID.replace("[     100.180000] 0003 003a 00000000\n", ""))
        self.rejects("extra_active_frame", VALID.replace("[     100.180000] 0003 0039 ffffffff\n", ""))
        self.rejects("missing_final_syn", VALID.rsplit("[     100.180000] 0000 0000 00000000\n", 1)[0])
        self.rejects("missing_release", VALID.split("[     100.180000]", 1)[0])

    def test_release_without_start_or_too_early(self):
        self.rejects("release_without_contact", VALID.replace("0039 00000001", "0039 ffffffff", 1))
        self.rejects("early_or_invalid_release", VALID.replace("[     100.020000] 0000", "[     100.020000] 0003 003a 00000000\n[     100.020000] 0003 0039 ffffffff\n[     100.020000] 0000", 1))

    def test_wrong_release_position(self):
        self.rejects("wrong_release_position", VALID.replace("[     100.180000] 0003 003a", "[     100.180000] 0003 0035 000052f4\n[     100.180000] 0003 003a", 1))

    def test_extra_frame_or_second_contact(self):
        self.rejects("events_after_release", VALID + "[     100.200000] 0003 0039 00000001\n")
        self.rejects("events_after_release", VALID + "[     100.200000] 0000 0000 00000000\n")

    def test_timestamp_regression_and_equal_syn(self):
        self.rejects("timestamp_regression", VALID.replace("100.020000", "099.020000"))
        self.rejects("non_increasing_syn", VALID.replace("100.020000", "100.000000"))

    def test_duration_upper_bound_inclusive(self):
        self.assertEqual(self.check(VALID.replace("100.180000", "100.300000"))["duration_us"], 300000)
        self.rejects("duration_out_of_contract", VALID.replace("100.180000", "100.300001"))

    def test_duration_lower_bound_inclusive(self):
        mapping = {"100.020000": "100.010000", "100.040000": "100.020000", "100.060000": "100.030000", "100.080000": "100.040000", "100.100000": "100.050000", "100.120000": "100.060000", "100.140000": "100.070000", "100.160000": "100.080000", "100.180000": "100.120000"}
        text = retime(VALID, mapping)
        self.assertEqual(self.check(text)["duration_us"], 120000)
        self.rejects("duration_out_of_contract", text.replace("100.120000", "100.119999"))

    def test_truncated_nonnumeric_or_stderr_text_never_ignored(self):
        for text in [VALID + "[     100.200000] 0003 0035 0000", VALID + "Can't enable monotonic clock reporting: denied\n", VALID.replace("0003 0039", "EV_ABS ABS_MT_TRACKING_ID", 1)]:
            with self.subTest(text=text[-80:]):
                self.rejects("unrecognized_line", text)

    def test_initial_global_contact_absence_is_not_a_claim(self):
        self.assertIn("Global initial absence of contacts", self.check()["not_proven"])
        self.assertIn("Activity on other input devices", self.check()["not_proven"])
        self.assertIn("Fresh independent getter for supplied initial selected slot and axes", self.check()["external_evidence_required"])


if __name__ == "__main__":
    unittest.main()
