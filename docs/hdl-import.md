# HDL import into editable circuits

Issue #58 uses the explicitly enabled Mac companion to synthesize selected
Verilog, SystemVerilog or BLIF files with Yosys. The mobile core converts the
returned bit-level JSON into ordinary editable circuits. Each source module
becomes a circuit, and module instances become subcircuits.

Opening a project, selecting a source, pairing, or discovering tools does not
run Yosys. The import operation is `yosys-import`, which the host operator must
enable with `--allow yosys-import`. The host supplies the executable path or
allows discovery from its own `PATH`. A request cannot supply an executable or
an arbitrary Yosys script.

The main implementation paths are:

| Path | Responsibility |
| --- | --- |
| `companion/hdl_import.py` | Input policy, fixed synthesis script and JSON artifact |
| `companion/hdl_import_cells.v` | Fixed cells accepted by the desktop BLIF component |
| `core/.../HdlImportCompanion.kt` | Authenticated job request and verified artifact consumption |
| `core/.../HdlNetlist.kt` | Bounded Yosys JSON parsing and hierarchy validation |
| `core/.../HdlNativeImporter.kt` | Detached conversion into native components and subcircuits |
| `companion/tests/test_hdl_import.py` | Real synthesis and artifact behavior checks |
| `fixtures/hdl/import/` | Source files and selected real Yosys JSON artifacts |

The Kotlin paths above are under
`core/src/commonMain/kotlin/dev/logisim/mobile/core/`.

## Import request and result

The companion request includes explicitly selected UTF-8 files, their hashes,
and these parameters:

```json
{
	"sourceNames": ["memory.v"],
	"format": "verilog",
	"top": "memory",
	"defines": [],
	"includes": ["."],
	"memoryPolicy": "logic",
	"undefined": "error"
}
```

`format` is `verilog`, `system-verilog` or `blif`. `sourceNames` selects the
compilation units from the uploaded files. Other uploaded files can supply
headers and memory data. `top` selects a module; an empty value uses Yosys's
automatic top selection. Native conversion still requires a unique valid top.

`defines` contains simple `NAME` or `NAME=VALUE` definitions. The mobile request
builder accepts a smaller literal syntax than the companion endpoint. The
endpoint also accepts bounded source-file count and simple literal definitions
such as `WIDTH=3` and `MASK=3'b101`. Macro expressions remain subject to Yosys
syntax and the source policy below. Verilog module parameters come from the
selected source and its synthesis elaboration; there is no arbitrary command
or parameter-override script field.

The result identifies `yosys-bit-netlist-v1` and returns `netlist.json` as a
base64 artifact with its byte count and SHA-256 digest. `HdlImportCompanion.kt`
accepts only a successful `yosys-import` job and verifies the artifact before
decoding it. `HdlNativeImporter` builds a detached project. A failure leaves the
editor's existing project intact.

The converter preserves selected source texts in inert custom options:
`<options><a name="logisim-mobile-hdl-source" file="source.v">escaped text</a></options>`.
The mobile codec retains the file name and exact text, including Unicode,
line endings, tabs and XML delimiters. Source file names follow the companion's
plain ASCII name policy; relative paths and non-ASCII names are rejected before
execution. The desktop loader accepts and ignores these custom options. Saving
again in the desktop discards unknown options, so retain the original sources
or mobile project when those embedded texts are needed.

Generated circuit names use `hdl_` plus a sanitized module name, with
a suffix when an existing circuit already uses that name. Pins retain their
port labels and widths. Splitters and tunnels connect the normalized one-bit
logic into buses. Native word components keep their bus widths at their terminals.
Source hierarchy remains editable as native subcircuits.

## Supported synthesis and native cells

The fixed script runs hierarchy checking, process lowering, tri-state
normalization, optimization, memory selection, technology mapping, flip-flop
normalization and simple gate mapping before writing JSON. It does not flatten
the source hierarchy.

For Verilog and SystemVerilog, the app requests native word cells from the
companion. Addition, subtraction, two-input muxes and edge-triggered registers
stay editable as native components when their buses are 2–64 bits wide. The
converter extends signed operands when both are signed, keeps the clock edge and
enable polarity, and uses a data mux for synchronous reset. Cells outside that
set still go through logic mapping. Older companions return the bit-level
netlist, which the app continues to import.

The native converter also accepts these normalized one-bit Yosys cells:

- AND, OR, XOR, XNOR, NAND, NOR, NOT and buffer cells.
- ANDNOT, ORNOT, MUX and NMUX cells, expanded into native gates.
- `$_TBUF_`, converted into a controlled buffer.
- Positive-edge and negative-edge D flip-flops.
- Supported D flip-flops with asynchronous reset or set/reset, including their
  active polarities.
- Positive-level and negative-level latches.
- Instances of modules present in the same validated netlist.

One-bit cells must use the normalized connections expected by the converter. An unsupported cell returns a diagnostic naming its module and
cell. Black-box modules and recursive hierarchy are rejected. Vendor primitives
and general mixed-language elaboration are not claimed.

A Verilog `inout io` imports as an editable pair with the original bit width.
`io_in` is a tri-state input that starts floating in the top circuit and feeds
the shared net. `io_out` exposes only the module's own drive. An imported
subcircuit instance connects both pins to the external bus automatically.
Keeping the two contributions separate lets a released output float again.
Each declared inout bit must be a distinct net.

The BLIF path supports ordinary truth tables and latches accepted by Yosys,
plus the fixed desktop cell library. `TRISI` forwards A when B is low and floats
when B is high. `BUS` keeps both drivers; a conflicting-driver design fails
Yosys's `check -assert` instead of silently discarding its B input. The importer
does not claim general multi-driver bus resolution.

## Memory and undefined values

`memoryPolicy=native` is the default in the app for Verilog and SystemVerilog.
It preserves a memory as an editable ROM or RAM when all of the following hold:

- The memory is zero-based, has 2 to 4,096 words, and its depth fills the address
  bus. Data words are 1 to 64 bits wide.
- There is one continuously enabled asynchronous read port with no reset.
- A RAM has one edge-triggered write port that shares the read address and
  writes a whole word at a time.

Other memories are expanded into registers, decoders and gates. The companion
API also accepts `memoryPolicy=logic` to expand every memory, including for BLIF.
The app offers this mode for use with older companions. Designs that exceed
cell or layout limits fail conversion. Expanded nonzero register initialization
still requires explicit reset or write logic.

`$readmemh` and `$readmemb` can read a literal plain filename that appears among
the explicitly uploaded inputs. For example, `memory.v` reads the selected
`words.hex` fixture. Absolute paths, parent directories, missing files and
computed filename expressions are rejected before Yosys starts. Native
memories save their startup image in the Mobile `.circ`. Runtime RAM writes
belong to simulation state, and reset restores the imported image. Unknown
bits in that image initialize to zero unless the import policy selects one.

An explicit `x` constant fails native conversion by default. The user can
choose the explicit `undefined` policy `0` or `1`; that choice replaces those
constants during native conversion. A `z` constant remains undriven. A tool or
conversion failure never produces a substitute circuit or invented outputs.

HDL import is separate from execution as an HDL-IP component. The latter has
its own source, time and logic contract in
[issue #59](https://github.com/ThalesMMS/logisim-mobile-dev/issues/59).

## Files, macros and trust

The importer stages only explicitly uploaded files. In the import panel,
enter a relative path such as `inc/defs.vh` when adding source text, or enter
`inc` as the subdirectory before choosing `defs.vh` in the file picker.
Review the declared paths in the approval plan. Leave the subdirectory empty
for flat input files.

For a tree, the request's `inputPaths` object maps each uploaded file name to
its declared path. The companion creates the tree in the job directory.
`includes` is empty or `["."]` and cannot add a host directory. Verilog include
directives accept literal quoted paths relative to their source directory or
to the declared root. Absolute paths, dot segments, undeclared files and
case-insensitive file or directory collisions are rejected before synthesis.
BLIF `.include` and `.search` are rejected.

The source policy accepts only complete system-function identifiers from the
explicit `SYSTEM_FUNCTIONS` set in `hdl_import.py`. The set includes supported
sizing, casting, assertion and formal functions, plus `readmemh` and `readmemb`
under their separate literal-file rule. Other dollar identifiers, standalone
`$`, dynamic readmem filenames and adjacent identifier/macro concatenation are
rejected before Yosys starts. Ordinary constant macros remain supported.
The accepted preprocessor subset is deliberately narrower than arbitrary
Verilog preprocessing.

The initial policy assumed that finding literal file-task spellings before
preprocessing was enough to control the files opened by the frontend. That
premise failed twice. Both explicit token pasting and an ordinary adjacent
macro can construct `$readmemh` after a lexical check. Rejecting ` `` ` alone
did not close the latter case. The complete-identifier rule removes the
ability to pass fragments such as `$readmem` or `$` through that boundary.
The comment filter had a separate incorrect premise: comment delimiters could
be recognized without first recognizing escaped Verilog identifiers. A name
such as `\abc//def` continues until whitespace. Its `//` is part of the name,
so treating it as a comment hid a subsequent file task on the same line.
The lexical pass now recognizes that complete token before looking for
comments and reports `unsupported-identifier`. Escaped Verilog identifiers are
outside this adapter's supported subset. Backslash escapes inside quoted
strings and text inside comments remain valid, and the census includes the
escaped-name case.

The rerunnable census is
`test_preprocessor_census_rejects_file_task_fragments_before_launch` together
with `test_preprocessor_census_matches_actual_frontend_behavior` in
`companion/tests/test_hdl_import.py`. The latter deliberately disables the
source policy only inside the test and uses a temporary external file created
by that test, containing the literal `a`. This separates policy behavior from
preprocessor and frontend behavior without reading user files.

| Input form | Previous lexical policy | Yosys frontend with policy disabled | Current policy |
| --- | --- | --- | --- |
| Direct `$readmemh` of an external path | Rejected | Reads `a` | Rejected before launch |
| `$readmem``h` | Rejected after the first fix | Constructs the task and reads `a` | Rejected before launch |
| `$readmem` followed by a suffix macro | Accepted | Constructs the task and reads `a` | Rejected before launch |
| Prefix and suffix macros | Accepted | Constructs the task and reads `a` | Rejected before launch |
| `$` passed through a macro parameter | Accepted | Constructs the task and reads `a` | Rejected before launch |
| Backslash-newline between task fragments | Accepted | Syntax error | Rejected before launch |
| `//` inside an escaped identifier before a file task | Accepted | Reads `a` | Rejected before launch |

Comments do not authorize host access. Source input names must also be unique
ignoring case, and reserved names are checked ignoring case, so staging cannot
overwrite another selected file or companion file on a case-insensitive host.

Yosys receives an argument array with no shell and a script generated by the
handler. Source names, top identifiers and macro definitions pass validation
before they enter that script. The source content remains compiler input.
The companion uses a fresh job directory and enforces time, output, CPU,
file-size and supervised process-group memory limits.

These controls are not an OS filesystem or network sandbox. The compiler runs
with the host user's permissions, so the enabled adapter requires trusted
source and a trusted tool installation. The complete trust and resource policy
is in [External integrations and trust policy](interop-policy.md), and the
authenticated wire format is in [Companion protocol](companion-protocol.md).

## Limits, failure and cancellation

The importer accepts up to 64 uploaded files, each at most 128 KiB, and at most
4 MiB of decoded input in total. Yosys has a 30-second command limit inside the
companion's overall job deadline. The companion's configured output and result
limits also apply to logs and the encoded JSON artifact.

The native JSON parser accepts up to 512 modules, 16,384 cells in total,
256 ports per module, and 64 bits per port or connection. The editable layout
accepts at most 8,192 cells per module. These are separate conversion limits;
a source can synthesize successfully and still exceed a native limit.
Rows reserve the complete height of instantiated subcircuits and their bus
splitters. Layouts beyond the file format's coordinate range are rejected.

Missing tools, disabled operations, invalid source, unsupported file access,
synthesis errors, unsupported cells, size limits and cancellation remain
distinct failures. Cancellation before launch starts no tool. Cancellation
after launch terminates the active process group through `JobContext.run`.
The client accepts only a successful completed job, so canceled or failed work
cannot replace the open project.

## Fixtures and verification

The import behavior tests run with:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest companion.tests.test_hdl_import -v
```

Each successful real-tool test decodes and verifies the returned JSON artifact,
reads that JSON back into Yosys, emits Verilog and executes the emitted netlist
with Icarus/VVP. Expected gate, state and memory values are literals in the
test. The tests do not substitute execution of the original source for
execution of the returned artifact. Tri-state cells use the simulation models
shipped with the same Yosys installation.

`hierarchy_counter.v` and `hierarchy_counter.yosys.json` provide a native
comparison fixture with two source modules, a two-bit AND subcircuit and a
two-bit counter. With `a=2`, `b=3`, reset low and successive rising clock edges,
the concatenated `and_y,count` samples are `1000`, `1001`, `1010`, `1011`, `1000`.
Setting `a=1`, `b=3` and applying a rising edge with reset high gives `0100`.
`desktop_trisi.blif` and its real JSON fixture cover the inverted enable.

The full companion suite runs with:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s companion/tests -v
```

The real-tool checks used Yosys 0.70+post and Icarus/VVP 13.0 on this Mac.
Missing tools skip the corresponding integration tests. A skipped test is not
execution evidence. Native JVM tests, device UI, LAN TLS and deployment remain
separate checks.

`HdlImportCompanionIntegrationTest` starts an authenticated loopback companion,
reviews and confirms the request, runs actual Yosys synthesis, verifies the
artifact and imports it with the registered Gates, Wiring and Sequential
definitions. All sixteen two-bit AND input combinations execute in the native
hierarchical circuit. `HdlImportIntegrationTest` also checks the saved JSON
counter/TRISI fixtures and thirteen tall subcircuit instances without joining
their terminal rows. Floating `z` outputs remain independent when edited.

The shared EN/PT resources are in `hdl_import.json` and `hdl_tools.json`.
Regenerate the catalog before compiling a checkout containing new messages:

```sh
python3 scripts/generate-localization.py
./gradlew :core:jvmTest --tests '*HdlImport*Test' --tests '*HdlCompanionTest'
```
