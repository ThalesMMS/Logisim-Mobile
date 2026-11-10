import base64
import hashlib
from pathlib import Path
import stat
import struct
import tempfile
import time
import unittest
from unittest.mock import patch
import zlib

from companion import host_isolation, tk_linux, tk_worker
from companion.api import JobFailure
from companion.server import CompanionService, Configuration, default_registry
from companion.tcl_runtime import _window_preview


def request(source, operation="tcl-tk-run"):
    data = source.encode()
    return {"operation": operation, "inputs": [{"name": "script.tcl",
            "base64": base64.b64encode(data).decode(), "sha256": hashlib.sha256(data).hexdigest()}]}


class TkLinuxContractTest(unittest.TestCase):
    def test_private_framebuffer_becomes_a_bounded_rgb_png(self):
        width, height = tk_worker.WIDTH, tk_worker.HEIGHT
        fields = [100, 7, 2, 24, width, height, 0, 0, 32, 0, 32, 32, width * 4, 4,
                  0xff0000, 0xff00, 0xff, 8, 256, 1, width, height, 0, 0, 0]
        header = struct.pack("!25I", *fields)
        frame = header + bytes(12) + bytes((3, 2, 1, 0)) * (width * height)
        png = tk_worker.framebuffer_png(frame)
        self.assertEqual(struct.unpack_from("!2I", png, 16), (640, 480))
        idat_size, = struct.unpack_from("!I", png, 33)
        self.assertEqual(zlib.decompress(png[41:41 + idat_size]), (b"\0" + bytes((1, 2, 3)) * width) * height)
        preview = _window_preview(["nonce", "preview", base64.b64encode(png).decode()])
        self.assertEqual(preview["sha256"], hashlib.sha256(png).hexdigest())
        self.assertEqual(preview["size"], len(png))
        self.assertLess(preview["size"], tk_worker.PREVIEW_LIMIT)
        with self.assertRaises(ValueError):
            tk_worker.framebuffer_png(frame[:-1])
        with self.assertRaises(JobFailure):
            _window_preview(["nonce", "preview", base64.b64encode(frame[:100]).decode()])

    def test_unavailable_profile_refuses_before_plan(self):
        with patch.object(tk_linux, "available", return_value=False):
            service = CompanionService(Configuration("synthetic-token-for-private-tk-fixtures", {"tcl-tk-run"}),
                                       default_registry())
            self.addCleanup(service.close)
            operation = next(op for op in service.capabilities()["operations"] if op["name"] == "tcl-tk-run")
            self.assertEqual(operation["osSandbox"], host_isolation.TK_PROFILE)
            self.assertTrue(operation["requiresConfirmation"])
            self.assertFalse(operation["available"])
            with self.assertRaises(JobFailure) as caught:
                service.plan(request("button .proof"))
            self.assertEqual(caught.exception.code, "sandbox-unavailable")

    def test_launcher_uses_private_mounts_and_rejects_caller_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            launcher = Path(directory) / "bwrap"
            launcher.touch()
            with patch.object(tk_linux, "available", return_value=True), patch.object(tk_linux, "BWRAP", launcher), \
                 patch.object(tk_linux, "PYTHON", launcher), patch.object(tk_linux, "XVFB", launcher), \
                 patch.object(tk_linux, "XKBCOMP", launcher), patch.object(tk_linux, "RUNTIME_DIRS", ()):
                command = tk_linux.command(str(launcher.resolve()), [])
                for option in ("--unshare-user", "--unshare-pid", "--unshare-net", "--unshare-ipc", "--unshare-uts",
                               "--disable-userns", "--die-with-parent", "--clearenv"):
                    self.assertIn(option, command)
                self.assertEqual(command[command.index("--tmpfs") + 1], "/tmp")
                self.assertNotIn("DISPLAY", command)
                self.assertNotIn("--bind", command)
                self.assertNotIn("--share-net", command)
                self.assertIn(("--ro-bind", str(tk_linux.XKB_SHELL.resolve()), str(tk_linux.XKB_SHELL)),
                              zip(command, command[1:], command[2:]))
                mailbox = Path(directory) / "input"
                mailbox.touch()
                live = tk_linux.command(str(launcher.resolve()), [], mailbox)
                mounted_at = live.index("/logisim-tk-input")
                self.assertEqual(live[mounted_at - 2:mounted_at], ["--ro-bind", str(mailbox)])
                with self.assertRaises(ValueError):
                    tk_linux.command(str(launcher.resolve()), ["--share-net"])

    def test_authority_is_private_and_uses_an_independent_cookie(self):
        with tempfile.TemporaryDirectory() as directory:
            first, second = (Path(directory) / name for name in ("first", "second"))
            tk_worker.write_authority(first)
            tk_worker.write_authority(second)
            self.assertEqual(stat.S_IMODE(first.stat().st_mode), 0o600)
            self.assertEqual(first.read_bytes()[:-16], second.read_bytes()[:-16])
            self.assertNotEqual(first.read_bytes()[-16:], second.read_bytes()[-16:])


@unittest.skipUnless(tk_linux.available(), "a non-root merged-/usr Linux host with private Tk prerequisites is required")
class TkLinuxExecutionTest(unittest.TestCase):
    def test_live_tap_and_key_change_the_private_widget(self):
        service = CompanionService(Configuration("synthetic-token-for-private-tk-fixtures", {"tcl-tk-live"},
            tool_paths={"bwrap": str(tk_linux.BWRAP)}), default_registry())
        self.addCleanup(service.close)
        payload = request("""
button .proof -text ready -background #12ab34 -activebackground #12ab34 -command {
    .proof configure -background #ab1234 -activebackground #ab1234
    focus -force .entry
}
entry .entry
bind .entry <KeyPress-a> {.proof configure -background #2345ab -activebackground #2345ab}
pack .proof .entry
set status ready
""", "tcl-tk-live")
        job = service.submit({**payload, "planToken": service.plan(payload)["planToken"]})["jobId"]

        def frame(sequence):
            deadline = time.monotonic() + 6
            while time.monotonic() < deadline:
                status = service.poll(job)
                self.assertIn(status["state"], ("queued", "running"), status)
                result = status["result"]
                if result and result["frame"]["sequence"] == sequence:
                    return result["frame"]["windowPreview"]
                time.sleep(0.025)
            self.fail(f"No private Tk frame {sequence} arrived.")

        def points(preview, rgb):
            png = base64.b64decode(preview["base64"], validate=True)
            idat_size, = struct.unpack_from("!I", png, 33)
            pixels = zlib.decompress(png[41:41 + idat_size])
            stride = 1 + 640 * 3
            return [(x, y) for y in range(480) for x in range(640)
                    if pixels[y * stride + 1 + x * 3:y * stride + 4 + x * 3] == rgb]

        target = points(frame(0), b"\x12\xab\x34")
        self.assertTrue(target)
        x, y = target[len(target) // 2]
        self.assertEqual(service.send_tk_input(job, {"sequence": 1, "type": "tap", "x": x, "y": y}), {"sequence": 1})
        self.assertTrue(points(frame(1), b"\xab\x12\x34"))
        self.assertEqual(service.send_tk_input(job, {"sequence": 2, "type": "key", "key": "a"}), {"sequence": 2})
        self.assertTrue(points(frame(2), b"\x23\x45\xab"))
        service.cancel(job)
        deadline = time.monotonic() + 2
        while service.poll(job)["state"] == "running" and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertEqual(service.poll(job)["state"], "cancelled")

    def test_private_widget_timer_and_io_controls(self):
        service = CompanionService(Configuration("synthetic-token-for-private-tk-fixtures", {"tcl-tk-run"},
            tool_paths={"bwrap": str(tk_linux.BWRAP)}), default_registry())
        self.addCleanup(service.close)
        payload = request("""
set b [button .proof -text ready -background #12ab34 -activebackground #12ab34 -command {set ::clicked 1}]
pack $b
update
$b invoke
after 10 {set ::elapsed 42}
vwait ::elapsed
list [$b cget -text] $::clicked [winfo ismapped $b] $::elapsed [catch {open /etc/passwd}] [catch {socket localhost 80}] [catch {exec /usr/bin/true}]
""")
        with self.assertRaises(JobFailure) as caught:
            service.submit(payload)
        self.assertEqual(caught.exception.code, "confirmation-required")
        plan = service.plan(payload)
        self.assertEqual(plan["osSandbox"], host_isolation.TK_PROFILE)
        with self.assertRaises(JobFailure) as caught:
            service.submit({**request("button .changed"), "planToken": plan["planToken"]})
        self.assertEqual(caught.exception.code, "confirmation-required")
        job = service.submit({**payload, "planToken": service.plan(payload)["planToken"]})
        deadline = time.monotonic() + 8
        while job["state"] in ("queued", "running") and time.monotonic() < deadline:
            time.sleep(0.025)
            job = service.poll(job["jobId"])
        self.assertEqual(job["state"], "succeeded", job)
        self.assertEqual(job["result"]["mode"], "tk-console-v1")
        self.assertEqual(job["result"]["result"], "ready 1 1 42 1 1 1")
        preview = job["result"]["windowPreview"]
        self.assertEqual(preview["name"], "tk-window.png")
        png = base64.b64decode(preview["base64"], validate=True)
        self.assertEqual(struct.unpack_from("!2I", png, 16), (640, 480))
        self.assertEqual(preview["sha256"], hashlib.sha256(png).hexdigest())
        idat_size, = struct.unpack_from("!I", png, 33)
        pixels = zlib.decompress(png[41:41 + idat_size])
        stride = 1 + 640 * 3
        self.assertTrue(any(pixels[offset:offset + 3] == b"\x12\xab\x34"
                            for y in range(480) for offset in range(y * stride + 1, (y + 1) * stride, 3)))
        self.assertFalse(any(preview["base64"] in entry["text"] for entry in job["logs"]))
