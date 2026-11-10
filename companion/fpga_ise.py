"""Xilinx ISE bitstream and JEDEC generation for the approved board profiles."""
import re

from companion.api import JobFailure, Operation
from companion.fpga import _execute, _hdl_sources, _quote, _text


OPERATION = "fpga-ise-build"
CPLD_OPERATION = "fpga-ise-cpld-build"
_TOOLS = ("xst", "ngdbuild", "map", "par", "bitgen")
_CPLD_TOOLS = ("xst", "ngdbuild", "cpldfit", "hprep6")
_TOP = "logisim_fpga_top"
_PARTS = {
    "basys2": "XC3S100E-CP132-4",
    "reptar-spartan-6": "XC6SLX150T-FGG900-3",
    "digilent-spartan-3e-starter": "XC3S500E-FG320-5",
    "gecko4led": "XC3S5000-FG676-5",
    "microlabtle": "XC2C64-VQ44-5",
}
_TWO_LETTER_PIN_PROFILES = {"reptar-spartan-6", "gecko4led"}
_NET = r"fpga_p[0-9]{1,4}_b[0-9]{1,4}"
_LOCATION = re.compile(
    rf'NET "(?P<net>{_NET})" LOC = "(?P<pin>(?P<row>[A-Z]{{1,2}})[0-9]{{1,2}})"'
    r'(?: \| IOSTANDARD = [A-Z0-9_]{1,32})?(?: \| DRIVE = [0-9]{1,3})?'
    r'(?: \| PULLUP| \| PULLDOWN)?;'
)
_CLOCK = re.compile(rf'NET "(?P<net>{_NET})" TNM_NET = "clock_(?P=net)";')
_TIMESPEC = re.compile(
    rf'TIMESPEC "TS_(?P<net>{_NET})" = PERIOD "clock_(?P=net)"'
    r' [0-9]+(?:\.[0-9]+)?(?:[Ee][+-]?[0-9]+)? ns HIGH 50%;'
)


def _check_constraints(path, profile):
    if path.stat().st_size > 1_000_000:
        raise JobFailure("fpga-constraint-limit", "The constraint file exceeds its size limit.")
    try:
        lines = path.read_text(encoding="ascii").splitlines()
    except UnicodeError as error:
        raise JobFailure("invalid-fpga-constraint", "ISE constraints must be ASCII text.") from error
    nets, pins, clocks, periods = set(), set(), set(), set()
    for line in lines:
        if match := _LOCATION.fullmatch(line):
            net, pin = match["net"], match["pin"]
            if net not in nets and pin not in pins and (profile in _TWO_LETTER_PIN_PROFILES or len(match["row"]) == 1):
                nets.add(net)
                pins.add(pin)
                continue
        elif match := _CLOCK.fullmatch(line):
            net = match["net"]
            if net in nets and net not in clocks:
                clocks.add(net)
                continue
        elif match := _TIMESPEC.fullmatch(line):
            net = match["net"]
            if net in clocks and net not in periods:
                periods.add(net)
                continue
        raise JobFailure("invalid-fpga-constraint", f"Expected generated pin and clock constraints for {profile}.")
    if clocks != periods:
        raise JobFailure("invalid-fpga-constraint", "Every ISE clock group requires one period constraint.")


def _require_file(path):
    if not path.is_file() or path.stat().st_size == 0:
        raise JobFailure("fpga-missing-artifact", f"The ISE stage did not produce {path.name}.")


def _translate(ctx, parameters, profile, tool_names, translated_name):
    language = _text(parameters, "language", r"verilog|vhdl")
    sources = _hdl_sources(ctx, parameters, language)
    constraint = ctx.input_path("pins.ucf")
    _check_constraints(constraint, profile)
    tools = {name: ctx.tool(name) for name in tool_names}
    work = ctx.working_directory
    project = work / "build.prj"
    project.write_text("".join(f"{language} work {_quote(path.resolve().relative_to(work))}\n" for path in sources), encoding="ascii")
    (work / "build.xst").write_text(
        f"run -top {_TOP} -ofn synthesized.ngc -ofmt NGC -ifn build.prj -ifmt mixed -p {_PARTS[profile]}\n",
        encoding="ascii",
    )
    log = work / "xst.log"
    try:
        _execute(ctx, "synthesize", [tools["xst"], "-ifn", "build.xst", "-ofn", log.name])
    finally:
        # XST records diagnostics in its own log even when the process exits unsuccessfully.
        if log.is_file():
            with log.open("rb") as stream:
                stream.seek(max(0, log.stat().st_size - 8192))
                ctx.emit("xst-log", stream.read().decode("utf-8", errors="replace"))
    _require_file(work / "synthesized.ngc")
    _execute(ctx, "translate", [tools["ngdbuild"], "-intstyle", "ise", "-uc", str(constraint),
                                "synthesized.ngc", translated_name])
    _require_file(work / translated_name)
    return tools


def build(ctx, parameters):
    if not isinstance(parameters, dict) or set(parameters) - {"language", "sources", "profile"}:
        raise JobFailure("invalid-fpga-parameter", "ISE accepts an approved profile, one HDL language, source names and pins.ucf.")
    profile = _text(parameters, "profile", r"basys2|reptar-spartan-6|digilent-spartan-3e-starter|gecko4led", "basys2")
    work = ctx.working_directory
    tools = _translate(ctx, parameters, profile, _TOOLS, "translated.ngd")
    _execute(ctx, "map", [tools["map"], "-intstyle", "ise", "-o", "mapped.ncd", "translated.ngd"])
    _require_file(work / "mapped.ncd")
    _require_file(work / "mapped.pcf")
    _execute(ctx, "place-route", [tools["par"], "-w", "-intstyle", "ise", "-ol", "high",
                                  "mapped.ncd", "routed.ncd", "mapped.pcf"])
    _require_file(work / "routed.ncd")
    bitstream = ctx.output_path("design.bit")
    bitgen = [tools["bitgen"], "-w"]
    if profile == "gecko4led":
        bitgen.extend(["-g", "UnusedPin:PULLDOWN"])
    _execute(ctx, "pack", bitgen + ["-g", "StartupClk:CCLK", "routed.ncd", str(bitstream)])
    _require_file(bitstream)
    ctx.check_cancelled()
    return {"synthesized": True, "placedAndRouted": True, "bitstreamGenerated": True,
            "programmed": False, "hardwareVerified": False, "flow": "ise", "top": _TOP, "profile": profile,
            "artifacts": [ctx.artifact(bitstream)]}


def build_cpld(ctx, parameters):
    if not ctx.user_confirmed:
        raise JobFailure("confirmation-required", "ISE requires an approved CPLD build plan.")
    if not isinstance(parameters, dict) or set(parameters) != {"language", "sources"}:
        raise JobFailure("invalid-fpga-parameter", "CPLD builds accept one HDL language, source names and pins.ucf.")
    work = ctx.working_directory
    tools = _translate(ctx, parameters, "microlabtle", _CPLD_TOOLS, "logisim.ngd")
    _execute(ctx, "cpld-fit", [tools["cpldfit"], "-p", "XC2C64-5-VQ44", "-intstyle", "ise",
                               "-terminate", "float", "-loc", "on", "-log", "logisim_cpldfit.log", "logisim.ngd"])
    _require_file(work / "logisim.vm6")
    _execute(ctx, "generate-jedec", [tools["hprep6"], "-i", "logisim.vm6"])
    generated = work / "logisim.jed"
    _require_file(generated)
    ctx.check_cancelled()
    return {"synthesized": True, "placedAndRouted": True, "bitstreamGenerated": True,
            "programmed": False, "hardwareVerified": False, "flow": "ise", "top": _TOP,
            "artifacts": [ctx.artifact(generated, "design.jed")]}


def register(registry):
    registry.register(Operation(OPERATION, build, required_tools=_TOOLS, requires_confirmation=True,
                                description="Generate a BASYS2, Reptar Spartan-6, Spartan-3E Starter, or GECKO4LED bitstream with Xilinx ISE."))
    registry.register(Operation(CPLD_OPERATION, build_cpld, required_tools=_CPLD_TOOLS, requires_confirmation=True,
                                description="Generate a JEDEC file for MICROLABTLE using Xilinx ISE on the host."))
