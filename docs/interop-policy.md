# External integrations and trust policy

Issue #18 chooses a desktop companion for host executables and network resources. The mobile application keeps circuit editing and ordinary simulation local. Opening a project, importing source, inspecting a library manifest, or discovering host capabilities must not launch a process or listener.

The host selected for development is the user's Mac. There is no hosted service, cloud account, automatic LAN discovery, or telemetry endpoint. The companion starts only when the operator runs `python3 -m companion.server`. Python 3.10 or newer and a POSIX host are required for external processes; this implementation has been exercised on macOS. A Windows executor has not been implemented.

## Capability decisions

The table defines execution location, rather than asserting that every feature is finished. Each feature issue must supply its own UI, fixtures, and integration evidence.

| Capability | Android | iOS/iPadOS | Desktop companion | Failure or unavailable behavior |
| --- | --- | --- | --- | --- |
| Ordinary circuit editing and supported component simulation | Local | Local | Not required | Unsupported components retain an explicit diagnostic |
| `.circ`, memory, source, and generated HDL files | Local parse, edit, import/export | Local parse, edit, import/export | Explicit upload only when requested | Import does not execute embedded source or library code |
| Desktop Java JAR libraries | Inspect identity/manifest; no JVM class loading | Inspect identity/manifest; no JVM class loading | No generic JAR execution endpoint | Arbitrary desktop libraries remain unsupported unless ported or supplied through a separately reviewed adapter |
| Tcl console/components | Local source editing and result display | Local source editing and result display | Fixed Tcl operations, enabled explicitly | Missing interpreter, unsupported script APIs, or host disconnection produce visible errors |
| Verilog/SystemVerilog synthesis/import | Local source editing and netlist consumption | Local source editing and netlist consumption | Fixed Yosys operation | Tool/version, syntax, external file access, size, and timeout failures are reported |
| VHDL co-simulation (BLIF components simulate locally, as in Evolution) | Local source editing and signal display | Local source editing and signal display | Explicit bounded simulation jobs using the selected backend | Capabilities report available tool groups; no substitute result is fabricated |
| FPGA HDL/constraints/DRC/export | Local where implemented | Local where implemented | Optional synthesis/place/route/bitstream jobs | Board/tool mismatch is an error; generated artifacts carry hashes |
| FPGA programming | Review a concrete program plan | Review a concrete program plan | Separately enabled target and single-use confirmation | No device is programmed on import, synthesis, pairing, or capability discovery; physical validation is last |
| Telnet component | Local component state; explicit remote resource session | Local component state; explicit remote resource session | Bounded listener, default loopback; start/stop are explicit | Lease expiry/disconnect closes resources; LAN needs an operator grant and confirmed start |
| Port I/O, video, RTC | Local deterministic model or injected clock where implemented | Local deterministic model or injected clock where implemented | No raw physical GPIO or display passthrough | Physical devices are not implied by component simulation |

## Host selection and permission

The user enters an endpoint and pairs with a random bearer token generated in an owner-only host file. The default API listens on `127.0.0.1`; this is reachable only on the host itself. A phone or tablet needs an explicitly configured host LAN address with TLS and a certificate trusted by the platform. The clients must use standard certificate validation; there is no trust-all or automatic certificate bypass.

The host operator enables individual fixed operations with `--allow`. Registering an operation or finding an executable does not enable it. Tool paths come from host configuration or host `PATH`, never a mobile request. Host listeners outside loopback require a separate `--allow-lan-resource-listeners` grant. Do not expose the API through a public tunnel as an implicit setup step.

Operations that change external resources use a plan containing the operation, parameters, and input hashes/sizes. The app displays this concrete plan and submits its single-use token only after the user confirms. The token expires after 60 seconds and cannot authorize altered input. A valid token proves a matching request was reviewed through this protocol; the host still trusts the paired client to display the review honestly.

## Limits and isolation

The API accepts finite UTF-8 JSON, flat file names, verified SHA-256 input hashes, and registered operation names. Default limits are 6 MiB per HTTP request, 4 MiB of decoded inputs, 64 input files, 2 MiB per result/command output/artifact, 256 KiB retained logs, 8 pending jobs, 64 retained jobs, and 4096 retry receipts. A connection has a 10-second absolute HTTP deadline in addition to a 5-second inactivity timeout, so a slow header/body cannot hold the dispatcher indefinitely. Only one job runs at a time. Artifacts encoded in a result also count toward the 2 MiB JSON result limit, so a maximum-size raw artifact may not fit after base64 encoding.

External tools run with argument arrays and no shell. Each job has a temporary working directory and a 60-second deadline, including queue time. The launcher applies POSIX CPU, 64 MiB per-file size, 128 file-descriptor, and disabled core-dump limits. It supervises the process group's resident memory against 2 GiB and terminates the process group on cancellation, timeout, limit violations, and command completion. On non-macOS POSIX systems the launcher also applies an address-space limit. macOS uses process-group resident-memory supervision because its address-space resource limit is not accepted on the tested host. Supervision samples are not a zero-overshoot memory guarantee.

Ordinary jobs run with the desktop user's filesystem and network permissions. A working directory, filtered environment, fixed argument construction, and resource limits do not prevent every host file read or network request. Each adapter must reject source directives that escape staged inputs, arbitrary command/script fields, unsafe Tcl APIs, and tool-specific file/process escape paths. Use only source and tools trusted for the enabled adapter. A new operation for untrusted code must enforce its own OS isolation policy and qualify the runtime before use.

Handlers are trusted repository code, loaded from a fixed module list. An HTTP request cannot load a Python module, provide an executable path, or register a plugin. In-process handlers must check cancellation and obey their own allocation and resource bounds; POSIX child-process limits do not constrain Python handler code.

### Isolated host workers

Trusted adapters can register an isolated operation on macOS. The host must explicitly enable the operation. Each job consumes a single-use plan bound to its parameters and uploaded file hashes. The companion fails the job if it cannot apply the required sandbox.

The worker uses a default-deny macOS Seatbelt profile. It can read its approved files and the system libraries needed to start. A Java worker also receives read access to its dedicated, host-installed JDK. The sandbox denies writes, networking, child-process creation, and access to desktop services. The worker returns results through its bounded stdout and stderr streams. The existing CPU, memory, output, and job time limits still apply.

An authenticated client renews an isolated job's five-second lease by polling its job ID. The companion terminates the worker on cancellation, lease expiry, or orderly host shutdown. The lease depends on a live supervisor. Recovery from a forced stop of the companion process is outside this policy.

This backend uses the deprecated macOS `sandbox-exec` command and requires host qualification. The regression probes cover system Tcl and a headless JDK 21 worker. Tk display access and the user-facing host operations belong to their own acceptance checks. [Tcl host mode #217](https://github.com/ThalesMMS/logisim-mobile-dev/issues/217) · [JAR libraries #218](https://github.com/ThalesMMS/logisim-mobile-dev/issues/218)

## Privacy, offline behavior, and lifecycle

The app sends only the explicitly selected files and parameters. It does not upload the whole project directory, absolute local paths, credentials from source files, or unrelated documents automatically. Operators should review selected source before submission: source text and compiler diagnostics may contain private material. Bearer tokens, source bodies, and result bodies are not written to HTTP access logs. Handler diagnostics are retained in memory and may include source excerpts.

There are no automatic reconnections that replay a side effect. A request ID makes a retry idempotent only during the current companion process lifetime. Read-only polling should omit request IDs. Once all 4096 retry receipts are used, only new requests carrying a new ID are rejected; polling and resource cleanup without an ID remain available. The app must never silently remove an ID from an uncertain side-effecting retry. On an interrupted request, query the existing job before retrying. Restarting the companion removes that retry history. Pairing and capability discovery do not submit jobs. Offline, expired pairing, invalid TLS, missing tools, disabled operations, queue/size limits, compiler errors, and cancellation remain distinct failures; local editing and export can continue.

The host retains at most the latest 64 jobs and their uploads/results in temporary storage; it prunes an older terminal job when space is needed. Queued/running jobs are never pruned. Save or import desired artifacts promptly; this storage is not a durable archive. A receipt for a pruned result still prevents replay: retry returns `job-expired` and the original job ID, rather than executing again. The service cancels work, closes registered resources, and removes its temporary directory on orderly shutdown. A crash can leave an operating-system temporary directory; no promise of secure erasure is made. After the retry-receipt budget is exhausted, review outstanding outcomes before an explicit restart; a restart revokes in-memory retry guarantees and remote sessions.

## Technical evidence and remaining gates

`python3 -m unittest discover -s companion/tests -p 'test_*.py'` exercises real loopback HTTP and TLS servers, certificate-trust enforcement, pairing rejection, browser-origin rejection, upload integrity/path checks, cancellation, retry identity, confirmation binding, and real subprocess limits. `verify-inputs` proves that receiving a circuit/source file can verify integrity without executing it. The HDL worker additionally exercised `JobContext.run` with real NVC and Yosys/Icarus AND-gate start/step jobs on this Mac; their result was logic `1` at 1 ns. Feature-specific reports belong to the corresponding issues.

These checks do not prove LAN TLS on a physical phone/tablet, store acceptance, arbitrary JAR compatibility, vendor FPGA programming, or hardware behavior. Physical device and board checks remain separate release gates. See [companion-protocol.md](companion-protocol.md) for the wire contract.
