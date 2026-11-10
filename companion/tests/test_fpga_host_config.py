import base64
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

from companion.api import JobFailure, OperationRegistry
from companion.fpga import FpgaProgramTarget, register, validate_program_parameters
from companion.fpga_host_config import load_program_targets
from companion.fpga_usbtmc import program as program_usbtmc
from companion.server import CompanionService, Configuration, default_registry


class FpgaHostConfigTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "fpga-targets.json"

    def write(self, targets):
        self.path.write_text(json.dumps({"version": 1, "targets": targets}))
        return load_program_targets(self.path)

    def test_explicit_host_configuration_registers_confirmed_programming_without_running_it(self):
        targets = self.write({"bench": {"board": "ulx3s", "serial": "fixture-cable-serial"}})
        self.assertEqual({"bench": FpgaProgramTarget("ulx3s", "fixture-cable-serial")}, targets)
        registry = OperationRegistry()
        register(registry, targets)
        self.assertTrue(registry.operations["fpga-program"].requires_confirmation)
        self.assertEqual(("openFPGALoader",), registry.operations["fpga-program"].required_tools)

    def test_capabilities_expose_only_the_configured_aliases(self):
        targets = self.write({"z-1": {"board": "ecp5_evn", "serial": "private-serial-a", "allowFlash": True,
                                      "transport": "openfpgaloader", "cable": "ft2232", "chainPosition": 0},
                              "a.2": {"board": "ulx3s", "serial": "private-serial-b"}})
        configuration = Configuration("fixture-token-" * 4, {"fpga-program"}, tool_paths={"openFPGALoader": sys.executable})
        service = CompanionService(configuration, default_registry(program_targets=targets))
        self.addCleanup(service.close)
        capabilities = service.capabilities()
        operations = {operation["name"]: operation for operation in capabilities["operations"]}
        self.assertEqual(["a.2", "z-1"], operations["fpga-program"]["programTargets"])
        self.assertEqual(["z-1"], operations["fpga-program"]["flashProgramTargets"])
        self.assertEqual(targets["z-1"].fingerprint, operations["fpga-program"]["programTargetProfiles"]["z-1"]["fingerprint"])
        self.assertTrue(operations["fpga-program"]["allowed"])
        self.assertTrue(operations["fpga-program"]["requiresConfirmation"])
        self.assertNotIn("programTargets", operations["fpga-synthesize"])
        encoded = json.dumps(capabilities)
        for private in ("ulx3s", "private-serial-a", "private-serial-b"):
            self.assertNotIn(private, encoded)

    def test_flash_requires_per_target_approval_and_a_matching_plan(self):
        targets = self.write({"ram": {"board": "ulx3s", "serial": "serial-a"},
                              "flash": {"board": "ulx3s", "serial": "serial-b", "allowFlash": True,
                                        "transport": "openfpgaloader", "cable": "ft2232", "chainPosition": 1}})
        # An executable is needed for availability, but no request in this test reaches a job.
        tool = self.path.with_name("fixture-programmer")
        tool.write_text("#!/bin/sh\nexit 97\n")
        tool.chmod(0o755)
        service = CompanionService(Configuration("fixture-token-" * 4, {"fpga-program"},
                                                 tool_paths={"openFPGALoader": str(tool)}),
                                   default_registry(program_targets=targets))
        self.addCleanup(service.close)
        request = {"operation": "fpga-program", "parameters": {"target": "ram", "flash": True, "bitstream": "design.bit"}}
        for action in (service.plan, service.submit):
            with self.assertRaises(JobFailure) as failure:
                action(request)
            self.assertEqual("fpga-flash-not-allowed", failure.exception.code)

        profile = targets["flash"].fingerprint
        request["parameters"].update(target="flash", flash=False, profile=profile)
        token = service.plan(request)["planToken"]
        request["parameters"]["flash"] = True
        with self.assertRaises(JobFailure) as failure:
            service.submit({**request, "planToken": token})
        self.assertEqual("confirmation-required", failure.exception.code)
        bitstream = b"reviewed bitstream"
        bitstream_hash = hashlib.sha256(bitstream).hexdigest()
        request["inputs"] = [{"name": "design.bit", "base64": base64.b64encode(bitstream).decode(),
                              "sha256": bitstream_hash}]
        flash_plan = service.plan(request)
        self.assertTrue(flash_plan["parameters"]["flash"])
        self.assertEqual([{"name": "design.bit", "sha256": bitstream_hash, "size": len(bitstream)}], flash_plan["inputs"])
        replacement = b"replacement bitstream"
        request["inputs"] = [{"name": "design.bit", "base64": base64.b64encode(replacement).decode(),
                              "sha256": hashlib.sha256(replacement).hexdigest()}]
        with self.assertRaises(JobFailure) as failure:
            service.submit({**request, "planToken": flash_plan["planToken"]})
        self.assertEqual("confirmation-required", failure.exception.code)
        with self.assertRaises(JobFailure) as failure:
            service.plan({**request, "parameters": {**request["parameters"], "profile": "0" * 64}})
        self.assertEqual("fpga-target-changed", failure.exception.code)

    def test_empty_targets_do_not_advertise_programming(self):
        registry = OperationRegistry()
        register(registry, self.write({}))
        self.assertNotIn("fpga-program", registry.operations)

    def test_confirmed_download_only_binds_bytes_and_explicit_jtag_profile(self):
        self.assert_confirmed_quartus_download("DE0_CV", "quartus", "existing.sof", "existing.pof", None)

    def test_nonvolatile_download_only_binds_bytes_and_explicit_jtag_profile(self):
        self.assert_confirmed_quartus_download("MAX_V_CONSOLE", "quartus-cpld", "existing.pof", "existing.sof",
                                               "cpld-nonvolatile")

    def assert_confirmed_quartus_download(self, board, transport, filename, invalid_filename, mode):
        targets = self.write({
            "board-a": {"board": board, "transport": transport, "cable": "USB-Blaster [1-2]", "chainPosition": 2},
            "board-b": {"board": board, "transport": transport, "cable": "USB-Blaster [1-3]", "chainPosition": 2},
        })
        fake = self.path.with_name("test-programmer")
        fake.write_text(f"#!{sys.executable}\nimport pathlib, sys\n"
                        f"assert sys.argv[1:] == ['-c', 'USB-Blaster [1-2]', '-m', 'jtag', '-o', 'P;inputs/{filename}@3']\n"
                        f"assert pathlib.Path('inputs/{filename}').read_bytes() == b'fixture bitstream'\n")
        fake.chmod(0o755)
        service = CompanionService(Configuration("fixture-token-" * 4, {"fpga-program"},
                                                 tool_paths={"quartus_pgm": str(fake)}),
                                   default_registry(program_targets=targets))
        self.addCleanup(service.close)
        capability = next(item for item in service.capabilities()["operations"] if item["name"] == "fpga-program")
        profile_a = capability["programTargetProfiles"]["board-a"]
        profile_b = capability["programTargetProfiles"]["board-b"]
        self.assertTrue(capability["available"])
        self.assertEqual((board, transport, "USB-Blaster [1-2]", 2),
                         (profile_a["board"], profile_a["transport"], profile_a["cable"], profile_a["chainPosition"]))
        payload = b"fixture bitstream"
        request = {"operation": "fpga-program", "inputs": [{"name": filename,
                   "sha256": hashlib.sha256(payload).hexdigest(), "base64": base64.b64encode(payload).decode()}],
                   "parameters": {"target": "board-a", "profile": profile_a["fingerprint"], "bitstream": filename}}
        plan = service.plan(request)
        self.assertTrue(plan["requiresConfirmation"])
        self.assertEqual(hashlib.sha256(payload).hexdigest(), plan["inputs"][0]["sha256"])
        with self.assertRaises(JobFailure) as wrong_format:
            service.plan({**request, "parameters": {**request["parameters"], "bitstream": invalid_filename}})
        self.assertEqual("invalid-bitstream", wrong_format.exception.code)
        with self.assertRaises(JobFailure) as external_flash:
            service.plan({**request, "parameters": {**request["parameters"], "flash": True}})
        self.assertEqual("fpga-flash-not-allowed", external_flash.exception.code)
        with self.assertRaises(JobFailure) as wrong_profile:
            service.plan({**request, "parameters": {**request["parameters"], "profile": profile_b["fingerprint"]}})
        self.assertEqual("fpga-target-changed", wrong_profile.exception.code)
        with self.assertRaises(JobFailure) as wrong_cable:
            service.submit({**request, "planToken": plan["planToken"], "parameters": {
                **request["parameters"], "target": "board-b", "profile": profile_b["fingerprint"]}})
        self.assertEqual("confirmation-required", wrong_cable.exception.code)
        changed = b"changed bitstream"
        with self.assertRaises(JobFailure) as wrong_hash:
            service.submit({**request, "planToken": plan["planToken"], "inputs": [{
                "name": filename, "sha256": hashlib.sha256(changed).hexdigest(),
                "base64": base64.b64encode(changed).decode()}]})
        self.assertEqual("confirmation-required", wrong_hash.exception.code)
        submitted = service.submit({**request, "planToken": plan["planToken"]})
        job_id = submitted["jobId"]
        service._jobs[job_id]["future"].result(timeout=5)
        completed = service.snapshot(job_id)
        self.assertEqual("succeeded", completed["state"], completed)
        self.assertEqual(mode, completed["result"].get("programmingMode"))
        self.assertFalse(completed["result"]["flash"])
        self.assertFalse(completed["result"]["hardwareVerified"])

    def test_profile_config_rejects_auto_cable_and_invalid_chain_or_device(self):
        for target in [
            {"board": "BASYS2", "transport": "ise", "cable": "auto", "chainPosition": 0},
            {"board": "BASYS2", "transport": "ise", "cable": "usb21", "chainPosition": 0, "allowFlash": True},
            {"board": "ice40_generic", "serial": "serial", "allowFlash": True},
            {"board": "DE0_CV", "transport": "quartus-cpld", "cable": "USB-Blaster [1-2]", "chainPosition": 0},
            {"board": "MAX_V_CONSOLE", "transport": "quartus-cpld", "cable": "USB-Blaster", "chainPosition": 0},
            {"board": "BASYS3", "transport": "vivado", "cable": "*/xilinx_tcf/Digilent/ABC", "chainPosition": 0},
            {"board": "BASYS3", "transport": "vivado", "cable": "localhost/xilinx_tcf/Digilent/ABC", "chainPosition": True},
            {"board": "BASYS3", "transport": "vivado", "cable": "localhost/xilinx_tcf/Digilent/ABC", "chainPosition": 64},
            {"board": "GECKO4LED", "transport": "usbtmc", "cable": "usb", "serial": "serial", "device": "/dev/null"},
            {"board": "GECKO4LED", "transport": "usbtmc", "cable": "usb", "device": "/dev/usbtmc0"},
        ]:
            with self.subTest(target=target), self.assertRaises(ValueError):
                self.write({"bench": target})

    def test_one_quartus_chain_position_cannot_have_conflicting_modes(self):
        cable = {"cable": "USB-Blaster [1-2]", "chainPosition": 0}
        with self.assertRaises(ValueError):
            self.write({"volatile": {"board": "DE0_CV", "transport": "quartus", **cable},
                        "nonvolatile": {"board": "MAX_V_CONSOLE", "transport": "quartus-cpld", **cable}})

    def test_jedec_programming_binds_cpld_mode_cable_position_and_bytes(self):
        cpld = {"board": "MICROLABTLE", "transport": "ise-cpld", "cable": "usb21", "chainPosition": 2}
        fpga = {"board": "BASYS2", "transport": "ise", "cable": "usb22", "chainPosition": 2}
        with self.assertRaises(ValueError):
            self.write({"bench": {**cpld, "board": "BASYS2"}})
        with self.assertRaises(ValueError):
            self.write({"bench": cpld, "duplicate": {**fpga, "cable": "usb21"}})
        targets = self.write({"bench": cpld, "other": {**cpld, "chainPosition": 1}, "fpga": fpga})
        fake = self.path.with_name("test-impact")
        fake.write_text(f"#!{sys.executable}\nfrom pathlib import Path\nimport sys\n"
                        "assert sys.argv[1] == '-batch'\n"
                        "commands = Path(sys.argv[2]).read_text().splitlines()\n"
                        "assert commands[:3] == ['setMode -bscan', 'setCable -port usb21', 'identify']\n"
                        "assert commands[3].startswith('assignFile -p 3 -file ')\n"
                        "assert commands[4:] == ['program -p 3 -e', 'quit']\n"
                        "assert Path(commands[3].split(' -file ')[1].strip(chr(34))).read_bytes() in (b'JEDEC bytes', b'no success')\n"
                        "if Path('inputs/design.jed').read_bytes() == b'JEDEC bytes': print(\"'3': Programmed successfully.\")\n")
        fake.chmod(0o755)
        service = CompanionService(Configuration("fixture-token-" * 4, {"fpga-program"},
                                                 tool_paths={"impact": str(fake)}),
                                   default_registry(program_targets=targets))
        self.addCleanup(service.close)
        params = {"target": "bench", "profile": targets["bench"].fingerprint,
                  "bitstream": "design.jed", "flash": False}

        def request(payload):
            return {"operation": "fpga-program", "parameters": params,
                    "inputs": [{"name": "design.jed", "sha256": hashlib.sha256(payload).hexdigest(),
                                "base64": base64.b64encode(payload).decode()}]}

        def complete(job):
            job_id = job["jobId"]
            service._jobs[job_id]["future"].result(timeout=5)
            return service.snapshot(job_id)

        baseline = request(b"JEDEC bytes")
        plan = service.plan(baseline)
        self.assertTrue(plan["requiresConfirmation"])
        self.assertEqual(baseline["inputs"][0]["sha256"], plan["inputs"][0]["sha256"])
        approved = {**baseline, "planToken": plan["planToken"]}
        variants = (
            ({**approved, "parameters": {**params, "target": "other", "profile": targets["other"].fingerprint}}, "confirmation-required"),
            ({**approved, "inputs": request(b"changed bytes")["inputs"]}, "confirmation-required"),
            ({**approved, "parameters": {**params, "target": "fpga", "profile": targets["fpga"].fingerprint}}, "invalid-bitstream"),
            ({**approved, "parameters": {**params, "bitstream": "design.bit"}}, "invalid-bitstream"),
            ({**approved, "parameters": {**params, "flash": True}}, "fpga-flash-not-allowed"),
        )
        for changed, code in variants:
            with self.subTest(code=code), self.assertRaises(JobFailure) as denied:
                service.submit(changed)
            self.assertEqual(code, denied.exception.code)
        self.assertEqual({}, service._jobs)
        completed = complete(service.submit(approved))
        self.assertEqual("succeeded", completed["state"], completed)
        self.assertEqual("cpld-nonvolatile", completed["result"]["programmingMode"])
        self.assertFalse(completed["result"]["hardwareVerified"])
        with self.assertRaises(JobFailure) as reused:
            service.submit(approved)
        self.assertEqual("confirmation-required", reused.exception.code)
        unconfirmed = request(b"no success")
        unsuccessful = complete(service.submit({**unconfirmed, "planToken": service.plan(unconfirmed)["planToken"]}))
        self.assertEqual("failed", unsuccessful["state"])
        self.assertEqual("cpld-program-unconfirmed", unsuccessful["error"]["code"])

    def test_vivado_ise_and_open_jtag_use_only_the_configured_cable_and_position(self):
        targets = self.write({
            "modern": {"board": "BASYS3", "transport": "vivado", "cable": "localhost/xilinx_tcf/Digilent/A", "chainPosition": 2},
            "legacy": {"board": "BASYS2", "transport": "ise", "cable": "usb21", "chainPosition": 2},
            "open": {"board": "BASYS3", "transport": "openfpgaloader", "cable": "digilent", "serial": "CABLE1", "chainPosition": 2},
        })
        from types import SimpleNamespace
        folder = self.path.parent
        ctx = SimpleNamespace(working_directory=folder, tool=lambda tool: tool)
        bitstream = folder / "fixture.bit"
        self.assertEqual(["vivado", "-mode", "batch", "-source", str(folder / "program.tcl"), "-tclargs", str(bitstream)],
                         targets["modern"].command(ctx, bitstream, False))
        script = (folder / "program.tcl").read_text()
        self.assertIn("get_hw_targets {localhost/xilinx_tcf/Digilent/A}", script)
        self.assertIn("lindex [get_hw_devices] 2", script)
        self.assertIn("program_hw_device $device", script)
        self.assertEqual(["impact", "-batch", str(folder / "program.cmd")], targets["legacy"].command(ctx, bitstream, False))
        script = (folder / "program.cmd").read_text()
        self.assertIn("setCable -port usb21", script)
        self.assertIn("program -p 3 -onlyFpga", script)
        self.assertEqual(["openFPGALoader", "--cable", "digilent", "--ftdi-serial", "CABLE1", "--index-chain", "2",
                          "--write-sram", str(bitstream)], targets["open"].command(ctx, bitstream, False))
        self.assertEqual(["openFPGALoader", "--board", "ice40_generic", "--ftdi-serial", "ICE1",
                          "--write-sram", str(bitstream)],
                         FpgaProgramTarget("ice40_generic", "ICE1").command(ctx, bitstream, False))
        with self.assertRaises(JobFailure) as flash:
            validate_program_parameters({"target": "open", "bitstream": "fixture.bit",
                                         "profile": targets["open"].fingerprint, "flash": True}, targets)
        self.assertEqual("fpga-flash-not-allowed", flash.exception.code)

    def test_usbtmc_sends_gecko_prefix_and_bytes_only_to_matching_usb_serial(self):
        from types import SimpleNamespace
        folder = self.path.parent
        endpoint, source = folder / "endpoint", folder / "fixture.bit"
        payload = b"\x01\x02\x03" * 30_000
        source.write_bytes(payload)
        device = folder / "sysfs-usb"
        device.mkdir()
        (device / "idVendor").write_text("1234\n")
        (device / "serial").write_text("FIXTURE-SERIAL\n")
        real_open = os.open
        for serial in ("wrong", "FIXTURE-SERIAL"):
            endpoint.write_bytes(b"")
            with patch("companion.fpga_usbtmc.os.open", side_effect=lambda path, flags: real_open(endpoint, flags)), \
                 patch("companion.fpga_usbtmc.os.fstat", return_value=SimpleNamespace(st_mode=stat.S_IFCHR, st_rdev=0)), \
                 patch("companion.fpga_usbtmc.Path.resolve", return_value=device):
                if serial == "wrong":
                    with self.assertRaisesRegex(ValueError, "does not match"):
                        program_usbtmc("/dev/usbtmc0", serial, source)
                    self.assertEqual(b"", endpoint.read_bytes())
                else:
                    program_usbtmc("/dev/usbtmc0", serial, source)
                    self.assertEqual(b"FPGA " + payload, endpoint.read_bytes())

    def test_rejects_injected_commands_paths_unknown_fields_and_missing_identity(self):
        invalid = [
            {"bench": {"board": "ulx3s", "serial": ""}},
            {"bench": {"board": "ulx3s", "serial": "  "}},
            {"bench": {"board": "ulx3s", "serial": " serial"}},
            {"bench": {"board": "ulx3s --run script", "serial": "serial"}},
            {"bench": {"board": "../board", "serial": "serial"}},
            {"../bench": {"board": "ulx3s", "serial": "serial"}},
            {"bench": {"board": "ulx3s", "serial": "serial", "argv": ["sh"]}},
            {"bench": {"board": "ulx3s", "serial": "serial", "path": "../bitstream"}},
            {"bench": {"board": "ulx3s", "serial": 17}},
            {"bench": {"board": "ulx3s", "serial": "serial", "allowFlash": 1}},
            {"bench": {"board": "ulx3s", "serial": "serial\n"}},
            {"bench": {"board": "", "serial": "serial"}},
            {"bench": {"board": "ulx3s", "serial": "serial"}, "alias": {"board": "ulx3s", "serial": "serial"}},
        ]
        for targets in invalid:
            with self.subTest(targets=targets), self.assertRaises(ValueError):
                self.write(targets)

    def test_rejects_duplicate_fields_versions_bad_encoding_and_oversize_files(self):
        for value in [
            b'{"version":1,"version":1,"targets":{}}',
            b'{"version":true,"targets":{}}',
            b'{"version":2,"targets":{}}',
            b'{"version":1,"targets":{},"command":"anything"}',
            b'[]', b'\xff', b' ' * 65_537,
            b'{"version":1,"targets":{"bench":{"board":"ulx3s","board":"other","serial":"cable"}}}',
        ]:
            self.path.write_bytes(value)
            with self.subTest(value=value[:100]), self.assertRaises(ValueError):
                load_program_targets(self.path)

    def test_missing_config_is_an_error_and_never_falls_back_to_a_default_target(self):
        with self.assertRaises(FileNotFoundError):
            load_program_targets(self.path)


if __name__ == "__main__":
    unittest.main()
