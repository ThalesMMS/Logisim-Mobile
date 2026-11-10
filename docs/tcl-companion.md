# Tcl console and circuit components

Android and iOS use the explicitly selected Mac companion for Tcl. Importing a circuit leaves Tcl stopped. Mobile never starts `tclsh` locally or reads a desktop `filePath` to run a script. The host must advertise and permit the fixed `tcl-run` and `tcl-step` operations and have `tclsh` installed. Authentication, configured executables and process limits come from the companion service.

The **command console** submits one source to `tcl-run` and displays its result, stdout and stderr. It is independent of circuit state. The desktop component called **TclConsoleReds** is a circuit component with 42 signal terminals; its name does not mean an interactive command console. **TclGeneric** derives its terminals from its preserved VHDL entity declaration. Both use `tcl-step` with the callbacks `enableLogisim`, `logisimForce` and `logisimExamine`.

## Run an explicit source

1. Select the configured companion and open the Tcl tool.
2. Paste the source and choose its plain file name. To approve additional sources for a component, select **Attach Tcl source file** and choose each file by name.
3. For an independent command, tap **Run console on the Mac**. For a selected circuit component, tap **Approve sources and start component**. After approval, input changes are sent in order even when the panel is closed.
4. Tap **Cancel** to cancel a console job. Tap **Stop component** to cancel automatic frames and disable the component. Reset, source changes and replacing the approved instance invalidate outstanding responses and require a new approval.

`filePath`, VHDL `content`, labels and font attributes remain part of the project. A desktop path is displayed as a preserved reference, not treated as permission to access that file. Use the original source supplied by its author. `source` can only resolve the auxiliary files that were selected and approved for this component. Changing an attached source requires a new approval.

## Compatibility and limits

The companion creates a Tcl **safe interpreter** and removes time-dependent commands. `exec`, filesystem access, sockets, Tk, `clock`, `after`, `update`, `vwait`, `pid` timing commands and nested interpreter creation are unavailable, including internal clock entry points. Each job initializes the random generator to seed 1; scripts may choose an explicit seed. This restricts interpreter commands; it is not an operating-system sandbox. Stateful globals within one replay are supported. Scripts that depend on desktop Tk windows, local files, tools or unbounded external timing are unavailable in this mode and fail visibly. No unrestricted Tcl mode is silently selected.

Each source is limited to 256 KiB; a job accepts at most 16 UTF-8 sources totaling 1 MiB. The Tcl command budget is at most one million, with a default five-second timeout and a configurable maximum of 30 seconds. Script stdout/stderr are limited to 64 KiB. The host also applies its advertised output, memory and process limits. The KMP adapter uses a 35-second execution deadline and cancels outstanding jobs on cancellation. Submission and cancellation cleanup are each bounded to five seconds; cleanup can continue after that deadline. A lost network response can prevent immediate cancellation acknowledgement; the host job timeout remains in force.

Generic interfaces accept literal `std_logic` and `std_logic_vector` ports with `in`, `out` and `inout`, widths 1–64 and at most 512 terminals. Generic parameters, expressions in bounds, duplicate names and other types produce a diagnostic while retaining the original source. An incompatible declaration gets a visible component placeholder without invented terminals. The runtime requires a one-bit input named exactly **`sysclk_i`**, as does the desktop Java component. The desktop Generic template instead names it `horloge_i`; the app preserves that template and asks the user to correct the declaration explicitly.

`tcl-step` replays up to 1,024 changed input frames from the approved source in a fresh interpreter. Each instance retains its own history. A 512 KiB estimate for input and output frame data scales the extended allowance with the interface, with the original allowance of 256 frames preserved for very wide interfaces. The host still enforces its request, output, command and time limits. Replay preserves script state without a persistent remote socket process. A changed `sysclk_i` triggers `logisimForce` on **either edge**, including the first change from unknown. An unchanged clock invokes `logisimExamine`; driven outputs hold their previous values. Unknown inputs use the desktop default `U` representation. `examine` and `force` support the desktop optional type and hierarchical signal names. Output values trim their most significant excess bits, pad missing bits with unknown and convert non-binary output symbols to unknown. Outputs propagate after delay 1.

The wrapper's `run` command records a tick request for its input frame. A completed-frame cursor keeps intermediate requests and prevents old requests from repeating when the full history is replayed. The panel shows the pending count and presents **Apply requested Tcl tick**, which acknowledges and advances one simulation tick. To apply requests automatically, select **Allow up to 16 automatic ticks when starting this Tcl session.** before approving the source. This allowance belongs to the approved component in the current editor session. Automatic ticks continue while the Tcl panel is closed. Reopen it and choose **Stop applying ticks automatically** to cancel a queued automatic tick and return to manual application. Further requests remain available for manual application after the allowance runs out. Safe replay permits at most one `run` per frame callback and rejects `run` during source initialization or `enableLogisim`; these incompatible scripts fail explicitly. Wrapper presentation commands `restart`, `add`, `delete`, `configure` and `WaveRestoreZoom` are accepted as no-ops. At the frame limit, reset and start the component again explicitly. Reset or a new approval starts with fresh history. After a failed connection, explicitly approve the source again to start a new session. The companion keeps no Tcl interpreter between jobs. Update the companion together with the app to use histories beyond 256 frames.


## Approved timers through the companion API

The Mac host can enable `tcl-timed-run` and `tcl-timed-step` explicitly with
`--allow` and select the sandbox-compatible system Tcl with
`--tool tclsh-isolated=/usr/bin/tclsh`. Each job requires a single-use companion
plan bound to the script bytes and parameters. Pairing and regular Tcl approval
do not grant the timed operations. In the app, select **Run console with timers…**
and confirm the source and host. The client requires the macOS sandbox in the
plan. It uses `tcl-run` for the default console action and `tcl-step` for circuit
components.

In the timed operations, the safe interpreter exposes `after`, `vwait`, and
`update` inside the isolated worker. The host timeout and renewable five-second
lease apply even when the script waits on an event. The worker returns output
over its standard streams. File writes, network access, subprocesses, and Tk
windows remain unavailable. Source includes still resolve only to the uploaded
files. Timer callbacks run during each job, so timed component replay does not
guarantee deterministic behavior or keep a window or interpreter alive between
jobs.

## Private Tk display on Linux

The optional companion API operation `tcl-tk-run` requires `--allow tcl-tk-run`
and a single-use plan for `linux-private-xvfb-v1`. It requires a non-root x86_64
or aarch64 Linux host with merged `/usr`, system Bubblewrap, Python with Tk,
Xvfb, XKB, fonts and libseccomp. The fixed worker refuses to start if any of
the namespace or syscall restrictions cannot be installed.

The worker starts one private 640 × 480 Xvfb display with a new Xauthority
cookie and TCP disabled. It runs the approved source in a safe Tcl/Tk child.
After Tk and its timer notifier initialize, the syscall filter blocks new
sockets and process creation or execution. Uploaded files are available by
name through the existing `source` alias. The worker has its own temporary
filesystem and no host desktop, home or network. The job deadline, output
limits and renewable lease apply to the entire display lifetime.

The display is offscreen. Safe Tk supports contained widgets and disables
host-facing commands such as clipboard access and standalone toplevels. In
Mobile, select **Run Tk and capture preview…** and approve the source. The
console shows its result and a 640 × 480 PNG of the private display after
execution. The preview is a still image. The client verifies its size, format
and hash before decoding it. Native qualification must exercise the widget and
callback, prove the image is visible on the device and verify cancellation,
lease expiry and refusal outside the approved inputs.

For event-driven scripts, an operator can also allow `tcl-tk-live` on a host
with libXtst and XTEST support in Xvfb. Mobile
requests a separate approval with **Interact with private Tk…**. The approved
job lasts at most 20 seconds, subject to the configured output limit and the
five-second renewable lease. It returns an initial frame, then accepts one
tap or key at a time. Each accepted action produces a verified new frame. The
session accepts up to eight actions. Scripts must return after creating their
widgets so the worker can start its event loop.

`POST /v1/jobs/<id>/tk-input` accepts one JSON event up to 256 bytes with
bounded pixel coordinates or a fixed key name. The companion appends each
sequence once to a single file mounted read-only in the private worker. It
rejects input for inactive or expired Tk jobs. The JAR worker uses its existing
`/input` endpoint.
The worker opens XTEST only on its own Xvfb display before applying seccomp.
No desktop socket or clipboard crosses the sandbox. Android and Linux native
qualification must verify a click and a keypress change the same private
widget, and that cancel or lease expiry stops the worker and the display.

## Application integration

Add `TclComponents.definitions()` to the application registry. Construct `CompanionTclClient` from the injected authenticated `CompanionClient` and selected host label. Pass it to `tclEditorTool`, with `TclComponentActions` that route events to the selected circuit instance and cancel queued events. The simulator consumes each valid tick request in the same step that advances the editor clock. The factory neither chooses a host nor stores credentials. The editor must use its instance-scoped event delivery; never broadcast a component ID across subcircuit instances.

The editor keeps approval for the selected component instance and source until it is revoked. It submits one ordered frame at a time and replays the preceding frames to restore script state. Approval, generation, source digest and completed-frame cursor prevent stale replies from publishing after a reset or newer approval. Executor errors produce a visible diagnostic and unknown outputs. `TclSubmissionState` guards console results separately.

The companion loader registers `companion.tcl_runtime.register`. Imported modules only register fixed operations; no source is evaluated at import.

## Verification

`TclComponentsTest` covers Reds pinning, literal VHDL parsing, preserved invalid sources, no implicit execution, explicit events, stale results, output delay, unknown states and bounded replay. `CompanionTclClientTest` covers the wire adapter, approved source hashing, operation grants, invalid replies and remote cancellation.

Run the real Tcl executor checks with:

```sh
python3 -m unittest companion.tests.test_tcl_runtime -v
```

The differential protocol tests launch the **unmodified original desktop wrapper**, communicate over a local socket and compare its observations with the companion replay. They cover both edges, unknown clock, examine holding outputs, `run`, padding and truncation. Set `LOGISIM_DESKTOP_SOURCE` to a desktop checkout when it is not adjacent to this repository. They explicitly skip when that source or `tclsh` is unavailable:

```sh
LOGISIM_DESKTOP_SOURCE=/path/to/logisim-evolution python3 -m unittest companion.tests.test_tcl_desktop_protocol -v
```

`companion/fixtures/tcl/counter.circ` and `counter.tcl` provide a portable example. The source is a user-supplied fixture; opening its circuit alone leaves Tcl stopped. JVM and local Tcl execution checks do not prove physical mobile interaction or companion connectivity from a device.
