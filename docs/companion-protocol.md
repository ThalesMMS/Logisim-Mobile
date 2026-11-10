# Companion protocol v1

The Python implementation is `companion/server.py`; trusted operation handlers use `companion/api.py`. All routes require `Authorization: Bearer <paired-token>`. Responses have `Cache-Control: no-store`. Requests carrying any `Origin` header are denied. The server is a native-client API and does not enable browser CORS.

## Start and pair

From the repository root:

```sh
python3 -m companion.server
```

The default URL is `http://127.0.0.1:8765`. The server prints the location of its token file, not the token. The default token path is `~/.config/logisim-mobile/companion-token`; the file is created with mode `0600` and files with group/other permissions are rejected. Transfer the token through a trusted local pairing flow. Treat it as a credential that authorizes every host-enabled operation. Stop the host and replace its token file to revoke pairing.

Enable fixed operations explicitly; for example, once the corresponding handler is installed:

```sh
python3 -m companion.server --allow hdl.validate --allow hdl.start --allow hdl.step --allow hdl.reset --allow hdl.cancel
```

Use `--tool NAME=/absolute/path` to choose an installed executable. The host otherwise resolves registered tool names from its `PATH` without running them during discovery. Non-loopback API addresses require both `--tls-cert` and `--tls-key`; the server requires TLS 1.2 or newer. A LAN endpoint must use a certificate already trusted by the mobile platform. Binding an IPv4 or IPv6 literal is supported. No certificate is generated or installed automatically.

The CLI enables only `verify-inputs` by default. Static adapters are discovered from the repository when installed, but remain disabled. FPGA programming is absent unless the operator supplies `--fpga-targets /absolute/path/targets.json`. This host-only file is never read from a mobile job input. Its versioned schema contains board/cable identities, not commands:

```json
{"version":1,"targets":{"bench":{"board":"ulx3s","serial":"physical-cable-serial"}}}
```

An empty `targets` object leaves programming absent. The loader limits the file to 64 KiB and 32 targets, rejects unknown/duplicate fields and duplicate physical identities, and validates the board/serial. Providing targets does not enable an operation by itself: `--allow fpga-program`, an available toolchain and a request-bound confirmation are still required. This setup does not detect, open or program a board. Real hardware remains a separate verification step.

To program an existing bitstream, pair the app with the configured companion and open **FPGA → Program an existing bitstream**. Select a file of up to 4 MiB, then select and review the programming profile. The review shows the SHA-256 of the selected bytes and the host-selected board, cable and chain position. The companion binds the plan to the input hash, target alias and profile fingerprint. Changing the file or destination requires a new confirmation.

Explicit host profiles add `transport`, `cable` and a zero-based `chainPosition` for JTAG. The names below are examples. Use the exact identities reported by the installed programmer on your host.

| Transport | Format | Host identity |
| --- | --- | --- |
| `openfpgaloader` | `.bit`, `.bin`, `.fs`, `.sof` | Cable model such as `digilent` and the FTDI `serial` |
| `vivado` | `.bit` | Full hardware target such as `localhost:3121/xilinx_tcf/Digilent/210203339395A` |
| `ise` | `.bit` | Explicit iMPACT port such as `usb21` |
| `quartus` | `.sof` | Exact cable label such as `USB-Blaster [1-2]` |
| `usbtmc` | `.bit` | Linux `device` such as `/dev/usbtmc0`, USB `serial` and a review label in `cable`. Omit `chainPosition`. |

An unavailable tool or unsupported host platform disables its profile in the app. A disconnected cable, wrong USB serial or out-of-range JTAG position surfaces a programming error from the companion. The USBTMC adapter checks the attached serial before sending the GECKO command and bitstream. Programmer completion and physical board verification are separate results.

For a board and cable that support persistent programming over JTAG, set `"allowFlash": true` on an explicit `openfpgaloader` profile with a cable model, FTDI serial and chain position. A legacy board/serial alias cannot enable flash. The companion passes `--write-sram` explicitly for temporary openFPGALoader programming. The capability advertises approved aliases as `flashProgramTargets`. Changing the mode invalidates the review. A flash request requires its own plan with `flash: true`, bound to the same target, profile and bitstream bytes.

The BASYS2 iMPACT profile also supports `"allowFlash": true` when `transport` is `ise`, `board` is `BASYS2`, the cable port is explicit, `chainPosition` is 0, and `flashChainPosition` is 1. The host fixes the PROM model to XCF02S and includes it and the PROM position in the advertised profile and fingerprint. The app accepts a source `.bit`; the host generates the MCS before checking the JTAG chain and programming and verifying the PROM. Job results include the source and MCS SHA-256 values. Keep the approved cable connected and reserved throughout the job.

## Routes

| Method | Route | Result |
| --- | --- | --- |
| GET | `/v1/capabilities` | Protocol version, operation grants/availability, tool alternatives, limits |
| POST | `/v1/plans` | Concrete operation/input summary and expiring plan token; does not execute |
| POST | `/v1/jobs` | HTTP 202 plus a job snapshot |
| GET | `/v1/jobs/{jobId}` | Current snapshot |
| DELETE | `/v1/jobs/{jobId}` | Cancellation requested plus current snapshot |

The connection has a 10-second absolute HTTP deadline and a 5-second inactivity timeout. Every POST body needs `Content-Length` and one finite UTF-8 JSON object without duplicate keys. Unknown envelope fields, paths, and operations are rejected. Authentication failures return 401, browser-origin requests return 403, unknown routes return 404, and validated protocol/operation rejection returns 400. A job execution failure is recorded in its snapshot rather than changing the status of later polling requests.

Capabilities resemble:

```json
{
  "protocolVersion": 1,
  "operations": [{
    "name": "hdl.start", "allowed": false, "available": true,
    "missingTools": [],
    "toolAlternatives": [{"tools": ["nvc"], "available": true}],
    "requiresConfirmation": false, "description": "..."
  }],
  "limits": {
    "requestBytes": 6291456, "inputBytes": 4194304,
    "outputBytes": 2097152, "timeoutSeconds": 60,
    "retainedJobs": 64, "requestReceipts": 4096, "requestSeconds": 10,
    "processMemoryBytes": 2147483648,
    "memoryEnforcement": "supervised-process-group", "osSandbox": false
  }
}
```

`allowed` is the host operator grant. `available` describes installed tools, independently of that grant. All `required_tools` and at least one declared `tool_alternatives` group must be available. A handler can still reject a particular backend whose tool is missing. Tool discovery does not assert version compatibility or physical hardware availability.

## Submission, confirmation, and retry

The envelope has exactly these supported fields:

```json
{
  "operation": "verify-inputs",
  "inputs": [{
    "name": "main.circ",
    "sha256": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
    "base64": "aGVsbG8="
  }],
  "parameters": {},
  "requestId": "optional-retry-1"
}
```

File names match `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`. Input names must be unique ignoring ASCII case on every host, so `A.v` and `a.v` cannot collide when staged on a case-insensitive filesystem. Hashes are lowercase SHA-256; base64 is strict. Parameters are an operation-specific object validated by its handler. The server never accepts a generic command, executable path, shell script, or arbitrary module name.

For a confirmation-required operation, send this same request to `/v1/plans`, display its returned `operation`, `parameters`, and `inputs` summary, then put the returned `planToken` in the `/v1/jobs` envelope only after confirmation. The server canonicalizes the JSON request with sorted object keys, excluding `requestId` and `planToken`; every other field, including omitted versus present empty fields and input order, must remain identical. Plans expire after 60 seconds and are consumed once. A changed or reused plan returns `confirmation-required`.

`requestId` is optional, 1–64 letters/digits/dots/underscores/hyphens. Repeating the same request ID with the same canonical request returns the existing job, including after plan consumption. Reusing it with different content returns `request-conflict`. Keep the ID until the outcome is known; do not silently create a new one after a transport error. Retry records last only for the current companion session, up to 4096 distinct request IDs. Once full, new IDs return `receipt-limit`; operations without an ID can continue. Use IDs for actions needing retry safety, and omit them for read-only polling. Never retry an uncertain side effect without its original ID.

## Snapshots, artifacts, and errors

```json
{
  "jobId": "32-lowercase-hex-characters",
  "operation": "verify-inputs", "state": "succeeded",
  "result": {"files": [{"name": "main.circ", "size": 5, "sha256": "..."}]},
  "error": null, "logs": [{"stream": "stdout", "text": "..."}]
}
```

States are `queued`, `running`, `succeeded`, `failed`, and `cancelled`. `result` is null before success. A DELETE response can still show `running`; poll until a terminal state confirms cancellation. A completed job retains its outcome when cancelled later. When the 64-job retention budget is full, a new submission prunes the oldest terminal job and its workspace. Polling a pruned job returns `unknown-job`; retrying its original request ID returns `job-expired` with `details.jobId`, without repeating the operation. A changed request with that ID still returns `request-conflict`. Active jobs are not pruned.

Handlers return operation-specific JSON. A file artifact has `{name, size, sha256, base64}` inside that JSON; the client checks decoded size and SHA-256 before allowing import or save. There is no arbitrary host-file download route. Logs are bounded and may be truncated; they must not be treated as an unbounded streaming protocol. A normal nonzero tool exit is available to its handler for a compiler-specific diagnostic.

Protocol errors have `{ "error": { "code": "...", "message": "...", "details": null } }`. Execution errors use the same object in the snapshot's `error`. Clients distinguish transport/TLS/offline failures from server codes including `unauthorized`, `operation-disabled`, `missing-tool`, `confirmation-required`, `hash-mismatch`, `request-conflict`, `queue-full`, `job-limit`, `job-expired`, `receipt-limit`, `timeout`, `cancelled`, `memory-limit`, `resource-limit`, and `output-limit`. Raw unexpected Python exceptions are replaced by `operation-failed`; the server does not return tracebacks.

## Trusted adapter contract

An installed adapter exports `register(registry)` and registers `Operation(name, handler, required_tools=(), description="", requires_confirmation=False, tool_alternatives=(), isolated=False)`. `handler(context, parameters)` returns JSON or raises `JobFailure(code, message, details=None)`.

`JobContext` exposes staged `input_files`, `input_path(name)`, `working_directory`, `output_directory`, `output_path(name)`, `tool(name)`, `run(argv, timeout_seconds=None, stdin=None, *, java_home=None)`, `artifact(path, name=None)`, `emit(stream,text)`, `check_cancelled()`, `cancelled`, and `user_confirmed`. For ordinary jobs, `run` returns `CommandResult(returncode, stdout, stderr)` after a normal exit, including a nonzero exit. Launch, cancellation, timeout, and resource failures raise `JobFailure`. Only host-configured executables are accepted, and a filtered environment points HOME/TMPDIR at the job workspace. Registered shutdown callbacks release sessions/listeners.

Isolated operations require `requires_confirmation=True`. Their capability and plan include `osSandbox: "macos-seatbelt-v1"` and `leaseSeconds: 5`. Missing sandbox support returns `sandbox-unavailable`. A nonzero exit from the sandbox launcher or worker fails the job with `isolated-command-failed`. The error includes the exit status and the job retains bounded logs. An active `GET /v1/jobs/{jobId}` renews the lease. A missed lease reports `cancelled` with error code `lease-expired`. Expiry is final even if polling resumes.

An isolated adapter passes absolute paths to its staged inputs. The trusted handler supplies `java_home` only when the selected executable is the `bin/java` inside that dedicated JDK. A job request cannot choose or widen the runtime grant. For details, see [isolated host workers](interop-policy.md#isolated-host-workers).

Adapters must never derive `user_confirmed` from request parameters, run source at registration/import time, use shell execution, permit source-controlled host paths, or leave unmanaged child processes. Resource and privacy limitations are documented in [interop-policy.md](interop-policy.md).
