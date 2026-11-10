# Native input controls on Alpha 3d231c53

Three dedicated cases passed on the isolated Android API 35 emulator app. This receipt qualifies only `cancel`, `context`, and `mixed` on producer `3d231c537ac25de240889e327f3fd27a3b1e0902`, based on `50668f45`. It does not close issue #27 or qualify the later Beta app, physical-device ergonomics, or the remaining Native cases.

| Case | Host run time | Observed document result |
| --- | ---: | --- |
| CANCEL control | 11.373 s | DOWN, ten MOVE events and CANCEL kept the full XML byte-exact. The separate DOWN, ten MOVE events and UP control moved NOT from `(200,100)` to `(250,120)` and rerouted two wires into four segments. One keyboard Undo restored the original XML. |
| Context control | 12.119 s | Secondary mouse input opened a distinct popup window. Duplicate added a new NOT with ID `c1` at `(220,120)` and kept the two original wires. The test returned to the original editor window; one keyboard Undo restored the original XML. |
| Mixed input | 13.347 s | Touch selected the NOT; Right Arrow moved it to `(210,100)`; a stylus drag moved it to `(260,120)`. The first keyboard Undo restored the exact keyboard-only document and the second restored the fixture. |

Each case used the same app APK, SHA-256 `6c094eb693758117fd19ab5b54c0a87718cec5046d34e4ef8566dfe5128f3556`. CANCEL and context used test APK `31d6e6cad94ac10f6a0bf21b5bd9c675b2ea80cbbfb71f7917f554f394b97f70`, supplied from producer `b3437c97`. Mixed used `f5bca14d490db09c2045700f525251138568b961d918532ecfa23a20b56db267`, built at `10651a76` with observation patch `439e4d7e`. The injector source was unchanged by that observation patch. The app build receipt and mixed-test build receipt are retained with the evidence. The older control-test producer is declared in its supplied triplet manifest; this package does not add a separate build receipt for that APK.

The host runner checked the installed app and test APK bytes before and after each case. Native results independently retain the app hash before and after execution. Every recorded input was accepted; all three last-ANR records were unchanged. The supplied triplet manifests declare auxiliary snapshot helper `139f59d5…`, but these dedicated Native wrappers did not measure that helper before and after their cases. This receipt does not claim such a measurement.

CANCEL retained the complete pre-gesture frame and verified unchanged editor window, canvas, and input/output anchors before reusing the observed point for the UP control. Mixed retained the corresponding frame and verified the model's exact `(10,0)` world delta before deriving the new stylus target. Neither receipt assumes that a hidden semantic node remains at its old position.

The separate `focus` case from the same mixed/focus host job failed later and is outside this PASS receipt. It remains preserved in its original output. Scroll, pinch, focusloss and I/O release are also separate scopes. These synthesized public Android SDK events do not establish physical mouse, trackpad or pen ergonomics.

The [JSON receipt](android-native-input-3d231c53.json) records the original paths, file sizes and SHA-256 values. Its [artifact directory](artifacts/android-native-input-3d231c53) contains the three original manifests, complete Native results, instrumentation protocol output, screenshots, 15 complete fixture/checkpoint circuit files, ANR records and build provenance. No APK or JAR is copied.

Run the archive check from the repository root:

```sh
python3 docs/validation/artifacts/android-native-input-3d231c53/verify.py
```

This check validates raw-file integrity, case scope, before/after APK identities, accepted event sequences, exact XML restoration, component IDs and attributes, full wire topology, viewport proofs and editor-window return. It reads archived files only and does not execute the app again.
