"""Explicit companion Telnet resources. Importing this module opens no sockets."""
from __future__ import annotations

from collections import OrderedDict
from dataclasses import dataclass, field
import hashlib
import re
import secrets
import socket
import threading
import time


class TelnetFailure(Exception):
    def __init__(self, code: str, message: str, details=None):
        super().__init__(message)
        self.code, self.message, self.details = code, message, details


def _integer(value, name: str, minimum: int, maximum: int) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise TelnetFailure("invalid-parameter", f"{name} must be an integer from {minimum} to {maximum}.")
    return value


def _boolean(value, name: str) -> bool:
    if type(value) is not bool:
        raise TelnetFailure("invalid-parameter", f"{name} must be a boolean.")
    return value


def _bytes(value) -> bytes:
    if not isinstance(value, list) or len(value) > 65_536 or any(type(item) is not int or not 0 <= item <= 255 for item in value):
        raise TelnetFailure("invalid-parameter", "bytes must be a list of at most 65536 unsigned bytes.")
    return bytes(value)


class _Decoder:
    """Evolution discards the two wire bytes after IAC regardless of command."""

    def __init__(self):
        self.remaining_command_bytes = 0

    def decode(self, data: bytes) -> bytes:
        result = bytearray()
        for value in data:
            if self.remaining_command_bytes:
                self.remaining_command_bytes -= 1
            elif value == 255:
                self.remaining_command_bytes = 2
            else:
                result.append(value)
        return bytes(result)


@dataclass(frozen=True)
class _Configuration:
    port: int
    allow_lan: bool
    telnet_mode: bool
    buffer_size: int
    maximum_output_bytes: int


@dataclass
class _Session:
    identifier: str
    configuration: _Configuration
    listener: socket.socket
    local_port: int
    last_activity: float
    buffer_size: int
    request_id: str | None = None
    peer: socket.socket | None = None
    received: bytearray = field(default_factory=bytearray)
    pending_output: bytearray = field(default_factory=bytearray)
    accepted: OrderedDict[int, str] = field(default_factory=OrderedDict)
    highest_packet_id: int = 0
    decoder: _Decoder = field(default_factory=_Decoder)
    peer_generation: int = 0
    dropped_input: int = 0
    dropped_output: int = 0
    last_poll_id: int = 0
    last_poll_limit: int = 0
    last_poll_result: dict | None = None
    last_resize_id: int = 0


class TelnetManager:
    """One host-owned manager with bounded leases and nonblocking per-session sockets."""

    def __init__(self, *, allow_lan: bool = False, maximum_sessions: int = 8,
                 maximum_buffer_bytes: int = 32 * 1024 * 1024, idle_timeout_seconds: float = 300,
                 clock=time.monotonic):
        self.allow_lan = _boolean(allow_lan, "allow_lan")
        self.maximum_sessions = _integer(maximum_sessions, "maximum_sessions", 1, 128)
        self.maximum_buffer_bytes = _integer(maximum_buffer_bytes, "maximum_buffer_bytes", 1, 256 * 1024 * 1024)
        if isinstance(idle_timeout_seconds, bool) or not isinstance(idle_timeout_seconds, (float, int)) or not 0 < idle_timeout_seconds <= 3600:
            raise TelnetFailure("invalid-parameter", "idle_timeout_seconds must be from 0 to 3600, excluding zero.")
        self.idle_timeout_seconds = float(idle_timeout_seconds)
        self._clock = clock
        self._sessions: dict[str, _Session] = {}
        self._requests: dict[str, str] = {}
        self._lock = threading.RLock()
        self._closed = False
        self._wake = threading.Event()
        self._reaper: threading.Thread | None = None

    @property
    def active_session_count(self) -> int:
        with self._lock:
            return len(self._sessions)

    def start(self, *, port: int = 8000, allow_lan: bool = False, telnet_mode: bool = False,
              buffer_size: int = 1024, maximum_output_bytes: int = 65_536,
              request_id: str | None = None) -> dict:
        configuration = _Configuration(
            _integer(port, "port", 0, 65535), _boolean(allow_lan, "allowLan"),
            _boolean(telnet_mode, "telnetMode"), _integer(buffer_size, "bufferSize", 1, 16_777_216),
            _integer(maximum_output_bytes, "maximumOutputBytes", 1, 16_777_216))
        if configuration.allow_lan and not self.allow_lan:
            raise TelnetFailure("lan-disabled", "The host operator has not enabled LAN resource listeners.")
        if configuration.telnet_mode and configuration.maximum_output_bytes < 6:
            raise TelnetFailure("invalid-parameter", "Telnet negotiation needs an output buffer of at least six bytes.")
        if request_id is not None and (not isinstance(request_id, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", request_id)):
            raise TelnetFailure("invalid-parameter", "requestId must be a plain identifier of at most 128 characters.")
        with self._lock:
            self._require_open()
            self._expire_idle_locked()
            if request_id in self._requests:
                existing = self._sessions[self._requests[request_id]]
                if existing.configuration != configuration:
                    raise TelnetFailure("request-conflict", "requestId was already used with another configuration.")
                existing.last_activity = self._clock()
                return self._status(existing) | {"created": False}
            if len(self._sessions) >= self.maximum_sessions:
                raise TelnetFailure("resource-limit", "The host has reached its active Telnet session limit.")
            self._check_buffer_budget(configuration.buffer_size + configuration.maximum_output_bytes)
            listener = None
            try:
                listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                listener.setblocking(False)
                listener.bind(("0.0.0.0" if configuration.allow_lan else "127.0.0.1", configuration.port))
                listener.listen(1)
            except OSError as error:
                if listener is not None:
                    listener.close()
                raise TelnetFailure("listen-failed", str(error)) from error
            identifier = secrets.token_urlsafe(24)
            session = _Session(identifier, configuration, listener, listener.getsockname()[1], self._clock(),
                               configuration.buffer_size, request_id)
            self._sessions[identifier] = session
            if request_id is not None:
                self._requests[request_id] = identifier
            if self._reaper is None:
                self._reaper = threading.Thread(target=self._reap, name="logisim-telnet-leases", daemon=True)
                self._reaper.start()
            return self._status(session) | {"created": True}

    def poll(self, identifier: str, *, maximum_bytes: int = 4096, poll_id: int | None = None) -> dict:
        maximum_bytes = _integer(maximum_bytes, "maximumBytes", 0, 65_536)
        if poll_id is not None:
            poll_id = _integer(poll_id, "pollId", 1, 9_223_372_036_854_775_807)
        with self._lock:
            session = self._session(identifier)
            if poll_id is not None and poll_id <= session.last_poll_id:
                if poll_id != session.last_poll_id:
                    raise TelnetFailure("stale-poll", "pollId is older than the retained response.")
                if maximum_bytes != session.last_poll_limit:
                    raise TelnetFailure("poll-conflict", "pollId was already used with another byte limit.")
                # Copy the byte list so in-process clients cannot mutate the retained reply.
                return session.last_poll_result | {"bytes": list(session.last_poll_result["bytes"])}
            self._pump(session)
            data = bytes(session.received[:maximum_bytes])
            del session.received[:maximum_bytes]
            result = self._status(session) | {"bytes": list(data)}
            if poll_id is not None:
                result["pollId"] = poll_id
                session.last_poll_id = poll_id
                session.last_poll_limit = maximum_bytes
                session.last_poll_result = result | {"bytes": list(result["bytes"])}
            return result

    def resize_input(self, identifier: str, buffer_size: int, resize_id: int) -> dict:
        buffer_size = _integer(buffer_size, "bufferSize", 1, 16_777_216)
        resize_id = _integer(resize_id, "resizeId", 1, 9_223_372_036_854_775_807)
        with self._lock:
            session = self._session(identifier)
            if resize_id < session.last_resize_id:
                raise TelnetFailure("stale-resize", "resizeId is older than the latest resize.")
            if resize_id == session.last_resize_id and buffer_size != session.buffer_size:
                raise TelnetFailure("resize-conflict", "resizeId was already used with another buffer size.")
            if buffer_size != session.buffer_size:
                self._check_buffer_budget(buffer_size - session.buffer_size)
                session.buffer_size = buffer_size
                session.dropped_input += len(session.received)
                session.received.clear()
                # A lost poll reply must not bring discarded bytes back into the circuit.
                if session.last_poll_result is not None:
                    session.last_poll_result = session.last_poll_result | {"bytes": []}
            session.last_resize_id = resize_id
            return self._status(session) | {"resizeId": resize_id, "bufferSize": buffer_size}

    def _check_buffer_budget(self, extra_bytes: int):
        reserved = sum(session.buffer_size + session.configuration.maximum_output_bytes
                       for session in self._sessions.values())
        if reserved + extra_bytes > self.maximum_buffer_bytes:
            raise TelnetFailure("resource-limit", "The configured Telnet buffers exceed the host limit.")

    def send(self, identifier: str, packet_id: int, data: list[int]) -> dict:
        packet_id = _integer(packet_id, "packetId", 1, 9_223_372_036_854_775_807)
        payload = _bytes(data)
        digest = hashlib.sha256(payload).hexdigest()
        with self._lock:
            session = self._session(identifier)
            if packet_id in session.accepted:
                if session.accepted[packet_id] != digest:
                    raise TelnetFailure("packet-conflict", "packetId was already accepted with different bytes.")
                return self._status(session) | {"acceptedPacketId": packet_id, "duplicate": True}
            if packet_id <= session.highest_packet_id:
                raise TelnetFailure("stale-packet", "packetId is older than the retained retry window.",
                                    {"highestPacketId": session.highest_packet_id})
            self._pump(session)
            if session.peer is None:
                raise TelnetFailure("not-connected", "The Telnet session has no connected peer.")
            if len(session.pending_output) + len(payload) > session.configuration.maximum_output_bytes:
                raise TelnetFailure("buffer-full", "The Telnet output queue is full; packetId was not accepted.")
            session.pending_output.extend(payload)
            session.accepted[packet_id] = digest
            session.highest_packet_id = packet_id
            while len(session.accepted) > 1024:
                session.accepted.popitem(last=False)
            self._flush(session)
            return self._status(session) | {"acceptedPacketId": packet_id, "duplicate": False}

    def stop(self, identifier: str) -> dict:
        self._identifier(identifier)
        with self._lock:
            session = self._sessions.pop(identifier, None)
            if session is not None:
                self._close_session(session)
            return {"sessionId": identifier, "link": "stopped", "alreadyStopped": session is None}

    def expire_idle(self) -> int:
        with self._lock:
            return self._expire_idle_locked()

    def close(self):
        with self._lock:
            if self._closed:
                return
            self._closed = True
            self._wake.set()
            for session in list(self._sessions.values()):
                self._close_session(session)
            self._sessions.clear()
            reaper = self._reaper
        if reaper is not None and threading.current_thread() is not reaper:
            reaper.join(timeout=2)

    def _reap(self):
        while not self._wake.wait(min(1.0, self.idle_timeout_seconds)):
            self.expire_idle()

    def _expire_idle_locked(self) -> int:
        expired = [identifier for identifier, session in self._sessions.items()
                   if self._clock() - session.last_activity >= self.idle_timeout_seconds]
        for identifier in expired:
            self._close_session(self._sessions.pop(identifier))
        return len(expired)

    def _close_session(self, session: _Session):
        self._disconnect(session)
        session.listener.close()
        session.received.clear()
        if session.request_id is not None:
            self._requests.pop(session.request_id, None)

    @staticmethod
    def _disconnect(session: _Session):
        if session.peer is not None:
            session.peer.close()
            session.peer = None
        session.dropped_output += len(session.pending_output)
        session.pending_output.clear()
        session.decoder = _Decoder()

    def _session(self, identifier: str) -> _Session:
        self._identifier(identifier)
        self._require_open()
        self._expire_idle_locked()
        try:
            session = self._sessions[identifier]
        except KeyError:
            raise TelnetFailure("unknown-session", "The Telnet session does not exist or its lease expired.") from None
        session.last_activity = self._clock()
        return session

    @staticmethod
    def _identifier(identifier: str):
        if not isinstance(identifier, str) or not re.fullmatch(r"[A-Za-z0-9_-]{20,64}", identifier):
            raise TelnetFailure("invalid-parameter", "sessionId is invalid.")

    def _require_open(self):
        if self._closed:
            raise TelnetFailure("closed", "The Telnet manager is closed.")

    def _status(self, session: _Session) -> dict:
        return {"sessionId": session.identifier, "link": "connected" if session.peer is not None else "listening",
                "localPort": session.local_port, "bindAddress": "0.0.0.0" if session.configuration.allow_lan else "127.0.0.1",
                "telnetMode": session.configuration.telnet_mode, "peerGeneration": session.peer_generation,
                "droppedInputBytes": session.dropped_input, "droppedOutputBytes": session.dropped_output,
                "leaseSeconds": self.idle_timeout_seconds}

    def _pump(self, session: _Session):
        try:
            peer, _ = session.listener.accept()
        except BlockingIOError:
            peer = None
        except OSError as error:
            raise TelnetFailure("network-error", str(error)) from error
        if peer is not None:
            self._disconnect(session)
            session.peer = peer
            peer.setblocking(False)
            session.peer_generation += 1
            if session.configuration.telnet_mode:
                session.pending_output.extend(b"\xff\xfb\x03\xff\xfb\x01")
        if session.peer is None:
            return
        remaining_budget = 65_536
        while remaining_budget > 0:
            try:
                raw = session.peer.recv(min(8192, remaining_budget))
            except BlockingIOError:
                break
            except OSError:
                self._disconnect(session)
                break
            if not raw:
                self._disconnect(session)
                break
            remaining_budget -= len(raw)
            data = session.decoder.decode(raw) if session.configuration.telnet_mode else raw
            available = max(0, session.buffer_size - len(session.received))
            session.received.extend(data[:available])
            session.dropped_input += max(0, len(data) - available)
        self._flush(session)

    def _flush(self, session: _Session):
        if session.peer is None or not session.pending_output:
            return
        try:
            written = session.peer.send(session.pending_output)
            del session.pending_output[:written]
        except BlockingIOError:
            pass
        except OSError:
            self._disconnect(session)


def register(registry, *, allow_lan: bool = False, manager: TelnetManager | None = None) -> TelnetManager:
    """Register job handlers after the server has applied its local host grants."""
    from .api import JobFailure, Operation

    manager = manager or TelnetManager(allow_lan=allow_lan)

    def handler(action):
        def invoke(context, parameters):
            context.check_cancelled()
            if not isinstance(parameters, dict):
                raise JobFailure("invalid-parameter", "Telnet parameters must be an object.")
            try:
                return action(context, parameters)
            except TelnetFailure as error:
                raise JobFailure(error.code, error.message, error.details) from error
        return invoke

    def start(context, parameters):
        lan = _boolean(parameters.get("allowLan", False), "allowLan")
        if lan and not allow_lan:
            raise TelnetFailure("lan-disabled", "The host operator has not enabled LAN resource listeners.")
        if not context.user_confirmed:
            raise TelnetFailure("confirmation-required", "Starting a Telnet listener requires an explicitly confirmed job.")
        result = manager.start(port=parameters.get("port", 8000), allow_lan=lan,
                               telnet_mode=parameters.get("telnetMode", False),
                               buffer_size=parameters.get("bufferSize", 1024),
                               maximum_output_bytes=parameters.get("maximumOutputBytes", 65_536),
                               request_id=parameters.get("requestId"))
        try:
            context.check_cancelled()
            context.emit("stdout", f"Telnet listening on {result['bindAddress']}:{result['localPort']}\n")
        except Exception:
            if result["created"]:
                manager.stop(result["sessionId"])
            raise
        return result

    registry.register(Operation("telnet.start", handler(start), description="Start an explicit host Telnet listener.",
                                requires_confirmation=True))
    registry.register(Operation("telnet.poll", handler(lambda context, parameters:
        manager.poll(parameters.get("sessionId"), maximum_bytes=parameters.get("maximumBytes", 4096),
                     poll_id=parameters.get("pollId"))),
        description="Read queued bytes and connection status from a Telnet listener."))
    registry.register(Operation("telnet.resize", handler(lambda context, parameters:
        manager.resize_input(parameters.get("sessionId"), parameters.get("bufferSize"), parameters.get("resizeId"))),
        description="Resize and clear an existing Telnet listener's input buffer."))
    registry.register(Operation("telnet.send", handler(lambda context, parameters:
        manager.send(parameters.get("sessionId"), parameters.get("packetId"), parameters.get("bytes"))),
        description="Queue one idempotent Telnet output packet."))
    registry.register(Operation("telnet.stop", handler(lambda context, parameters:
        manager.stop(parameters.get("sessionId"))), description="Close a Telnet listener and its peer."))
    registry.on_shutdown(manager.close)
    return manager
