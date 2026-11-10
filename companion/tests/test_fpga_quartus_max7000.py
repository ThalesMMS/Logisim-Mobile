import base64
import hashlib
from pathlib import Path
import struct
import sys
import tempfile
import threading
import unittest

from companion.api import JobContext, JobFailure
from companion.fpga import FpgaProgramTarget
from companion.fpga_quartus import build
from companion.server import CompanionService, Configuration, default_registry


def fixture_pof(part):
    packets = [(2, part.encode("ascii") + b"\0"), (0x11, b"fixture configuration"), (8, b"\0\0")]
    return b"POF\0\0\0\1\0" + struct.pack("<I", len(packets)) + b"".join(
        struct.pack("<HI", tag, len(payload)) + payload for tag, payload in packets
    )


class QuartusMax7000Test(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)

    def tool(self, name, code):
        path = self.directory / name
        path.write_text(f"#!{sys.executable}\n" + code)
        path.chmod(0o755)
        return str(path)

    def test_build_returns_only_the_pof_for_the_requested_part(self):
        for target, part in (
            ("max-7000-epm7128s", "EPM7128SLC84-7"),
            ("max-7000-epm7256s", "EPM7256SQC208-7"),
        ):
            with self.subTest(target=target):
                directory = self.directory / target
                directory.mkdir()
                inputs = {
                    "source-0000.v": "module logisim_fpga_top(output fpga_p0_b0); assign fpga_p0_b0=1'b0; endmodule",
                    "pins.qsf": 'set_location_assignment PIN_8 -to fpga_p0_b0\n'
                                'set_global_assignment -name RESERVE_ALL_UNUSED_PINS_NO_OUTPUT_GND "AS INPUT TRI-STATED"\n',
                    "timing.sdc": "derive_clock_uncertainty\n",
                }
                for name, content in inputs.items():
                    (directory / name).write_text(content)
                expected = fixture_pof(part)
                sh = self.tool("quartus_sh", f"""
from pathlib import Path
import sys
assert sys.argv[1:] == ['--flow', 'compile', 'build']
qsf = Path('build.qsf').read_text()
assert 'FAMILY "MAX7000S"' in qsf
assert 'DEVICE {part}' in qsf
assert 'RESERVE_ALL_UNUSED_PINS_NO_OUTPUT_GND "AS INPUT TRI-STATED"' in qsf
output = Path('quartus-output')
output.mkdir()
(output / 'build.pof').write_bytes({expected!r})
(output / 'build.sof').write_bytes(b'unrelated sof')
""")
                context = JobContext(directory, {name: directory / name for name in inputs}, {"quartus_sh": sh},
                                     threading.Event(), timeout_seconds=3, user_confirmed=True, emit=lambda *_: None)
                result = build(context, {"language": "verilog", "sources": ["source-0000.v"], "target": target})
                [artifact] = result["artifacts"]
                self.assertEqual("design.pof", artifact["name"])
                self.assertEqual(expected, base64.b64decode(artifact["base64"]))
                self.assertFalse(result["programmed"])

    def test_program_checks_part_and_chain_before_writing_reviewed_pof(self):
        target = FpgaProgramTarget("SLO_MAX7000S", transport="quartus-cpld", cable="USB-Blaster [1-2]", chain_position=1)
        expected = fixture_pof("EPM7256SQC208-7")
        trace = self.directory / "programmer.log"
        chain = self.directory / "chain.txt"
        pgm = self.tool("quartus_pgm", f"""
from pathlib import Path
import sys
assert sys.argv[1:3] == ['-c', 'USB-Blaster [1-2]']
with Path({str(trace)!r}).open('a') as log:
    log.write(' '.join(sys.argv[3:]) + '\\n')
if sys.argv[3:] == ['--auto']:
    print(Path({str(chain)!r}).read_text(), end='')
else:
    assert sys.argv[3:] == ['-m', 'jtag', '-o', 'P;inputs/design.pof@2']
    assert Path('inputs/design.pof').read_bytes() == {expected!r}
""")
        service = CompanionService(Configuration("fixture-token-" * 4, {"fpga-program"},
                                                 tool_paths={"quartus_pgm": pgm}),
                                   default_registry(program_targets={"bench": target}))
        self.addCleanup(service.close)
        capability = next(item for item in service.capabilities()["operations"] if item["name"] == "fpga-program")
        profile = capability["programTargetProfiles"]["bench"]
        self.assertEqual(("EPM7256SQC208-7", "USB-Blaster [1-2]", 1),
                         (profile["part"], profile["cable"], profile["chainPosition"]))

        def request(data):
            return {"operation": "fpga-program", "parameters": {
                "target": "bench", "profile": profile["fingerprint"], "bitstream": "design.pof",
            }, "inputs": [{"name": "design.pof", "base64": base64.b64encode(data).decode(),
                           "sha256": hashlib.sha256(data).hexdigest()}]}

        def execute(req, token=None):
            token = token or service.plan(req)["planToken"]
            job_id = service.submit({**req, "planToken": token})["jobId"]
            service._jobs[job_id]["future"].result(timeout=5)
            return service.snapshot(job_id)

        for invalid in (fixture_pof("EPM7128SLC84-7"), expected[:-1], b"renamed sof"):
            with self.subTest(invalid=invalid):
                self.assertEqual("invalid-bitstream", execute(request(invalid))["error"]["code"])
                self.assertFalse(trace.exists())

        req = request(expected)
        plan = service.plan(req)
        self.assertEqual(hashlib.sha256(expected).hexdigest(), plan["inputs"][0]["sha256"])
        with self.assertRaises(JobFailure) as error:
            service.submit({**request(fixture_pof("EPM7128SLC84-7")), "planToken": plan["planToken"]})
        self.assertEqual("confirmation-required", error.exception.code)
        chain.write_text("1) USB-Blaster [1-2]\n  072560DD EPM7256S\n  071280DD EPM7128S\n")
        self.assertEqual("fpga-target-changed", execute(req)["error"]["code"])
        self.assertEqual(["--auto"], trace.read_text().splitlines())

        chain.write_text("1) USB-Blaster [1-2]\n  071280DD EPM7128S\n  172560DD EPM7256S\n")
        job = execute(req, plan["planToken"])
        self.assertEqual("succeeded", job["state"], job)
        self.assertEqual("cpld-nonvolatile", job["result"]["programmingMode"])
        self.assertFalse(job["result"]["hardwareVerified"])
        self.assertEqual(["--auto", "--auto", "-m jtag -o P;inputs/design.pof@2"], trace.read_text().splitlines())

    def test_epm2525_profiles_bind_the_catalog_part(self):
        for board in ("EPM2525_CONSOLE", "EPM2525_FULL"):
            target = FpgaProgramTarget(board, transport="quartus-cpld", cable="USB-Blaster [1-2]", chain_position=0)
            self.assertEqual("EPM7128SLC84-7", target.profile()["part"])
