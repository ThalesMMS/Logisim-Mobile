"""Compare the companion replay with the unmodified desktop Tcl socket wrapper."""
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import unittest

from companion.api import JobContext
from companion.tcl_runtime import run_components


def desktop_wrapper():
    configured = os.environ.get("LOGISIM_DESKTOP_SOURCE")
    project = Path(__file__).resolve().parents[2]
    roots = [Path(configured)] if configured else [
        project.parent / "logisim-evolution",
        project.parent.parent / "logisim-evolution",
    ]
    for root in roots:
        wrapper = root / "src/main/resources/resources/logisim/tcl/tcl_wrapper.tcl"
        if wrapper.is_file():
            return wrapper
    return None


DESKTOP_WRAPPER = desktop_wrapper()
TCLSH = shutil.which("tclsh")


@unittest.skipUnless(TCLSH and DESKTOP_WRAPPER,
                     "Set LOGISIM_DESKTOP_SOURCE to a desktop checkout and install tclsh")
class TclDesktopProtocolTest(unittest.TestCase):
    PORTS = [
        {"name": "sysclk_i", "direction": "input", "width": 1},
        {"name": "val_i", "direction": "input", "width": 4},
        {"name": "result_o", "direction": "output", "width": 4},
    ]
    SOURCE = """
set count 0
proc enableLogisim {enabled} {set ::enabled $enabled}
proc logisimExamine {} {force result_o 0000}
proc logisimForce {} {
    incr ::count
    force result_o [examine val_i]
    if {$::count == 2} {run}
}
"""

    def run_desktop(self, root, source, frames):
        script = root / "desktop-fixture.tcl"
        script.write_text(source, encoding="utf-8")
        bootstrap = root / "desktop-bootstrap.tcl"
        bootstrap.write_text("""
set argv [list $::env(LOGISIM_TEST_PORT) $::env(LOGISIM_TEST_SCRIPT)]
source $::env(LOGISIM_TEST_WRAPPER)
vwait ::desktopFixtureFinished
""", encoding="utf-8")
        outputs = {port["name"]: "?" * port["width"]
                   for port in self.PORTS if port["direction"] != "input"}
        steps = []
        previous_clock = "U"
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            server.settimeout(5)
            environment = dict(os.environ, LOGISIM_TEST_PORT=str(server.getsockname()[1]),
                               LOGISIM_TEST_SCRIPT=str(script),
                               LOGISIM_TEST_WRAPPER=str(DESKTOP_WRAPPER))
            process = subprocess.Popen([TCLSH, str(bootstrap)], cwd=root, env=environment,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       text=True, encoding="utf-8")
            try:
                connection, _ = server.accept()
                with connection:
                    connection.settimeout(5)
                    with connection.makefile("rw", encoding="utf-8", newline="\n") as channel:
                        for frame in frames:
                            for index, port in enumerate(self.PORTS):
                                direction = {"input": 1, "output": 2, "inout": 3}[port["direction"]]
                                value = frame.get(port["name"], outputs.get(port["name"], "X"))
                                channel.write(f"{direction}:{port['name']}:{value}:{index}\n")
                            forced = frame["sysclk_i"] != previous_clock
                            previous_clock = frame["sysclk_i"]
                            channel.write("sync_force\n" if forced else "sync_examine\n")
                            channel.flush()
                            requested_tick = False
                            while True:
                                message = channel.readline()
                                self.assertNotEqual(message, "", "The desktop closed before sync")
                                message = message.rstrip("\r\n")
                                if message == "sync":
                                    break
                                if message == "run":
                                    requested_tick = True
                                    continue
                                name, raw, index = message.split(":")
                                port = self.PORTS[int(index)]
                                self.assertEqual(name, port["name"])
                                self.assertIn(port["direction"], ("output", "inout"))
                                known = "".join(bit if bit in "01" else "?" for bit in raw)
                                outputs[name] = ("?" * port["width"] + known)[-port["width"]:]
                            steps.append({"forced": forced, "requestedTick": requested_tick,
                                          "outputs": dict(outputs)})
                        channel.write("end\n")
                        channel.flush()
                stdout, stderr = process.communicate(timeout=5)
                self.assertEqual(process.returncode, 0, stderr)
                self.assertIn("TCL_WRAPPER_RUNNING", stdout)
                self.assertIn("TCL_WRAPPER_ENDED", stdout)
                self.assertEqual(stderr, "")
            finally:
                if process.poll() is None:
                    process.kill()
                    process.communicate()
        return steps

    def run_companion(self, root, source, frames):
        script = root / "script.tcl"
        script.write_text(source, encoding="utf-8")
        context = JobContext(root, {"script.tcl": script}, {"tclsh": TCLSH},
                             threading.Event(), 10)
        result = run_components(context, {"ports": self.PORTS, "frames": frames})
        self.assertEqual(result["delay"], 1)
        self.assertEqual(result["stderr"], "")
        return result["steps"]

    def test_both_clock_edges_examine_hold_tick_request_and_unknown_bits(self):
        frames = [
            {"sysclk_i": "0", "val_i": "0001"},
            {"sysclk_i": "0", "val_i": "1111"},
            {"sysclk_i": "1", "val_i": "E0U1"},
            {"sysclk_i": "0", "val_i": "1100"},
            {"sysclk_i": "U", "val_i": "0101"},
            {"sysclk_i": "U", "val_i": "1111"},
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            desktop = self.run_desktop(root, self.SOURCE, frames)
            companion = self.run_companion(root, self.SOURCE, frames)
        self.assertEqual([step["forced"] for step in desktop],
                         [True, False, True, True, True, False])
        self.assertEqual([step["requestedTick"] for step in desktop],
                         [False, False, True, False, False, False])
        self.assertEqual([step["outputs"]["result_o"] for step in desktop],
                         ["0001", "0001", "?0?1", "1100", "0101", "0101"])
        self.assertEqual(companion, desktop)

    def test_replays_past_256_inputs_and_reconnects_with_the_same_history(self):
        source = """
set count 0
proc enableLogisim {enabled} {}
proc logisimExamine {} {}
proc logisimForce {} {
    incr ::count
    force result_o [format %04b [expr {$::count & 15}]]
}
"""
        frames = [{"sysclk_i": str(index % 2), "val_i": "0000"} for index in range(258)]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            desktop = self.run_desktop(root, source, frames)
            before_reconnect = self.run_companion(root, source, frames[:256])
            after_reconnect = self.run_companion(root, source, frames)
            reset = self.run_companion(root, source, frames[:1])
        self.assertEqual(before_reconnect, desktop[:256])
        self.assertEqual(after_reconnect, desktop)
        self.assertEqual([step["outputs"]["result_o"] for step in after_reconnect[-2:]], ["0001", "0010"])
        self.assertEqual(reset, desktop[:1])

    def test_initial_unknown_clock_holds_and_output_width_normalization_matches(self):
        source = """
set count 0
proc enableLogisim {enabled} {}
proc logisimExamine {} {force result_o 1111}
proc logisimForce {} {
    incr ::count
    if {$::count == 1} {force result_o 1} else {force result_o E110101}
}
"""
        frames = [
            {"sysclk_i": "U", "val_i": "0000"},
            {"sysclk_i": "0", "val_i": "0000"},
            {"sysclk_i": "1", "val_i": "0000"},
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            desktop = self.run_desktop(root, source, frames)
            companion = self.run_companion(root, source, frames)
        self.assertEqual([step["outputs"]["result_o"] for step in desktop],
                         ["????", "???1", "0101"])
        self.assertEqual(companion, desktop)


if __name__ == "__main__":
    unittest.main()
