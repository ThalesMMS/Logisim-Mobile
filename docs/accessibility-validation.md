# Validate editor accessibility and layouts

This procedure covers the criteria for issues [#28](https://github.com/ThalesMMS/logisim-mobile-dev/issues/28) and [#12](https://github.com/ThalesMMS/logisim-mobile-dev/issues/12). Run it in the integrated app. Record the commit and installed artifact. Isolated module tests cover only the module.

## Record the environment and preserve settings

1. Use a device or simulator reserved for testing.
2. Record the commit SHA, APK or app hash, identifier, OS version, logical dimensions, text scale, and screen reader version.
3. Record rotation, text size, contrast, and screen reader settings before changing them.
4. Export the open project before testing creation and deletion.
5. Restore settings and the project after the run, including after failure.

The requested product identifier is `thalesmms.logisim.mobile`. Verify the installed artifact's identifier before running commands. On Android, use these reads to record the environment.

```sh
adb shell wm size
adb shell wm density
adb shell settings get system font_scale
adb shell settings get system accelerometer_rotation
adb shell settings get system user_rotation
adb shell settings get secure enabled_accessibility_services
```

Use **Settings → Accessibility → TalkBack** on the test device and complete the system flow. Avoid enabling unknown services by command. The [Android documentation](https://developer.android.com/guide/topics/ui/accessibility/testing) describes linear navigation, touch exploration, and the option to display TalkBack speech.

## Record evidence separately

Record a result for each layer. Each layer has its own scope.

| Layer | Evidence | Outside its scope |
| --- | --- | --- |
| Kotlin model test | Modeled identities, names, values, connections, and actions | Native tree publication or screen reader operation |
| Compose semantics test | Roles, states, actions, order, keyboard focus, and control sizes | Actual TalkBack or VoiceOver speech and gestures |
| UI Automator or XCTest | Elements published by the installed app and actions that actually edit the circuit | Speech comprehension, rotor navigation, or full equivalence without sight |
| Accessibility scanner or audit | Detectable label, contrast, target, and layout problems on the inspected screen | Other screens and the full workflow |
| Active TalkBack or VoiceOver | Order, speech, gestures, actions, and flow completion using the tested service | Other devices, versions, or assistive technologies |

[Compose accessibility testing](https://developer.android.com/develop/ui/compose/accessibility/testing) combines manual verification and automated tests. When an instrumented Android Compose test exists, enable the Accessibility Test Framework and report failures. Keep warnings visible in the results.

On iOS, use Accessibility Inspector to examine elements and run audits. A UI test can call `XCUIApplication.performAccessibilityAudit()` with the [Apple API](https://developer.apple.com/documentation/xcuiautomation/xcuiapplication/performaccessibilityaudit%28for%3A_%3A%29). Record the version providing the API. Record VoiceOver verification separately.

When debugging Compose on iOS, check how `testTag` maps to native identifiers. Current versions synchronize the tree lazily. `AccessibilitySyncOptions.Always` was removed in Compose Multiplatform 1.8. The [Kotlin documentation](https://kotlinlang.org/docs/multiplatform/compose-ios-accessibility.html) also describes Full Keyboard Access and limitations of the high-contrast palette.

## Navigate the circuit with a screen reader

Run the same steps with TalkBack and VoiceOver. Apple's [general guide](https://developer.apple.com/documentation/accessibility/performing-accessibility-testing-for-your-app) still recommends VoiceOver testing on a physical device. [Xcode 27 added XCUIVoiceOverService](https://developer.apple.com/documentation/xcode-release-notes/xcode-27-release-notes), with screen reader navigation and speech results. Local SDK 27.2 includes the public API. An isolated probe passed 1/1 on iOS Simulator 27.0, returned speech for current, forward, and backward, and restored reader state. This capability check leaves focus identity and order, activation, the full flow, and physical devices unverified. See the [prepared plan and probe](validation/accessibility-reader-3a0a01f1/README.md). Tree inspection, ordinary XCTest, service use, and physical gestures provide separate evidence. The maintainer deferred physical-device tests until the end, so that layer remains pending.

1. Open the test project and locate accessible circuit navigation.
2. Navigate components in linear order without guessing canvas coordinates.
3. Confirm that the name includes the type and distinguishes duplicate labels.
4. Open a component's terminals and confirm each name, direction, width, and value.
5. Navigate connections and follow one to the other terminal.
6. Select the component, open its properties, and return to the starting point.
7. Select and remove a wire using the accessible action. Use **Undo** and confirm that the connection is restored.

Fail the scenario if a terminal or wire is exposed only through color or visual coordinates. Also fail if the reader announces an unresponsive control or an action whose result differs from the touch edit.

## Create, simulate, and save without dragging

Use the accessible Explorer, library, and properties dialogs. Action names must match controls in the tested build. Placement must offer coordinate fields or another predictable method. The flow must go beyond entering placement mode and requiring a precise canvas tap.

1. Create an empty project.
2. Insert two one-bit inputs, an AND gate, and one output.
3. Set the labels to `A`, `B`, and `Y`.
4. Connect each input to its AND terminal using terminal actions.
5. Connect the AND output to `Y`.
6. Read the full connection in Explorer and confirm all terminals are connected.
7. Run the four combinations below using accessible input controls.
8. Save a copy through a native dialog or the available document mechanism.
9. Terminate the process and reopen the copy.
10. Confirm the labels, connections, and all four combinations again.

| A | B | Expected Y |
| ---: | ---: | ---: |
| 0 | 0 | 0 |
| 1 | 0 | 0 |
| 1 | 1 | 1 |
| 0 | 1 | 0 |

Also test editing and deleting a component, followed by **Undo**. The restored document must retain IDs and connectivity. If copy and paste are available, reach them without touch-only gestures.

## Read values and control announcements

1. Open the built-in SAP-1 and confirm that all seven circuits are available in navigation.
2. Read an undefined bus and confirm the description preserves the undefined state.
3. Run the program and read `08` from the displays or output register.
4. Pause and confirm focus stays on the same component.
5. Import a conflict fixture and confirm that the error is conveyed through text as well as color.
6. Read a 64-bit bus and confirm that its width and value are understandable without unnecessarily listing every known bit.

During automatic execution, record ten seconds of speech or a transcript from the service. The focused component's announcement should remain stable across ticks and signal changes. Announce start, pause, and new diagnostics. Repeated instances of the same diagnostic should coalesce without accumulating announcements. Read again after pausing to detect delayed messages.

Use `stateDescription` for states and named actions for operations that replace gestures. Check indexes and navigation groups in long lists. The [semantics documentation](https://developer.android.com/develop/ui/compose/accessibility/semantics) recommends accessible actions for complex gestures and cautions against frequently updated live regions. Adjust [traversal order](https://developer.android.com/develop/ui/compose/accessibility/traversal) with groups and indexes when visual and logical order differ.

## Test focus and keyboard input

1. Connect a keyboard or enable Full Keyboard Access on a supported iOS environment.
2. Use Tab and Shift+Tab to reach the library, canvas, Explorer, properties, simulation controls, and documents.
3. Activate controls with Enter or Space according to the native role.
4. Open a property, edit its value, and cancel the dialog.
5. Confirm focus returns to the control that opened the dialog.
6. Open the software keyboard and confirm the focused field and the confirm and cancel buttons stay reachable.
7. Resize or rotate the window while the field has focus.
8. Confirm the typed text, selection, and focus are preserved.

Fail if Tab is trapped in the canvas, a global shortcut deletes text instead of editing the field, or focus jumps on every simulation step. Bring offscreen scrollable items into view before giving them focus.

## Run the layout matrix

Use the content's logical dimensions, excluding system bars. Choose a supported OS and device for the sizes and record actual values.

| Environment | Window | Text and input | Required evidence |
| --- | --- | --- | --- |
| Small Android | 320 × 568 dp and landscape | 100% and system maximum, including 200% where offered | Library, properties, signals, and documents are reachable |
| Android phone | 360 × 800 dp and landscape | Open keyboard, TalkBack, and physical keyboard | No overlap or loss of focus |
| Android tablet | 800 × 1280 dp, landscape, and 320 dp split window | Maximum text size and TalkBack | Panels or compact alternatives are accessible |
| Small supported iPhone | Approximately 375 × 667 pt and landscape | Default and largest accessibility category, with keyboard open | Full text or an action to read it |
| iPad | Portrait, landscape, and narrow Split View | Largest accessibility category and Full Keyboard Access | Circuit, selection, and focus preserved |

For each combination, open the library, find a long label, edit a property, inspect a signal, and open a document dialog. Check safe areas, system bars, keyboard, and scrolling. Capture before and after rotation and compare the center of the circuit and selection along with the zoom percentage.

## Measure contrast and targets

1. Run Accessibility Scanner or the platform audit on each main screen.
2. Check the project's minimum target sizes of [48 dp on Android](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) and [44 pt on iOS](https://developer.apple.com/design/human-interface-guidelines/accessibility).
3. Check normal text against the [4.5:1](https://www.w3.org/TR/WCAG22/#contrast-minimum) minimum and essential graphics against the [3:1](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast) minimum.
4. Use a grayscale view to check that signals and errors are distinguishable.
5. Enable Increase Contrast on iOS and repeat the readings.

The initial static calculation found these pairs in the inspected code. Recalculate if the palette changes and audit the installed artifact separately.

| RGB pair | Calculated ratio | Static result |
| --- | ---: | --- |
| Text `181818` on `EFEFEF` | 15.44:1 | Passes for normal text |
| Action `285580` on `FFFFFF` | 7.77:1 | Passes for normal text |
| LOW wire `006400` on `FFFFFF` | 7.44:1 | Passes for essential graphics |
| HIGH wire `00D200` on `FFFFFF` | 2.06:1 | Fails 3:1 |
| UNKNOWN wire `2828FF` on `FFFFFF` | 7.40:1 | Passes for essential graphics |
| ERROR wire `C00000` on `FFFFFF` | 6.48:1 | Passes for essential graphics |
| No-width (NIL) wire `808080` on `FFFFFF` | 3.95:1 | Passes for essential graphics |
| Component `000000` on `FFFFFF` | 21.00:1 | Passes for normal text |

Canvas colors follow the Evolution `d58a61e0` light-theme defaults (`AppPreferences`, #337). Mobile still offers a high-contrast option for the HIGH wire.

The ratios use sRGB relative luminance. Textual state remains required even when contrast passes.

## Record results and decide closure

For each scenario, record `PASS`, `FAIL`, or `NOT RUN`, the installed artifact, reader and version, configuration, expected result, and observed result. Include a capture, native tree, and speech transcript or recording when that layer is tested. Keep personal data out of fixtures.

Explorer and overlay implementation ran 14 Kotlin tests with no failures and compiled for Android. The focused command was as follows.

```sh
ANDROID_HOME=/Users/thales/Library/Android/sdk python3 /tmp/logisim-gradle-serial.py \
  ./gradlew :composeApp:testAndroidHostTest --tests '*Accessibility*Test' \
  :composeApp:compileAndroidMain --console=plain
```

The tests check stable names and terminals, junctions and crossings, tunnels, Portuguese and English values, AND creation and simulation through terminal connections, Undo, navigation of a 4,000-terminal network, a 250-node cap with room reserved for selected ports, target transformation, and palette contrast. The translation generator passed with `--check`.

In consolidation `518840e`, component families began providing their own geometry. The fixtures were updated to share the editor's registry and choose terminals by name (`out`, `in0`, `in1`, `in`) independently of list order. The isolated `*CircuitAccessibility*Test` run passed 15 tests. It verifies the three exact wires, separate networks, all four AND combinations, and the actual `editorCircuitAccessibilityActions → EditorState → CircuitEditor` path, including undo and redo and input stimuli. The result required fixture changes only.

XCTest trials of alpha `0beefb4` on a simulated iPhone 17e found touch areas partly clipped by bars. They also exposed harness limitations and missing identifiers in that binary. Their scope is the alpha build. TalkBack, physical VoiceOver, and the final artifact's native matrix remain `NOT RUN` pending integrator evidence.

Close #28 after completing creation, connection, editing, removal, simulation, and saving with both screen readers. Close #12 after covering the layout matrix with focus, viewport, enlarged text, and keyboard. If an environment is unavailable, record its pending result.

## Identifiers for integrated automation

Register `circuitAccessibilityEditorTool(registry)` in the editor. Its title is **Explorador do circuito** in Portuguese and **Circuit explorer** in English. `editorCircuitAccessibilityActions(context)` forwards commands to the actual editor history and protections. Rejected removals return `false`.

| Element | `testTag` | Accessible label |
| --- | --- | --- |
| Search | `a11y-circuit-search` | Field with a localized label |
| Scrollable Explorer list | `a11y-circuit-list` | Explorer navigation group |
| Explicit refresh | `a11y-refresh-values` | Atualizar valores / Refresh values |
| Component selection | `component:<id>` | Selecionar / Select, followed by the unique name |
| Connection start or destination | `port:<id-length>:<id>:<terminal-name>` | Iniciar ligação em / Start a connection at, or Conectar a / Connect to, followed by component and terminal |
| Wire selection | `a11y-wire-<index>` | Fio / Wire, number and coordinates |
| Canvas node | `canvas-` prefix followed by the component or terminal key | Localized name, state, and actions |

On Android, `testTag` appears as a UI Automator `resource-id` only when the Android root enables `testTagsAsResourceId`. Component and terminal buttons also have unique descriptions. Use stable registry terminal names to select a terminal independently of list position.

The `editor-project-toolbar`, `editor-command-toolbar`, and `editor-simulation-toolbar` show only whole actions. The `<toolbar>-overflow` control opens the scrollable `<toolbar>-menu` with the remaining actions. In the minimum layout, project actions appear in the command toolbar. The native helper opens the actual menu before looking for a missing command, without dragging the toolbar or using fixed coordinates. The native audit opens and audits each available overflow menu without filtering items. Use `a11y-circuit-list` to scroll Explorer. `editor-tool-sheet` includes the header and extends beyond the list viewport.

The overlay requires the actual `isSimulationRunning` and an `onNotice` callback for explicit results. While running, it retains a semantics snapshot and describes that in text. Pausing publishes current values. `AccessibleEditorNotice` provides a polite live region for results and diagnostics. Keep ticks and signal samples out of that region.

## Run the native matrix on iOS Simulator

The `scripts/run-ios-accessibility.py` runner generates an independent XCTest project in its output directory. App project edits and Kotlin framework builds are outside the runner's scope. It installs the supplied `.app` on an explicitly reserved, booted simulator. Use a simulator free of other agents' tests.

```sh
python3 scripts/run-ios-accessibility.py \
  --simulator 20A4D87D-A2A6-46BD-A889-C4888CE1E152 \
  --bundle-id thalesmms.logisim.mobile \
  --app /path/to/consolidated/build/LogisimMobile.app \
  --baseline-sha BUILD_COMMIT \
  --catalog-root /path/to/consolidated/checkout \
  --output /path/to/new/evidence
```

The case uses the integrated project's active circuit and searches for the SAP-1 `Reset` component. For a different fixture, pass `--component-search` with the unique label of a one-bit input in the main circuit. The runner selects components and opens panels while preserving properties, circuits, and app documents. Use `--prepare-only` to generate the harness without installing the app or changing Simulator settings.

Each text size runs six tests covering canvas, Explorer, and inspector access in portrait, the same in landscape, signal access in both orientations, focus and text with the keyboard after rotation, retention of selection and relative position of the selected circuit point, and `performAccessibilityAudit()` without exclusion filters. The matrix uses `large` and `accessibility-extra-extra-extra-large`. The accessibility size also enables Increase Contrast. The runner confirms applied values and restores originals even after failure. It delays SIGINT and SIGTERM during restoration to attempt every command. Restoration failure makes the process return an error.

The keyboard scenario requires at least six keys with finite frames within both the screen and keyboard, captures that evidence, and taps `s`, `e`, and `t` after rotation without refocusing search. The field must remain fully on screen without two-dimensional overlap with the keyboard. Scrolling uses the bounds of identified containers. The title and Close button positions do not establish a list viewport.

Before this scenario, focus search in the reserved simulator and verify that the software keyboard appears. If hidden, use **Device > Keyboard > Toggle Software Keyboard** in that device's window and record the prior state for restoration. The global hardware keyboard connection can stay as configured. Receiving `typeText` or finding a `Keyboard` node establishes input availability only. Check on-screen keyboard visibility separately.

The `.xcresult` files include captures, native trees, measurements, and results. `results.json` identifies the device as Simulator and records the SHA supplied by the build coordinator and hashes of the executable and full bundle, including the shared framework. A build, action, geometry, or audit failure is recorded as a failure. VoiceOver stays `NOT RUN` in the report.

To inventory elements without querying the device again, export attachments with `xcrun xcresulttool export attachments --path /path/to/large.xcresult --output-path /path/to/attachments` and run `python3 scripts/ios_native_census.py --attachments /path/to/attachments --output /path/to/census.json`. The census selects trees by name in the manifest, preserves rows and ancestors, counts identifiers, and identifies invalid or zero-sized keyboard frames. This data explains navigation failures. Accessibility verification still requires its own results.

To repeat without rebuilding the entire SDK cache, pass `--derived-data /path/to/previous/cache`. Use a new `--output` to preserve earlier reports and `.xcresult` files. The runner rebuilds the harness and checks the catalog in its bundle before running scenarios. A missing resource keeps the gate at `NATIVE_NOT_RUN`.

An earlier harness version compiled in isolation with XcodeBuildMCP on 2026-11-04 without errors or warnings. The current integration with editing scenarios passed Swift parsing, 25 Python tests, and XcodeGen project generation. Its compilation and execution were pending at the end of that run. Scenario acceptance requires execution against the consolidated artifact and evidence from the integrator.

## Run TalkBack on a reserved Android emulator

Before the reader session, validate the layout runner with `python3 -m unittest discover -s scripts/tests -p 'test_android_accessibility_layout.py'` (28 hermetic tests). It uses the Android harness timeout, finds the component by name before selecting it, and scrolls the identified list or menu. An open overflow menu limits search to its own items. The availability of background app controls is separate. Run the matrix with `--harness-dir` pointing to the consolidated Android harness's `scripts` directory.

A read-only inventory on 2026-11-04 found Android 15 on `emulator-5556`, TalkBack `15.0.0.639625893` installed and off, font scale `1.0`, physical screen `1080×2400`, and density `420`. Record the environment again at execution and verify accessibility in the app.

After the UI Automator matrix, use an exclusive emulator window to open system settings and enable the TalkBack version identified in inventory. Complete the system prompt, record the initial configuration, and enable **Display speech output** in TalkBack developer settings. The [Android documentation](https://developer.android.com/guide/topics/ui/accessibility/testing) describes this option for observing speech from the service.

Navigate the creation, simulation, and saving flow with screen reader swipes and double-tap activation, recording speech output and the actual document result. Use the reader gestures to verify reader navigation separately from UI Automator taps. Afterward, restore the service, speech output, rotation, size, and font settings. Keep this session separate from the document runner and any other agent's layout matrix.
