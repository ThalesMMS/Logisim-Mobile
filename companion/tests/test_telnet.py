from concurrent.futures import ThreadPoolExecutor
import socket
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from companion.api import JobContext, JobFailure, OperationRegistry
from companion.telnet import TelnetFailure, TelnetManager, _Decoder, register


class TelnetDecoderTests(unittest.TestCase):
    def test_iac_consumes_exactly_two_bytes_across_every_read_boundary(self):
        for command in (0, 240, 250, 251, 252, 253, 254, 255):
            wire = bytes((65, 255, command, 66, 67))
            for split in range(len(wire) + 1):
                decoder = _Decoder()
                self.assertEqual(b"AC", decoder.decode(wire[:split]) + decoder.decode(wire[split:]))
            decoder = _Decoder()
            self.assertEqual(b"AC", b"".join(decoder.decode(bytes((value,))) + decoder.decode(b"") for value in wire))

    def test_subnegotiation_bytes_follow_the_same_three_byte_rule(self):
        self.assertEqual(bytes((65, 2, 68)), _Decoder().decode(bytes((65, 255, 250, 1, 2, 255, 240, 67, 68))))


class TelnetManagerTests(unittest.TestCase):
    def setUp(self):
        self.manager = TelnetManager()
        self.addCleanup(self.manager.close)

    def connect(self, **parameters):
        session = self.manager.start(port=0, **parameters)
        peer = socket.create_connection(("127.0.0.1", session["localPort"]), timeout=2)
        peer.settimeout(1)
        self.addCleanup(peer.close)
        deadline = time.monotonic() + 2
        status = self.manager.poll(session["sessionId"])
        while status["link"] != "connected" and time.monotonic() < deadline:
            time.sleep(0.001)
            status = self.manager.poll(session["sessionId"])
        self.assertEqual("connected", status["link"])
        return session["sessionId"], peer, session

    def receive(self, identifier, count):
        result = []
        deadline = time.monotonic() + 2
        while len(result) < count and time.monotonic() < deadline:
            result.extend(self.manager.poll(identifier)["bytes"])
            if len(result) < count:
                time.sleep(0.001)
        self.assertEqual(count, len(result))
        return result

    def test_loopback_session_exchanges_unsigned_bytes_and_closes(self):
        identifier, peer, started = self.connect()
        self.assertEqual("127.0.0.1", started["bindAddress"])
        peer.sendall(bytes([65, 128, 255]))
        self.assertEqual([65, 128, 255], self.receive(identifier, 3))
        result = self.manager.send(identifier, 1, [90, 255])
        self.assertEqual(1, result["acceptedPacketId"])
        self.assertEqual(bytes([90, 255]), peer.recv(2))
        self.assertEqual("stopped", self.manager.stop(identifier)["link"])
        self.assertEqual(b"", peer.recv(1))
        self.assertTrue(self.manager.stop(identifier)["alreadyStopped"])
        with self.assertRaises(OSError):
            socket.create_connection(("127.0.0.1", started["localPort"]), timeout=0.2)

    def test_resize_clears_input_and_poll_replay_without_replacing_the_peer(self):
        identifier, peer, started = self.connect(buffer_size=8, request_id="original")
        peer.sendall(b"AB")
        poll_id = 1
        deadline = time.monotonic() + 2
        while True:
            batch = self.manager.poll(identifier, maximum_bytes=1, poll_id=poll_id)
            if batch["bytes"] or time.monotonic() >= deadline:
                break
            poll_id += 1
        self.assertEqual([65], batch["bytes"])

        resized = self.manager.resize_input(identifier, 2, 1)
        self.assertEqual(started["localPort"], resized["localPort"])
        self.assertEqual(batch["peerGeneration"], resized["peerGeneration"])
        self.assertEqual("connected", resized["link"])
        self.assertEqual(1, resized["droppedInputBytes"])
        self.assertEqual([], self.manager.poll(identifier, maximum_bytes=1, poll_id=poll_id)["bytes"])
        self.assertFalse(self.manager.start(port=0, buffer_size=8, request_id="original")["created"])

        peer.sendall(b"CDE")
        deadline = time.monotonic() + 2
        while self.manager.poll(identifier, maximum_bytes=0)["droppedInputBytes"] < 2 and time.monotonic() < deadline:
            time.sleep(0.001)
        self.assertEqual(2, self.manager.resize_input(identifier, 2, 1)["bufferSize"])
        self.assertEqual([67, 68], self.receive(identifier, 2))
        self.manager.send(identifier, 1, [90])
        self.assertEqual(b"Z", peer.recv(1))

    def test_resize_rejects_over_budget_without_discarding_input(self):
        self.manager.maximum_buffer_bytes = 10
        identifier, peer, _ = self.connect(buffer_size=3, maximum_output_bytes=7)
        peer.sendall(b"A")
        self.manager.poll(identifier, maximum_bytes=0)
        with self.assertRaises(TelnetFailure) as failure:
            self.manager.resize_input(identifier, 4, 1)
        self.assertEqual("resource-limit", failure.exception.code)
        self.assertEqual([65], self.receive(identifier, 1))

    def test_stopped_connected_listener_reopens_the_same_port_immediately(self):
        identifier, peer, started = self.connect()
        self.assertEqual("stopped", self.manager.stop(identifier)["link"])
        self.assertEqual(b"", peer.recv(1))
        peer.close()
        self.assertEqual(0, self.manager.active_session_count)

        restarted = self.manager.start(port=started["localPort"])
        self.assertEqual(started["localPort"], restarted["localPort"])
        self.assertNotEqual(identifier, restarted["sessionId"])
        self.assertEqual(1, self.manager.active_session_count)
        replacement = socket.create_connection(("127.0.0.1", restarted["localPort"]), timeout=2)
        replacement.settimeout(1)
        self.addCleanup(replacement.close)
        replacement.sendall(b"N")
        self.assertEqual([78], self.receive(restarted["sessionId"], 1))
        self.manager.stop(restarted["sessionId"])
        self.assertEqual(b"", replacement.recv(1))

    def test_address_reuse_cannot_replace_an_active_foreign_listener(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.settimeout(1)
            port = listener.getsockname()[1]
            with self.assertRaises(TelnetFailure) as failure:
                self.manager.start(port=port)
            self.assertEqual("listen-failed", failure.exception.code)
            self.assertEqual(0, self.manager.active_session_count)
            with socket.create_connection(("127.0.0.1", port), timeout=2) as peer:
                accepted, _ = listener.accept()
                with accepted:
                    accepted.sendall(b"F")
                    self.assertEqual(b"F", peer.recv(1))

    def test_packet_retry_does_not_send_duplicate_bytes(self):
        identifier, peer, _ = self.connect()
        self.manager.send(identifier, 1, [65])
        self.assertEqual(b"A", peer.recv(1))
        retry = self.manager.send(identifier, 1, [65])
        self.assertTrue(retry["duplicate"])
        peer.settimeout(0.05)
        with self.assertRaises(socket.timeout):
            peer.recv(1)
        with self.assertRaises(TelnetFailure) as failure:
            self.manager.send(identifier, 1, [66])
        self.assertEqual("packet-conflict", failure.exception.code)

    def test_concurrent_retries_accept_one_packet_and_send_it_once(self):
        identifier, peer, _ = self.connect()
        with ThreadPoolExecutor(max_workers=8) as workers:
            results = list(workers.map(lambda _: self.manager.send(identifier, 1, [65]), range(16)))
        self.assertEqual(1, sum(not result["duplicate"] for result in results))
        self.assertEqual(b"A", peer.recv(1))
        peer.settimeout(0.05)
        with self.assertRaises(socket.timeout):
            peer.recv(1)

    def test_retry_window_is_bounded_and_rejects_old_packet_ids(self):
        identifier, _, _ = self.connect()
        for packet in range(1, 1026):
            self.manager.send(identifier, packet, [])
        with self.assertRaises(TelnetFailure) as failure:
            self.manager.send(identifier, 1, [])
        self.assertEqual("stale-packet", failure.exception.code)
        self.assertTrue(self.manager.send(identifier, 1025, [])["duplicate"])

    def test_output_limit_does_not_accept_or_remember_the_rejected_packet(self):
        identifier, peer, _ = self.connect(maximum_output_bytes=2)
        with self.assertRaises(TelnetFailure) as failure:
            self.manager.send(identifier, 1, [1, 2, 3])
        self.assertEqual("buffer-full", failure.exception.code)
        self.assertFalse(self.manager.send(identifier, 1, [1, 2])["duplicate"])
        self.assertEqual(bytes([1, 2]), peer.recv(2))

    def test_input_queue_is_bounded_and_reports_dropped_bytes(self):
        identifier, peer, _ = self.connect(buffer_size=2)
        peer.sendall(bytes(range(256)) * 128)
        time.sleep(0.01)
        result = self.manager.poll(identifier)
        self.assertEqual([0, 1], result["bytes"])
        self.assertGreater(result["droppedInputBytes"], 0)

    def test_telnet_iac_fragmentation_and_resize_match_evolution(self):
        identifier, peer, _ = self.connect(telnet_mode=True)
        self.assertEqual(bytes([255, 251, 3, 255, 251, 1]), peer.recv(6))
        peer.sendall(bytes([65, 255]))
        self.assertEqual([65], self.receive(identifier, 1))
        self.manager.resize_input(identifier, 8, 1)
        peer.sendall(bytes([255]))
        self.assertEqual([], self.manager.poll(identifier)["bytes"])
        peer.sendall(bytes([66, 67]))
        self.assertEqual([67], self.receive(identifier, 1))
        self.manager.send(identifier, 1, [255])
        self.assertEqual(bytes([255]), peer.recv(2))

    def test_peer_replacement_closes_the_previous_connection(self):
        identifier, previous, started = self.connect()
        replacement = socket.create_connection(("127.0.0.1", started["localPort"]), timeout=2)
        self.addCleanup(replacement.close)
        deadline = time.monotonic() + 2
        status = self.manager.poll(identifier)
        while status["peerGeneration"] < 2 and time.monotonic() < deadline:
            time.sleep(0.001)
            status = self.manager.poll(identifier)
        self.assertEqual(2, status["peerGeneration"])
        self.assertEqual(b"", previous.recv(1))
        replacement.sendall(b"N")
        self.assertEqual([78], self.receive(identifier, 1))

    def test_idle_lease_expires_and_activity_renews_it(self):
        now = [0.0]
        manager = TelnetManager(idle_timeout_seconds=10, clock=lambda: now[0])
        self.addCleanup(manager.close)
        session = manager.start(port=0)
        now[0] = 5
        manager.poll(session["sessionId"])
        now[0] = 14
        self.assertEqual(0, manager.expire_idle())
        now[0] = 15
        self.assertEqual(1, manager.expire_idle())
        with self.assertRaises(TelnetFailure) as failure:
            manager.poll(session["sessionId"])
        self.assertEqual("unknown-session", failure.exception.code)
        with self.assertRaises(OSError):
            socket.create_connection(("127.0.0.1", session["localPort"]), timeout=0.2)

    def test_idle_reaper_releases_a_listener_without_another_job(self):
        manager = TelnetManager(idle_timeout_seconds=0.05)
        self.addCleanup(manager.close)
        session = manager.start(port=0)
        deadline = time.monotonic() + 2
        while manager.active_session_count and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertEqual(0, manager.active_session_count)
        with self.assertRaises(ConnectionRefusedError):
            socket.create_connection(("127.0.0.1", session["localPort"]), timeout=0.2)

    def test_start_request_id_is_idempotent_and_configuration_conflicts_fail(self):
        first = self.manager.start(port=0, request_id="start-1")
        second = self.manager.start(port=0, request_id="start-1")
        self.assertEqual(first["sessionId"], second["sessionId"])
        self.assertTrue(first["created"])
        self.assertFalse(second["created"])
        with self.assertRaises(TelnetFailure) as failure:
            self.manager.start(port=0, request_id="start-1", buffer_size=2048)
        self.assertEqual("request-conflict", failure.exception.code)

    def test_failed_bind_and_resource_limit_do_not_leak_sessions(self):
        session = self.manager.start(port=0)
        failed_manager = TelnetManager(maximum_sessions=1)
        self.addCleanup(failed_manager.close)
        with self.assertRaises(TelnetFailure) as failure:
            failed_manager.start(port=session["localPort"])
        self.assertEqual("listen-failed", failure.exception.code)
        self.assertTrue(failed_manager.start(port=0)["created"])
        manager = TelnetManager(maximum_sessions=1)
        self.addCleanup(manager.close)
        first = manager.start(port=0)
        with self.assertRaises(TelnetFailure) as failure:
            manager.start(port=0)
        self.assertEqual("resource-limit", failure.exception.code)
        manager.stop(first["sessionId"])
        self.assertTrue(manager.start(port=0)["created"])

    def test_socket_creation_failure_uses_the_public_failure_type(self):
        with patch("companion.telnet.socket.socket", side_effect=OSError("file descriptor limit")):
            with self.assertRaises(TelnetFailure) as failure:
                self.manager.start(port=0)
        self.assertEqual("listen-failed", failure.exception.code)
        self.assertEqual(0, self.manager.active_session_count)
        self.assertTrue(self.manager.start(port=0)["created"])

    def test_parameters_reject_bool_numbers_malformed_bytes_and_lan_by_default(self):
        for parameters in ({"port": True}, {"port": -1}, {"buffer_size": 0}, {"telnet_mode": "false"},
                           {"maximum_output_bytes": 0}, {"request_id": "../path"}):
            with self.subTest(parameters=parameters), self.assertRaises(TelnetFailure):
                self.manager.start(**parameters)
        with self.assertRaises(TelnetFailure) as failure:
            self.manager.start(allow_lan=True)
        self.assertEqual("lan-disabled", failure.exception.code)
        identifier, _, _ = self.connect()
        for packet, data in ((True, [1]), (0, [1]), (1, [256]), (1, [False]), (1, "text")):
            with self.subTest(packet=packet, data=data), self.assertRaises(TelnetFailure):
                self.manager.send(identifier, packet, data)


class TelnetRegistrationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.registry = OperationRegistry()
        self.addCleanup(self.registry.close)

    def context(self, *, confirmed=False):
        return JobContext(Path(self.directory.name), {}, {}, threading.Event(), user_confirmed=confirmed)

    def invoke(self, operation, parameters, *, context=None):
        return self.registry.operations[operation].handler(context or self.context(confirmed=True), parameters)

    def test_registration_is_inert_and_shutdown_closes_started_resources(self):
        with patch("companion.telnet.socket.socket", side_effect=AssertionError("implicit socket")):
            register(self.registry)
        self.assertEqual({"telnet.start", "telnet.poll", "telnet.resize", "telnet.send", "telnet.stop"}, set(self.registry.operations))
        self.assertTrue(self.registry.operations["telnet.start"].requires_confirmation)
        result = self.invoke("telnet.start", {"port": 0})
        peer = socket.create_connection(("127.0.0.1", result["localPort"]), timeout=2)
        peer.settimeout(1)
        self.addCleanup(peer.close)
        deadline = time.monotonic() + 2
        status = self.invoke("telnet.poll", {"sessionId": result["sessionId"]})
        while status["link"] != "connected" and time.monotonic() < deadline:
            time.sleep(0.001)
            status = self.invoke("telnet.poll", {"sessionId": result["sessionId"]})
        self.assertEqual("connected", status["link"])
        self.registry.close()
        self.assertEqual(b"", peer.recv(1))

    def test_registered_operations_exchange_real_peer_data_and_stop_idempotently(self):
        register(self.registry)
        started = self.invoke("telnet.start", {"port": 0})
        identifier = started["sessionId"]
        peer = socket.create_connection(("127.0.0.1", started["localPort"]), timeout=2)
        peer.settimeout(1)
        self.addCleanup(peer.close)
        self.invoke("telnet.poll", {"sessionId": identifier})
        peer.sendall(b"A")
        deadline = time.monotonic() + 2
        received = []
        while not received and time.monotonic() < deadline:
            received = self.invoke("telnet.poll", {"sessionId": identifier})["bytes"]
        self.assertEqual([65], received)
        result = self.invoke("telnet.send", {"sessionId": identifier, "packetId": 1, "bytes": [66]})
        self.assertEqual(1, result["acceptedPacketId"])
        self.assertEqual(b"B", peer.recv(1))
        self.assertFalse(self.invoke("telnet.stop", {"sessionId": identifier})["alreadyStopped"])
        self.assertTrue(self.invoke("telnet.stop", {"sessionId": identifier})["alreadyStopped"])

    def test_host_grant_and_user_confirmation_are_both_required_for_lan(self):
        register(self.registry)
        with self.assertRaises(JobFailure) as failure:
            self.invoke("telnet.start", {"port": 0, "allowLan": True}, context=self.context(confirmed=True))
        self.assertEqual("lan-disabled", failure.exception.code)
        granted_registry = OperationRegistry()
        self.addCleanup(granted_registry.close)
        register(granted_registry, allow_lan=True)
        with self.assertRaises(JobFailure) as failure:
            granted_registry.operations["telnet.start"].handler(self.context(), {"port": 0, "allowLan": True})
        self.assertEqual("confirmation-required", failure.exception.code)

    def test_loopback_start_also_requires_the_confirmed_plan_context(self):
        manager = register(self.registry)
        with self.assertRaises(JobFailure) as failure:
            self.invoke("telnet.start", {"port": 0}, context=self.context())
        self.assertEqual("confirmation-required", failure.exception.code)
        self.assertEqual(0, manager.active_session_count)

    def test_cancelled_start_releases_the_listener_created_by_that_job(self):
        register(self.registry)
        reservation = socket.socket()
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
        reservation.close()
        context = self.context(confirmed=True)
        checks = [0]

        def cancel_after_creation():
            checks[0] += 1
            if checks[0] == 2:
                raise JobFailure("cancelled", "cancelled in test")

        context.check_cancelled = cancel_after_creation
        with self.assertRaises(JobFailure) as failure:
            self.invoke("telnet.start", {"port": port}, context=context)
        self.assertEqual("cancelled", failure.exception.code)
        self.assertTrue(self.invoke("telnet.start", {"port": port})["created"])


if __name__ == "__main__":
    unittest.main()
