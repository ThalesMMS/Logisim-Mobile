# Private Explorer Search preparation

This test-only change prepares a later execution of the selected Editor36 scenario. **Native execution is NOT_RUN.** The original iPhone failure remains preserved in commit `4c60e16ed574eea549cff125c7bfb60f82841811`; it is not converted into a pass.

In that run, the source port was reached, but the next destination search followed twelve upward scrolls with one 588-point downward scroll. The Explorer was absent afterward. The recording shows Search above the viewport and later visible while the sheet is displaced downward. Application source places Search in the list itself. These observations support revealing that known earlier list item in the backward direction first. They do not identify the internal gesture consumer or prove a dismissal callback.

Only the private `explorerSearch` preparation changes. It now resolves the unique tagged native TextView or TextField and requires its canonical label, enabled state, complete finite frame of at least 48 × 48 points, containment in the observed list/app viewport and hittability. Keyboard, inputView and assistant frames are inspected independently. Its reveal uses at most twelve backward scrolls from the middle of the usable list area. Each distance is the smaller of 96 points and one quarter of that viewport. Geometry is attached before a scroll and before passing the field to the existing text policy. A missing sheet/list or covered viewport fails conservatively.

The helper returns the observed field to the unchanged `replaceEditorText` policy. It does not add a tap fallback or change the global NativeUIHarness. Normalizing out the two new private helpers and restoring the two call-site lines reproduces the complete original Editor source byte for byte. This includes the 62-step test, Start/Complete connection commands and existing Command-A diagnostic probes. The shared literal contract and both base text helpers remain byte-identical.

The complete 17-source XCTest target passed an iOS Simulator SDK typecheck. Fifteen Foundation/CoreGraphics geometry probes passed. They cover the observed viewport and drag span, keyboard and assistant-only obstacles, floating input, unusable regions, a shifted window and invalid input geometry.

A host counterexample exposed normalization of negative raw CGRect dimensions through the width/height getters. The guard now reads size.width/size.height and rejects both negative dimensions. The first host probe also lacked an explicit CoreGraphics import. Both earlier diagnostics are preserved separately; the final SDK typecheck and geometry probes passed. Neither diagnostic represents a native execution.

The [receipt](receipt.json) records source and probe hashes. These checks validate the preparation and coordinate bounds. They do not prove that a short backward drag will successfully reveal Search on the device. No Simulator action, retry, app rebuild or new native result was produced by this change.
