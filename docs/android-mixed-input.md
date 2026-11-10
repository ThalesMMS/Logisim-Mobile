# Android mixed-input verification

This suite drives the mounted editor through Android's public `UiAutomation`, `MotionEvent`, keyboard and accessibility APIs. It covers issue #27 with nine isolated cases:

| Case | Native input and observable result |
| --- | --- |
| `cancel` | Touch drag followed by `ACTION_CANCEL` preserves exported XML. A subsequent valid drag moves the gate to `(250,120)` and one Undo restores the original. |
| `mixed` | Touch selects, a keyboard arrow moves by ten circuit units, and a stylus drag moves to `(260,120)`. Each Undo reverses one operation. Exported wires retain two distinct logic nets. |
| `context` | Secondary mouse click opens a separate application popup. Duplicate is invoked inside that popup, creates a distinct component ID at `(220,120)`, and Ctrl+Z restores the document. |
| `longpress` | A touch long-press on a component opens the application context popup with Copy and Duplicate. Back closes it and the exported XML is unchanged (#326). |
| `focus` | Ctrl+N leaves the project open while its label field has focus. Ctrl+D and Delete leave the component collection intact. Apply commits the empty label. With canvas focus, Ctrl+T advances one tick and Ctrl+D duplicates the selection. |
| `scroll` | Ctrl+scroll zooms in around the pointer. Unmodified scroll pans and the inverse restores the position. XML stays unchanged. |
| `pinch` | Expanding a two-pointer touch span zooms in with the injected ratio and preserves the anchor. It does not edit the document. |
| `focusloss` | A drag preview is interrupted by opening and focusing the component inspector. The helper then sends a normal UP. Exported XML remains unchanged. |
| `io-release` | Button output changes from low to high while pressed. Focusing the inspector releases it to low before the helper sends UP. |

The suite tests synthesized system events. It does not qualify physical mouse, trackpad or pen ergonomics.

Use `--case mouse-mappings` to exercise imported Logisim 2.7.1 bindings. The case checks that Ctrl + secondary click changes the simulated input while Wire is selected, and that Shift + middle click places an eight-bit Pin. It runs separately from the nine standard scenarios.

## Build and ownership

Apply the test sources to the Android E2E checkout that declares the isolated `e2e` build type. The production APK is not modified by these sources. `DocumentIntegrationInstrumentation` dispatches to this suite only when `mixedInputCase` is present.

The target package must end in `.e2e`. The injector checks the focused application before each gesture or key sequence. It refuses global navigation keys. The fixtures belong to the separate test APK's provider and each import has a unique name and labels.

Coordinate the emulator serial with its owner before building, installing or executing. The runner neither installs artifacts nor creates, resets or wipes an emulator.

With the matching app and test APKs already installed:

```sh
python3 scripts/run-android-mixed-input.py \
  --serial emulator-5558 \
  --apk /absolute/path/androidApp-e2e.apk \
  --test-apk /absolute/path/androidApp-e2e-androidTest.apk \
  --source-revision <full-production-source-sha> \
  --case all \
  --output outputs/android-mixed-input/<unique-run>
```

The runner verifies both installed APK hashes before and after execution. The source revision is a declared build provenance field; the external build receipt must link those APK hashes to that source and test-source revision.

## Evidence and qualification

Each XML oracle uses the editor's visible Copy action and a fresh clipboard nonce. It parses the complete copied project and checks the unique fixture identity. Topology assertions use that exported document.

Input receipts contain event source, tool type, pointer coordinates, buttons, modifiers, timestamps and injection results. Screenshots, failure hierarchies and XML exports are copied from a UUID directory owned by the isolated app. Archive extraction rejects links, traversal and oversized payloads. A changed APK or new ANR prevents qualification.

Only all nine successful cases produce `SYNTHESIZED_SYSTEM_INPUT_PASS`. A successful selected case is `PARTIAL`; a missing case, failed native result, absent receipt or changed artifact fails. A PASS receipt also requires exactly one successful instrumentation terminal code (`-1`); failure or crash diagnostics reject it. Protocol records after that terminal are rejected. If an interrupted instrumentation remains active, the runner stops only its isolated target process.

## Preparation evidence

Before device execution:

- The Java injector compiled against the public Android SDK 35 with `javac --release 8`.
- The complete Kotlin scenario file compiled with Kotlin 2.4.21 targeting JVM 17 against SDK 35 and an immutable copy of the core JAR.
- Eleven Python tests passed for result qualification, successful instrumentation termination, APK identity, package isolation and evidence extraction.
- The NOT fixture had three components, two wires and two distinct logic nets. The button fixture had two components and one wire, and its pure core result was low, high after Press, then low after Cancel.

These checks prove compilation and fixture preconditions. Native qualification is incomplete and issue #27 remains open.

## Labels in native dialogs

The first device attempt stopped at the import report before injecting any gestures. Its visible Close label was a non-clickable TextView beneath an unnamed clickable View. The selector now resolves labels to the nearest visible, enabled clickable ancestor inside the requested subtree. Repeated labels for that same control deduplicate; two distinct controls remain ambiguous. An incomplete tree is retried within the existing deadline.

The regression fixture is a structural projection of the captured dialog. Host tests cover that shape, direct and coalesced labels, nearest ancestors, ambiguity, hidden/disabled controls and scope boundaries. No coordinate-proximity fallback is used.

## Action state and inspector lifecycle

The second cancel attempt passed the import report and fixture export, then stopped before the drag because the harness expected `isSelected` on the click target. The resolved Android Compose delegate exposes a selected Button as `checkable` and `checked`; `selected` is used for a Tab. The harness now keeps the click target separate from its owned semantic state. It accepts explicit selected or checked state, rejects conflicting state, and does not borrow state from nested clickable controls or other actions. Thirteen resolver tests cover both state mappings and the dialog selector cases. The mode receipt includes the click target and semantic source nodes.

The focus case checks the empty focused draft, invokes the visible Apply action, and then checks the exported label. Inspector sheets are dismissed through the unique visible descendant advertising the public `ACTION_DISMISS` action. The focus-loss checks still occur before the pointer helper sends UP. Failure hierarchies include structural paths, selection, checked/checkable, enabled/clickable, focus and state-description attributes.

These are harness corrections. A new native execution is required before qualifying any of the nine cases or closing issue #27.

## Readiness diagnostics

The third cancel attempt stopped while waiting for the fresh fixture, before selecting a mode or injecting a gesture. Its later hierarchy contained the new fixture's import report. That capture does not identify which roots the wait received or whether Close was attempted, so no cause is assigned to R3.

The diagnostic variant acquires each candidate window root once and uses that same object for validation, logging and return. Acceptance remains either a target-package active root or a target-package root from an application window marked focused. The `active` flag is recorded when windows are enumerated and does not become an additional acceptance condition. No root queries are added to fill diagnostic fields.

Each acquisition records its callsite, wait/attempt IDs, monotonic duration, package/window ID, outcome and the metadata of inspected windows. Unqueried metadata and truncated inspection are explicit. Tree traversals record completion and missing children. Import-report detection, Close resolution and fixture-label matching record their own outcomes using the trees already read. Click records distinguish emission from the returned boolean or exception. A wait's latest outcome is separate from its historical exception, including when a later predicate returns false.

The receipt keeps a tail of at most 128 diagnostic records and 32 wait summaries, with serialization budgets of 48 Ki characters and 16 Ki characters respectively. Each root record includes at most 16 inspected-window records; fixture matching retains at most eight observed MI labels. Total counts, sequence ranges, logging errors and omissions by capacity or size are reported. Logs contain values rather than accessibility objects. Seven host tests cover retention and wait-state history. The deadlines and polling interval remain 15 seconds and 50 milliseconds.

R4 executed this diagnostic source. Compilation and host tests do not qualify native input behavior.

## Fresh-fixture preparation

R4 recorded a resolved Close action returning true after the shared preparation deadline. The immediate label query still received the modal; the later failure hierarchy contained the editor and exact expected label. That sequence identifies the failed predicate, without attributing the earlier query costs to a product subsystem.

Preparation now acquires one root and traverses one complete tree per iteration. Report detection, Close resolution and label matching reuse its cached records. An incomplete tree cannot emit Close or qualify readiness. A visible report takes precedence over a label, and a report iteration never evaluates the label. Close is attempted at most once per import, including when its callback fails. After the attempt, only a later polling iteration can observe the editor and qualify readiness.

The same 15-second deadline is passed to the readiness decision and checked again after click logging, immediately before `performAction`. No phase receives another deadline. Eight host tests execute the readiness policy with counted Close callbacks, covering Wait, Close once, the outgoing modal, Ready, incomplete observations, unresolved actions, expiration, callback failure and the unchanged deadline. The total budget and 50-millisecond polling interval remain unchanged. This preparation reached the native gesture in R5.

## Observing selected components and returning popups

The fixed-APK R6 run preserved the before/after CANCEL XML byte for byte. It then stopped because the selected NOT body no longer had an accessible component node, while its port targets were present. The full scenario remained failed before the normal-UP and Undo controls. The earlier viewport anchors were not retained, so that run cannot retrospectively prove that reusing its screen point was safe.

The cancel case now captures the real body center, input/output anchor centers, canvas bounds and window ID from one complete tree before injecting CANCEL. After the unchanged XML assertion, it observes both anchors again and requires exact equality of their absolute x/y coordinates, canvas bounds and window ID. Only then does it reuse the literal previously observed body point. It does not infer a body or bounds from port nodes. Both observations and the reused point are retained in the case receipt. Missing, duplicated, hidden, disabled or invalid targets prevent authorization.

Undo focuses the observed input anchor only after Select is observed active in the same complete tree. The existing moved position, topology and exact one-Undo restoration checks remain unchanged.

The independent context run successfully invoked Duplicate, then tried to find the export toolbar in the outgoing popup. It now waits for the original editor window with a complete canvas/project-toolbar observation before exporting. It does not click Duplicate again. Both waits use the existing 15-second/50-millisecond helper. R7 completed the CANCEL, normal-UP and single-Undo case; context R2 completed Duplicate and single Undo. Both are successful individual cases, with overall suite qualification still partial.

## Translating a previously observed target

Fullmixed R2 passed RIGHT's exported position `(210,100)` and connectivity checks, then stopped before stylus because the selected NOT body was occluded. It emitted no stylus input. The revised case captures the real body and viewport before RIGHT. After the existing XML oracles, a separate model guard requires identical component IDs, unchanged target geometry and unchanged anchor components, permitting only the expected NOT displacement `(10,0)`.

Only after exact window/canvas/absolute-anchor checks does the helper calculate the previous point plus the validated world displacement times the originally observed scale. Delta and scale must be finite, scale positive, and the resulting point inside the unchanged canvas. The case records the result as `TRANSLATED_FROM_XML`, with its baseline, delta, scale and frame proof. It does not claim a new body observation or use port centers.

The focus case uses the same NOT observation with zero displacement after the expected empty label is applied and geometry/position are verified. It does not focus another component before Ctrl+D. Its duplicate check also requires two distinct NOT components at the expected positions. The baseline project is the existing fixture export, so no extra preparation action is introduced.

Seventeen Java host tests cover the frame and translation guards. The committed `scripts/probes/NativeTargetGeometryProbe.kt`, compiled with the actual helper and core, passed fifteen model cases against the R2 exports, including unexpected displacement, IDs, anchor/target geometry and label changes. The complete scenario plus model helper compiled with K2/SDK35.

The complete mixed case passed in `3d231c53-native-mixed-439`: touch selection, RIGHT, stylus movement and two individual Undos. App SHA256 was `6c094eb693758117fd19ab5b54c0a87718cec5046d34e4ef8566dfe5128f3556`, test SHA256 `f5bca14d490db09c2045700f525251138568b961d918532ecfa23a20b56db267`. Artifacts stayed stable and last ANR was unchanged. This selected case reports PARTIAL suite qualification.

## Label-clear focus opt-in

With the same artifacts, the focus case reached its unchanged geometry, world position `(200,100)` and empty label checks. It then stopped at the strict viewport guard. The preserved frame in `3d231c53-native-focus-439` shows window 23249 and canvas `[0,655,1080,2119]` unchanged. Both 126-by-126 anchor rectangles moved by `(0,-26)`, with x coordinates unchanged. That observation does not identify the cause of the pan.

Only the focus branch now permits this class of common translation, after the model guard has verified delta zero and the exact empty label. Both complete anchor rectangles must remain inside the same canvas, with unchanged dimensions and identical displacement vectors. The point is the literal prior body center plus that common vector; the recorded example maps `(703,1326)` to `(703,1300)`. It must remain finite and inside the canvas. The receipt retains both anchor vectors and marks the point as derived from their common displacement.

This is a separate opt-in method. CANCEL and mixed continue to reject pan, including the same input frame accepted by the label-clear guard. Tests reject unequal vectors, one moved/missing/ambiguous anchor, clipping before or after, dimension changes, zoom, another window/canvas, incomplete trees and an outside target. Model probes also reject unchanged labels, changed target position/size/facing and moved anchor components. The focused Ctrl+D still requires the same NOT target and two distinct NOT duplicates at the expected positions. Native execution of this focus-only revision remains pending; no timeout, app, injector or existing document oracle changed.

## Fixture source ownership

Creating a fixture through the instrumented editor's resolver required a prior write grant to the test provider. A case could therefore fail before opening the editor when run without document-picker history.

Each mixed-input case now sends an explicit preparation request to the test APK's receiver. The receiver asks its provider to write the exact XML under a new UUID-prefixed filename. The provider accepts this operation only from its own UID and clears its simulated revoked state after the write succeeds. The receiver validates the returned URI, grants the editor read access to that document, and returns it in the ordered-broadcast result. The editor neither creates nor writes the source. The legacy reset and replacement broadcasts, provider manifest and existing grants are unchanged.

Eleven host tests run the actual receiver and provider against Android boundary stubs. They cover exact UTF-8 content, fresh identities, read-only grants, prior revocation, foreign callers, write failure, malformed input, forged URI responses and both legacy broadcasts. They do not prove Android's IPC permission enforcement.

For the coordinated device check, build the test APK with this delta and record both APK hashes and source revisions. Clear only the isolated `.e2e` target's data to remove prior URI permissions; keep the provider APK installed. Run one selected mixed-input case without opening a document picker first, then inspect its prepared source URI and exported XML nonce. A separate check can put the provider in `fixture:deny` before starting another case. Preparation must succeed without root write access, and the target must have only read access to the newly returned URI. Do not reuse these test-APK changes in the cancellation A/B comparison that preserves the earlier injector artifact.

No device execution has been performed for this ownership delta.
