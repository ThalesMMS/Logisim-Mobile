"""Explicit VHDL and BLIF co-simulation operations for the local companion.

Importing this module never starts a compiler or executes a circuit. Each step
replays a bounded deterministic trace in a fresh working directory. The source
and generic values remain immutable for the lifetime of a session.
"""

from __future__ import annotations

from dataclasses import dataclass, field
import hashlib
from pathlib import Path
import re
import selectors
import shutil
import subprocess
import tempfile
import threading
import time
from typing import Any
import uuid


MAX_SOURCE_BYTES = 128 * 1024
MAX_PORTS = 128
MAX_WIDTH = 256
MAX_STEPS = 256
MAX_TIME_NS = 1_000_000
MAX_SESSIONS = 16
MAX_OUTPUT_BYTES = 64 * 1024
PHASE_TIMEOUT_SECONDS = 10
IDENTIFIER = re.compile(r"[A-Za-z](?:[A-Za-z0-9]|_(?=[A-Za-z0-9]))*\Z")
STD_LOGIC = frozenset("UX01ZWLH-")
MOBILE_LOGIC = str.maketrans({"U": "?", "X": "E", "Z": "?", "W": "?", "L": "0", "H": "1", "-": "?"})
# These constructs grant host access or break deterministic replay. This is a
# deliberately limited source policy, not a general VHDL parser or OS sandbox.
FORBIDDEN_WORDS = frozenset({
    "file", "file_open", "file_close", "foreign", "textio", "std_logic_textio",
    "readline", "writeline", "getenv", "environment", "impure", "access",
})
ALLOWED_PACKAGES = frozenset({"ieee.std_logic_1164", "ieee.numeric_std", "ieee.numeric_bit", "std.standard"})
# Keywords determine whether an apostrophe starts a character literal or is
# the tick in an attribute/qualified expression such as std_logic'('1').
VHDL_KEYWORDS = frozenset("""
abs access after alias all and architecture array assert attribute begin block
body buffer bus case component configuration constant context cover default
disconnect downto else elsif end entity exit file for force function generate
generic group guarded if impure in inertial inout is label library linkage
literal loop map mod nand new next nor not null of on open or others out
package parameter port postponed procedure process protected pure range record
register reject release rem report return reverse_range rol ror select severity
shared signal sla sll sra srl subtype then to transport type unaffected units
until use variable wait when while with xnor xor
""".split())
# Fixed cells used by the desktop BLIF template. Their initial state and
# asynchronous set/reset priority follow DenseLogicCircuitBuilder. Generic
# .names/.latch BLIF remains governed by Yosys's standard BLIF interpretation.
DESKTOP_BLIF_CELLS = """
module NOT(input A, output Y); assign Y = ~(A === 1'b1); endmodule
module BUF(input A, output Y); assign Y = A; endmodule
module BUS(input A, B, output Y); assign Y = A; assign Y = B; endmodule
module TRIS(input A, B, output Y); assign Y = (B === 1'b1) ? A : 1'bz; endmodule
module TRISI(input A, B, output Y); assign Y = (B !== 1'b1) ? A : 1'bz; endmodule
module AND(input A, B, output Y); assign Y = (A === 1'b1) & (B === 1'b1); endmodule
module OR(input A, B, output Y); assign Y = (A === 1'b1) | (B === 1'b1); endmodule
module XOR(input A, B, output Y); assign Y = (A === 1'b1) ^ (B === 1'b1); endmodule
module NAND(input A, B, output Y); assign Y = ~((A === 1'b1) & (B === 1'b1)); endmodule
module NOR(input A, B, output Y); assign Y = ~((A === 1'b1) | (B === 1'b1)); endmodule
module NXOR(input A, B, output Y); assign Y = ~((A === 1'b1) ^ (B === 1'b1)); endmodule
module ANDNOT(input A, B, output Y); assign Y = (A === 1'b1) & (B !== 1'b1); endmodule
module ORNOT(input A, B, output Y); assign Y = (A === 1'b1) | (B !== 1'b1); endmodule
module MUX(input A, B, S, output Y); assign Y = (S === 1'b1) ? B : ((S === 1'b0) ? A : 1'bz); endmodule
module DFF(input C, D, output reg Q); initial Q = 0; always @(posedge C) Q <= (D === 1'b1); endmodule
module DFFSR(input C, D, S, R, output reg Q); initial Q = 0;
  always @(posedge C or posedge S or posedge R) if (R === 1'b1) Q <= 0; else if (S === 1'b1) Q <= 1; else Q <= (D === 1'b1);
endmodule
module DLATCH(input D, E, output reg Q); initial Q = 0; always @* if (E === 1'b1) Q <= (D === 1'b1); endmodule
module PULLUP(output Y); pullup pull(Y); endmodule
module PULLDOWN(output Y); pulldown pull(Y); endmodule
"""


class HdlFailure(Exception):
    def __init__(self, code: str, message: str, details: str = ""):
        super().__init__(message)
        self.code, self.message, self.details = code, message, details


@dataclass(frozen=True)
class Port:
    name: str
    direction: str
    width: int
    kind: str


@dataclass(frozen=True)
class Design:
    source: str
    backend: str
    top_level: str
    ports: tuple[Port, ...]
    parameters: tuple[tuple[str, str], ...]
    digest: str


@dataclass(frozen=True)
class Step:
    inputs: tuple[tuple[str, str], ...]
    advance_ns: int


@dataclass
class Session:
    design: Design
    steps: tuple[Step, ...] = ()
    state: str = "ready"
    cancel_event: threading.Event = field(default_factory=threading.Event)
    lock: threading.Lock = field(default_factory=threading.Lock)


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    stdout: str
    stderr: str


def _identifier(value: Any, label: str) -> str:
    if not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
        raise HdlFailure("invalid_request", f"{label} must be a basic VHDL identifier")
    return value


def _logic(value: Any, width: int) -> str:
    if not isinstance(value, str):
        raise HdlFailure("invalid_request", "Logic values must be strings")
    value = value.upper().replace("?", "U").replace("E", "X")
    if len(value) != width or not set(value) <= STD_LOGIC:
        raise HdlFailure("invalid_request", f"Expected {width} IEEE std_logic bits")
    return value


def map_std_logic(value: str) -> str:
    """Preserve defined strengths, map contention X to error and others unknown."""
    if not value or not set(value) <= STD_LOGIC:
        raise HdlFailure("invalid_output", "Simulator returned invalid std_logic data")
    return value.translate(MOBILE_LOGIC)


def _literal(value: Any) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int) and -(2**31) <= value < 2**31:
        return str(value)
    if isinstance(value, str):
        # Source imports commonly preserve generic literals as text. Do not
        # accept expressions, arbitrary strings, or compiler option fragments.
        if re.fullmatch(r"[+-]?[0-9]{1,10}", value):
            return _literal(int(value))
        # Time generics travel in femtoseconds with their unit, as Evolution's generator writes them.
        time = re.fullmatch(r"([+-]?[0-9]{1,10}) fs", value)
        if time and -(2**31) <= int(time.group(1)) < 2**31:
            return f"{int(time.group(1))} fs"
        if value.lower() in {"true", "false"}:
            return value.lower()
        if re.fullmatch(r"'[UX01ZWLH-]'", value.upper()):
            return value.upper()
        if re.fullmatch(r'"[UX01ZWLH-]{1,256}"', value.upper()):
            return value.upper()
    raise HdlFailure("unsupported_parameter", "Generics require a bounded integer, time in fs, boolean, or quoted std_logic literal")


def _vhdl_lexical(source: str) -> str:
    """Remove literal/comment contents without hiding subsequent VHDL tokens."""
    output, offset = [], 0
    # After an identifier, closing bracket/parenthesis, or ALL, a tick cannot
    # start a character literal. Character literals themselves are identifiers
    # for this rule, including the three-apostrophe literal for an apostrophe.
    can_qualify = False
    while offset < len(source):
        char = source[offset]
        if char.isspace():
            output.append(char)
            offset += 1
        elif source.startswith("--", offset):
            end = source.find("\n", offset + 2)
            offset = len(source) if end < 0 else end
            output.append(" ")
        elif source.startswith("/*", offset):
            end = source.find("*/", offset + 2)
            if end < 0:
                raise HdlFailure("unsupported_source", "Unterminated VHDL delimited comment")
            output.append(" ")
            offset = end + 2
        elif char in {'"', '%'}:
            delimiter = char
            offset += 1
            while offset < len(source):
                if source[offset] in "\r\n":
                    raise HdlFailure("unsupported_source", "Unterminated VHDL string literal")
                if source[offset] == delimiter:
                    offset += 1
                    if offset < len(source) and source[offset] == delimiter:
                        offset += 1
                    else:
                        break
                else:
                    offset += 1
            else:
                raise HdlFailure("unsupported_source", "Unterminated VHDL string literal")
            output.append(" ")
            can_qualify = False
        elif char == "\\":
            raise HdlFailure("unsupported_source", "Extended VHDL names are not supported")
        elif (char == "'" and not can_qualify and offset + 2 < len(source)
              and source[offset + 2] == "'" and source[offset + 1] not in "\r\n"):
            output.append(" ")
            offset += 3
            can_qualify = True
        elif char.isalpha():
            end = offset + 1
            while end < len(source) and (source[end].isalnum() or source[end] == "_"):
                end += 1
            word = source[offset:end]
            output.append(word)
            lowered = word.lower()
            can_qualify = lowered == "all" or lowered not in VHDL_KEYWORDS
            offset = end
        else:
            output.append(char)
            can_qualify = char in ")]"
            offset += 1
    return "".join(output)


def _source_policy(source: str) -> None:
    # The original source stays intact. A token-aware copy prevents quotes in
    # characters or comments from swallowing a following host-I/O declaration.
    lexical = _vhdl_lexical(source)
    if "\\" in lexical or "<<" in lexical or "`" in lexical:
        raise HdlFailure("unsupported_source", "Extended/external names and preprocessing are not supported")
    words = set(re.findall(r"[A-Za-z][A-Za-z0-9_]*", lexical.lower()))
    denied = sorted(words & FORBIDDEN_WORDS)
    if denied:
        raise HdlFailure("unsupported_source", f"Host I/O and nondeterministic source constructs are disabled: {', '.join(denied)}")
    for match in re.finditer(r"\blibrary\s+([^;]+);", lexical, re.IGNORECASE):
        if not {x.strip().lower() for x in match.group(1).split(",")} <= {"ieee", "std", "work"}:
            raise HdlFailure("unsupported_source", "Only work, ieee and std libraries are supported")
    # Also inspect selected names, so fully qualified std.textio access cannot
    # bypass the policy by omitting a use clause.
    for match in re.finditer(r"\b(ieee|std)\s*\.\s*([A-Za-z][A-Za-z0-9_]*)", lexical, re.IGNORECASE):
        package = f"{match.group(1).lower()}.{match.group(2).lower()}"
        if package not in ALLOWED_PACKAGES:
            raise HdlFailure("unsupported_source", f"Package {package} is outside the deterministic source policy")


def parse_design(request: dict[str, Any]) -> Design:
    source = request.get("source")
    if not isinstance(source, str) or not source or len(source.encode("utf-8")) > MAX_SOURCE_BYTES:
        raise HdlFailure("invalid_source", f"source must contain at most {MAX_SOURCE_BYTES} UTF-8 bytes")
    if "\0" in source:
        raise HdlFailure("invalid_source", "source contains a NUL byte")
    backend = request.get("backend", "vhdl")
    if backend not in {"vhdl", "blif"}:
        raise HdlFailure("unsupported_backend", "backend must be vhdl or blif")
    if backend == "vhdl":
        _source_policy(source)
    else:
        # BLIF is data accepted by one fixed read_blif command. In particular,
        # external includes, scripts and black-box executable models are absent.
        allowed = {".model", ".inputs", ".outputs", ".names", ".latch", ".end", ".conn", ".subckt", ".gate"}
        directives = re.findall(r"^\s*(\.[^\s]+)", source, re.MULTILINE)
        if not directives or any(word not in allowed for word in directives):
            raise HdlFailure("unsupported_source", "BLIF supports model, inputs, outputs, names, latch, conn, subckt and gate directives")
    top = _identifier(request.get("topLevel"), "topLevel")
    raw_ports = request.get("ports")
    if not isinstance(raw_ports, list) or not 1 <= len(raw_ports) <= MAX_PORTS:
        raise HdlFailure("invalid_request", f"ports must contain 1 to {MAX_PORTS} ports")
    ports = []
    seen = set()
    for raw in raw_ports:
        if not isinstance(raw, dict):
            raise HdlFailure("invalid_request", "Each port must be an object")
        direction, width = raw.get("direction"), raw.get("width", 1)
        kind = raw.get("kind", "std_logic" if width == 1 else "std_logic_vector")
        if direction not in {"in", "out"} and (direction != "inout" or backend != "vhdl"):
            raise HdlFailure("unsupported_port", "Supported modes are in and out, plus inout for VHDL")
        if type(width) is not int or not 1 <= width <= MAX_WIDTH:
            raise HdlFailure("invalid_request", f"Port width must be between 1 and {MAX_WIDTH}")
        if kind not in {"std_logic", "std_logic_vector"} or (kind == "std_logic" and width != 1):
            raise HdlFailure("unsupported_port", "Ports must use std_logic or std_logic_vector")
        name = raw.get("name")
        single_blif_bit = backend == "blif" and width == 1 and isinstance(name, str)
        binding = _identifier(name.removesuffix("[0]") if single_blif_bit else name, "port name").lower()
        if binding in seen:
            raise HdlFailure("invalid_request", "Port names must be unique ignoring case")
        seen.add(binding)
        ports.append(Port(name, direction, width, kind))
    parameters = request.get("parameters", {})
    if not isinstance(parameters, dict) or len(parameters) > 32:
        raise HdlFailure("invalid_request", "parameters must be an object containing at most 32 generics")
    if backend == "blif" and parameters:
        raise HdlFailure("unsupported_parameter", "BLIF has no top-level generic parameter override")
    generics = tuple((_identifier(name, "generic name"), _literal(value)) for name, value in parameters.items())
    if len({name.lower() for name, _ in generics}) != len(generics):
        raise HdlFailure("invalid_request", "Generic names must be unique ignoring case")
    return Design(source, backend, top, tuple(ports), generics, hashlib.sha256(source.encode()).hexdigest())


def parse_step(design: Design, request: dict[str, Any]) -> Step:
    inputs = request.get("inputs")
    expected = {p.name: p for p in design.ports if p.direction != "out"}
    if not isinstance(inputs, dict) or set(inputs) != set(expected):
        raise HdlFailure("invalid_request", "inputs must supply every in and inout port by its declared name")
    advance = request.get("advanceNs", 1)
    if type(advance) is not int or not 1 <= advance <= MAX_TIME_NS:
        raise HdlFailure("invalid_request", f"advanceNs must be between 1 and {MAX_TIME_NS}")
    values = tuple((name, _logic(inputs[name], port.width)) for name, port in expected.items())
    for name, value in values:
        if expected[name].direction == "inout" and not set(value) <= set("01Z"):
            raise HdlFailure("unrepresentable_inout_input", f"VHDL inout {name} requires a strong external drive of 0 or 1, or a released bus (Z)")
    return Step(values, advance)


def _inout_contribution(name: str, external: str, resolved: str) -> str:
    # A matching external driver masks the distinction between a released
    # DUT and one driving the same bit. Neither requires a Mobile driver.
    contribution = []
    for incoming, sampled in zip(external, resolved):
        if sampled == "X":
            contribution.append("E")
        elif incoming == sampled:
            contribution.append("?")
        elif incoming == "Z" and sampled in "01":
            contribution.append(sampled)
        else:
            raise HdlFailure("unrepresentable_inout_output",
                             f"VHDL inout {name} sampled {sampled} with external drive {incoming}; Mobile cannot represent it")
    return "".join(contribution)


def render_testbench(design: Design, steps: tuple[Step, ...], token: str) -> str:
    signals, mapping = [], []
    indices = {port.name: index for index, port in enumerate(design.ports)}
    for index, port in enumerate(design.ports):
        datatype = "std_logic" if port.kind == "std_logic" else f"std_logic_vector({port.width - 1} downto 0)"
        signals.append(f"  signal p{index}: {datatype};")
        mapping.append(f"{port.name} => p{index}")
    generic_map = ""
    if design.parameters:
        generic_map = " generic map (" + ", ".join(f"{name} => {value}" for name, value in design.parameters) + ")"
    body = []
    ports = {port.name: port for port in design.ports}
    for step in steps:
        for name, value in step.inputs:
            literal = f"'{value}'" if ports[name].kind == "std_logic" else f'"{value}"'
            body.append(f"    p{indices[name]} <= {literal};")
        body.append(f"    wait for {step.advance_ns} ns;")
        # Sample in a postponed process after every delta cycle at the target
        # physical time. The trigger is assigned below in the driving process.
    if steps:
        body.append("    sample <= true;")
    body.append("    wait;")
    reports = []
    for index, port in enumerate(design.ports):
        if port.direction != "in":
            expression = f"std_logic'image(p{index})(2 to 2)" if port.kind == "std_logic" else f"to_string(p{index})"
            reports.append(f'      report "{token}:{index}:" & {expression} severity note;')
    reports.append(f'      report "{token}:done" severity note;')
    return "\n".join([
        "library ieee; use ieee.std_logic_1164.all;",
        "entity mobile_cosim_tb is end entity;",
        "architecture test of mobile_cosim_tb is",
        *signals, "  signal sample: boolean := false;", "begin",
        f"  dut: entity work.{design.top_level}{generic_map} port map ({', '.join(mapping)});",
        "  driver: process begin", *body, "  end process;",
        "  sampler: postponed process(sample) begin", "    if sample then",
        *reports, "    end if;", "  end process;", "end architecture;", "",
    ])


def render_verilog_testbench(design: Design, steps: tuple[Step, ...], token: str, *, desktop_drive: bool) -> str:
    lines = ["`timescale 1ns/1ps", "module mobile_cosim_tb;"]
    indices = {port.name: index for index, port in enumerate(design.ports)}
    ports = {port.name: port for port in design.ports}
    for index, port in enumerate(design.ports):
        datatype = "reg" if port.direction == "in" else "wire"
        lines.append(f"  {datatype} [{port.width - 1}:0] p{index};")
    # Yosys -wideports merges a lone name[0] into the Verilog formal name.
    mapping = ", ".join(f".{port.name.removesuffix('[0]')}(p{index})" for index, port in enumerate(design.ports))
    lines += [f"  {design.top_level} dut({mapping});", "  initial begin"]
    no_drive = "z" if desktop_drive else "x"
    to_verilog = str.maketrans({"U": no_drive, "X": "x", "Z": "z", "W": no_drive, "L": "0", "H": "1", "-": no_drive})
    for step in steps:
        for name, value in step.inputs:
            lines.append(f"    p{indices[name]} = {ports[name].width}'b{value.translate(to_verilog)};")
        lines.append(f"    #{step.advance_ns};")
    if steps:
        for index, port in enumerate(design.ports):
            if port.direction == "out":
                lines.append(f'    $strobe("{token}:{index}:%b", p{index});')
        lines.append(f'    $strobe("{token}:done");')
        # Finish one precision unit after the sample, allowing all NBA and
        # strobe events in the target time slot to complete.
        lines.append("    #0.001;")
    lines += ["    $finish;", "  end", "endmodule", ""]
    return "\n".join(lines)


def desktop_blif_library(source: str) -> tuple[str, bool]:
    """Supply referenced cells and recognize the fixed desktop drive profile."""
    models = re.findall(r"^\s*\.model\s+(\S+)", source, re.MULTILINE)
    references = set(re.findall(r"^\s*\.(?:subckt|gate)\s+(\S+)", source, re.MULTILINE))
    modules = re.findall(r"module\s+(\w+)\b(.*?endmodule)", DESKTOP_BLIF_CELLS, re.DOTALL)
    supplied = {name: f"module {name}{body}" for name, body in modules if name in references and name not in models}
    # Generic truth tables and latches can produce X without a bus conflict.
    tables = re.findall(r"^\s*\.names[ \t]+([^\n#]*)", source, re.MULTILINE)
    desktop_drive = (
        len(models) == 1
        and bool(references & {"BUS", "TRIS", "TRISI"})
        and references == supplied.keys()
        and not re.search(r"^\s*\.latch\b", source, re.MULTILINE)
        and all(table.strip() in {"$true", "$false", "$undef"} for table in tables)
    )
    return "\n".join(supplied.values()), desktop_drive


class LocalContext:
    """Standalone runner used by tests and an explicitly invoked local client."""

    def __init__(self, working_directory: Path, cancelled: threading.Event, executable: str | None = None):
        self.working_directory = working_directory
        self.cancelled = cancelled
        self.executable = executable

    def tool(self, name: str) -> str:
        value = self.executable if name == "nvc" and self.executable else shutil.which(name)
        if value is None:
            raise HdlFailure("tool_unavailable", f"The local companion requires {name}")
        return str(Path(value).resolve())

    def check_cancelled(self) -> None:
        if self.cancelled.is_set():
            raise HdlFailure("cancelled", "Co-simulation was cancelled")

    def run(self, argv: list[str], timeout_seconds: float, stdin: str | None = None) -> CommandResult:
        self.check_cancelled()
        # No shell, no inherited tool-specific options or source search paths.
        env = {"PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "HOME": str(self.working_directory), "TMPDIR": str(self.working_directory), "LANG": "C"}
        process = subprocess.Popen(argv, cwd=self.working_directory, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        chunks: dict[str, bytearray] = {"stdout": bytearray(), "stderr": bytearray()}
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ, "stdout")
        selector.register(process.stderr, selectors.EVENT_READ, "stderr")
        deadline, size = time.monotonic() + timeout_seconds, 0
        try:
            while selector.get_map():
                self.check_cancelled()
                if time.monotonic() > deadline:
                    raise HdlFailure("timeout", "Simulator exceeded its wall-clock time limit")
                for key, _ in selector.select(timeout=0.02):
                    data = key.fileobj.read1(4096)
                    if not data:
                        selector.unregister(key.fileobj)
                        continue
                    size += len(data)
                    if size > MAX_OUTPUT_BYTES:
                        raise HdlFailure("output_limit", "Simulator exceeded its diagnostic output limit")
                    chunks[key.data].extend(data)
            process.wait(timeout=max(0.01, deadline - time.monotonic()))
            return CommandResult(process.returncode, chunks["stdout"].decode("utf-8", "replace"), chunks["stderr"].decode("utf-8", "replace"))
        finally:
            selector.close()
            if process.poll() is None:
                process.kill()
            process.wait()
            process.stdout.close()
            process.stderr.close()


class _SessionContext:
    def __init__(self, context: Any, event: threading.Event):
        self.context, self.event = context, event
        self.working_directory = context.working_directory

    def tool(self, name: str) -> str:
        return self.context.tool(name)

    def check_cancelled(self) -> None:
        self.context.check_cancelled()
        if self.event.is_set():
            raise HdlFailure("cancelled", "Co-simulation session was cancelled")

    def run(self, argv: list[str], timeout_seconds: float) -> Any:
        self.check_cancelled()
        # The companion job runner owns process cancellation. A session cancel
        # mirrors its event into that runner while this operation is active.
        return self.context.run(argv, timeout_seconds=timeout_seconds)


class HdlSimulationManager:
    def __init__(self, executable: str | None = None):
        self.executable = executable
        self.sessions: dict[str, Session] = {}
        self.lock = threading.Lock()
        self.active_contexts: dict[str, Any] = {}
        self.pending_starts = 0

    @staticmethod
    def _failure(error: Any, session_id: str | None = None) -> dict[str, Any]:
        result = {"ok": False, "status": "failed", "code": getattr(error, "code", "runtime_failure"), "message": str(error), "outputs": {}}
        if result["code"] == "cancelled":
            result["status"] = "cancelled"
        details = getattr(error, "details", "")
        if details:
            result["diagnostics"] = str(details)[:MAX_OUTPUT_BYTES]
        if session_id:
            result["sessionId"] = session_id
        return result

    def _execute(self, design: Design, steps: tuple[Step, ...], context: Any) -> dict[str, Any]:
        if design.backend == "blif":
            return self._execute_blif(design, steps, context)
        executable = context.tool("nvc")
        token = "MOBILE_" + uuid.uuid4().hex
        work = Path(context.working_directory)
        (work / "design.vhd").write_text(design.source, encoding="utf-8")
        (work / "mobile_cosim_tb.vhd").write_text(render_testbench(design, steps, token), encoding="utf-8")
        common = [executable, "--std=2008", "--messages=compact", "-H", "16m"]
        phases = [
            ("analysis_failed", ["-a", "design.vhd", "mobile_cosim_tb.vhd"]),
            ("elaboration_failed", ["-e", "-O0", "mobile_cosim_tb"]),
        ]
        if steps:
            total = sum(step.advance_ns for step in steps)
            phases.append(("simulation_failed", ["-r", "mobile_cosim_tb", "--exit-severity=error", "--stop-delta=1000", f"--stop-time={total}ns"]))
        diagnostics = []
        final = ""
        for code, args in phases:
            context.check_cancelled()
            result = context.run(common + args, timeout_seconds=PHASE_TIMEOUT_SECONDS)
            text = result.stdout + result.stderr
            diagnostics.append(text)
            if result.returncode != 0:
                raise HdlFailure(code, f"VHDL {code.removesuffix('_failed')} failed", text)
            final = text
        context.check_cancelled()
        if not steps:
            return {"outputs": {}, "diagnostics": "".join(diagnostics)[:MAX_OUTPUT_BYTES]}
        if token + ":done" not in final:
            raise HdlFailure("incomplete_sample", "Simulator stopped before the requested sample", final)
        outputs = {}
        inputs = dict(steps[-1].inputs)
        for index, port in enumerate(design.ports):
            if port.direction == "in":
                continue
            matches = re.findall(re.escape(token) + f":{index}:([UX01ZWLH-]+)", final)
            if len(matches) != 1 or len(matches[0]) != port.width:
                raise HdlFailure("invalid_output", f"Missing or invalid simulator output for {port.name}", final)
            raw = matches[0]
            logic = _inout_contribution(port.name, inputs[port.name], raw) if port.direction == "inout" else map_std_logic(raw)
            outputs[port.name] = {"stdLogic": raw, "logic": logic}
        # Internal report framing is not a user diagnostic.
        diagnostics = [line for line in "".join(diagnostics).splitlines() if token not in line]
        return {"outputs": outputs, "diagnostics": "\n".join(diagnostics)[:MAX_OUTPUT_BYTES]}

    def _execute_blif(self, design: Design, steps: tuple[Step, ...], context: Any) -> dict[str, Any]:
        yosys, iverilog = context.tool("yosys"), context.tool("iverilog")
        vvp = context.tool("vvp") if steps else None
        token = "MOBILE_" + uuid.uuid4().hex
        work = Path(context.working_directory)
        library, desktop_drive = desktop_blif_library(design.source)
        # Yosys can read a standalone comment after .names as a truth-table row.
        # Keep the original source and its digest, and preserve compiler line numbers.
        source = re.sub(r"(?m)^[ \t]*#[^\r\n]*", "", design.source)
        if desktop_drive:
            # An undriven $undef net yields Z instead of Yosys's constant X.
            source = re.sub(r"(?m)^[ \t]*\.names[ \t]+\$undef[ \t]*(?:#[^\n]*)?\r?$", "", source)
        (work / "design.blif").write_text(source, encoding="utf-8")
        (work / "desktop_cells.v").write_text(library, encoding="utf-8")
        (work / "mobile_cosim_tb.v").write_text(render_verilog_testbench(design, steps, token, desktop_drive=desktop_drive), encoding="utf-8")
        # The only substituted script token has already passed IDENTIFIER.
        script = f"read_blif -wideports design.blif; read_verilog -lib desktop_cells.v; hierarchy -check -top {design.top_level}; write_verilog -noattr design.v"
        phases = [
            ("analysis_failed", [yosys, "-Q", "-T", "-p", script]),
            ("elaboration_failed", [iverilog, "-g2012", "-s", "mobile_cosim_tb", "-o", "simulation.vvp", "design.v", "desktop_cells.v", "mobile_cosim_tb.v"]),
        ]
        if steps:
            phases.append(("simulation_failed", [vvp, "simulation.vvp"]))
        final = ""
        for code, argv in phases:
            context.check_cancelled()
            result = context.run(argv, timeout_seconds=PHASE_TIMEOUT_SECONDS)
            final = result.stdout + result.stderr
            if result.returncode != 0:
                raise HdlFailure(code, f"BLIF {code.removesuffix('_failed')} failed", final)
        context.check_cancelled()
        if not steps:
            return {"outputs": {}, "diagnostics": ""}
        if token + ":done" not in final:
            raise HdlFailure("incomplete_sample", "Simulator stopped before the requested sample", final)
        outputs = {}
        for index, port in enumerate(design.ports):
            if port.direction != "out":
                continue
            matches = re.findall(re.escape(token) + f":{index}:([01xz]+)", final)
            if len(matches) != 1 or len(matches[0]) != port.width:
                raise HdlFailure("invalid_output", f"Missing or invalid simulator output for {port.name}", final)
            raw = matches[0]
            std_logic = raw.translate(str.maketrans({"x": "X" if desktop_drive else "U", "z": "Z"}))
            outputs[port.name] = {"rawLogic": raw, "stdLogic": std_logic, "logic": map_std_logic(std_logic)}
        return {"outputs": outputs, "diagnostics": "\n".join(line for line in final.splitlines() if token not in line)[:MAX_OUTPUT_BYTES]}

    def handle_request(self, operation: str, request: dict[str, Any], context: Any = None) -> dict[str, Any]:
        session_id = request.get("sessionId") if isinstance(request, dict) else None
        if not isinstance(request, dict):
            return self._failure(HdlFailure("invalid_request", "Request must be an object"))
        try:
            if operation in {"hdl.validate", "hdl.start"}:
                design = parse_design(request)
                if operation == "hdl.start":
                    with self.lock:
                        if len(self.sessions) + self.pending_starts >= MAX_SESSIONS:
                            raise HdlFailure("session_limit", "Close or cancel an existing session before starting another")
                        self.pending_starts += 1
                try:
                    result = self._with_context(design, (), context, threading.Event())
                except Exception:
                    if operation == "hdl.start":
                        with self.lock:
                            self.pending_starts -= 1
                    raise
                result.update(ok=True, status="validated", sourceSha256=design.digest, backend=design.backend, simulator="nvc" if design.backend == "vhdl" else "yosys-iverilog", timeNs=0)
                if operation == "hdl.start":
                    session_id = uuid.uuid4().hex
                    with self.lock:
                        self.pending_starts -= 1
                        self.sessions[session_id] = Session(design)
                    result.update(status="ready", sessionId=session_id)
                return result
            if operation not in {"hdl.step", "hdl.reset", "hdl.cancel"}:
                raise HdlFailure("unknown_operation", "Unknown co-simulation operation")
            if not isinstance(session_id, str) or not re.fullmatch(r"[0-9a-f]{32}", session_id):
                raise HdlFailure("invalid_request", "sessionId must be a session identifier returned by hdl.start")
            with self.lock:
                session = self.sessions.get(session_id)
            if session is None:
                raise HdlFailure("unknown_session", "Co-simulation session does not exist")
            if operation == "hdl.cancel":
                session.cancel_event.set()
                session.state = "cancelled"
                with self.lock:
                    active = self.active_contexts.get(session_id)
                    self.sessions.pop(session_id, None)
                if active is not None:
                    active.cancelled.set()
                return {"ok": True, "status": "cancelled", "sessionId": session_id, "outputs": {}}
            if not session.lock.acquire(blocking=False):
                raise HdlFailure("session_busy", "Another operation is active for this session")
            try:
                if operation == "hdl.reset":
                    session.steps = ()
                    session.state = "ready"
                    session.cancel_event.clear()
                    return {"ok": True, "status": "ready", "sessionId": session_id, "outputs": {}, "timeNs": 0, "sourceSha256": session.design.digest}
                if session.state != "ready":
                    raise HdlFailure("session_failed", "Reset the session after a simulation failure")
                step = parse_step(session.design, request)
                steps = session.steps + (step,)
                if len(steps) > MAX_STEPS or sum(x.advance_ns for x in steps) > MAX_TIME_NS:
                    raise HdlFailure("trace_limit", "Session exceeds the step or simulation-time limit; reset to begin again")
                with self.lock:
                    if context is not None:
                        self.active_contexts[session_id] = context
                try:
                    result = self._with_context(session.design, steps, context, session.cancel_event)
                except Exception as error:
                    session.state = "failed"
                    return self._failure(error, session_id)
                finally:
                    with self.lock:
                        self.active_contexts.pop(session_id, None)
                if session.cancel_event.is_set():
                    return self._failure(HdlFailure("cancelled", "Co-simulation session was cancelled"), session_id)
                session.steps = steps
                result.update(ok=True, status="ready", sessionId=session_id, timeNs=sum(x.advance_ns for x in steps), step=len(steps), sourceSha256=session.design.digest)
                return result
            finally:
                session.lock.release()
        except Exception as error:
            return self._failure(error, session_id)

    def _with_context(self, design: Design, steps: tuple[Step, ...], context: Any, cancel_event: threading.Event) -> dict[str, Any]:
        if context is not None:
            return self._execute(design, steps, _SessionContext(context, cancel_event))
        with tempfile.TemporaryDirectory(prefix="logisim-hdl-") as directory:
            return self._execute(design, steps, LocalContext(Path(directory), cancel_event, self.executable))

    def close(self) -> None:
        with self.lock:
            for session in self.sessions.values():
                session.cancel_event.set()
            for context in self.active_contexts.values():
                context.cancelled.set()
            self.sessions.clear()


def register(registry: Any) -> None:
    """Register local-only actions; importing HDL never calls these handlers."""
    from companion.api import Operation

    manager = HdlSimulationManager()
    for name in ("hdl.validate", "hdl.start", "hdl.step", "hdl.reset", "hdl.cancel"):
        def handler(ctx: Any, parameters: dict[str, Any], operation: str = name) -> dict[str, Any]:
            return manager.handle_request(operation, parameters, context=ctx)
        # Reset/cancel never need a compiler, including after tool removal.
        alternatives = (("nvc",), ("yosys", "iverilog", "vvp")) if name in {"hdl.validate", "hdl.start", "hdl.step"} else ()
        registry.register(Operation(name=name, handler=handler, required_tools=(), tool_alternatives=alternatives, description="Explicit local VHDL or BLIF simulation", requires_confirmation=False))
    registry.on_shutdown(manager.close)
