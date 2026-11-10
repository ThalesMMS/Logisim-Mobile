import dataclasses
import unittest

from contact import console_ok, frames, transmit, validate


class FakeTime:
    def __init__(self):
        self.now = 0.0

    def clock(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class ContactTest(unittest.TestCase):
    def test_payload_and_raw_coordinates_are_literal_expected_values(self):
        data = frames()
        self.assertEqual([dict((code, value) for kind, code, value in f.events if kind == 3)[53]
                          for f in data[:-1]], [9101, 10618, 12135, 13652, 15169, 16686, 18203, 19720, 21237])
        self.assertEqual(data[0].events, ((3, 47, 0), (3, 57, 1), (3, 55, 0),
                                        (3, 53, 9101), (3, 54, 10922), (3, 58, 64), (0, 0, 0)))
        self.assertEqual(data[-1].events, ((3, 47, 0), (3, 58, 0), (3, 57, -1), (0, 0, 0)))

    def test_one_contact_has_absolute_nominal_times_and_never_claims_reader_success(self):
        timer = FakeTime()
        calls = []
        result = transmit(lambda frame: calls.append((frame.name, round(timer.now, 3))), timer.clock, timer.sleep)
        self.assertEqual(calls, [("down", 0.0), ("move-1", 0.02), ("move-2", 0.04),
                                 ("move-3", 0.06), ("move-4", 0.08), ("move-5", 0.1),
                                 ("move-6", 0.12), ("move-7", 0.14), ("move-8", 0.16), ("up", 0.18)])
        self.assertFalse(result["reader_qualified"])
        self.assertFalse(result["kernel_close_proven"])

    def test_error_after_possible_down_still_attempts_one_close(self):
        timer = FakeTime()
        calls = []

        def send(frame):
            calls.append(frame.name)
            if frame.name == "down":
                raise RuntimeError("An acknowledgment can fail after delivery")

        result = transmit(send, timer.clock, timer.sleep)
        self.assertEqual(calls, ["down", "up"])
        self.assertEqual(result["operation_error"], "RuntimeError")

    def test_interruption_during_move_closes_without_retry_or_further_move(self):
        timer = FakeTime()
        calls = []

        def send(frame):
            calls.append(frame.name)
            if frame.name == "move-1":
                raise KeyboardInterrupt()

        transmit(send, timer.clock, timer.sleep)
        self.assertEqual(calls, ["down", "move-1", "up"])

    def test_late_sample_stops_and_closes(self):
        timer = FakeTime()
        calls = []

        def send(frame):
            calls.append(frame.name)
            if frame.name == "down":
                timer.now += 0.2

        result = transmit(send, timer.clock, timer.sleep)
        self.assertEqual(calls, ["down", "up"])
        self.assertEqual(result["operation_error"], "TimeoutError")

    def test_release_error_is_retained_without_retry(self):
        timer = FakeTime()
        calls = []

        def send(frame):
            calls.append(frame.name)
            if frame.name == "up":
                raise OSError("release receipt unavailable")

        result = transmit(send, timer.clock, timer.sleep)
        self.assertEqual(calls.count("up"), 1)
        self.assertEqual(result["release_error"], "OSError")

    def test_interruption_while_waiting_for_up_still_attempts_close(self):
        timer = FakeTime()
        calls = []

        def sleep(seconds):
            if timer.now >= 0.16 and seconds > 0:
                raise KeyboardInterrupt()
            timer.sleep(seconds)

        result = transmit(lambda frame: calls.append(frame.name), timer.clock, sleep)
        self.assertEqual(calls[-1], "up")
        self.assertEqual(result["release_wait_error"], "KeyboardInterrupt")

    def test_malformed_event_is_rejected_before_transmission(self):
        data = list(frames())
        data[0] = dataclasses.replace(data[0], events=((3, 53, 99999), (0, 0, 0)))
        with self.assertRaises(ValueError):
            validate(data)

    def test_process_success_with_protocol_ko_is_a_failure(self):
        self.assertTrue(console_ok(0, b"OK\r\n"))
        self.assertFalse(console_ok(0, b"KO: invalid event\r\n"))
        self.assertFalse(console_ok(1, b"OK\r\n"))

    def test_embedded_carriage_return_is_not_a_valid_ok(self):
        self.assertFalse(console_ok(0, b"O\rK"))


if __name__ == "__main__":
    unittest.main()
