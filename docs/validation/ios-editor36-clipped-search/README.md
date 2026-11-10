# Clipped Search preparation

This private XCTest correction is SOURCE_PREPARED. Its native execution is NOT_RUN. R3 remains a failed execution of harness `91bc58e38e0171777df5951b018a23ac6da8da69`, with 15 completed steps, one failure and a 300.753-second case duration. No new Simulator execution follows that result.

The literal R3 [tree](source-only/r3-tree.txt) exposes one `a11y-circuit-search` TextView beneath `a11y-circuit-list`. The [observation](source-only/r3-observation.txt) reports `enabled=true`, an empty label and frame `(24,158,342,24)`. Its usable viewport is `(24,182,342,604)`. The field ends exactly at the viewport's top edge. The failed helper rejected both the empty canonical label and its 24-point height before requesting any reveal gesture. The tree's `value=E2E_IN` is not its canonical label, and `Keyboard Focused` does not establish a visible keyboard.

The private resolver now separates preparation from final admission. Its unchanged query must still identify at most one candidate in the required native role. A present target must be enabled and have a finite frame with positive raw dimensions. When that frame is outside or partially outside the usable viewport, the existing bounded backward reveal may prepare it. A fully contained field with an empty or wrong label, or a target below the 48-point minimum, fails immediately.

Final admission still requires the canonical label, enabled state, a frame of at least 48 by 48 points, complete containment in the usable viewport and native hittability. Components may skip its tap only when the unique observed Button is publicly selected and retains its canonical label, enabled state and complete minimum-sized frame. A clipped or noncanonical selected representation cannot use that shortcut.

The query and ambiguity guard are byte-identical to R3. The change adds no direction, gesture distance, text replacement, timeout, retap or fallback. The existing maximum of twelve short backward gestures remains. An observed target below the viewport still fails that preparation. The full 62-step test, Start/Complete bodies, output assertions, literal contract and shared text helpers remain unchanged.

The [source checks](source-only/validation.json) passed the iOS Simulator SDK typecheck of the seventeen target Swift files and the fifteen unchanged geometry cases. The [readiness probe](source-only/readiness-validation.json) extracts the actual private function and passes seventeen cases without a device. It pins the entire R3 tree, observation and EN/PT canonical labels. The literal 24-point state permits preparation only; a revealed canonical 48-point field permits admission. Visible empty or wrong labels, disabled targets, undersized contained controls and invalid finite geometry continue to fail. Separate cases prevent skipping an offscreen wrong-label target or an offscreen undersized canonical target.

The duplicate fixture inventories two literal candidates. Byte comparison preserves the existing native query and rejection guard. This host check does not execute an XCUI query or establish duplicate handling on a device. The [receipt](source-only/receipt.json) records the original source, reviewed hashes and check results.

Run the readiness probe from the repository root with a new output directory:

```sh
python3 docs/validation/ios-editor36-clipped-search/source-only/check.py --output outputs/clipped-search-check-new
```

The SDK and geometry checks use the existing verifier:

```sh
python3 docs/validation/ios-editor36-joint-header/check.py --output outputs/clipped-search-sdk-new
```

Both commands serialize their compiler invocation through `/tmp/logisim-gradle-serial.py`. The checks do not build an app or operate a device. They establish source invariants and readiness behavior, not successful native scrolling, focus delivery or an Editor62 pass.
