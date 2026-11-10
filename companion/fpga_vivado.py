"""Fixed, build-only Vivado operation for supported Artix-7 and Zynq-7000 profiles."""
import re

from companion.api import JobFailure, Operation
from companion.fpga import _execute, _hdl_sources, _text


PART = r"(?:xc7a15tcpg236|xc7a35t(?:cpg236|ftg256)|xc7z010clg400)-1"
TOP = "logisim_fpga_top"
PORT = r"fpga_(?:p[0-9]{1,4}_b[0-9]{1,4}|scan_(?:clock|[0-9]{1,4}_p[0-9]{1,4}))"
SELECT_PORT = rf"\[get_ports \{{{PORT}\}}\]"
STANDARD = r"LVCMOS(?:12|15|18|25|33)|LVTTL"
PIN_PROPERTY = re.compile(
    rf"set_property (?:PACKAGE_PIN [A-Z][A-Z0-9]{{0,7}}|IOSTANDARD (?:{STANDARD})|"
    rf"PULL(?:UP|DOWN) true|DRIVE (?:2|4|8|16|24)) {SELECT_PORT}"
)
CLOCK = re.compile(rf"create_clock -period ([0-9]{{1,12}}(?:\.[0-9]{{1,18}})?(?:[Ee][+-]?[0-9]{{1,2}})?) {SELECT_PORT}")
PARAMETERS = {"part", "top", "language", "sources", "constraint"}


def _constraint_text(path):
    if path.stat().st_size > 1_000_000:
        raise JobFailure("fpga-constraint-limit", "The constraint file exceeds its size limit.")
    try:
        # Split only at LF. Tcl continuation, comments, separators and substitutions
        # must all be rejected before Vivado sees any content.
        lines = path.read_bytes().decode("ascii").split("\n")
    except UnicodeDecodeError as error:
        raise JobFailure("invalid-fpga-constraint", "Vivado constraints must be ASCII.") from error
    valid = []
    for line in lines:
        line = line.rstrip("\r")
        if not line:
            continue
        clock = CLOCK.fullmatch(line)
        if not PIN_PROPERTY.fullmatch(line) and not (clock and 0.001 <= float(clock[1]) <= 1e9):
            raise JobFailure("invalid-fpga-constraint", "Vivado accepts only generated pin, electrical and clock constraints.")
        valid.append(line)
    return "".join(line + "\n" for line in valid)


def build(ctx, parameters):
    if not ctx.user_confirmed:
        raise JobFailure("confirmation-required", "Vivado requires the approved build plan.")
    if not isinstance(parameters, dict) or set(parameters) != PARAMETERS:
        raise JobFailure("invalid-fpga-parameter", "The Vivado build requires only the documented parameters.")
    part = _text(parameters, "part", PART)
    _text(parameters, "top", TOP)
    language = _text(parameters, "language", "verilog|vhdl")
    source_paths = _hdl_sources(ctx, parameters, language)
    source_commands = []
    for path in source_paths:
        relative = path.relative_to(ctx.working_directory).as_posix()
        if not re.fullmatch(r"(?:inputs/)?source-[0-9]{4}\.(?:v|vhd)", relative):
            raise JobFailure("invalid-fpga-sources", "Use the flattened Mobile HDL file names.")
        data = path.read_bytes()
        if re.search(rb"\bDPI(?:-C)?\b|\$(?:fclose|fread|fscanf|writemem[bh])\b", data, re.IGNORECASE) or (
            language == "vhdl" and re.search(rb"\b(?:file|foreign)\b", re.sub(rb"(?m)^[ \t]*--[^\r\n]*", b"", data), re.IGNORECASE)
        ):
            raise JobFailure("hdl-external-file", "External files and foreign-language directives are not accepted.")
        command = "read_vhdl -vhdl2008" if language == "vhdl" else "read_verilog -sv"
        source_commands.append(f"{command} {{{relative}}}")
    constraint = _text(parameters, "constraint", r"pins\.xdc")
    validated = _constraint_text(ctx.input_path(constraint))
    (ctx.working_directory / "validated-pins.xdc").write_text(validated, encoding="ascii")
    script = ctx.working_directory / "generated-vivado.tcl"
    script.write_text("\n".join(source_commands) + f"""
read_xdc {{validated-pins.xdc}}
puts {{Logisim stage: synthesis}}
synth_design -top {TOP} -part {part}
puts {{Logisim stage: place and route}}
opt_design
place_design
route_design
report_timing_summary -file {{outputs/timing.txt}}
set_property BITSTREAM.CONFIG.UNUSEDPIN Pullnone [current_design]
puts {{Logisim stage: bitstream}}
write_bitstream -force {{outputs/design.bit}}
""", encoding="ascii")
    _execute(ctx, "vivado-build", [ctx.tool("vivado"), "-mode", "batch", "-nojournal", "-nolog", "-source", script.name])
    paths = [ctx.output_path(name) for name in ("design.bit", "timing.txt")]
    if any(not path.is_file() or path.stat().st_size == 0 for path in paths):
        raise JobFailure("fpga-missing-artifact", "Vivado did not produce the bitstream and timing report.")
    return {"flow": "vivado", "top": TOP, "part": part, "synthesized": True,
            "placedAndRouted": True, "bitstreamGenerated": True, "programmed": False,
            "hardwareVerified": False, "artifacts": [ctx.artifact(path) for path in paths]}


def register(registry):
    registry.register(Operation("fpga-vivado-build", build, required_tools=("vivado",), requires_confirmation=True,
                                description="Build a supported Artix-7 or Zynq-7000 bitstream with the host-configured Vivado tool."))
