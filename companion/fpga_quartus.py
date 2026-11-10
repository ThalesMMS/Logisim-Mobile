"""Build an approved device image using the host's Quartus installation."""
import re

from companion.api import JobFailure, Operation
from companion.fpga import _execute, _hdl_sources, _text
from companion import fpga_sof
from companion.fpga_quartus_max7000 import MAX7000_DEVICES, validate_pof


_PORT = r"fpga_p[0-9]{1,4}_b[0-9]{1,4}"
_LOCATION = re.compile(rf"set_location_assignment (PIN_[A-Z0-9]{{1,8}}) -to ({_PORT})")
_INSTANCE = re.compile(
    rf'set_instance_assignment -name (IO_STANDARD|CURRENT_STRENGTH_NEW|WEAK_PULL_UP_RESISTOR) '
    rf'("[A-Z0-9. -]{{1,24}}"|ON) -to ({_PORT})'
)
_UNUSED = re.compile(r'set_global_assignment -name RESERVE_ALL_UNUSED_PINS "AS INPUT (PULLUP|PULLDOWN|TRI-STATED)"')
_UNUSED_MAX7000 = re.compile(r'set_global_assignment -name RESERVE_ALL_UNUSED_PINS_NO_OUTPUT_GND "AS INPUT (TRI-STATED)"')
_CLOCK = re.compile(
    rf"create_clock -name ([A-Za-z_][A-Za-z0-9_]{{0,127}}) -period "
    rf"([0-9]+(?:\.[0-9]+)?(?:[Ee][+-]?[0-9]+)?) \[get_ports \{{({_PORT})\}}\]"
)
_IO_STANDARD = {
    '"LVCMOS12"': '"1.2 V"', '"LVCMOS15"': '"1.5 V"', '"LVCMOS18"': '"1.8 V"',
    '"LVCMOS25"': '"2.5 V"', '"LVCMOS33"': '"3.3-V LVCMOS"', '"LVTTL"': '"3.3-V LVTTL"',
}
_DRIVE = {'"2 MA"', '"4 MA"', '"8 MA"', '"16 MA"', '"24 MA"'}
_BUILD_TARGETS = {
    "de0-cv": ("Cyclone V", "5CEBA4F23C7", "sof"),
    "max-v-5m570z": ("MAX V", "5M570ZF256C5", "pof"),
    "5csxfc6d6f31c6": ("Cyclone V", "5CSXFC6D6F31C6", "sof"),
    "ep4ce22f17c7": ("Cyclone IV E", "EP4CE22F17C7", "sof"),
    "ep1c12q240c8": ("Cyclone", "EP1C12Q240C8", "sof"),
    "5cgxfc5c6f27c7": ("Cyclone V", "5CGXFC5C6F27C7", "sof"),
    "ep2c20f484c8": ("Cyclone II", "EP2C20F484C8", "sof"),
    "ep4ce15f23c8": ("Cyclone IV E", "EP4CE15F23C8", "sof"),
    "ep4ce30f23c8": ("Cyclone IV E", "EP4CE30F23C8", "sof"),
    "10cl016yf484c8g": ("Cyclone 10 LP", "10CL016YF484C8G", "sof"),
    "10m08sau169c8g": ("MAX 10", "10M08SAU169C8G", "sof"),
    "ep3c16f484c6": ("Cyclone III", "EP3C16F484C6", "sof"),
    "ep4ce22f17c6": ("Cyclone IV E", "EP4CE22F17C6", "sof"),
    "10m50daf484c7g": ("MAX 10", "10M50DAF484C7G", "sof"),
    "5cseba6u23i7": ("Cyclone V", "5CSEBA6U23I7", "sof"),
    "5csema5f31c6": ("Cyclone V", "5CSEMA5F31C6", "sof"),
    **{target: ("MAX7000S", part, "pof") for target, (part, _) in MAX7000_DEVICES.items()},
}


def _constraint_lines(ctx, name):
    path = ctx.input_path(name)
    if path.stat().st_size > 1_000_000:
        raise JobFailure("fpga-constraint-limit", f"{name} exceeds its size limit.")
    try:
        return [line for line in path.read_text(encoding="utf-8").splitlines() if line]
    except UnicodeDecodeError as error:
        raise JobFailure("invalid-fpga-constraint", f"{name} must be UTF-8 text.") from error


def _pins(ctx, max7000):
    ports, locations, settings = set(), set(), {}
    unused = None
    unused_pattern = _UNUSED_MAX7000 if max7000 else _UNUSED
    for line in _constraint_lines(ctx, "pins.qsf"):
        if match := _LOCATION.fullmatch(line):
            location, port = match.groups()
            if port in ports or location in locations:
                raise JobFailure("invalid-fpga-constraint", "Quartus pin assignments must be unique.")
            ports.add(port)
            locations.add(location)
            settings[("location", port)] = f"set_location_assignment {location} -to {port}"
        elif match := _INSTANCE.fullmatch(line):
            name, value, port = match.groups()
            if name == "IO_STANDARD":
                value = _IO_STANDARD.get(value)
            elif name == "CURRENT_STRENGTH_NEW":
                value = value if value in _DRIVE else None
            else:
                value = value if value == "ON" else None
            if value is None or (name, port) in settings:
                raise JobFailure("invalid-fpga-constraint", "Invalid or repeated Quartus I/O assignment.")
            settings[(name, port)] = f"set_instance_assignment -name {name} {value} -to {port}"
        elif unused_pattern.fullmatch(line) and unused is None:
            unused = line
        else:
            raise JobFailure("invalid-fpga-constraint", "Unexpected Quartus pin assignment.")
    if unused is None or any(port not in ports for _, port in settings):
        raise JobFailure("invalid-fpga-constraint", "Quartus settings require assigned ports and an unused pin policy.")
    return [*settings.values(), unused], ports


def _timing(ctx, ports):
    lines = _constraint_lines(ctx, "timing.sdc")
    if not lines or lines[-1] != "derive_clock_uncertainty":
        raise JobFailure("invalid-fpga-constraint", "Quartus timing constraints are incomplete.")
    clocks, clock_ports, assignments = set(), set(), []
    for line in lines[:-1]:
        match = _CLOCK.fullmatch(line)
        if not match:
            raise JobFailure("invalid-fpga-constraint", "Unexpected Quartus timing constraint.")
        name, period, port = match.groups()
        if name in clocks or port in clock_ports or port not in ports or not 0.001 <= float(period) <= 1_000_000_000:
            raise JobFailure("invalid-fpga-constraint", "Invalid or repeated Quartus clock.")
        clocks.add(name)
        clock_ports.add(port)
        assignments.append(f"create_clock -name {name} -period {period} [get_ports {{{port}}}]")
    return [*assignments, "derive_clock_uncertainty"]


def build(ctx, parameters):
    if not ctx.user_confirmed:
        raise JobFailure("confirmation-required", "Review a matching Quartus build plan before submitting.")
    if not isinstance(parameters, dict) or set(parameters) - {"language", "sources", "target", "binarySof"}:
        raise JobFailure("invalid-fpga-parameter", "Quartus accepts only documented build parameters.")
    target = parameters.get("target", "de0-cv")
    if not isinstance(target, str) or target not in _BUILD_TARGETS:
        raise JobFailure("unsupported-fpga-device", "Select a supported Quartus target.")
    family, device, extension = _BUILD_TARGETS[target]
    binary_sof = parameters.get("binarySof", False)
    if type(binary_sof) is not bool or "binarySof" in parameters and extension != "sof":
        raise JobFailure("invalid-fpga-parameter", "Binary artifact transport requires a Quartus SOF target.")
    language = _text(parameters, "language", r"verilog|vhdl")
    sources = _hdl_sources(ctx, parameters, language)
    if set(ctx.input_files) != set(parameters["sources"]) | {"pins.qsf", "timing.sdc"}:
        raise JobFailure("invalid-fpga-parameter", "Quartus accepts only the listed sources and pin and timing constraints.")
    max7000 = target in MAX7000_DEVICES
    pins, ports = _pins(ctx, max7000)
    timing = _timing(ctx, ports)
    tool = ctx.tool("quartus_sh")
    directory = ctx.working_directory
    lines = [
        f'set_global_assignment -name FAMILY "{family}"',
        f"set_global_assignment -name DEVICE {device}",
        "set_global_assignment -name TOP_LEVEL_ENTITY logisim_fpga_top",
        "set_global_assignment -name PROJECT_OUTPUT_DIRECTORY quartus-output",
        "set_global_assignment -name SDC_FILE timing.sdc",
        "set_global_assignment -name NUM_PARALLEL_PROCESSORS 1",
    ]
    assignment = "VHDL_FILE" if language == "vhdl" else "SYSTEMVERILOG_FILE"
    for source in sources:
        path = source.resolve().relative_to(directory).as_posix()
        lines.append(f"set_global_assignment -name {assignment} {{{path}}}")
    (directory / "build.qpf").write_text('PROJECT_REVISION = "build"\n', encoding="utf-8")
    (directory / "build.qsf").write_text("\n".join(lines + pins) + "\n", encoding="utf-8")
    (directory / "timing.sdc").write_text("\n".join(timing) + "\n", encoding="utf-8")
    _execute(ctx, "quartus-compile", [tool, "--flow", "compile", "build"])
    ctx.check_cancelled()
    bitstream = directory / "quartus-output" / f"build.{extension}"
    if not bitstream.is_file() or bitstream.stat().st_size == 0:
        raise JobFailure("fpga-missing-artifact", f"Quartus did not produce a nonempty .{extension} file.")
    if bitstream.stat().st_size > fpga_sof.INLINE_ARTIFACT_BYTES:
        if not binary_sof:
            raise JobFailure("output-limit", "The Quartus programming file exceeds the Mobile vendor artifact limit.")
        if bitstream.is_symlink() or not bitstream.resolve().is_relative_to(directory):
            raise JobFailure("invalid-artifact", "The Quartus SOF must remain inside the job directory.")
        artifact = fpga_sof.descriptor(bitstream, device)
        part = {"part": device}
    else:
        if max7000:
            validate_pof(bitstream, device)
        artifact = ctx.artifact(bitstream, name=f"design.{extension}")
        part = {}
    return {
        "synthesized": True, "placedAndRouted": True, "bitstreamGenerated": True,
        "programmed": False, "hardwareVerified": False, "flow": "quartus", "top": "logisim_fpga_top",
        "artifacts": [artifact],
        **part,
    }


def register(registry):
    registry.register(Operation(
        "fpga-quartus-build", build, required_tools=("quartus_sh",), requires_confirmation=True,
        description="Compile an approved device design with host-configured Quartus and return its programming file.",
    ))
