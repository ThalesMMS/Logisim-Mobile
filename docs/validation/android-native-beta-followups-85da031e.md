# Native I/O release and pinch on Beta 85da031e

The dedicated `io-release` case passed on Beta producer `85da031e4ed11474c2e763976392729810ef142d`, whose production source is identical to `6cef0542`. The separate `pinch` case failed. Both results are preserved here; issue #27 remains partially qualified. Neither case was retried for this receipt.

| Case | Native case time | Host wrapper time | Result |
| --- | ---: | ---: | --- |
| I/O release | 7.839 s | 12.245 s | Output Low, accepted TOUCH DOWN, output High, Inspector Label focused, output Low before UP, accepted UP, output remains Low. The complete final XML equals the fixture byte-for-byte and contains two components and one wire. |
| Pinch | 31.023 s | 33.592 s | Timeout waiting for an expanding touch pinch to increase the observed span. The SDK accepted 17 motion events. The test did not reach ratio, anchor or final XML assertions. |

The I/O release proof retains screenshots of the held High output, focused Inspector field, Low output before UP, and the completed case. Its ordered wait milestones are retained in `elapsedRealtime` summaries. The archived synchronous source calls DOWN, the held callback, then UP; that callback checks Low and takes the pre-UP screenshot before returning. A release caused only by UP cannot satisfy that check. Motion-event timestamps use `uptimeMillis`; no comparison or subtraction across these two clocks is made. This does not trace an internal Canvas callback or establish physical touch ergonomics.

Pinch retains DOWN, POINTER_DOWN, thirteen MOVE events, POINTER_UP and UP with SDK acceptance records. Those records do not establish receipt by the Canvas. The Native result contains `Timed out waiting for expanding touch pinch zooms in` with the last outcome `RETURNED_FALSE`. Its instrumentation terminal is `0`; the wrapper rejects that terminal and preserves FAIL. Only the fixture and pre-pinch circuit were exported. There is no successful ratio, anchor or post-gesture document assertion in this run, and this receipt does not assign a product, renderer or observer cause.

The app APK was `67e81d691beb3830cf49325c81ccffca9b66f2bf8aa98fdc46c9ee7d1f9e5e2c`. These cases used the separately built test APK `04a4fdecd9dfdae6577ce040a7595e8c96bb62ac821080ee6faa0f1de7cde52f` from producer `f4f72c6d`, carrying observation patch `4a1658d7`. It replaces the test APK listed in the original Beta artifact manifest. The preserved test build receipt describes assembly-time checks; it does not itself claim Native execution. Native execution is recorded by these two subsequent manifests and protocols.

The preflight retains the supplied three APK hashes and their compiled manifests before installing only the isolated test APK. Both case wrappers checked installed app and test bytes before and after execution, and their last-ANR records were unchanged. A separate read-only sidecar after both cases confirms all three installed hashes, including snapshot helper `2f17d8af178460537e2e4106d8ea79063fa938b0fdebcae1710b9b0294fee387`.

The later `pidof` query returned no process. The two latest retained exit-info entries report `USER REQUESTED`, `FORCE STOP`, and `due to finished inst`. This empty PID is not classified as a crash. The sidecar was collected without relaunching the app. Timings describe these synthesized cases on the API 35 emulator; they are not issue #48 performance measurements or cross-boot comparisons.

The [JSON receipt](android-native-beta-followups-85da031e.json) records original paths, complete-file hashes, timing distinctions and unqualified assertions. Its [artifact directory](artifacts/android-native-beta-followups-85da031e) includes complete manifests, Native results and protocol output, circuit files, screenshots, failure tree, preflight, compiled manifests, producer records, post-run state and the two relevant test source files. No APK or JAR is copied.

Run the offline archive check from the repository root:

```sh
python3 docs/validation/artifacts/android-native-beta-followups-85da031e/verify.py
```

The verifier checks raw-file integrity, installed identities, sequence ordering, exact complete XML preservation and the retained pinch failure. Its negative controls alter the sequence and XML while recomputing payload hashes, so semantic rejection is checked independently of file-integrity rejection. It does not run the app again.
