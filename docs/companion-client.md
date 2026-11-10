# Companion client

The Kotlin client implements Companion HTTP protocol v1 in `core`. Android and JVM use `HttpURLConnection`; iOS uses an ephemeral `URLSession`. Neither implementation changes platform TLS trust. Both use direct connections, refuse redirects, disable caches, limit response bytes, and apply a total request deadline. Cancellation closes the active request.

## Pairing

`CompanionPairingPanel` is an isolated Compose panel. Its `onConnected(client, capabilities)` callback supplies the verified client to the host screen. `onDisconnected()` invalidates that selection. Opening the panel performs no network requests. **Verify host** sends only `GET /v1/capabilities`. The panel keeps the token in memory and clears its input after verification; it does not save credentials or submit jobs.

Use HTTPS for another machine. Plain HTTP is accepted only for the literal addresses `127.0.0.1` and `[::1]`. Android network security and Apple transport security exceptions have the same scope. On a phone, loopback refers to that phone. It does not refer to a desktop on the local network.

```kotlin
val connection = CompanionConnection(
    CompanionEndpoint.parse("https://desktop.example.org:8765"),
    CompanionToken.parse(userEnteredToken),
)
val client = CompanionClient(connection)
val capabilities = client.capabilities()
```

Do not print tokens, HTTP headers, raw request bodies, or arbitrary job results. The built-in request, connection, approval, job, log, and error descriptions omit their sensitive contents. Server messages and logs redact the active token.

## Review and execute

Construct a `CompanionJobRequest` with a registered operation, flat input filenames, and typed JSON parameters. `CompanionInput.fromBytes` copies the bytes and computes SHA-256. The client accepts at most 64 input files and 4 MiB in total.

Call `client.plan(request)` to retrieve the operation, input hashes and sizes, parameters, and confirmation requirement. Display those values to the user. Only the user's explicit execution action should call `plan.confirm()` and then `client.submit(request, approval, requestId)`. Changing the files or parameters invalidates the approval. Server confirmation tokens expire and are single use. Numeric spelling differences such as `1e0` and `1.0` compare as the same exact value; rounded large integers do not.

Retain the returned `jobId` outside a transient panel. Poll with `client.status(jobId)` until `job.state.terminal`. Display `job.error` for a failed job, and pass artifact objects through `job.artifact(value)`. Artifact decoding verifies the filename, canonical Base64, declared size, SHA-256, and byte limit before returning a copy of the bytes.

Closing a screen can cancel its polling coroutine. It does not cancel the remote job. The user's explicit **Cancel job** action should call `client.cancel(jobId)`. Cancelling an HTTP request cannot prove that a submitted job was not accepted. For actions that need recovery after a lost response, retain one stable `requestId` and retry the same request with that identifier. A changed request with the same identifier fails. Do not retry silently without an identifier after `receipt-limit` or `job-expired`. Read-only GET requests need no retry identifier; operations that consume data, such as Telnet polling, do.

## Verification

The integration test fixture imports the actual Python companion server. It registers deterministic artifact and confirmation operations, binds a random loopback port, and uses a disposable token passed through the process environment. It requires Python 3 and OpenSSL. The FPGA test also requires the FPGA handler and Yosys; JUnit reports a skip when either is unavailable.

```sh
ANDROID_HOME=/path/to/android-sdk ./gradlew :core:jvmTest --tests '*Companion*Test' :composeApp:compileAndroidMain
./gradlew :core:compileKotlinIosSimulatorArm64
```

When testing from a worktree that does not yet contain the Python server, set `LOGISIM_COMPANION_SOURCE` to a checkout containing `companion/server.py`. During independent FPGA development, `LOGISIM_COMPANION_FPGA_SOURCE` can point to its checkout containing `companion/fpga.py`.

Tests exercise authentication, real uploads, plan confirmation, idempotent retries and conflicts, status, remote cancellation, verified artifacts, redirects, response bounds, a trickling response deadline, caller cancellation, and rejection of an untrusted HTTPS certificate. The optional FPGA test uploads Verilog and verifies a netlist produced by Yosys without programming hardware.

Native compilation verifies the iOS API bindings. It does not establish HTTP execution, local network permission behavior, or pairing usability on an iPhone or iPad. Those require a device or simulator session with the host screen integrated.
