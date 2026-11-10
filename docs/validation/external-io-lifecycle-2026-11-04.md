# External I/O lifecycle validation

The Telnet bridge previously skipped ownership checks when network polling was paused or a confirmed start had an unknown result. Removing its instance, changing its settings, or resetting its observed simulation state could leave the host listener active until a later cleanup or lease expiry.

The bridge now checks ownership and reset before the pause filter. It retains the existing recovery request when shutdown fails. A plan that was prepared but never confirmed does not start a listener during cleanup.

## Measured software results

The base is `cc43bae617c6afa436ecb8b9fae61fd7cd51d7e9`. The [machine receipt](external-io-lifecycle-2026-11-04.json) contains source hashes, log hashes, and the frozen reference-class manifest hash.

| Run | Result |
| --- | --- |
| Existing Python Telnet tests, real loopback sockets | 24 passed in 1.834 seconds |
| New bridge regression suite before the fix | 15 tests, 5 expected failures in 14.333 seconds |
| Same suite after the fix, plus an unconfirmed-plan control | 16 passed in 9.3 seconds |

Four failures reported `expected:<0> but was:<1>` for active listeners. The real HTTP test also failed after removal because the entry still held a `Connected` status. Its input, output, and document checks had already passed.

`ExternalIoRuntimeBridgeHttpTest` uses the production editor sender, bridge, KMP HTTP client, Python companion, and a real TCP peer. It checks these observable results:

- The editor receives exactly `0, 127, 128, 255`.
- A circuit clock edge queues byte `65`; the peer receives it and the editor removes its acknowledged output packet.
- Those network events preserve the exported document byte for byte.
- A deliberately discarded completed HTTP response pauses polling.
- Removing the owning component then closes the peer with EOF and refuses a new connection to the released port.

The bridge and tests were recompiled with Kotlin and Compose compiler 2.4.21. Both runs used the same frozen editor/core Android-host class snapshot. This was a direct JUnit run, rather than a new Gradle build.

The receipt also records earlier integrated core reports inspected during this task. Those reports contain 38 passing JVM tests and 32 passing Native tests across the selected external-I/O, Telnet, framebuffer, palette, and catalog suites. They cover controlled RTC sampling, local PortIO contention, video writes/reset, document roundtrips, TCP behavior, and HTTP retries. They were not rerun for this bridge-only change.

## Reproduce the focused tests

Run the repository's normal build commands from a checkout containing this change:

```sh
./gradlew :composeApp:testAndroidHostTest \
  --tests '*ExternalIoRuntimeBridgeTest*' \
  --tests '*ExternalIoRuntimeBridgeHttpTest*'
python3 -m unittest discover -s companion/tests -p 'test_telnet*.py' -v
```

The HTTP fixture uses a disposable token in its process environment and binds loopback. It starts no shell commands or hardware operations. Its Python process and sockets close at the end of the test.

## Acceptance limits

The component and platform support decisions remain in [External I/O component contracts](../external-io.md). ReptarLB preserves its document and reports unavailable simulation, matching the reference desktop limitation.

This run does not mount the Compose runtime, operate the pairing/confirmation buttons, exercise mobile LAN permission, or prove native framebuffer presentation. Android/iOS interaction and layout qualification remain separate. Issue #61 also retains its dependency on open issue #44.
