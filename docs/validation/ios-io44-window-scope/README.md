# I/O document-window scope, source checks

The original iOS IO44 call has four failed test methods. The three methods that stopped at baseline observation expose two editor descriptions in different public `Window` roots. Both descriptions contain the 19 correct probe values in those final trees. The global count-of-one observer therefore rejects the final snapshot. This finding does not establish each earlier snapshot or the app's internal reset state.

The first method copied a complete 54-component, 64-wire XML through the public receiver. It then failed while closing the XML modal. The recorded synthesized point differs from the later observed Close frame. The evidence does not prove delivery to Close or invocation of the app's callback.

This patch binds I/O document queries to the unique public `XCUIElement` window that exposes the exact opened-file status or import origin for the current UUID filename. It retains that accessibility-element reference. It rejects two matching windows, a missing window, an empty tree, and unusable geometry. Canvas summaries still require exactly one marker and one description inside that window.

`nativeQueryRoot` defaults to the application. Only `IoEndToEndTests` overrides it. The default `observedProjectXML` implementation retains its original statements byte for byte. The scoped XML path requires the same retained window after ClipboardReceiver returns. The path requires one owned XML body, one title, and one reachable Close button. It records the tree, frame, and timestamps before rechecking and tapping that same target once.

The filename can be hidden while the XML modal is presented. That stage proves only continuity of the retained public accessibility reference and the owned modal. After the same XML Close, the retained window must expose a positive editor and no XML modal. The harness then always opens that window's existing import report, reads exactly one canonical origin for the current filename, and closes that report once. It rechecks the origin and the same Close frame before the tap. The return gate requires the retained live window, a positive editor, and both modals absent. No window transfer, global fallback, value deduplication, or coordinate retap is allowed.

## Source verification

The [receipt](receipt.json) contains the staged source hashes, the complete Swift typecheck command, and the unchanged XML-body hash. The checks passed as follows.

- Foundation window rules: 107 of 107 checks.
- Negative control without verified origin: one expected failure, with 106 of 107 checks passing.
- Runner and selectors: 20 tests with the approved app's 2,497-key catalog.
- Simulator-target Swift typecheck: all 19 staged Swift files.
- Runner preparation: `PREPARED`, `NATIVE_NOT_RUN`, and `NOT_QUALIFIED`.

The Foundation check is rerunnable with `python3 scripts/test_ios_document_window_rules.py`. The runner and selector checks use `python3 -m unittest scripts/test_ios_e2e_runner.py scripts/test_ios_e2e_selectors.py` with `LOGISIM_TEST_CATALOG_ROOT` set to the approved product source. Preparation and typecheck do not launch or install the app.

The opened-file status is not a persistent identity. In app 3a, selection, mode changes, and property edits replace that status. The import report retains its source origin across these operations, and its show and dismiss actions retain that origin. The [source review](status-origin-source-review.json) records the exact source pin, hashes, and excerpts. The report action is fixed after every scoped XML return, rather than a fallback when a status lookup fails.

Independent source review found no additional scope defect. These checks do not prove native window continuity, successful event delivery, or I/O behavior. A frame center is a candidate point, not a receipt for the point synthesized by `tap()`.

## Proposed native call

The [unexecuted plan](next-run-plan.json) keeps the same four methods, app 3a, 600-second timeout, fixture, and literal oracles. It needs root review, canonical integration, source freeze, artifact revalidation, and a new explicit lease. The previous lease has been returned.

The plan retains the 18 expected XML checkpoints and pairs each with metadata from its own method. Any missing checkpoint remains incomplete. The held controls still need their separate functional observation while the pointer is down. The origin reading occurs after ClipboardReceiver returns, immediately before the report's Close. It is not a second reading after the report has closed. A passive video alone does not prove held output or establish clock alignment. This patch does not promote any of the four original failures, and it does not qualify issue #44.
