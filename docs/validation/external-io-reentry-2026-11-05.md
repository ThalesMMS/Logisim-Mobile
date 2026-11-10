# External I/O session re-entry

The External I/O panel retained a remembered Telnet entry after runtime cleanup removed it from the bridge. A later confirmation could create a listener outside the bridge's polling and cleanup map. The panel now obtains the canonical entry through `externalIoTelnetEntry` on each composition.

A recreated entry also restarted event IDs at one, although its circuit instance retained a higher acknowledgement. The editor then discarded the new session's first received bytes. New entries now start from the instance's applied sequence. A bridge-wide sequence counter also preserves the order of already accepted events that the editor has not applied yet. Event IDs may have gaps, as permitted by `TelnetRuntimeEvents`.

## Reproduced results

The base is `50668f45230ada6f49b10c1e05fe983b8ea183c4`. Before testing, the existing panel lookup was extracted without changing its `remember` behavior. The new state argument was present but did not initialize the entry yet. The [machine receipt](external-io-reentry-2026-11-05.json) records each stage and its source/log hashes.

| Stage | Observed result |
| --- | --- |
| Initial active-close TCP restart | 18 tests, two failures. Event ID expected `8`, actual `1`; the second listener also failed to bind its recently closed port. |
| Peer-disconnect variant isolates event ordering | 18 tests, two failures. Event ID expected `8`, actual `1`; real HTTP/TCP input expected `[65, 66]`, actual `[65]`. |
| Canonical lookup and applied-state initialization | 18 tests passed. This intermediate result did not cover accepted events still waiting for application. |
| Queued-sender regression | 19 tests, one failure. Applying the accepted rounds produced `[65]` instead of `[65, 65]`. |
| Final bridge fix with listener-reuse dependency | 20 tests passed in 9.235 seconds. |

The final suite retains both real TCP restart variants. One closes the server-side connection during cleanup. The other observes peer disconnect first, then performs cleanup. Both reopen the same configured port, preserve the document, retain the second session's first byte, and finish with peer EOF and a refused connection to the closed listener.

The active-close variant depends on the separate [listener restart correction](telnet-listener-restart-2026-11-05.md), original commit `2aa2a989786ba33ebd4357d63a3877a82d8491cd`. The listener failure was retained as a product failure and was not bypassed with another port.

## Composition lookup probe

A local `ControlledComposition` probe calls the actual production lookup used by the panel. With the old lookup, removing the entry and recomposing retained the removed object: `registered=false`, `entries=0`. The corrected lookup creates the registered entry: `registered=true`, `entries=1`.

This JVM probe uses a temporary no-op Android tracing adapter because the host Android SDK jar throws from tracing methods. The probe source and adapter are identical before and after. They do not alter entry state. No tracing adapter was added to the repository or application.

## Verification and limits

The bridge, panel source, and tests were compiled directly with Kotlin and Compose compiler 2.4.21 against the same frozen set of 1,838 editor/core reference class files. The normal source-boundary and formatting checks passed. No Gradle build or native UI session was run for this change.

The permanent regression tests can be run through the repository's normal test task:

```sh
./gradlew :composeApp:testAndroidHostTest \
  --tests '*ExternalIoRuntimeBridgeTest*' \
  --tests '*ExternalIoRuntimeBridgeHttpTest*'
```

The queued-sender test exercises the sender's accepted-before-applied contract against the actual component evaluator. It does not establish a reproduced race in a mounted application. The composition probe verifies the lookup, not `Runtime` lifecycle effects or native controls. Android/iOS background, pairing, touch, and display qualification remain separate Native61 work.
