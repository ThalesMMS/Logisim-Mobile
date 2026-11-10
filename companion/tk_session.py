"""Bounded mailbox for one approved Tk job."""
import json
import threading

from companion.api import JobFailure


MAX_EVENTS = 8
KEYS = frozenset("abcdefghijklmnopqrstuvwxyz0123456789") | {
    "space", "Return", "BackSpace", "Escape", "Tab", "Left", "Right", "Up", "Down",
}


class TkSession:
    def __init__(self, path):
        self.path = path
        path.touch(mode=0o600, exist_ok=False)
        self._lock = threading.Lock()
        self._stream = None
        self._nonce = None
        self._decoder = None
        self._offset = 0
        self._partial = b""
        self._latest = None
        self._last_input = None
        self.source_sha256 = None

    def attach_output(self, path):
        with self._lock:
            self._stream = path

    def configure(self, nonce, decoder, source_sha256):
        with self._lock:
            self._nonce = nonce.encode("ascii")
            self._decoder = decoder
            self.source_sha256 = source_sha256

    def offer(self, event):
        if not isinstance(event, dict) or type(event.get("sequence")) is not int or not 1 <= event["sequence"] <= MAX_EVENTS:
            raise JobFailure("invalid-tk-input", "Supply an input sequence from 1 to 8.")
        if event.get("type") == "tap" and set(event) == {"sequence", "type", "x", "y"}:
            if not (type(event["x"]) is int and 0 <= event["x"] < 640 and type(event["y"]) is int and 0 <= event["y"] < 480):
                raise JobFailure("invalid-tk-input", "Tap outside the private display bounds.")
        elif event.get("type") == "key" and set(event) == {"sequence", "type", "key"}:
            if not isinstance(event["key"], str) or event["key"] not in KEYS:
                raise JobFailure("invalid-tk-input", "The private Tk keyboard does not support this key.")
        else:
            raise JobFailure("invalid-tk-input", "Supply one typed tap or key event.")
        with self._lock:
            if event == self._last_input:
                return event["sequence"]
            if (self._latest is None or self._latest["sequence"] + 1 != event["sequence"] or
                    (self._last_input and self._last_input["sequence"] == event["sequence"])):
                raise JobFailure("tk-input-order", "Wait for the current Tk frame before sending another input.")
            record = json.dumps(event, separators=(",", ":")).encode("ascii") + b"\n"
            with self.path.open("ab") as output:
                output.write(record)
            self._last_input = dict(event)
            return event["sequence"]

    def latest(self, max_bytes):
        with self._lock:
            if self._stream is None or self._nonce is None or not self._stream.is_file():
                return self._latest
            with self._stream.open("rb") as stream:
                stream.seek(self._offset)
                data = stream.read(max(0, max_bytes + 1 - self._offset))
            self._offset += len(data)
            if self._offset > max_bytes:
                raise JobFailure("output-limit", "The Tk frame stream exceeded its limit.")
            lines = (self._partial + data).split(b"\n")
            self._partial = lines.pop()
            for line in lines:
                fields = line.split(b"|", 3)
                if len(fields) != 4 or fields[:2] != [self._nonce, b"frame"]:
                    continue
                expected = 0 if self._latest is None else self._latest["sequence"] + 1
                if fields[2] != str(expected).encode("ascii") or (expected > 0 and (
                        self._last_input is None or self._last_input["sequence"] != expected)):
                    raise JobFailure("tcl-protocol", "The Tk worker returned a frame out of order.")
                try:
                    encoded = fields[3].decode("ascii")
                except UnicodeDecodeError as error:
                    raise JobFailure("tcl-protocol", "The Tk worker returned invalid frame data.") from error
                self._latest = {"sequence": expected, "windowPreview": self._decoder([None, "preview", encoded])}
            return self._latest
