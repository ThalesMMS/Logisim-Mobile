"""Explicit FPGA synthesis jobs; no raw shell commands or implicit programming."""
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import sys

from companion import fpga_quartus_flash, fpga_quartus_max7000, fpga_sof
from companion.api import JobFailure, Operation, safe_name


_ECP5 = {"12k", "25k", "45k", "85k", "um-25k", "um-45k", "um-85k"}
_ICE40 = {"lp384", "lp1k", "lp4k", "lp8k", "hx1k", "hx4k", "hx8k", "up3k", "up5k", "u4k"}
_PARAMETERS = {"flow", "part", "package", "top", "sources", "constraint", "language", "goal", "frequency_mhz"}
_PROGRAM_TOOLS = {
    "openfpgaloader": "openFPGALoader", "vivado": "vivado",
    "ise": "impact", "ise-cpld": "impact", "quartus": "quartus_pgm", "quartus-cpld": "quartus_pgm", "usbtmc": "python-usbtmc",
}
_PROGRAM_FORMATS = {
    "openfpgaloader": {".bit", ".bin", ".fs", ".sof"},
    "vivado": {".bit"}, "ise": {".bit"}, "ise-cpld": {".jed"}, "quartus": {".sof"}, "quartus-cpld": {".pof"}, "usbtmc": {".bit"},
}


def _text(parameters, name, pattern, default=None):
    value = parameters.get(name, default)
    if not isinstance(value, str) or not re.fullmatch(pattern, value):
        raise JobFailure("invalid-fpga-parameter", f"Invalid FPGA parameter: {name}")
    return value


def _quote(path):
    value = str(path)
    if any(ord(c) < 32 for c in value):
        raise JobFailure("invalid-job-path", "The job path contains control characters.")
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _execute(ctx, stage, argv):
    ctx.check_cancelled()
    ctx.emit("stage", stage)
    result = ctx.run(argv)
    if result.returncode != 0:
        raise JobFailure("fpga-stage-failed", f"FPGA stage failed: {stage}",
                         {"stage": stage, "exitCode": result.returncode,
                          "stdout": result.stdout[-8192:], "stderr": result.stderr[-8192:]})
    return result


def _hdl_sources(ctx, parameters, language):
    source_names = parameters.get("sources")
    if not isinstance(source_names, list) or not 1 <= len(source_names) <= 128 or any(not isinstance(name, str) for name in source_names) or len(set(source_names)) != len(source_names):
        raise JobFailure("invalid-fpga-sources", "Provide between one and 128 distinct HDL input files.")
    source_paths = []
    total = 0
    for name in source_names:
        safe_name(name)
        if not name.lower().endswith((".v", ".sv") if language == "verilog" else (".vhd", ".vhdl")):
            raise JobFailure("invalid-fpga-sources", "An HDL input has the wrong language extension.")
        path = ctx.input_path(name)
        data = path.read_bytes()
        total += len(data)
        if len(data) > 2_000_000 or total > 16_000_000:
            raise JobFailure("fpga-source-limit", "The HDL source size limit was exceeded.")
        try:
            text = data.decode("utf-8", errors="strict")
        except UnicodeDecodeError as error:
            raise JobFailure("invalid-hdl-encoding", "HDL inputs must be UTF-8 text.") from error
        if re.search(r"`\s*include\b|\$(?:readmem[bh]|fopen|system)\b", text, re.IGNORECASE):
            raise JobFailure("hdl-external-file", "Upload self-contained HDL; external file and process directives are not accepted.")
        source_paths.append(path)
    return source_paths


def synthesize(ctx, parameters):
    if not isinstance(parameters, dict) or set(parameters) - _PARAMETERS:
        raise JobFailure("invalid-fpga-parameter", "The synthesis operation accepts only the documented parameters.")
    flow = _text(parameters, "flow", r"ecp5|ice40")
    parts = _ECP5 if flow == "ecp5" else _ICE40
    part = _text(parameters, "part", r"[a-z0-9-]{1,16}")
    if part not in parts:
        raise JobFailure("unsupported-fpga-device", "The selected device is not supported by this open flow.")
    package = _text(parameters, "package", r"[A-Za-z0-9]{1,32}")
    top = _text(parameters, "top", r"[A-Za-z_][A-Za-z0-9_]{0,127}")
    language = _text(parameters, "language", r"verilog|vhdl", "verilog")
    goal = _text(parameters, "goal", r"synthesis|bitstream", "synthesis")
    source_paths = _hdl_sources(ctx, parameters, language)
    frequency = parameters.get("frequency_mhz")
    if frequency is not None and (isinstance(frequency, bool) or not isinstance(frequency, (int, float)) or not 0 < frequency <= 100_000):
        raise JobFailure("invalid-fpga-parameter", "The requested frequency is out of range.")
    required = ["yosys"]
    if language == "vhdl":
        required.append("ghdl")
    if goal == "bitstream":
        required += [f"nextpnr-{flow}", "ecppack" if flow == "ecp5" else "icepack"]
    tools = {name: ctx.tool(name) for name in required}
    constraint = None
    if goal == "bitstream":
        constraint_name = parameters.get("constraint")
        safe_name(constraint_name)
        constraint = ctx.input_path(constraint_name)
        if constraint.stat().st_size > 1_000_000:
            raise JobFailure("fpga-constraint-limit", "The constraint file exceeds its size limit.")
        expected_suffix = ".lpf" if flow == "ecp5" else ".pcf"
        if constraint.suffix.lower() != expected_suffix:
            raise JobFailure("invalid-fpga-constraint", "The constraint format does not match the selected flow.")
    if language == "vhdl":
        translated = ctx.output_path("translated.v")
        result = _execute(ctx, "translate-vhdl", [tools["ghdl"], "--synth", "--std=08", "--out=verilog", *map(str, source_paths), "-e", top])
        translated.write_text(result.stdout)
        source_paths = [translated]
    netlist = ctx.output_path("synthesized.json")
    script = ctx.working_directory / "generated-synthesis.ys"
    script.write_text("".join(f"read_verilog -sv {_quote(path)}\n" for path in source_paths) +
                      f"hierarchy -check -top {top}\nsynth_{flow} -top {top} -json {_quote(netlist)}\ncheck -assert\n")
    _execute(ctx, "synthesize", [tools["yosys"], "-s", str(script)])
    if not netlist.is_file():
        raise JobFailure("fpga-missing-artifact", "Synthesis did not produce a netlist.")
    try:
        modules = json.loads(netlist.read_text())["modules"]
        if top not in modules:
            raise ValueError("Top-level module missing")
    except (ValueError, KeyError, TypeError) as error:
        raise JobFailure("fpga-invalid-netlist", "The synthesis output is not a usable netlist.") from error
    artifacts = [ctx.artifact(netlist)]
    if goal == "bitstream":
        routed = ctx.output_path("routed.config" if flow == "ecp5" else "routed.asc")
        route_args = [tools[f"nextpnr-{flow}"], f"--{part}", "--package", package.upper() if flow == "ecp5" else package.lower(),
                      "--json", str(netlist), "--lpf" if flow == "ecp5" else "--pcf", str(constraint),
                      "--textcfg" if flow == "ecp5" else "--asc", str(routed)]
        if frequency is not None:
            route_args += ["--freq", str(frequency)]
        _execute(ctx, "place-route", route_args)
        if not routed.is_file():
            raise JobFailure("fpga-missing-artifact", "Place and route did not produce its output.")
        bitstream = ctx.output_path("design.bit" if flow == "ecp5" else "design.bin")
        if flow == "ecp5":
            pack_args = [tools["ecppack"], "--compress", "--freq", "62.0", "--input", str(routed), "--bit", str(bitstream)]
        else:
            pack_args = [tools["icepack"], str(routed), str(bitstream)]
        _execute(ctx, "pack", pack_args)
        if not bitstream.is_file() or bitstream.stat().st_size == 0:
            raise JobFailure("fpga-missing-artifact", "Packing did not produce a bitstream.")
        artifacts += [ctx.artifact(routed), ctx.artifact(bitstream)]
    return {"synthesized": True, "placedAndRouted": goal == "bitstream", "bitstreamGenerated": goal == "bitstream",
            "programmed": False, "hardwareVerified": False, "flow": flow, "top": top, "artifacts": artifacts}


@dataclass(frozen=True)
class FpgaProgramTarget:
    board: str
    serial: str | None = None
    transport: str | None = None
    cable: str | None = None
    chain_position: int | None = None
    device: str | None = None
    allow_flash: bool = False
    flash_chain_position: int | None = None
    sof_part: str | None = None

    def __post_init__(self):
        if self.sof_part is not None and (self.transport != "quartus" or not isinstance(self.sof_part, str) or
                                          not fpga_sof.SOF_PART.fullmatch(self.sof_part)):
            raise ValueError("A large SOF part requires an exact Quartus device in a Quartus JTAG profile.")
        if not isinstance(self.allow_flash, bool):
            raise ValueError("Flash approval must be a boolean.")
        if self.allow_flash and self.transport not in ("openfpgaloader", "ise", "quartus"):
            raise ValueError("Flash approval requires an explicit supported JTAG profile.")
        if self.transport == "ise" and self.allow_flash:
            if (self.board != "BASYS2" or self.chain_position != 0 or
                    type(self.flash_chain_position) is not int or self.flash_chain_position != 1):
                raise ValueError("ISE flash requires BASYS2 with FPGA position 0 and XCF02S PROM position 1.")
        elif self.flash_chain_position is not None:
            raise ValueError("A separate flash position requires an approved ISE PROM profile.")
        if not isinstance(self.board, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", self.board):
            raise ValueError("A host-selected board is required.")
        if self.serial is not None and (not isinstance(self.serial, str) or not self.serial.strip() or
                self.serial != self.serial.strip() or len(self.serial) > 128 or any(ord(c) < 32 for c in self.serial)):
            raise ValueError("Invalid physical cable serial.")
        if self.transport is None:
            if not self.serial or any(v is not None for v in (self.cable, self.chain_position, self.device)):
                raise ValueError("A legacy target requires a board and physical cable serial.")
            return
        if not isinstance(self.transport, str) or self.transport not in _PROGRAM_TOOLS:
            raise ValueError("Unknown FPGA programming transport.")
        if not isinstance(self.cable, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9 ._:/\[\]-]{0,127}", self.cable) or self.cable != self.cable.strip():
            raise ValueError("Invalid host-selected cable.")
        if self.transport == "usbtmc":
            if not isinstance(self.device, str) or not re.fullmatch(r"/dev/usbtmc[0-9]{1,4}", self.device) or not self.serial or self.chain_position is not None:
                raise ValueError("USBTMC requires a Linux device, its USB serial, and no JTAG position.")
            return
        if type(self.chain_position) is not int or not 0 <= self.chain_position <= 63 or self.device is not None:
            raise ValueError("JTAG requires a zero-based chain position from 0 to 63.")
        if self.transport == "openfpgaloader":
            if not self.serial or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", self.cable):
                raise ValueError("openFPGALoader requires a cable model and FTDI serial.")
        elif self.serial is not None:
            raise ValueError("This transport uses the selected cable name or path.")
        if self.transport == "vivado" and not re.fullmatch(r"[A-Za-z0-9._:-]+/xilinx_tcf/[A-Za-z0-9._:-]+/[A-Za-z0-9._:-]+", self.cable):
            raise ValueError("Vivado requires the full hardware target path.")
        if self.transport in ("ise", "ise-cpld") and not re.fullmatch(r"(?:usb[0-9]+|lpt[0-9]+|COM[0-9]+)", self.cable):
            raise ValueError("iMPACT requires an explicit USB, parallel or serial port.")
        if self.transport == "ise-cpld" and self.board.upper() != "MICROLABTLE":
            raise ValueError("This iMPACT CPLD profile requires a MICROLABTLE board.")
        if self.transport == "quartus" and self.allow_flash and self.quartus_flash_profile is None:
            raise ValueError("GECKO4 Education flash requires a specific USB-Blaster cable and chain position zero.")
        if self.transport == "quartus-cpld":
            if self.board not in {"MAX_V_CONSOLE", "MAX_V_80P_25P"} and not self.quartus_max7000:
                raise ValueError("Quartus CPLD programming requires a supported MAX V or MAX 7000S board.")
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9 ._:/-]* \[[A-Za-z0-9._:/-]+\]", self.cable):
                raise ValueError("Quartus CPLD programming requires the full name of the selected JTAG cable.")

    @property
    def tool(self):
        return _PROGRAM_TOOLS[self.transport or "openfpgaloader"]

    @property
    def quartus_flash_profile(self):
        return fpga_quartus_flash.flash_profile(self)

    @property
    def quartus_max7000(self):
        return fpga_quartus_max7000.MAX7000_BOARDS.get(self.board) if self.transport == "quartus-cpld" else None

    @property
    def flash_device(self):
        if self.quartus_flash_profile is not None:
            return fpga_quartus_flash.FLASH_DEVICE
        return "XCF02S" if self.transport == "ise" and self.allow_flash else None

    @property
    def fingerprint(self):
        parts = (self.board, self.serial, self.transport, self.cable, self.chain_position, self.device, self.allow_flash)
        quartus_profile = self.quartus_flash_profile
        if quartus_profile is not None:
            parts += (quartus_profile.part, quartus_profile.idcode, fpga_quartus_flash.FLASH_DEVICE)
        elif self.flash_device is not None:
            parts += (self.flash_device, self.flash_chain_position)
        elif self.quartus_max7000:
            parts += self.quartus_max7000
        if self.sof_part is not None:
            parts += (self.sof_part,)
        return hashlib.sha256(json.dumps(parts, separators=(",", ":")).encode()).hexdigest()

    def profile(self):
        result = {"board": self.board, "transport": self.transport, "cable": self.cable,
                  "fingerprint": self.fingerprint, "tool": self.tool}
        if self.chain_position is not None:
            result["chainPosition"] = self.chain_position
        if self.flash_device is not None:
            result["flashDevice"] = self.flash_device
        if self.flash_chain_position is not None:
            result["flashChainPosition"] = self.flash_chain_position
        if self.quartus_flash_profile is not None:
            result["flashTool"] = fpga_quartus_flash.FLASH_TOOL
        if self.quartus_max7000:
            result["part"] = self.quartus_max7000[0]
        if self.sof_part is not None:
            result["sofPart"] = self.sof_part
        return result

    def command(self, ctx, bitstream, flash):
        if self.transport == "usbtmc":
            if not sys.platform.startswith("linux"):
                raise JobFailure("fpga-transport-unavailable", "USBTMC requires a Linux companion host.")
            worker = Path(__file__).with_name("fpga_usbtmc.py")
            return [ctx.tool(self.tool), str(worker), self.device, self.serial, str(bitstream)]
        tool = ctx.tool(self.tool)
        if self.transport is None:
            return [tool, "--board", self.board, "--ftdi-serial", self.serial, "--write-sram", str(bitstream)]
        if self.transport == "openfpgaloader":
            mode = "--write-flash" if flash else "--write-sram"
            return [tool, "--cable", self.cable, "--ftdi-serial", self.serial,
                    "--index-chain", str(self.chain_position), mode, str(bitstream)]
        if self.transport in {"quartus", "quartus-cpld"}:
            if flash:
                raise JobFailure("invalid-fpga-parameter", "Quartus flash requires a converted JIC.")
            return [tool, "-c", self.cable, "-m", "jtag", "-o",
                    f"P;{bitstream.resolve().relative_to(ctx.working_directory)}@{self.chain_position + 1}"]
        # The host supplies a restricted cable identity and the fixed Tcl/script commands.
        if self.transport in ("ise", "ise-cpld"):
            script = ctx.working_directory / "program.cmd"
            position = self.chain_position + 1
            mode = "-e" if self.transport == "ise-cpld" else "-onlyFpga"
            script.write_text(f'setMode -bscan\nsetCable -port {self.cable}\nidentify\n'
                              f'assignFile -p {position} -file {_quote(bitstream)}\nprogram -p {position} {mode}\nquit\n')
            return [tool, "-batch", str(script)]
        script = ctx.working_directory / "program.tcl"
        script.write_text(
            "if {[catch {\n"
            "  open_hw_manager\n  connect_hw_server\n"
            f"  set targets [get_hw_targets {{{self.cable}}}]\n"
            '  if {[llength $targets] != 1} {error "Selected JTAG cable was not found uniquely"}\n'
            "  current_hw_target [lindex $targets 0]\n  open_hw_target\n"
            f"  set device [lindex [get_hw_devices] {self.chain_position}]\n"
            '  if {$device eq ""} {error "Selected JTAG chain position was not found"}\n'
            "  current_hw_device $device\n  refresh_hw_device -update_hw_probes false $device\n"
            "  set_property PROGRAM.FILE [lindex $argv 0] $device\n"
            "  program_hw_device $device\n  close_hw_manager\n"
            "} detail]} {puts stderr $detail; exit 1}\nexit 0\n"
        )
        return [tool, "-mode", "batch", "-source", str(script), "-tclargs", str(bitstream)]


def validate_program_parameters(parameters, targets):
    if not isinstance(parameters, dict) or set(parameters) - {"target", "bitstream", "flash", "profile"}:
        raise JobFailure("invalid-fpga-parameter", "Invalid programming parameters.")
    target_name = parameters.get("target")
    if not isinstance(target_name, str) or target_name not in targets:
        raise JobFailure("unknown-fpga-target", "Select a physical board configured by the host operator.")
    flash = parameters.get("flash", False)
    if not isinstance(flash, bool):
        raise JobFailure("invalid-fpga-parameter", "flash must be a boolean.")
    target = targets[target_name]
    profile = parameters.get("profile")
    if (target.transport is not None or profile is not None) and profile != target.fingerprint:
        raise JobFailure("fpga-target-changed", "Review the configured board, cable and chain again.")
    if flash and not target.allow_flash:
        raise JobFailure("fpga-flash-not-allowed", "The host operator has not enabled flash for this board and cable.")
    formats = _PROGRAM_FORMATS[target.transport or "openfpgaloader"]
    name = safe_name(parameters.get("bitstream"))
    if Path(name).suffix.lower() not in formats:
        raise JobFailure("invalid-bitstream", f"Provide a nonempty bitstream for this profile: {', '.join(sorted(formats))}.")


def program(ctx, parameters, targets):
    if not ctx.user_confirmed:
        raise JobFailure("confirmation-required", "Programming requires the server's request-bound one-shot confirmation.")
    validate_program_parameters(parameters, targets)
    target_name = parameters["target"]
    target = targets[target_name]
    flash = parameters.get("flash", False)
    bitstream = ctx.input_path(parameters["bitstream"])
    size = bitstream.stat().st_size
    if not 0 < size <= fpga_sof.INLINE_PROGRAM_BYTES:
        if not (target.sof_part is not None and not flash and bitstream.suffix.lower() == ".sof" and
                fpga_sof.INLINE_PROGRAM_BYTES < size <= fpga_sof.MAX_SOF_BYTES):
            raise JobFailure("invalid-bitstream", "Provide a nonempty bitstream up to 4 MiB or a reviewed large Quartus SOF.")
        with bitstream.open("rb") as stream:
            fpga_sof.verify_part(stream, size, target.sof_part)
    result = {"programmerReportedSuccess": True, "hardwareVerified": False, "target": target_name, "flash": flash}
    if flash and target.transport == "ise":
        from companion.fpga_prom import program_basys2_prom
        result.update(program_basys2_prom(ctx, target, bitstream))
    elif flash and target.quartus_flash_profile is not None:
        result["artifactHashes"] = fpga_quartus_flash.program_quartus_flash(ctx, target, bitstream)
    else:
        if target.quartus_max7000:
            part, idcode = target.quartus_max7000
            fpga_quartus_max7000.validate_pof(bitstream, part)
            ctx.check_cancelled()
            fpga_quartus_max7000.verify_chain(ctx, target, idcode)
        stage = "program" if target.transport is None else f"program-{target.transport}"
        completed = _execute(ctx, stage, target.command(ctx, bitstream, flash))
        if target.transport == "ise-cpld":
            position = target.chain_position + 1
            logs = f"{completed.stdout}\n{completed.stderr}"
            if not re.search(rf"(?m)^\s*'{position}':\s*Programmed successfully\.\s*$", logs):
                raise JobFailure("cpld-program-unconfirmed", "iMPACT did not report successful programming at the approved JTAG position.")
    if target.transport in ("quartus-cpld", "ise-cpld"):
        result["programmingMode"] = "cpld-nonvolatile"
    return result


def register(registry, program_targets=None):
    from companion.fpga_ise import register as register_ise
    from companion.fpga_quartus import register as register_quartus
    from companion.fpga_vivado import register as register_vivado

    register_vivado(registry)
    register_ise(registry)
    register_quartus(registry)
    registry.register(Operation("fpga-synthesize", synthesize, required_tools=("yosys",),
                                description="Synthesize uploaded self-contained HDL. Place/route/pack require their configured tools."))
    targets = dict(program_targets or {})
    if targets:
        legacy_only = all(target.transport is None for target in targets.values())
        profiles = {} if legacy_only else {name: (target.profile() if target.transport is not None else {"tool": target.tool})
                                            for name, target in targets.items()}
        tools = ("openFPGALoader",) if legacy_only else ()
        registry.register(Operation("fpga-program", lambda ctx, params: program(ctx, params, targets),
                                    required_tools=tools, requires_confirmation=True, program_targets=tuple(sorted(targets)),
                                    program_target_profiles=profiles,
                                    flash_program_targets=tuple(sorted(name for name, target in targets.items() if target.allow_flash)),
                                    validate_parameters=lambda params: validate_program_parameters(params, targets),
                                    description="Program a host-selected board and cable only after one-shot confirmation."))
