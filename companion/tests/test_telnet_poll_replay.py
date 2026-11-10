import socket
import time
import unittest

from companion.telnet import TelnetFailure, TelnetManager


class TelnetPollReplayTest(unittest.TestCase):
    def setUp(self):
        self.manager = TelnetManager()
        self.started = self.manager.start(port=0)
        self.session = self.started['sessionId']
        self.peer = socket.create_connection(('127.0.0.1', self.started['localPort']), timeout=2)
        self.manager.poll(self.session, maximum_bytes=0, poll_id=1)

    def tearDown(self):
        self.peer.close()
        self.manager.close()

    def send_and_buffer(self, data):
        self.peer.sendall(bytes(data))
        deadline = time.monotonic() + 2
        while True:
            # An empty poll is a valid retained response. Await the complete TCP
            # batch without consuming input or assigning its test poll ID yet.
            self.assertEqual([], self.manager.poll(self.session, maximum_bytes=0)['bytes'])
            with self.manager._lock:
                buffered = len(self.manager._sessions[self.session].received)
            if buffered >= len(data):
                return
            if time.monotonic() >= deadline:
                self.fail(f'Timed out buffering {len(data)} input bytes; received {buffered}')
            time.sleep(0.001)

    def test_lost_reply_can_be_retried_without_consuming_new_bytes(self):
        self.send_and_buffer([0, 127, 128, 255, 10])
        reply = self.manager.poll(self.session, maximum_bytes=5, poll_id=2)
        self.assertEqual([0, 127, 128, 255, 10], reply['bytes'])
        self.send_and_buffer([42])
        retry = self.manager.poll(self.session, maximum_bytes=5, poll_id=2)
        self.assertEqual(reply, retry)
        self.assertEqual([42], self.manager.poll(self.session, maximum_bytes=5, poll_id=3)['bytes'])

    def test_zero_byte_poll_keeps_input_in_the_host_queue(self):
        self.send_and_buffer([65])
        self.assertEqual([], self.manager.poll(self.session, maximum_bytes=0, poll_id=2)['bytes'])
        self.assertEqual([65], self.manager.poll(self.session, maximum_bytes=1, poll_id=3)['bytes'])

    def test_conflicting_or_stale_poll_is_rejected_without_reading_input(self):
        empty = self.manager.poll(self.session, maximum_bytes=1, poll_id=2)
        self.assertEqual([], empty['bytes'])
        self.send_and_buffer([65])
        with self.assertRaises(TelnetFailure) as conflict:
            self.manager.poll(self.session, maximum_bytes=2, poll_id=2)
        self.assertEqual('poll-conflict', conflict.exception.code)
        with self.assertRaises(TelnetFailure) as stale:
            self.manager.poll(self.session, maximum_bytes=1, poll_id=1)
        self.assertEqual('stale-poll', stale.exception.code)
        self.assertEqual(empty, self.manager.poll(self.session, maximum_bytes=1, poll_id=2))
        self.assertEqual([65], self.manager.poll(self.session, maximum_bytes=1, poll_id=3)['bytes'])

    def test_reply_mutation_does_not_change_retained_bytes(self):
        self.send_and_buffer([65])
        reply = self.manager.poll(self.session, maximum_bytes=1, poll_id=2)
        reply['bytes'].clear()
        self.assertEqual([65], self.manager.poll(self.session, maximum_bytes=1, poll_id=2)['bytes'])
        retry = self.manager.poll(self.session, maximum_bytes=1, poll_id=2)
        retry['bytes'].clear()
        self.assertEqual([65], self.manager.poll(self.session, maximum_bytes=1, poll_id=2)['bytes'])

    def test_invalid_poll_boundaries_are_rejected(self):
        for poll_id in (0, -1, True, 1.5, 2**63):
            with self.assertRaises(TelnetFailure):
                self.manager.poll(self.session, poll_id=poll_id)
        for maximum in (-1, True, 65_537):
            with self.assertRaises(TelnetFailure):
                self.manager.poll(self.session, maximum_bytes=maximum, poll_id=2)
