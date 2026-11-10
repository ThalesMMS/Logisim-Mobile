import base64
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest

from companion.api import JobFailure
from companion.fpga import FpgaProgramTarget
from companion.fpga_host_config import load_program_targets
from companion.server import CompanionService, Configuration, default_registry


def bitstream_input(data):
    return {"name": "design.bit", "sha256": hashlib.sha256(data).hexdigest(),
            "base64": base64.b64encode(data).decode("ascii")}


class Basys2PromTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.tool = Path(folder.name) / "impact"
        self.target = FpgaProgramTarget("BASYS2", transport="ise", cable="usb21", chain_position=0,
                                        allow_flash=True, flash_chain_position=1)
        self.alternate = FpgaProgramTarget(**{**vars(self.target), "cable": "usb22"})
        self.request = {"operation": "fpga-program", "inputs": [bitstream_input(b"approved BASYS2 bitstream")],
                        "parameters": {"target": "bench", "profile": self.target.fingerprint,
                                       "flash": True, "bitstream": "design.bit"}}

    def service(self, behavior="success"):
        self.tool.write_text(f"#!{sys.executable}\nbehavior = {behavior!r}\n" + """
import pathlib
import re
import sys

assert sys.argv[1] == "-batch"
script = pathlib.Path(sys.argv[2])
commands = script.read_text().splitlines()
assert commands[-1] == "quit"
if script.stem == "generate-prom-mcs":
    assert commands[:5] == [
        "setMode -pff", "setSubMode -pffserial", "addPromDevice -p 1 -size 0 -name xcf02s",
        'addDesign -version 0 -name "0"', "addDeviceChain -index 0",
    ]
    source = re.fullmatch(r'addDevice -p 1 -file "(.+)"', commands[5])
    assert pathlib.Path(source[1]).read_bytes() == b"approved BASYS2 bitstream"
    output = re.fullmatch(r'generate -format mcs -fillvalue FF -output "(.+)"', commands[6])
    assert output
    if behavior != "missing-mcs":
        pathlib.Path(output[1]).write_text(":0100000000FF\\n:00000001FF\\n")
elif script.stem == "identify-prom-chain":
    assert commands == ["setMode -bs", "setCable -port usb21", "identify", "quit"]
    print("INFO:iMPACT:501 - '1': Added Device xc3s100e successfully.")
    print("INFO:iMPACT:501 - '2': Added Device " +
          ("xcf04s" if behavior == "wrong-chain" else "xcf02s") + " successfully.")
elif script.stem == "program-prom":
    assert commands[:3] == ["setMode -bs", "setCable -port usb21", "identify"]
    source = re.fullmatch(r'assignFile -p 2 -file "(.+)"', commands[3])
    assert pathlib.Path(source[1]).read_text() == ":0100000000FF\\n:00000001FF\\n"
    assert commands[4:] == ["program -p 2 -e -v", "quit"]
    print("Program Failed." if behavior == "program-error" else "'2': Programmed successfully.")
else:
    raise RuntimeError(f"Unexpected iMPACT script {script.name}")
""")
        self.tool.chmod(0o700)
        configuration = Configuration("fixture-token-" * 4, {"fpga-program"}, timeout_seconds=5,
                                      tool_paths={"impact": str(self.tool)})
        targets = {"bench": self.target, "alternate": self.alternate}
        service = CompanionService(configuration, default_registry(program_targets=targets))
        self.addCleanup(service.close)
        return service

    def run_program(self, service):
        token = service.plan(self.request)["planToken"]
        job = service.submit({**self.request, "planToken": token})
        deadline = time.monotonic() + 5
        while job["state"] in {"queued", "running"}:
            self.assertLess(time.monotonic(), deadline)
            time.sleep(0.02)
            job = service.snapshot(job["jobId"])
        return job

    def test_prom_plan_binds_profile_and_bitstream_and_executes_stages_in_order(self):
        service = self.service()
        capability = next(item for item in service.capabilities()["operations"] if item["name"] == "fpga-program")
        self.assertEqual(["alternate", "bench"], capability["flashProgramTargets"])
        profile = capability["programTargetProfiles"]["bench"]
        self.assertEqual(("XCF02S", 0, 1), (profile["flashDevice"], profile["chainPosition"], profile["flashChainPosition"]))
        plan = service.plan(self.request)
        self.assertEqual(self.request["inputs"][0]["sha256"], plan["inputs"][0]["sha256"])
        with self.assertRaises(JobFailure) as changed:
            service.submit({**self.request, "inputs": [bitstream_input(b"different bytes")], "planToken": plan["planToken"]})
        self.assertEqual("confirmation-required", changed.exception.code)
        alternate = {**self.request["parameters"], "target": "alternate", "profile": self.alternate.fingerprint}
        with self.assertRaises(JobFailure) as retargeted:
            service.submit({**self.request, "parameters": alternate, "planToken": plan["planToken"]})
        self.assertEqual("confirmation-required", retargeted.exception.code)
        with self.assertRaises(JobFailure) as stale:
            service.submit({**self.request, "parameters": {**self.request["parameters"], "profile": "0" * 64},
                            "planToken": plan["planToken"]})
        self.assertEqual("fpga-target-changed", stale.exception.code)
        result = self.run_program(service)
        self.assertEqual("succeeded", result["state"], result)
        self.assertEqual(["generate-prom-mcs", "identify-prom-chain", "program-prom"],
                         [log["text"] for log in result["logs"] if log["stream"] == "stage"])
        self.assertEqual(self.request["inputs"][0]["sha256"], result["result"]["bitstreamSha256"])
        self.assertEqual(hashlib.sha256(b":0100000000FF\n:00000001FF\n").hexdigest(), result["result"]["mcsSha256"])
        self.assertTrue(result["result"]["programmerReportedSuccess"])
        self.assertFalse(result["result"]["hardwareVerified"])

    def test_missing_mcs_or_wrong_identified_prom_never_starts_write(self):
        for behavior, error, stage, stages in [
            ("missing-mcs", "fpga-invalid-mcs", "generate-prom-mcs", ["generate-prom-mcs"]),
            ("wrong-chain", "fpga-prom-chain-mismatch", "identify-prom-chain",
             ["generate-prom-mcs", "identify-prom-chain"]),
        ]:
            with self.subTest(behavior=behavior):
                job = self.run_program(self.service(behavior))
                self.assertEqual("failed", job["state"], job)
                self.assertEqual(error, job["error"]["code"])
                self.assertEqual(stage, job["error"]["details"]["stage"])
                self.assertEqual(stages, [log["text"] for log in job["logs"] if log["stream"] == "stage"])

    def test_zero_exit_with_program_failure_does_not_report_success(self):
        job = self.run_program(self.service("program-error"))
        self.assertEqual("failed", job["state"], job)
        self.assertEqual("fpga-stage-failed", job["error"]["code"])
        self.assertEqual("program-prom", job["error"]["details"]["stage"])
        self.assertIsNone(job["result"])

    def test_ise_flash_accepts_only_the_fixed_explicit_basys2_pair(self):
        config = self.tool.with_suffix(".json")
        config.write_text(json.dumps({"version": 1, "targets": {"bench": {
            "board": "BASYS2", "transport": "ise", "cable": "usb21", "chainPosition": 0,
            "allowFlash": True, "flashChainPosition": 1,
        }}}))
        self.assertEqual({"bench": self.target}, load_program_targets(config))
        for change in [
            {"board": "OTHER"},
            {"cable": "auto"},
            {"flash_chain_position": None},
            {"flash_chain_position": 0},
            {"flash_chain_position": True},
            {"chain_position": 1},
        ]:
            with self.subTest(change=change):
                with self.assertRaises(ValueError):
                    FpgaProgramTarget(**{**vars(self.target), **change})
