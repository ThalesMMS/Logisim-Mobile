"""Explicit Tcl console and component jobs with optional isolated timer access."""
import base64
from functools import partial
import hashlib
import re
import secrets

from companion import host_isolation, tk_worker
from companion.api import JobFailure, Operation


MAX_SCRIPT_BYTES = 256 * 1024
MAX_SOURCE_BYTES = 1024 * 1024
LEGACY_FRAME_LIMIT = 256
MAX_FRAMES = 1024
EXTENDED_REPLAY_BYTES = 512 * 1024
MAX_PORTS = 512


def register(registry):
    registry.register(Operation("tcl-run", run_console, ("tclsh",),
                                "Evaluate an explicitly submitted Tcl source in a safe interpreter."))
    registry.register(Operation("tcl-step", run_components, ("tclsh",),
                                "Replay an explicitly submitted Tcl component input history."))
    registry.register(Operation("tcl-timed-run", partial(run_console, timed=True), ("tclsh-isolated",),
                                "Run an approved Tcl console source with timers in an isolated worker.",
                                requires_confirmation=True, isolated=True))
    registry.register(Operation("tcl-timed-step", partial(run_components, timed=True), ("tclsh-isolated",),
                                "Replay an approved Tcl component source with timers in an isolated worker.",
                                requires_confirmation=True, isolated=True))
    registry.register(Operation("tcl-tk-run", partial(run_console, timed=True, tk=True), ("bwrap",),
                                "Run an approved Tcl console source on a private Linux Tk display.",
                                requires_confirmation=True, isolated=True, isolation_profile=host_isolation.TK_PROFILE))
    registry.register(Operation("tcl-tk-live", partial(run_console, timed=True, tk=True, live=True), ("bwrap",),
                                "Interact with one approved private Linux Tk display for a bounded session.",
                                requires_confirmation=True, isolated=True, isolation_profile=host_isolation.TK_PROFILE))


def _integer(value, low, high, name):
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise JobFailure("invalid-parameters", f"{name} must be an integer from {low} to {high}.")
    return value


def _sources(ctx, parameters):
    script_name = parameters.get("script", "script.tcl")
    if not isinstance(script_name, str):
        raise JobFailure("invalid-parameters", "script must name one provided Tcl input.")
    source = ctx.input_path(script_name).read_bytes()
    if len(source) > MAX_SCRIPT_BYTES:
        raise JobFailure("source-limit", "The Tcl source exceeds 256 KiB.")
    try:
        source.decode("utf-8")
    except UnicodeDecodeError as error:
        raise JobFailure("invalid-source", "Tcl sources must use UTF-8.") from error
    sources = {}
    total = 0
    for name in sorted(ctx.input_files):
        data = ctx.input_path(name).read_bytes()
        total += len(data)
        if len(data) > MAX_SCRIPT_BYTES or total > MAX_SOURCE_BYTES or len(sources) >= 16:
            raise JobFailure("source-limit", "Provide at most 16 Tcl sources, each at most 256 KiB and 1 MiB in total.")
        try:
            data.decode("utf-8")
        except UnicodeDecodeError as error:
            raise JobFailure("invalid-source", f"Tcl source {name} is not UTF-8.") from error
        sources[name] = data.hex()
    return script_name, source, sources


def _prefix(sources, nonce, command_limit, timed, tk=False):
    removed_commands = "clock pid time timerate interp" if timed else "clock after update vwait pid time timerate interp"
    assignments = "\n".join(f"dict set ::sources {{{name}}} {{{value}}}" for name, value in sources.items())
    return r'''
set ::sources [dict create]
SOURCES
set ::outputBytes 0
set ::outputLimit 65536
CREATE_CHILD
interp limit $::child command -value COMMAND_LIMIT
proc encode {value} {
    binary scan [encoding convertto utf-8 $value] H* result
    return $result
}
proc safe_source {child args} {
    if {[llength $args] != 1} {error "source accepts one explicitly supplied UTF-8 input name"}
    set name [lindex $args 0]
    if {![dict exists $::sources $name]} {error "source input was not supplied: $name"}
    return [interp eval $child [encoding convertfrom utf-8 [binary format H* [dict get $::sources $name]]]]
}
proc safe_puts {args} {
    set newline 1
    if {[lindex $args 0] eq "-nonewline"} {set newline 0; set args [lrange $args 1 end]}
    if {[llength $args] == 1} {set channel stdout; set value [lindex $args 0]} elseif {[llength $args] == 2} {
        set channel [lindex $args 0]; set value [lindex $args 1]
    } else {error "puts expects text and an optional stdout/stderr channel"}
    if {$channel ne "stdout" && $channel ne "stderr"} {error "Only stdout and stderr are available"}
    incr ::outputBytes [expr {[string length [encoding convertto utf-8 $value]] + $newline}]
    if {$::outputBytes > $::outputLimit} {error "Tcl output limit exceeded"}
    if {$newline} {append value "\n"}
    puts "NONCE|$channel|[encode $value]"
}
interp alias $::child source {} safe_source $::child
interp alias $::child puts {} safe_puts
foreach command {REMOVED_COMMANDS} {
    if {[llength [interp eval $::child [list info commands $command]]]} {interp eval $::child [list rename $command {}]}
}
foreach name {::tcl::clock ::tcl::unsupported} {
    if {[interp eval $::child [list namespace exists $name]]} {interp eval $::child [list namespace delete $name]}
}
interp eval $::child {expr {srand(1)}}
proc record_error {message} {puts "NONCE|error|[encode $message]"}
'''.replace("SOURCES", assignments).replace("COMMAND_LIMIT", str(command_limit)).replace("REMOVED_COMMANDS", removed_commands).replace("NONCE", nonce).replace(
        "CREATE_CHILD", "" if tk else "set ::child [interp create -safe]")


def _execute(ctx, parameters, suffix, sources, timed, tk=False, source_sha256=None):
    command_limit = _integer(parameters.get("commandLimit", 1_000_000), 100, 1_000_000, "commandLimit")
    session = ctx.tk_session if tk else None
    timeout_ms = _integer(parameters.get("timeoutMillis", 20_000 if session else 5000), 50,
                          20_000 if session else 30_000, "timeoutMillis")
    nonce = secrets.token_hex(16)
    driver = _prefix(sources, nonce, command_limit, timed, tk) + suffix.replace("NONCE", nonce)
    if tk:
        if session:
            session.configure(nonce, _window_preview, source_sha256)
        result = ctx.run([ctx.tool("bwrap")], timeout_seconds=timeout_ms / 1000,
                         stdin=f"{nonce}\n{driver}", log_stdout=False)
    elif timed:
        # The driver is generated locally. Feed it over stdin rather than granting a writable work directory.
        # Tclsh normally continues after stdin errors. Stop before executing source if setup fails.
        program = "if {[catch {\n" + driver + "\n} driverError]} {puts stderr $driverError; exit 1}\nexit 0\n"
        result = ctx.run([ctx.tool("tclsh-isolated")], timeout_seconds=timeout_ms / 1000, stdin=program)
    else:
        harness = ctx.output_path("tcl-harness.tcl")
        harness.write_text(driver, encoding="utf-8")
        result = ctx.run([ctx.tool("tclsh"), str(harness)], timeout_seconds=timeout_ms / 1000)
    if result.returncode:
        raise JobFailure("tcl-executor-failed", "The Tcl executor failed.",
                         {"returncode": result.returncode, "stderr": result.stderr[-8192:]})
    records, stdout, stderr = [], [], []
    for line in result.stdout.splitlines(keepends=True):
        if line.startswith(nonce + "|"):
            record = line.rstrip("\r\n").split("|")
            if len(record) == 3 and record[1] in ("stdout", "stderr"):
                try:
                    value = bytes.fromhex(record[2]).decode("utf-8")
                except (ValueError, UnicodeDecodeError) as error:
                    raise JobFailure("tcl-protocol", "The Tcl executor returned invalid output.") from error
                (stdout if record[1] == "stdout" else stderr).append(value)
                ctx.emit(record[1], value)
                continue
            if record[1] == "error":
                try:
                    message = bytes.fromhex(record[2]).decode("utf-8")
                except (ValueError, UnicodeDecodeError, IndexError) as error:
                    raise JobFailure("tcl-protocol", "The Tcl executor returned an invalid error record.") from error
                raise JobFailure("tcl-script-error", message, {"stderr": result.stderr[-8192:]})
            if not session or record[1] != "frame":
                records.append(record)
        else:
            stdout.append(line)
    return records, "".join(stdout), "".join(stderr) + result.stderr


def run_console(ctx, parameters, *, timed=False, tk=False, live=False):
    if not isinstance(parameters, dict) or parameters.keys() - {"script", "commandLimit", "timeoutMillis"}:
        raise JobFailure("invalid-parameters", "Unsupported Tcl console parameters.")
    name, source, sources = _sources(ctx, parameters)
    source_sha256 = hashlib.sha256(source).hexdigest()
    if live and ctx.tk_session is None:
        raise JobFailure("tk-input-unavailable", "The private Tk job has no input mailbox.")
    preview_ready = "set ::logisimPreviewReady 1" if tk else ""
    suffix = f'''
if {{[catch {{safe_source $::child {{{name}}}}} value]}} {{
    record_error $value
}} else {{
    puts "NONCE|result|[encode $value]"
    {preview_ready}
}}
'''
    records, stdout, stderr = _execute(ctx, parameters, suffix, sources, timed, tk, source_sha256)
    if len(records) != (2 if tk and not live else 1) or len(records[0]) != 3 or records[0][1] != "result":
        raise JobFailure("tcl-protocol", "The Tcl console did not return one result.")
    try:
        value = bytes.fromhex(records[0][2]).decode("utf-8")
    except (ValueError, UnicodeDecodeError, IndexError) as error:
        raise JobFailure("tcl-protocol", "The Tcl console returned an invalid result.") from error
    mode = "tk-live-v1" if live else "tk-console-v1" if tk else "timed-tcl-v1" if timed else "safe-tcl-v1"
    result = {"mode": mode, "result": value, "stdout": stdout, "stderr": stderr,
              "sourceSha256": source_sha256}
    if live:
        result["frame"] = ctx.tk_session.latest(ctx.max_output_bytes)
        if result["frame"] is None:
            raise JobFailure("tcl-protocol", "The Tcl worker returned no private window frames.")
    elif tk:
        result["windowPreview"] = _window_preview(records[1])
    return result


def _window_preview(record):
    if len(record) != 3 or record[1] != "preview" or len(record[2]) > (tk_worker.PREVIEW_LIMIT + 2) // 3 * 4:
        raise JobFailure("tcl-protocol", "The Tcl worker returned an invalid window preview.")
    encoded = record[2]
    try:
        data = base64.b64decode(encoded, validate=True)
    except ValueError as error:
        raise JobFailure("tcl-protocol", "The Tcl worker returned invalid preview data.") from error
    dimensions = tk_worker.WIDTH.to_bytes(4, "big") + tk_worker.HEIGHT.to_bytes(4, "big")
    if (len(data) > tk_worker.PREVIEW_LIMIT or not data.startswith(b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR" + dimensions) or
            base64.b64encode(data).decode("ascii") != encoded):
        raise JobFailure("tcl-protocol", "The Tcl worker returned an invalid preview image.")
    return {"name": "tk-window.png", "size": len(data), "sha256": hashlib.sha256(data).hexdigest(), "base64": encoded}


def _ports(raw):
    if not isinstance(raw, list) or not 1 <= len(raw) <= MAX_PORTS:
        raise JobFailure("invalid-ports", "Provide from 1 to 512 Tcl component ports.")
    result = []
    for port in raw:
        if not isinstance(port, dict) or port.keys() != {"name", "direction", "width"}:
            raise JobFailure("invalid-ports", "Each port requires name, direction and width.")
        name = port["name"]
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,127}", name):
            raise JobFailure("invalid-ports", "Tcl port names must use plain identifiers.")
        if port["direction"] not in ("input", "output", "inout"):
            raise JobFailure("invalid-ports", "Unsupported Tcl port direction.")
        width = _integer(port["width"], 1, 64, "port width")
        result.append({"name": name, "direction": port["direction"], "width": width})
    if len({port["name"] for port in result}) != len(result):
        raise JobFailure("invalid-ports", "Tcl port names must be unique.")
    if not any(port["name"] == "sysclk_i" and port["direction"] in ("input", "inout") and port["width"] == 1 for port in result):
        raise JobFailure("missing-clock", "The desktop Tcl protocol requires the one-bit input sysclk_i.")
    return result


def _frame_limit(ports):
    # Mirror TclInterface.replayFrameLimit. Charge inout ports for both
    # the request and observation, and retain the legacy minimum capacity.
    frame_bytes = sum((len(port["name"]) + port["width"] + 16)
                      * (2 if port["direction"] == "inout" else 1) for port in ports)
    return max(LEGACY_FRAME_LIMIT, min(MAX_FRAMES, EXTENDED_REPLAY_BYTES // frame_bytes))


def _frames(raw, ports):
    limit = _frame_limit(ports)
    if not isinstance(raw, list) or not 1 <= len(raw) <= limit:
        raise JobFailure("invalid-frames", f"Provide from 1 to {limit} explicit Tcl input frames.")
    inputs = {port["name"]: port["width"] for port in ports if port["direction"] != "output"}
    result = []
    for frame in raw:
        if not isinstance(frame, dict) or frame.keys() != inputs.keys():
            raise JobFailure("invalid-frames", "Each frame must contain every input/inout port and no other names.")
        checked = {}
        for name, width in inputs.items():
            value = frame[name]
            if not isinstance(value, str) or len(value) != width or not re.fullmatch(r"[01?EXU]+", value):
                raise JobFailure("invalid-frames", f"Input {name} must have exactly {width} logic bits.")
            checked[name] = value.replace("X", "U").replace("?", "U")
        result.append(checked)
    return result


def _normalize_bits(value, width):
    return ("?" * width + "".join(bit if bit in "01" else "?" for bit in value))[-width:]


def run_components(ctx, parameters, *, timed=False):
    if not isinstance(parameters, dict) or parameters.keys() - {"script", "commandLimit", "timeoutMillis", "ports", "frames"}:
        raise JobFailure("invalid-parameters", "Unsupported Tcl component parameters.")
    name, source, sources = _sources(ctx, parameters)
    ports = _ports(parameters.get("ports"))
    frames = _frames(parameters.get("frames"), ports)
    suffix = r'''
set ::requestedTick 0
set ::inFrame 0
proc signal_name {value} {
    if {![regexp {([^/\\(]+)(?:\([0-9]+\))?$} $value -> name]} {error "Invalid signal name"}
    return $name
}
proc examine {child args} {
    if {[llength $args] == 2} {set args [lrange $args 1 end]}
    if {[llength $args] != 1} {error "examine expects an optional type and one signal"}
    set signal [lindex $args 0]
    if {[string first "(" $signal] >= 0} {return 0}
    set name [signal_name $signal]
    if {[catch {interp eval $child [list set ::$name]} value]} {return 0}
    return $value
}
proc force {child args} {
    if {[llength $args] == 3} {set args [lrange $args 1 end]}
    if {[llength $args] != 2} {error "force expects an optional type, signal and value"}
    interp eval $child [list set ::[signal_name [lindex $args 0]] [lindex $args 1]]
}
proc request_tick {args} {
    if {!$::inFrame} {error "run is supported only inside logisimForce/logisimExamine callbacks"}
    if {$::requestedTick} {error "Safe Tcl replay supports at most one run request per input frame"}
    set ::requestedTick 1
}
proc ignored {args} {return ""}
interp alias $::child examine {} examine $::child
interp alias $::child force {} force $::child
interp alias $::child run {} request_tick
interp alias $::child echo {} safe_puts
foreach command {restart add delete configure WaveRestoreZoom} {interp alias $::child $command {} ignored}
if {[catch {safe_source $::child {MAIN}} value]} {record_error $value; exit 0}
foreach command {enableLogisim logisimForce logisimExamine} {
    if {![llength [interp eval $::child [list info procs $command]]]} {record_error "Missing callback: $command"; exit 0}
}
if {[catch {interp eval $::child {enableLogisim TRUE}} value]} {record_error $value; exit 0}
'''.replace("MAIN", name)
    previous_clock = "U"
    for index, frame in enumerate(frames):
        for port in ports:
            value = frame.get(port["name"], "X")
            suffix += f"interp eval $::child [list set ::{port['name']} {{{value}}}]\n"
        forced = frame["sysclk_i"] != previous_clock
        previous_clock = frame["sysclk_i"]
        callback = "logisimForce" if forced else "logisimExamine"
        suffix += f"set ::requestedTick 0\nset ::inFrame 1\nif {{[catch {{interp eval $::child {{{callback}}}}} value]}} {{record_error $value; exit 0}}\n"
        suffix += f'puts "NONCE|step|{index}|{1 if forced else 0}|$::requestedTick"\n'
        if forced:
            for port in ports:
                if port["direction"] != "input":
                    suffix += f'if {{[catch {{interp eval $::child [list set ::{port["name"]}]}} value]}} {{set value X}}\n'
                    suffix += f'puts "NONCE|output|{index}|{port["name"]}|[encode $value]"\n'
    records, stdout, stderr = _execute(ctx, parameters, suffix, sources, timed)
    outputs = {port["name"]: "?" * port["width"] for port in ports if port["direction"] != "input"}
    widths = {port["name"]: port["width"] for port in ports}
    steps = []
    try:
        for record in records:
            if record[1] == "step":
                if len(record) != 5 or int(record[2]) != len(steps):
                    raise ValueError("Invalid step sequence")
                steps.append({"forced": record[3] == "1", "requestedTick": record[4] == "1", "outputs": dict(outputs)})
            elif record[1] == "output":
                if len(record) != 5 or int(record[2]) != len(steps) - 1 or record[3] not in outputs:
                    raise ValueError("Invalid output record")
                outputs[record[3]] = _normalize_bits(bytes.fromhex(record[4]).decode("utf-8"), widths[record[3]])
                steps[-1]["outputs"] = dict(outputs)
            else:
                raise ValueError("Unknown record")
        if len(steps) != len(frames):
            raise ValueError("Missing steps")
    except (ValueError, UnicodeDecodeError, IndexError) as error:
        raise JobFailure("tcl-protocol", "The Tcl executor returned an invalid component response.") from error
    return {"mode": "timed-replay-v1" if timed else "safe-replay-v1", "steps": steps, "outputs": outputs, "delay": 1,
            "stdout": stdout, "stderr": stderr, "sourceSha256": hashlib.sha256(source).hexdigest()}
