"""Explicit HDL synthesis into the mobile importer's bounded Yosys JSON format."""
import json
from pathlib import Path, PurePosixPath
import re

from companion.api import JobFailure, Operation, safe_name

MAX_SOURCE_BYTES = 128 * 1024
MAX_INPUT_BYTES = 4 * 1024 * 1024
MAX_INPUTS = 64
SOURCE_TREE = "hdl-tree"
PATH_SEGMENT = r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}"
RELATIVE_PATH = re.compile(rf"{PATH_SEGMENT}(?:/{PATH_SEGMENT})*")
TOP = re.compile(r"[A-Za-z_][A-Za-z0-9_$]{0,127}\Z")
DEFINE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(?:=[A-Za-z0-9_+'().-]+)?\Z")
FILE_CALL = re.compile(r"\$(?:system|fopen|fclose|fread|fwrite|fdisplay|fgets|fgetc|fscanf|fseek|ftell|ferror|rewind)\b", re.I)
# Only complete, known spellings can enter preprocessing. A denylist alone
# misses $readmem`SUFFIX, which Yosys expands into $readmemh without ``.
SYSTEM_FUNCTIONS = frozenset({
    "$readmemh", "$readmemb", "$clog2", "$bits", "$signed", "$unsigned",
    "$size", "$high", "$low", "$left", "$right", "$increment",
    "$countones", "$countbits", "$onehot", "$onehot0", "$isunknown",
    "$display", "$write", "$warning", "$error", "$fatal", "$info",
    "$finish", "$stop", "$anyconst", "$anyseq", "$allconst", "$allseq",
    "$initstate", "$past", "$stable", "$changed", "$rose", "$fell",
})


def _without_comments(source: str) -> str:
    result, offset, quoted = [], 0, False
    while offset < len(source):
        if source[offset] == '"':
            quoted = not quoted
            result.append(source[offset])
            offset += 1
        elif quoted and source[offset] == "\\" and offset + 1 < len(source):
            result.extend(source[offset:offset + 2])
            offset += 2
        elif not quoted and source[offset] == "\\":
            # Verilog escaped identifiers continue through quotes and comment
            # markers until whitespace. They must be recognized before // or
            # /*, otherwise a following task on this line can disappear from
            # policy inspection. This adapter deliberately excludes them.
            end = offset + 1
            while end < len(source) and not source[end].isspace():
                end += 1
            if end > offset + 1:
                raise JobFailure("unsupported-identifier", "Escaped Verilog identifiers are not supported by HDL import.")
            result.append(source[offset])
            offset += 1
        elif not quoted and source.startswith("//", offset):
            end = source.find("\n", offset)
            offset = len(source) if end < 0 else end
        elif not quoted and source.startswith("/*", offset):
            end = source.find("*/", offset + 2)
            if end < 0:
                raise JobFailure("invalid-source", "Unterminated Verilog comment.")
            result.extend("\n" for c in source[offset:end + 2] if c == "\n")
            offset = end + 2
        else:
            result.append(source[offset])
            offset += 1
    return "".join(result)


def _validate_source(name: str, source: str, allowed_names: set[str], source_format: str):
    if source_format == "blif":
        for line in source.splitlines():
            if line.lstrip().startswith((".include", ".search")):
                raise JobFailure("external-file", f"{name}: BLIF includes must be supplied as explicit input files.")
        return
    text = _without_comments(source)
    # Token concatenation can build a system-task name only after this lexical
    # policy has run, for example $readmem``h. Do not let the preprocessor turn
    # an unchecked spelling into host file access inside Yosys.
    if "``" in text:
        raise JobFailure("unsupported-preprocessor", f"{name}: macro token concatenation is not supported by HDL import.")
    if re.search(r"[A-Za-z0-9_$]`[A-Za-z_]", text):
        raise JobFailure("unsupported-preprocessor", f"{name}: adjacent identifiers and macros are not supported by HDL import.")
    if FILE_CALL.search(text):
        raise JobFailure("host-access", f"{name}: host file/process operations are not supported by HDL import.")
    for match in re.finditer(r"\$[A-Za-z0-9_$]*", text):
        if match.group(0).lower() not in SYSTEM_FUNCTIONS:
            raise JobFailure("unsupported-system-function", f"{name}: only complete supported system-function identifiers are accepted.")
    for include in re.finditer(r"`include\b([^\n]*)", text):
        literal = re.fullmatch(r'\s*"([^"\r\n]+)"\s*', include.group(1))
        reference = literal.group(1) if literal else ""
        if not _is_relative_path(reference):
            raise JobFailure("external-file", f"{name}: include must use a declared relative path.")
        sibling_path = (PurePosixPath(name).parent / reference).as_posix()
        if reference not in allowed_names and sibling_path not in allowed_names:
            raise JobFailure("external-file", f"{name}: include must name a declared input file.")
    literal_reads = list(re.finditer(r'\$(?:readmemh|readmemb)\s*\(\s*"([A-Za-z0-9][A-Za-z0-9._-]{0,127})"\s*,', text, re.I))
    for literal in literal_reads:
        if literal.group(1) not in allowed_names:
            raise JobFailure("external-file", f"{name}: readmem must name an explicitly supplied plain file.")
    # A newline before the first argument must not evade the checks above.
    if len(re.findall(r"\$(?:readmemh|readmemb)\b", text, re.I)) != len(literal_reads):
        raise JobFailure("external-file", f"{name}: dynamic readmem file expressions are unsupported.")


def _is_relative_path(path: str) -> bool:
    return isinstance(path, str) and len(path) <= 255 and RELATIVE_PATH.fullmatch(path) is not None


def _source_paths(ctx, parameters):
    manifest = parameters.get("inputPaths")
    if manifest is None:
        return ctx.input_files
    if not isinstance(manifest, dict) or set(manifest) != set(ctx.input_files):
        raise JobFailure("invalid-request", "Declare one relative path for each uploaded input.")
    paths = {}
    entries = {}
    for upload_name, path in manifest.items():
        if not _is_relative_path(path):
            raise JobFailure("invalid-name", "Use a relative HDL path without dot segments or backslashes.")
        segments = path.split("/")
        for length in range(1, len(segments) + 1):
            prefix = "/".join(segments[:length])
            entry = (prefix, length == len(segments))
            existing = entries.setdefault(prefix.lower(), entry)
            if existing != entry:
                raise JobFailure("input-name-collision", "HDL input files and directories must have distinct paths ignoring case.")
        if path in paths:
            raise JobFailure("input-name-collision", "HDL input paths must be unique.")
        paths[path] = ctx.input_path(upload_name)
    return paths


def _reserved_name(name: str) -> bool:
    return (name.lower() in {"outputs", "inputs", "mobile_import.ys", "mobile_import_memory.json", "logisim_blif_cells.v"}
            or re.fullmatch(r"command-[0-9]+\.(?:stdout|stderr|stdin)", name, re.I) is not None)


def _request(ctx, parameters):
    if not isinstance(parameters, dict):
        raise JobFailure("invalid-request", "HDL synthesis parameters must be an object.")
    names = parameters.get("sourceNames")
    if not isinstance(names, list) or not 1 <= len(names) <= MAX_INPUTS or any(not isinstance(n, str) for n in names):
        raise JobFailure("invalid-request", "Choose 1 to 64 HDL source files.")
    if len({name.lower() for name in names}) != len(names):
        raise JobFailure("invalid-request", "HDL source names must be unique.")
    if len(ctx.input_files) > MAX_INPUTS:
        raise JobFailure("input-limit", "The synthesis job exceeds 64 input files.")
    if len({name.lower() for name in ctx.input_files}) != len(ctx.input_files):
        raise JobFailure("input-name-collision", "HDL input names must be unique ignoring case.")
    declares_tree = parameters.get("inputPaths") is not None
    paths = _source_paths(ctx, parameters)
    if declares_tree:
        if any(name not in paths for name in names):
            raise JobFailure("invalid-request", "Every root source must name a declared input file.")
    else:
        for name in names:
            ctx.input_path(name)
    source_format = parameters.get("format", "verilog")
    if source_format not in ("verilog", "system-verilog", "blif"):
        raise JobFailure("invalid-request", "Unknown HDL source format.")
    top = parameters.get("top", "")
    if not isinstance(top, str) or (top and not TOP.fullmatch(top)):
        raise JobFailure("invalid-request", "Top must be a basic HDL module identifier.")
    defines = parameters.get("defines", [])
    if not isinstance(defines, list) or len(defines) > 128 or any(not isinstance(d, str) or not DEFINE.fullmatch(d) for d in defines):
        raise JobFailure("invalid-request", "Use simple macro definitions NAME or NAME=VALUE.")
    if parameters.get("includes", []) not in ([], ["."]):
        raise JobFailure("external-file", "Only the staged input directory may be used for includes.")
    memory_policy = parameters.get("memoryPolicy", "logic")
    if memory_policy not in ("logic", "native") or source_format == "blif" and memory_policy != "logic":
        raise JobFailure("unsupported-policy", "Choose logic expansion or native memories for Verilog.")
    undefined = parameters.get("undefined", "error")
    if undefined not in ("error", "0", "1"):
        raise JobFailure("invalid-request", "Undefined policy must be error, 0 or 1.")
    total = 0
    allowed_names = set(paths)
    for name, path in paths.items():
        if not declares_tree:
            safe_name(name)
        root_name = name.split("/", 1)[0]
        if _reserved_name(root_name) or (declares_tree and root_name.lower() == SOURCE_TREE):
            raise JobFailure("reserved-name", f"{name}: this input name is reserved by the companion.")
        data = path.read_bytes()
        total += len(data)
        if len(data) > MAX_SOURCE_BYTES or total > MAX_INPUT_BYTES:
            raise JobFailure("input-limit", "HDL source/input size limit exceeded.")
        try:
            source = data.decode("utf-8", errors="strict")
        except UnicodeDecodeError as error:
            raise JobFailure("invalid-source", f"{name}: input is not UTF-8.") from error
        _validate_source(name, source, allowed_names, source_format)
    return names, source_format, top, defines, undefined, paths, memory_policy


def _preserve_memory(cell, muxes):
    params = cell["parameters"]
    ports = cell["connections"]
    width, bits, size, offset, reads, writes = (
        int(params[key], 2) for key in ("WIDTH", "ABITS", "SIZE", "OFFSET", "RD_PORTS", "WR_PORTS")
    )
    if not (1 <= width <= 64 and 1 <= bits <= 12 and size == 1 << bits and offset == 0 and reads == 1):
        return False
    if len(ports["RD_ADDR"]) != bits or len(ports["RD_DATA"]) != width or not all(isinstance(bit, int) for bit in ports["RD_DATA"]):
        return False
    if params["RD_CLK_ENABLE"] != "0" or params["RD_WIDE_CONTINUATION"] != "0":
        return False
    if ports["RD_EN"] != ["1"] or ports["RD_ARST"] != ["0"] or ports["RD_SRST"] != ["0"]:
        return False
    if writes == 0:
        return True
    if writes != 1 or params["WR_CLK_ENABLE"] != "1" or params["WR_WIDE_CONTINUATION"] != "0":
        return False
    if len(ports["WR_EN"]) != width or len(set(ports["WR_EN"])) != 1:
        return False
    enable = ports["WR_EN"][0]
    if len(ports["WR_CLK"]) != 1 or len(ports["WR_DATA"]) != width:
        return False
    enable_mux = muxes.get((enable,))
    if enable_mux and enable_mux["A"] == ["0"] and enable_mux["B"] == ["1"]:
        enable = enable_mux["S"][0]

    def when_enabled(port):
        connection = ports[port]
        mux = muxes.get(tuple(connection))
        # proc can leave the write address and data undefined while disabled.
        if mux and mux["S"] == [enable] and all(bit == "x" for bit in mux["A"]):
            return mux["B"]
        return connection

    address = when_enabled("WR_ADDR")
    data = when_enabled("WR_DATA")
    if address != ports["RD_ADDR"]:
        return False
    ports.update(WR_ADDR=address, WR_DATA=data, WR_EN=[enable] * width)
    return True


def _mark_native_memories(path):
    if path.stat().st_size > 32 * 1024 * 1024:
        raise JobFailure("artifact-limit", "The HDL synthesis artifact exceeds the importer size limit.")
    design = json.loads(path.read_text())
    for module in design["modules"].values():
        cells = module.get("cells", {}).values()
        muxes = {tuple(cell["connections"]["Y"]): cell["connections"] for cell in cells if cell["type"] == "$mux"}
        for cell in cells:
            if cell["type"] == "$mem_v2":
                attributes = cell["attributes"]
                attributes.pop("logisim_mobile_keep_memory", None)
                if _preserve_memory(cell, muxes):
                    attributes["logisim_mobile_keep_memory"] = "1"
    path.write_text(json.dumps(design))


def _run_yosys(ctx, lines):
    script = ctx.working_directory / "mobile_import.ys"
    script.write_text("\n".join(lines) + "\n")
    result = ctx.run([ctx.tool("yosys"), "-Q", "-T", "-s", str(script)], timeout_seconds=30)
    if result.returncode:
        raise JobFailure("synthesis-failed", "Yosys could not synthesize the supplied source.",
                         {"returncode": result.returncode, "log": (result.stdout + result.stderr)[-16384:]})


def synthesize(ctx, parameters):
    names, source_format, top, defines, undefined, paths, memory_policy = _request(ctx, parameters)
    ctx.check_cancelled()
    tree = SOURCE_TREE if parameters.get("inputPaths") is not None else "."
    root = ctx.working_directory / tree
    if tree != ".":
        root.mkdir()
    for name, path in paths.items():
        destination = root / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(path.read_bytes())
    staged_sources = [str(PurePosixPath(tree) / name) for name in names]
    lines = []
    if source_format == "blif":
        library = ctx.working_directory / "logisim_blif_cells.v"
        if library.name in ctx.input_files:
            raise JobFailure("reserved-name", "logisim_blif_cells.v is a reserved input name.")
        library.write_bytes(Path(__file__).with_name("hdl_import_cells.v").read_bytes())
        lines.append('read_verilog "logisim_blif_cells.v"')
        lines.extend(f'read_blif -wideports "{name}"' for name in staged_sources)
    else:
        options = " -sv" if source_format == "system-verilog" else ""
        options += "".join(" -D" + define for define in defines)
        lines.append(f"read_verilog -I {tree}" + options + " " + " ".join(f'"{name}"' for name in staged_sources))
    word_cells = parameters.get("wordCells", False)
    if not isinstance(word_cells, bool):
        raise JobFailure("invalid-request", "wordCells must be a boolean.")
    native_types = ("add", "sub", "mux", "dff", "dffe", "sdff", "sdffe", "sdffce")
    keep = " ".join(f"t:${kind}" for kind in native_types) + " %u" * (len(native_types) - 1)
    # Out-of-range and single-bit cells still follow the ordinary logic mapper.
    fallback = "r:A_WIDTH>64 r:B_WIDTH>64 r:Y_WIDTH>64 r:WIDTH>64 r:Y_WIDTH=1 r:WIDTH=1" + " %u" * 5
    mapping_scope = f" */c:* {keep} {fallback} %d %d" if word_cells else ""
    lines.extend([
        "hierarchy -check " + ("-top " + top if top else "-auto-top"),
        "proc", "tribuf", "opt",
    ])
    if memory_policy == "native":
        intermediate = ctx.working_directory / "mobile_import_memory.json"
        _run_yosys(ctx, lines + ["memory -nomap", "opt", 'write_json -noscopeinfo "mobile_import_memory.json"'])
        _mark_native_memories(intermediate)
        lines = ['read_json "mobile_import_memory.json"', "memory_map -attr !logisim_mobile_keep_memory", "opt_clean"]
    else:
        lines.append("memory")
    _run_yosys(ctx, lines + [
        "opt", "techmap" + mapping_scope, "opt", "dffunmap" + mapping_scope, "simplemap" + mapping_scope,
        "abc -g simple", "opt_clean", "check -assert",
        'write_json -noscopeinfo "outputs/netlist.json"',
    ])
    artifact = ctx.artifact(ctx.output_path("netlist.json"))
    return {"format": "yosys-bit-netlist-v1", "undefined": undefined, "memoryPolicy": memory_policy,
            "netlist": artifact, "sourceNames": names, "top": top or None,
            "diagnostics": [], "execution": "explicit-companion-job"}


def register(registry):
    registry.register(Operation(name="yosys-import", handler=synthesize, required_tools=("yosys",),
                                description="Synthesize explicit Verilog or BLIF inputs into editable mobile logic."))
