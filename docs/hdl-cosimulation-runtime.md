# Local HDL co-simulation

The Mac companion executes HDL only after an explicit `hdl.validate`,
`hdl.start`, or `hdl.step` action. Reading a `.circ` file, importing a source,
loading a project, and discovering capabilities do not execute HDL tools.

`companion/hdl_sim.py` registers these operations through the companion API.
The companion's operation allowlist, pairing and job controls apply to every
request. The backend is `vhdl` or `blif`.

| Backend | Local tools | Execution |
| --- | --- | --- |
| VHDL | NVC | Analyze and elaborate VHDL-2008, then run a generated testbench |
| BLIF | Yosys, Icarus Verilog, VVP | Read BLIF, check its hierarchy, emit Verilog, compile and run a generated testbench |

The tools are separate installations. They are not bundled in the mobile app.
Homebrew provides `nvc`, `yosys`, and `icarus-verilog`. A missing backend returns
an unavailable or failed result with an empty `outputs` object. The implementation
does not replace failed executions with constant output values.

## Requests

`hdl.validate` and `hdl.start` accept the same design description. For example,
the checked-in `fixtures/hdl/cosim/counter.vhd` uses this request:

```json
{
  "backend": "vhdl",
  "source": "<the complete VHDL source text>",
  "topLevel": "counter",
  "parameters": {"WIDTH": 4, "INITIAL": 5},
  "ports": [
    {"name": "clk", "direction": "in", "width": 1},
    {"name": "reset", "direction": "in", "width": 1},
    {"name": "q", "direction": "out", "width": 4}
  ]
}
```

`backend` defaults to `vhdl`. Port names bind explicitly to the source entity or
BLIF model. A wrong name, width, or direction must pass the real compiler's
elaboration checks. VHDL ports use `std_logic` or `std_logic_vector`; a one-bit
vector must set `"kind": "std_logic_vector"`. The default for width one is
`std_logic`; larger widths default to `std_logic_vector`. Use basic identifiers. VHDL supports
`in`, `out` and `inout`. BLIF supports `in` and `out`.

VHDL generic overrides accept signed 32-bit integers, booleans, or their literal
text. Quoted std_logic and std_logic_vector literals are also accepted, such as
`"'1'"` and `"\"0101\""`. Expressions and compiler options are rejected. BLIF
has no top-level generic override, so its `parameters` must be empty. The
session preserves the exact UTF-8 source, the parsed port bindings and the
generic literals. Responses include a SHA-256 digest of the original source.

`hdl.validate` analyzes and elaborates without advancing simulation time. It
does not create a session. `hdl.start` performs the same checks and returns a
`sessionId`, `status: "ready"`, `timeNs: 0`, and `outputs: {}`. The first sampled
outputs come from a step.

```json
{
  "sessionId": "<identifier from hdl.start>",
  "inputs": {"clk": "0", "reset": "0"},
  "advanceNs": 1
}
```

Every `hdl.step` supplies all `in` and `inout` ports. Bit strings list the most
significant bit first. On an `inout` port, send `0` or `1` for an external drive
and `Z` to release that bit. The Mobile adapter samples the bus with the
component's own bidirectional outputs excluded. `advanceNs` is an integer of at
least one and defaults to one. The step applies its inputs at the preceding
sample time and samples outputs after
the requested interval and its delta cycles. The result includes cumulative
`timeNs` and a one-based `step` number.

`hdl.reset` takes `sessionId`, discards the trace and returns time zero with no
sampled outputs. The next step starts from the original source initialization
and generic values. This differs from asserting a source's reset input, which
is an ordinary step and follows the circuit's own reset logic.

`hdl.cancel` takes `sessionId`, interrupts an active operation, discards that
session and returns `outputs: {}`. A canceled session cannot be resumed. Start a
new session to run the design again. Cancel and reset require no installed HDL
tool. The companion's ordinary job cancellation also interrupts an active run.

## Time and state

Sessions store immutable source and a bounded sequence of input/time records.
Each step runs the actual simulator from initialization and replays that
sequence in a fresh job directory. This preserves sequential state and separate
state for each session without leaving a simulator process running between
requests. It trades throughput for a small, inspectable execution contract.
It is suitable for bounded interactive circuits, not long performance runs.

For VHDL, a postponed process samples after delta-cycle propagation at the
target physical time. For BLIF, Verilog `$strobe` samples after nonblocking
assignments in that time slot. The BLIF testbench then finishes one precision
unit later, at one picosecond after the sample. Reported circuit time remains
the requested sample time.

The mobile engine must treat a pending step as an external operation, use the
returned `timeNs` for its accepted sample, and route only a successful result
back to the bound output ports. A validation error does not advance the trace.
A compiler/runtime failure, timeout or output limit returns no outputs and
requires `hdl.reset` before another step. Partial samples are discarded. These
runtime operations do not by themselves prove mobile engine or UI integration.

## Mobile component and editor integration

`HdlIpLibrary.definitions` registers the desktop `#HDL-IP` file identities
`VHDL Entity` and `BLIFCircuit`. The declaration reader determines the component's
ports without launching a tool. The source remains in the `content` attribute.

`BLIFCircuit` does not use the companion. As in Evolution `d58a61e0`, the Mobile
simulator runs it with a port of `BlifParser` and `DenseLogicCircuit`
(`core/.../BlifCircuit.kt`): the same accepted subset, ports, error messages,
four-level cells, update queue and sequential script. A source that does not
parse runs the bundled `lightswitch` template, as on the desktop, and reports the
parser's message; Mobile keeps the text in the file, which Evolution would drop.
`scripts/oracle/blif-circuit` compares it with the Evolution JAR. The `blif`
backend below remains a companion operation and is not used by the component.
VHDL overrides use `generic.<name>`; the desktop `vhdl_<name>` attributes are
also accepted and preserved. Conflicting overrides produce a diagnostic.
Unknown attributes remain in the component and survive `.circ` round trips.
A source beyond the execution limit is preserved, with execution unavailable.

The host supplies its paired `CompanionClient` to `hdlSimulationTool`. The panel
can insert or edit an HDL-IP component through the editor's undoable document
callback. Preparing a session sends a plan. The confirmation button starts
the compiler job. Pairing and opening the panel do not start compilation.

An approved session stays active when the panel closes. It samples the bound
component after the first settled input and after subsequent changes to those
input vectors. A design without input ports remains manually sampled. Reopen
the panel to reset or stop the session, or use **Amostrar sinais atuais** to
request a manual sample. Automatic samples advance by one nanosecond beyond
the later of the mobile propagation time and the companion trace time. Manual samples use the chosen interval instead. Both
honor the companion's trace limits.

The editor serializes requests per session. The engine records distinct
input vectors as it evaluates the component, even while a sample is in flight.
The editor sends those vectors in order and continues from the companion's
advanced trace time. If more than 256 retained vectors accumulate and the
next unsent revision is lost, the session stops with an overflow diagnostic.
The engine rejects replies that no longer
match the session, epoch, sequence, source, time, input revision, or port
names and widths. It rejects an obsolete sample even when the input vectors
have returned to their earlier values. Reset, source edits, stopping the
session and closing the editor invalidate outstanding samples.

`HdlIpBehavior` executes no source and performs no network I/O. Pending or
disconnected components produce unknown outputs. A failed operation produces
an engine diagnostic and error values.

A future accepted sample enters the engine's event queue at the requested time.
Its output generation is opt-in metadata. Changing that generation discards
obsolete pending outputs from the same component instance; ordinary components
retain their existing transport delays. Generations and sessions remain local
to each subcircuit instance.

The mobile declaration reader supports ports up to 64 bits. Unresolved widths,
VHDL `linkage` ports and unsupported generic declarations remain unavailable.
The companion's `blif` backend accepts BUS, TRIS and TRISI cells.

## Logic values

VHDL responses preserve the IEEE 1164 value in `stdLogic` and provide the mobile
logic string in `logic`.

| IEEE value | Mobile value |
| --- | --- |
| `0`, `L` | `0` |
| `1`, `H` | `1` |
| `X` | `E` |
| `U`, `Z`, `W`, `-` | `?` |

For example, `UX01ZWLH-` returns `stdLogic: "UX01ZWLH-"` and
`logic: "?E01??01?"`. A process failure is a separate failed response, never a
fabricated all-error or all-unknown sample.

On VHDL `inout` ports, `stdLogic` reports the resolved bus. `logic` reports the
contribution needed to reproduce that sample in Mobile. With the external driver
released, `0` and `1` drive the Mobile bus, and `Z` maps to a floating bit. If an
external driver already holds the sampled value, the Mobile contribution floats.
Contention `X` maps to `E`. The result applies to the sampled external inputs and
is invalidated when those inputs change. The resolved sample cannot distinguish a
DUT driving the same bit as the external source from a released DUT.

The companion rejects inputs outside `0`, `1` and `Z` and resolved bidirectional
states it cannot project onto the Mobile bus. It reports the port and sampled
value and returns no partial output. Use a strong external driver or release the
bus when sampling a bidirectional port.

Generic BLIF uses the Verilog four-state domain. Responses preserve `rawLogic` with
`0`, `1`, `x`, `z`. Its `x` and `z` values map to mobile `?`; BLIF has no distinct
IEEE contention `X` versus uninitialized `U`. `stdLogic` uses `U` and `Z` for
those two four-state values. Incoming IEEE strength values `L`/`H` become
`0`/`1`; unknown/error inputs become Verilog `x` and `Z` becomes `z`.

The fixed Verilog cell library also accepts the desktop template's `.subckt`
and `.gate` cells, including `NOT`, `DFFSR` and the other cells named by
`BlifParser`. Only referenced cells absent from the supplied source are added.
`DFF`, `DFFSR` and `DLATCH` start at zero, matching `DenseLogicCircuitBuilder`.
Reset takes priority over set in `DFFSR`. Desktop boolean cells test for a
defined high input, matching the desktop's binary gate coercion. The generic
BLIF truth-table path retains Verilog unknown propagation. The fixed desktop
drive profile uses a single model with built-in cells and no generic truth
tables or latches. For that profile, unknown inputs and the reserved `$undef`
net contribute no drive. Output `z` stays released, and contention `x` maps to
IEEE `X` and Mobile `E`. TRIS drives when its control is high. TRISI drives
when its control has any other value. The generic mapping still applies to
mixed BLIF designs, where Verilog `x` can also mean an uninitialized net.

## Execution limits and source policy

The standalone runner and the companion API invoke fixed executable argument
arrays with no shell. Source text never becomes a command, filename, compiler
option or plugin path. The Yosys script is fixed except for a validated basic
identifier. Each job writes its source and generated testbench only inside its
working directory. The standalone runner creates and removes a temporary
directory per operation and provides a minimal environment.

The runtime bounds source size at 128 KiB, ports at 128, each port at 256 bits,
generic overrides at 32, sessions at 16, trace length at 256 steps and total
simulation time at 1,000,000 ns. Each compiler/runtime phase has a 10-second
wall-clock limit. The standalone runner caps stdout plus stderr at 64 KiB;
the companion job runner applies its own configured stream limit. NVC also
receives a 16 MiB simulation heap limit and a 1,000-delta-cycle limit. The runner
terminates a process that times out, floods diagnostics, or is canceled.

The accepted VHDL subset rejects host file I/O, foreign functions, external or
extended names, access types, impure functions and external libraries. Only
local `work`, IEEE logic/numeric packages and `std.standard` are available.
BLIF accepts `.model`, `.inputs`, `.outputs`, `.names`, `.latch`, `.end`, `.conn`,
`.subckt` and `.gate`. Includes, black-box tool plugins and other directives are
rejected. This lexical source policy is not an OS sandbox. Compiler execution
belongs to the explicitly enabled local companion and its trust decision.

## Validation evidence

Run the behavior tests from the repository root:

```sh
python3 -m unittest companion.tests.test_hdl_sim -v
```

The real-tool cases use the source fixtures in `fixtures/hdl/cosim` and literal
expected outputs. They cover VHDL and BLIF AND tables, a generic VHDL counter,
independent sessions, reset, a BLIF D flip-flop, delayed VHDL assignments, all
nine IEEE logic states, VHDL bidirectional drive, release and contention, unknown
BLIF values, malformed sources, invalid port
bindings, assertion failures, wall timeouts, cancellation, diagnostic limits
and delta-cycle limits. The desktop `blif.templ` lightswitch circuit is also a
fixture, with literal toggle and reset expectations. Missing required tools cause the corresponding
integration cases to skip; a skipped test is not execution evidence.

The local runner used NVC 1.23.1, Yosys 0.70+post and Icarus/VVP 13.0 on this Mac.
The standalone runtime evidence covers the supported models and nanosecond
step boundaries described above.

The registered handler also passed actual `JobContext.run` checks for both
backends against the companion API, including its host resource limits. This
check exercised the handler API directly; it did not test an HTTP client.

`HdlSimulationCompanionIntegrationTest` exercises the authenticated HTTP client,
the real NVC and Yosys/Icarus backends, and the mobile event engine. It checks a
VHDL AND sample with source digest, session and target time, a bidirectional
VHDL bus with external drive and contention, a two-bit BLIF bus, reset, and an
actual compile failure with no fabricated output. These tests
passed without skips on this Mac. `HdlIpIntegrationTest` and
`RegisteredOutputGenerationTest` cover independent instances and cancellation
of stale future samples while preserving ordinary transport edges.

The source-policy tests use controlled temporary files to reproduce masked
VHDL file declarations with character literals, qualified expressions,
alternative string delimiters and block comments. The current lexical policy
rejects those declarations before launching a tool. Benign literals and
comments still pass the real compiler. This regression evidence does not
change the trusted-source and no-OS-sandbox contract above.

The shared EN/PT resources are in `hdl_ip.json` and
`hdl_simulation_tool.json`. The panels compile for Android and iOS Simulator;
these checks do not establish execution of the mobile UI on a device.

NVC's phase and limit options are described in the [NVC manual](https://github.com/nickg/nvc/blob/master/nvc.1).
