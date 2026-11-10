# Prepared VoiceOver observed-edge capture

Status: `PREPARED_NOT_BUILT_NOT_AUTHORIZED`. This directory contains new test source and a contract. No Xcode invocation, build, XCTest, device query, GUI action or product change was performed. The previous capability test is a separate artifact. Its 1/1 PASS demonstrated service responses and restoration, not focus identity or order.

The smallest proposed unit calls `currentSpeech`, `moveForward` once and `moveBackward` once. Each response is followed by two original main-screen screenshots bracketing one immutable public app snapshot. The snapshot preserves identifiers, labels, element types and frames. Its child indices are tree addresses, not a reading order. The test restores the original reader enabled state and retains both operation and restoration errors.

Apple's public `XCUIVoiceOverService.Output` exposes only `utterance`. It supplies no element, identifier or frame. `XCUIElementAttributes.hasFocus` promises UI focus without promising VoiceOver focus, so this capture omits it. The source does not parse debug descriptions, force focus, tap, activate or use a rotor. A native PASS means `CAPTURE_ONLY`.

## Independent identity review

A separate review must associate a clearly visible reader outline with exactly one snapshot node by geometry before considering speech. That node must have a unique nonempty identifier. The [contract](reader-contract.json) freezes the geometric threshold and ambiguity policy. Identical ancestor/child frames, an absent outline, uncertain screenshot scaling or a caption alone cannot qualify identity. The prepared [review template](focus-review-template.json) starts as `NOT_REVIEWED`.

The screenshots have not been captured. Whether this runtime includes a visible VoiceOver outline in `XCUIScreen` screenshots is unproven. If it does not, this unit cannot qualify identity. The service API provides no substitute. No phrase matcher or ordinary UI-focus property will be promoted to reader identity.

To report `PASS_OBSERVED_EDGE_ONLY`, review all three states independently, verify the return state identifies the same element as the baseline, verify the intermediate state identifies a different element, and confirm the circuit viewport remains stable. Each actual utterance must be consistent with its independently identified element. Speech may include context or hints, so equal strings are not required for the return state. This would witness one local edge, A forward to B and B backward to A, on the pinned app in this run. It would not approve a global traversal order or choose an expected target from XML/tree order.

## Before any future execution

Root must grant a new exclusive lane and separately approve building and executing this new target. Freeze source, SDK headers, product bytes and the selected method. Do not reuse the previous probe's binary or xctestrun. The owner must provide the existing foreground paused editor, record initial public settings and approve one bounded runner plus a recovery procedure. This preparation contains no runtime command.

No extra navigation is permitted if capture or independent review fails. The test restores only the original enabled state. It does not promise restoration of original reader focus, opaque state or viewport. FailedToStop, timeout, process death or any unverified restoration keeps the lane with the owner until recovery is confirmed.

The source is uncompiled and unexecuted. Compilation and runtime behavior remain unverified. A future reviewed result cannot close issue #28 or qualify activation, actions/rotor, creation, wiring, simulation, save/reopen, physical hardware or audio quality.

## Primary source basis

The local public SDK headers are hashed in `preparation-manifest.json`. `XCUIVoiceOverService.h` declares the reader operations, `XCUIVoiceOverOutput.h` exposes only utterance, `XCUIElement.h` declares `snapshotWithError` and `XCUIElementSnapshot`, and `XCUIElementAttributes.h` defines identifier/frame and describes hasFocus only as UI focus. `XCUIScreen.h` supplies the public screenshot entry point. These headers support preparing the capture, not a claim that screenshot outlines or reader behavior have already worked.

Official references: [VoiceOver service](https://developer.apple.com/documentation/xcuiautomation/xcuivoiceoverservice), [service output](https://developer.apple.com/documentation/xcuiautomation/xcuivoiceoverservice/output), [element snapshot](https://developer.apple.com/documentation/xcuiautomation/xcuielementsnapshot). Apple's web pages currently require JavaScript and link a Markdown view; the local SDK declarations were the inspected technical source for this preparation.
