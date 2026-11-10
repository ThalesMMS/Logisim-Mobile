# Native I/O showcase scenarios

These checks drive the production editor for issue #44. They use `examples/io-showcase.circ`, `examples/io-showcase.targets.json` and the literal expectations in `fixtures/user-scenarios/io-actions-v1.json`. The fixture contains all twelve I/O component types, nineteen binary output probes, and a stable LED input that can remain on while a screenshot is taken.

Native status belongs to each platform owner's case receipts. Python preflight, Swift type checking and core replay do not establish native execution or complete issue #44.

## Required application source

Use an application built after the DIP `last` persistence fix `0e75ed9f`. The prior application loses the switch positions on export/reopen and is expected to fail that check. Use the new I/O state descriptions for the screen-reader review when available. The test modules do not call an editor debug endpoint, inject model state or replace the production native document handler.

Before running, retain the source pin and installed APK/app hash using the platform owner's existing artifact checks. Keep the fixture SHA-256 `a2595e0ec949702685cf0465485e430b0491b1d8b31daf3350abe5f3697b55b9` and the contract hash with the result.

## Android

The standalone module is `scripts/android_io_showcase_scenarios.py`. It expects the Android owner's existing `Scenarios`, `AndroidUi` and `android_editing_scenarios._Editing` helpers. Importing it performs no device action. The owner can invoke it after the ordinary E2E cases:

```python
from android_io_showcase_scenarios import run_io_showcase
run_io_showcase(scenarios)
```

The module also accepts an explicit independent-case selection:

```python
from android_io_showcase_scenarios import normalize_io_cases, run_io_showcase
selected = normalize_io_cases(["pointer-controls", "keyboard-tty"])
run_io_showcase(scenarios, selected_cases=selected)
```

The five exact case names are `signals`, `dip-last`, `pointer-controls`, `keyboard-tty` and `dimensions`. `pointer-controls` retains the existing Button, keypad and joystick sequence. Each case still imports a fresh fixture and retains all its original assertions. Omitting the selection runs the original five-case order; an explicit empty list, unknown name or duplicate fails before the module imports the Android harness.

The CLI owner must validate repeated `--io-case NAME` values before constructing `AndroidUi` or performing any ADB command. `--io-case` must require the I/O suite option; it must not silently enable a different suite. Pass `None` when no selector was given and call `normalize_io_cases` on an explicit selection. A selector is a test-only scheduling option. It does not add a product control or change the I/O contract.

`io-showcase-observations.json` records `selectionScope`, `selectedCases`, `notSelectedCases` and the status of each selected case. A case after a failure remains `not_run`. Retain that same selection in the runner manifest/results context. Passing a selected subset does not qualify the full suite, unselected cases, visual review or issue #44. Selecting pointer/text cases does not rerun Signals or automatically retry a failed DIP case.

Do not change the runner's artifact validation or isolated `.e2e` package. The module uses the existing external fixture provider and the Copy/nonce export path. It leaves the principal runner and production code untouched.

The momentary tests fit the actual canvas component and convert the manifest's circuit pointer point through its observed native frame. They send DOWN and UP through Android's input tool. Joystick adds a MOVE to the manifest's validated extreme point. A `finally` sends UP even after an assertion fails. While held, they require the actual canvas output probe to match the literal held value and retain a native tree/screenshot. A pressed control label alone cannot pass this check. The owner must verify `input touchscreen motionevent` on the selected API level and adapt from an observed tree if its behavior differs. These commands have not been executed by the preparing agent.

The current Android owner is replacing idle-dependent hierarchy capture when a TextField has focus. Keep that public `AndroidUi.screen` implementation when adding this module; no private SDK snapshot method is called. The shared `_Editing.visible_in_scope` helper must be present for the inspector's Android local-coordinate bounds. Do not weaken assertions to work around a failed native hierarchy read.

## iOS and iPadOS

The standalone source is `scripts/ios_e2e_ui/IoEndToEndTests.swift`. It is intentionally outside the committed target/runner until the iOS owner stages its resources. It requires the existing `NativeUIHarness.swift` and `ObservedProjectXML.swift`.

The owner adds the source and `io-actions-v1.json` to the test bundle, then adds an absolute filesystem path under `Run.json`:

```json
{"fixtures": {"io": "/absolute/path/to/io-showcase.circ"}}
```

Each test copies that fixture to a unique `.circ` path and opens it with `XCUIDevice.shared.system.open`. It closes only an import report whose origin contains that unique filename, then waits for the production opened-file status containing it. Exports are read from the actual XML editor and attached in full. Every fresh import requests the public Event Step and asserts the nineteen initial probes before starting its case.

The XCTest momentary action blocks until it releases the pointer. Its assertions therefore establish the released state and the connected output afterward. They do not establish the intermediate held frame. Retain a simulator screen recording or a separately sampled native observation during Button/keypad hold and joystick drag. Review that evidence before marking those transient states verified. Do not infer a successful held frame from a successful release assertion.

## Cases and observations

| Case | Native actions | Required observations |
| --- | --- | --- |
| Displays | Activate stable LED twice, RGB green/blue Pins, 8-bit bar Pin, segment Pin and matrix row Pin | Nineteen-probe baseline; LED on then off; RGB red then magenta; bar `10100110`; segment g alone; matrix top row `01111`. Retain focused canvas screenshots and native component semantics. |
| DIP | Toggle switches 1, 2, 1; change 8 to 4 switches; reject 0; restore 8; reset; export/reopen | OUT_DIP0=0, OUT_DIP1=1; `resetbehavior=last`, `positions=2` in complete XML; invalid value leaves the valid four-switch state; reopened output remains 0/1. |
| Momentary and joystick | Hold/release Button and keypad `3`; move joystick with its right control; center; perform drag and release | Pressed/released motor-backed controls; Button output returns 0; keypad C2 returns 1; joystick right is X=1111/Y=1000 and centered is 1000/1000. During-touch evidence must show the transient press/movement. |
| Keyboard and TTY | Type AB; advance two half-cycles per character; send ASCII backspace and form feed; reduce buffer capacity to four and type ABCDE | Queue data A then B then zero; availability 1 then 0; TTY A, AB, A, empty; text editor truncates exactly to ABCD, not an accepted prefix of ABCDE. |
| Dimensions | Change bar height, TTY rows/columns, matrix rows and keypad size, restoring each | Valid schema value in complete export, focused drawing remains reachable, dimensions render visibly, and all twelve types remain present. |

The shared keyboard buffer starts at sixteen characters. TTY starts at three rows and twelve columns. The keypad starts in large PMOD mode. Every source Pin has explicit direction, width, behavior and initial value. Every output Pin has radix 2, so a global hexadecimal preference cannot change the probe oracle.

The two-half-cycle interval is conservative. The core fixture also proves that AB reaches TTY after clock 0→1→0→1, three half-cycles containing two rising edges. The native cases stop after each character rather than running a free-running clock.

## Results and qualification

Android retains `io-showcase-observations.json`, full `.circ` exports, native trees and screenshots through the existing owner. iOS retains `.xcresult` attachments with each complete export, probe description, component frame/state and screenshot. A visual-review record is a pending requirement, not a pass.

The Android module tracks each intended attribute edit and restoration, compares every component, location and serialized attribute against the reference fixture, and compares named-port connectivity across all wires. The shared contract supplies the reviewed layouts for dimensions that change terminal sets or positions. Routes may change segment counts while retaining those terminal connections. Removing every wire, replacing a connection without changing the count, or leaving an edited value after Restore fails. All exports remain available for geometry review.

iOS attaches a corresponding `N-name.io-checkpoint.json` for every complete `N-name.circ` export. It records the accumulated intended attributes and requires the same complete-document postflight as Android. An XCTest pass alone is partial until **every** document checkpoint passes that postflight. Pair these attachments using the owning XCTest case and original attachment names in the `.xcresult` manifest: names can repeat between test methods. Preserve the testcase namespace when extracting files; do not pair documents merely by a shared basename.

```sh
python3 scripts/io_showcase_contract.py \
  --metadata /absolute/case/1-baseline.io-checkpoint.json \
  --document /absolute/case/extracted-uuid.circ \
  --document-attachment-name 1-baseline.circ \
  --output /absolute/case/1-baseline.postflight.json
```

The postflight checks the exact original document name, all expected attributes and named-terminal connectivity, then records the fixture, target, contract, metadata and document hashes. Its `nativeExecution: not-inferred` value is deliberate: a valid exported XML is one part of the owner's mounted-editor receipt, not independent proof that the interaction ran. Retain a per-test attachment inventory to show no checkpoint was skipped.

A completed #44 receipt must identify both application artifacts, list the actual actions and assertions that ran, and resolve the visual/during-touch reviews. Keep NOT_RUN or partial results for missing cases. Core fixture tests and an adapter-only export test do not substitute for the mounted-editor run.


## Offline review corrections

The inspector joystick coordinates are now 0.99/0.01, which reach raw 14/-14. The old 0.95 coordinate reached 13 and could not justify the declared extreme output. The canvas driver instead uses `joystick-top-right` from the verified pointer manifest.

Each fresh import requests the public event-propagation step and waits for all nineteen literal initial probes. Merely finding the canvas does not establish propagation or known inputs.

The matrix dimension scenario grows from seven to eight rows and restores seven. Shrinking to four would remove connected terminals and is correctly rejected by the editor. The added eighth row has no source and must remain a separate net. The keypad large-to-normal edit is still present as an expectation, but an offline execution of the current CircuitEditor rejects that connected resize with `Group editing cannot preserve the connections`. This failure has been reported to the integrator for product/contract triage. It has not been converted to a native pass or hidden by the driver.

Sixteen Python preflight/negative tests cover the initial fixture, the reviewed false-PASS cases, and the postflight's exact-attachment, wire and restore checks. A real core import/export produced fifty-four migrations while preserving the fully normalized document. The DIP four-switch and matrix eight-row editor results passed the complete attribute/connectivity oracle. These are offline checks, not native UI execution.
