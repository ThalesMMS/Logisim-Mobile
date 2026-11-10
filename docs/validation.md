# Port and SAP-1 validation

November 4, 2026.

This record separates the engine comparison, automated tests, and interaction in the apps. The SAP-1 result covers part of [Epic #1](https://github.com/ThalesMMS/logisim-mobile-dev/issues/1). The [parity matrix](parity.md) tracks the remaining features and their limits.

## Comparison with the desktop version

The test uses the original `examples/sap1.circ` from commit `ddb9dd86d601ac217157beedeb88464ee0128c87` of [SAP-1-Logisim](https://github.com/ThalesMMS/SAP-1-Logisim). It has seven circuits, 156 components, and 318 wire segments. The source, license, and SHA-256 are in [examples/SAP-1.md](../examples/SAP-1.md).

Both engines received a reset pulse and the same sequence of 160 clock transitions. The comparison includes the initial state and the state after each step.

| Measurement | Result |
| --- | ---: |
| States compared | 161 |
| Pin values compared | 4,991 |
| Output register values compared | 161 |
| Differences | 0 |
| Diagnostics in the Mobile trace | 0 |

The output register starts at `00`, changes to `08` at step 56, and stays at `08` through step 160. The program calculates `2 + 3 + 4 - 1`. The comparison uses the register at `(600,340)` in the `main` circuit. The `Valor` bus can be undriven between transfers.

The files `outputs/sap1-validation/comparison.json` and `manifest.json` record the result and hashes of the circuit, the two artifacts that ran, and the scripts. The full traces are in the same directory. The desktop version was `logisim-evolution-5.1.0dev-all.jar`.

To reproduce the comparison, run the following command.

```sh
./scripts/verify-sap1.sh /path/to/logisim-evolution-5.1.0dev-all.jar
```

Omit the JAR argument to run only the `Sap1IntegrationTest` JVM test. With the JAR, the script runs both engines and compares their traces. Set `KOTLIN_STDLIB_JAR` to point to the Kotlin standard library if it is outside the expected cache location.

## Tests and builds of the expanded implementation

| Check | Recorded status |
| --- | --- |
| JVM core | 101 tests passed |
| Core on iOS Simulator ARM64 | 99 tests passed |
| Editor state and viewport on the Android host | 20 tests passed |
| Android APK | Debug APK generated |
| Compose framework for iOS Simulator ARM64 | Build completed |
| iOS host | Build completed without distribution signing |

The full `./scripts/verify.sh all` run passed. The log is in `outputs/sap1-validation/final-build.log`. The subsequent filtered SAP-1 run replaces the Gradle JVM reports with the two SAP-1 tests. The 101 tests above refer to the full run.

The tests cover multibit values, net resolution, geometry, gates, registers, counters, hierarchy, the project codec, editing, and checkpoint recovery. The matrix tracks observed test results. Compose state tests exercise the state layer.

The build environment uses JDK 21, Gradle 9.6.1, Kotlin 2.4.21, Compose 1.12.2, Android Gradle Plugin 9.3.1, Android SDK 37, and Xcode 27.2. The engine comparison ran on macOS ARM64.

## Android interaction

On a Pixel emulator running Android API 35, `scripts/smoke-sap1-android.py` used the app controls to perform these steps.

1. Loaded SAP-1 from the library and ran the clock until the displays showed `08`.
2. Exported the project and confirmed seven circuits, 156 components, and 318 wire segments.
3. Stopped and restarted the process and confirmed that the saved XML was identical.
4. Applied the restored circuit's reset pulse and ran it again until it reached `08`.

The log is in `outputs/sap1-validation/android-smoke.log`, and the exported XML is in `android-export.circ`. The `android-sap1.png` screenshot and `android-sap1-ui.xml` tree record the restored run. Local portrait and landscape captures cover those emulator configurations. Coverage across all screen sizes and on physical devices requires separate tests.

## iPad interaction

On the 11-inch iPad Pro (M5) simulator running iOS 27.0, the app reopened the saved SAP-1 project with its seven circuits. After a circuit reset and 56 manual clock transitions, the output register showed `08`. Continuous execution also showed `08`.

Local captures are in `outputs/sap1-validation/ipad-sap1-56.jpg` and `ipad-sap1-running-08.jpg`. The canvas preserves shared subcircuit labels such as `RI` alongside the name used as an internal reference. Visual inspection confirms the elements exercised in this run. Pixel-by-pixel parity and full accessibility coverage require separate measurements.

The toolbar's **Reset** button resets the simulation state. The app also applies the initial pulse when it loads the built-in example. For a restored or imported project, toggle the circuit's own `Reset` input from `0` to `1` and back to `0` before running it.

## Local checks and CI

```sh
./scripts/verify.sh android
./scripts/verify.sh ios
./scripts/verify.sh all
python3 scripts/check-source.py
python3 scripts/parity_matrix.py --check
```

The iOS script requires macOS, Xcode, and XcodeGen and builds the host without distribution signing. Use `scripts/run-android.sh` or `scripts/run-ios.sh` to install and launch the app.

With an Android emulator connected and the app installed, run the following command.

```sh
python3 scripts/smoke-sap1-android.py
```

The scenario replaces the open project with SAP-1 and restarts the process to check persistence. Use `ANDROID_SERIAL` to select the emulator when several devices are connected.

The workflow described in [ci.md](ci.md) is explicitly tracked in version control. The general rule still ignores `.github`. The remote workflow run was to be verified after the push.

## Scope of the evidence

The engine schedules events with per-component delays. The recorded comparison covers the SAP-1 sequence and the listed tests. Other desktop options, components, and projects require separate evidence.

At this stage, simulation support for RAM, the multiplexer, the decoder, and Bit Extender was pending. The catalog was fixed, attributes were stored as text maps, and the recoverable checkpoint covered the open project. File exchange used the XML dialog.

Physical devices, signed distribution, document provider integration, full accessibility, and energy and performance measurements on large circuits required separate validation. The SAP-1 evidence also excludes the TTL, floating-point, and SoC libraries and the HDL, Tcl, JAR, and FPGA integrations.

## Final run

The final build was also installed on the iPad and ran the program to `08` in 92 half-cycles, as shown in `outputs/sap1-validation/ipad-sap1-final.jpg`. The earlier capture `ipad-sap1-56.jpg` records the verification with 56 manual steps.

The command `ANDROID_SERIAL=emulator-5556 python3 scripts/smoke-sap1-android.py` exited with code 0. It verified `08`, export of all seven circuits, reopening with identical XML, another run, and rotation. In landscape, zoom was 23%, and Library and Properties were still accessible. The captures `android-sap1.png` and `android-sap1-landscape.png` and the file `android-landscape-result.json` are in `outputs/sap1-validation/`.

Consolidated JUnit reports are in `outputs/sap1-validation/junit-final/`, with counts in `test-counts.json`. The manifest was updated after the final desktop comparison.

## Workflows verified on November 5, 2026

- Android API 35, build `1d53bc8a`. The library's SAP-1 example showed `08` after 63 steps. The native picker exported a `.circ` with 7 circuits, 156 components, and 318 wires. After importing a copy and renaming the main circuit, build `c7073880` restored the edit after an app restart. The exported file retained the same bytes.
- Android API 35, build `c08964e4`. Import through the native picker opened the selected SAP-1 project and preserved the original bytes. Incoming sharing opened the newly marked copy, and the external receiver read the exact bytes during outgoing sharing. Canceling Import Copy, Save As, or Share preserved the XML. Invalid XML, an unavailable library, and invalid UTF-8 also preserved the open project. Save As exported the same XML with and without process recreation during the picker flow. These cases passed in separate runs after the full run stopped at the clipboard helper.
- iPhone 17e Simulator, build `1d53bc8a`. The 56-step SAP-1 run passed, along with saving and reopening an external file and preserving the original on conflict. On build `c7073880`, the incompatible import test and the attempt to save a removed external file passed. On build `ed32ba9a`, canceling Import Copy, Save As, or Share preserved the exact XML.
- iPad mini Simulator, build `c08964e4`. Rotating from compact to wide layout moved the inspector from a bottom sheet to a side panel. XML dialog actions passed in both orientations.
- iPhone 17e Simulator, build `25dace02`. When rotating from landscape to portrait with Signals open, the compact sheet became a strip in the editor. The keyboard and TTY flow also passed, including Backspace, Form Feed, and the four-character capacity. Both XML files passed connectivity and attribute checks.
- iPhone 17e Simulator, build `eb00e236`. Checks passed for the states observed after releasing the button, keypad, and joystick and for moving the joystick with the directional control. The dimensions test verified changing and restoring the LED bar size and the TTY rows and columns. It showed the new dot matrix dimensions before the nine-minute limit cut off the restoration and keypad size checks. All ten XML files captured in the two scenarios passed connectivity and attribute checks.
- iPhone 17e Simulator, build `eb00e236`. On a new run, all five dimension adjustments and restorations passed, including the dot matrix and keypad. All 12 XML files passed connectivity and attribute checks.
- iPhone 17 Simulator, build `eb00e236`. Video showed the button output at `1` while pressed and `0` after release. It showed key `3` highlighted while pressed and the joystick moving during the drag and returning to center. The DIP switch passed checks for toggling switches, changing the count, rejecting zero, exporting, and reopening. All six DIP XML files passed connectivity and attribute checks.
- iPhone 17e Simulator, build `1fb4e827`. The selected LED's accessible target grew from 13.3 × 48 to 48 × 48 points. The native test passed all seven transitions involving the LED, RGB LED, LED bar, seven-segment display, and dot matrix. Both exported XML files passed connectivity and attribute checks. Activation with VoiceOver and TalkBack remained pending in this run.
- Android API 35, build `63e64196`. The native scenario passed for momentary button and keypad operation and for joystick movement and return to center. A canvas drag checked signals with the joystick held and after release. The keyboard and TTY flow and the buffer capacity change also passed.
- Android API 35, build `537009e4`. Visual inspection of the TTY confirmed the clock symbol and the removal of overlapping terminal labels.
- Android API 35, build `eb00e236`. The DIP switch passed checks for toggling switches, editing the count, rejecting zero, restoring the count, and reopening the exported `.circ`. The seven display transitions and the final XML passed in a separate scenario.
- Android API 35, build `7e50e84f`. On a 64-output splitter, Tab focused the component, `Shift` + `Enter` opened the terminal list, Enter started a connection, and Escape canceled it. In native split screen, Signals changed the LED input in one project, and Properties opened the Fan out field in the other. The field and actions remained visible with the numeric keyboard open. The splitter stayed selected on return to full screen. This flow verified keyboard input.
- Android API 35, build `7e50e84f`. In the inspector, Tab focused the momentary button. The keyboard action kept the button pressed until Space was released.
- Android API 35, 320 × 568 dp viewport and 2× text size. Rotating with the keyboard open hid the Explorer search even though its field had focus. After the fix, the same process kept focus and displayed both the field and the hide-keyboard action in landscape. The user could complete `REF_LOW` without another tap and return to portrait with the draft intact.
- Twenty-one Android host gesture and focus tests passed, including simultaneous touch, stylus, and mouse events. Seven drawing tests also passed.
- With TalkBack enabled on Android, the Signals button announced the input name and value once. Alt+Enter on the virtual USB keyboard changed the input from 0 to 1 and the inverter output from 1 to 0 while retaining focus. The screen reader also activated creation and saving of a temporary workspace project. On the simulated iPhone 17, a VoiceOver double tap activated the LED input, changed its output, and allowed creation and saving of a workspace project. The temporary projects were moved to the trash and the screen readers were disabled after the run.
- On the simulated iPhone 17, with text set to `accessibility-extra-extra-extra-large`, increased system contrast, and high-contrast signals enabled, Projects, Library, Run, and the tool menus stayed accessible. Measured controls were at least 48 points. Preferences were restored afterward. TalkBack touch activation remained unverified because double-tap delivery also failed on an Android system button.
- Android API 35. Moving focus to the inspector during a touch drag left the circuit XML identical. On build `c44570e6`, a pinch moved from 137% to 274% and back to 137%, keeping the output pin at the gesture center. The scroll wheel panned in both directions, and Ctrl + wheel increased zoom from 230% to 260% using a virtual keyboard and mouse. A virtual trackpad recognized as `MOUSE | TOUCHPAD` opened the circuit context menu on secondary click. The instrumented Ctrl + wheel run still failed. Direct verification took place on the emulator.
- All 71 selected Android host tests passed, including the two HTTP tests that had timed out. The 12 selected I/O focus and interaction tests also passed after the joystick and keypad fix.
- The Android HDL source picker loaded `and_gate.blif` from Downloads. Seven selected JVM HDL import and co-simulation tests passed, including communication with the companion. Four spatial index tests and seven drawing tests also passed.
- Android opened the benchmark with 1,024 components and 256 wires from the historical corpus. During 12 seconds of pan and zoom on build `c7073880`, the collector observed 18 frames and recorded PSS of 153 to 194 MiB. This emulator sample preceded the optimization that limited drawing of wires and junctions to the visible area. The optimization's effect and physical-device budgets were still pending.
- On the same emulator and circuit after the optimization, a 12-second window on build `7e50e84f` recorded 72 frames and PSS of 144 to 175 MiB. The first run did not record the gesture cadence, and an iOS simulator was in use during the second. Both samples provide diagnostic data. Measuring the optimization's effect and setting device budgets require controlled runs.
- On Android `ed32ba9a`, the FPGA flow accepted the iCE40 HX1K board and validated the AND circuit with pins 21, 22, and 23. The Mac completed synthesis, placement, routing, and bitstream generation. The app exported a ZIP with the 32,220-byte bitstream and intermediate results. The configuration recovered from that binary passed all four input combinations in Icarus using the same physical pins. The run used simulation as requested because a board was unavailable. [Runnable example](../examples/fpga-bitstream/README.md)
- On Android `ed32ba9a`, Telnet reported `listen-failed` while the Mac port was in use and allowed setup again. After freeing the port and confirming a new plan, the app received one byte from a real client. Stop closed the connection and freed the port.
- On iPhone 17e Simulator, build `eb00e236`, the app authenticated the Mac and started the loopback Telnet listener after confirmation. The circuit read `0x42` from the Mac, and the client on the Mac received `0x41` from the circuit. The received value persisted on return from the background. Stop closed the connection with EOF and freed the port. In the same circuit, Port I/O pin 0 changed from undefined to `0`, the clock exposed the epoch sample, the 4 × 3 RGB framebuffer received a pixel, and reset cleared the frame. The panel reported that Reptar was unavailable.

On the simulated iPhone 17, the provider accepted the first edit to `revocation.circ`. After authentication was revoked, two attempts to sync the next edit returned `NSFileProviderErrorDomain -1000`. The remote file retained the same bytes, and Logisim preserved the local edit. Because a coordinated write can complete before the upload, the app reports the on-device write and refers users to Files for sync status. On the simulated iPad mini, Files showed an authentication prompt when opening the uncached circuit. The provider rejected two reads with the same code, and the remote hash stayed the same. On cancellation, Logisim preserved the open circuit, revision, and signal state.

## Layout and resizing

| Scenario | Observed result |
| --- | --- |
| Android API 35, 320 × 568 dp phone with 2× text size | Explorer search retained focus and the draft when rotating with the keyboard open. In the Data bits dialog, the field retained focus and its value of 0 in landscape. Apply was disabled, and Cancel was available. Both buttons were at least 48 dp and sat above the keyboard. Apply saved 2 with the keyboard still open. Cancel discarded a new draft value of 3. [Screenshot](validation/layout12/android-numeric-keyboard.png) |
| Android API 35, native split screen, build `7e50e84f` | Signals activated the LED input. Properties opened Fan out with the keyboard visible. The splitter remained selected on return to full screen. |
| iPhone 17e, build `25dace02` | On return to portrait, the Signals sheet became a strip in the editor. |
| iPad mini, iPadOS 27.0 | The RGB LED remained selected in the inspector while resizing the native window from 744 × 1133 to 375 × 746.5 points. Signals and zoom controls remained available. Restoring the window returned the LED target to the same coordinates at 400% zoom. [Full window](validation/layout12/ipad-full.png), [smaller window](validation/layout12/ipad-window.png) |

The Android scenario in issue #35 uses an isolated `.e2e` app and saves a screenshot, logs, and the document on failure. Earlier runs passed creation and editing, wiring, selection and history, zoom, and equivalent insertion by touch and through the Explorer. Sequential simulation passed with SAP-1, export, and reopening. [Editing](validation/android35-editing-3a0a01f1-r3/README.md), [sequential simulation](validation/android-editor-04a0b34.json)

## HDL co-simulation through the companion

[Issue #59](https://github.com/ThalesMMS/logisim-mobile-dev/issues/59) covers VHDL and BLIF on the Mac companion. Authenticated tests ran NVC and Yosys/Icarus with real sources, checked the source hashes, and applied outputs to the engine at the sampled time. The VHDL test verified reset and cancellation. The BLIF test verified two-bit buses. The compiler rejected an invalid source, and the engine published an error state. The companion suite also covers parameter preservation, independent per-instance states, and an unavailable backend. On the simulated iPhone, run D7 observed start, stepping, reset, and cancellation of a VHDL session through the editor.

## Verilog and BLIF import through the companion

[Issue #58](https://github.com/ThalesMMS/logisim-mobile-dev/issues/58) uses Yosys on the Mac to convert the documented Verilog and BLIF profile into editable circuits. On Android `c7073880`, the picker loaded `and_gate.blif` from Downloads. After confirming synthesis and applying the result, the editor showed the `hdl_and_gate` circuit. The output changed from `0` to `1` when both inputs were switched on.

The Logisim Evolution comparison passed all 44 input combinations across the four examples, including hierarchy, buses, includes, and BLIF. The companion suite checked naming, file, and command rules and interrupted a real Yosys process on cancellation. Applying a Verilog result in the app and preserving the project after an error remained additional UI cases. [Desktop comparison procedure](../scripts/oracle/hdl-import/README.md)

<a name="ruído-de-propagação-no-perfil-evolution"></a>

## Propagation noise in the Evolution profile

The #170 run passed 18 `CircuitSimulatorTest` tests with Kotlin 2.4.21 and JBR 21. With controlled random draws `2,0,0`, `simrand=10` produced delays of `17,16,16,17,17` in Mobile and the Evolution `d58a61e0` oracle. Values `0` and `1` kept delays at `1,1,1,1,1`. [Fix on `main` at `974248be`](https://github.com/ThalesMMS/logisim-mobile-dev/commit/974248be6d24b18d92a06635109f081c2a9d3b1a) · [Evidence for #170](https://github.com/ThalesMMS/logisim-mobile-dev/issues/170#issuecomment-6682113051)

The [2.7.1 profile record](../examples/compatibility/original-2.7.1/README.md#simulation-engine-and-component-cases) covers the other engine and component cases. Per-component and per-variant qualification and acceptance on the same integrated revision are tracked in [#238](https://github.com/ThalesMMS/logisim-mobile-dev/issues/238) and [#243](https://github.com/ThalesMMS/logisim-mobile-dev/issues/243).

## Evolution profile components

The cases below ran against Evolution `d58a61e0`. The revisions identify the published fixes. The receipts record artifacts and scope for each run.

| Matrix entry | Fix on `main` | Recorded result |
| --- | --- | --- |
| `I/O/HexDigit` | `614d5cea` | All 1,280 combinations matched. On Android, the three decimal-point settings produced the expected masks. [#268](https://github.com/ThalesMMS/logisim-mobile-dev/issues/268#issuecomment-6697300778) |
| `Input/Output-Extra/NE555` | `2f85799e` | Engineering notation import, canonical save, and reopening in both simulators. [#269](https://github.com/ThalesMMS/logisim-mobile-dev/issues/269#issuecomment-6695642737) |
| `Input/Output-Extra/Buzzer` | `75b799b7` | PCM samples at two phase transitions matched. [#271](https://github.com/ThalesMMS/logisim-mobile-dev/issues/271#issuecomment-6695639968) |
| `I/O/Video.factory` | `c07b5f56`, `5b574e74` | 116 framebuffer observations and four cursor observations matched. On Android, the first write occurred with the clock high after increasing the width, and the cursor followed the parity of the device's seconds. [#272](https://github.com/ThalesMMS/logisim-mobile-dev/issues/272#issuecomment-6699699028) · [#273](https://github.com/ThalesMMS/logisim-mobile-dev/issues/273#issuecomment-6699700257) |
| `I/O/PortIo` | `404cb151` | The conflicting circuit reproduced four Evolution values. The table covering four directions and three widths matched across 1,183 bits. [#283](https://github.com/ThalesMMS/logisim-mobile-dev/issues/283#issuecomment-6699812376) |
| `Memory/RAM` | `0ed2ec1e` | The simulators matched at the boundary of short banks. Evolution FPGA RTL wrapped to address zero in both languages. Portable HDL preserved word zero and passed all 454 comparisons. [#284](https://github.com/ThalesMMS/logisim-mobile-dev/issues/284#issuecomment-6700021616) |

Broader component qualification is tracked in [#238](https://github.com/ThalesMMS/logisim-mobile-dev/issues/238).

## Analysis, CLI, traces, and exports

The 15 cases in #249 have results from the individual issue runs. The revision column points to the published fix. The comments record commands, tested artifact revisions, and the scope of each run. Acceptance on the same integrated revision is tracked in [#243](https://github.com/ThalesMMS/logisim-mobile-dev/issues/243).

| Case | Fix on `main` | Recorded result |
| --- | --- | --- |
| Input before EOF in `-tty tty` | `493233f5` | 33 JVM tests passed. Real processes echoed `OK` and `YZ` with stdin open, exited on `halt` with code 0, and canceled with code 130. The finite pipe also passed. [#135](https://github.com/ThalesMMS/logisim-mobile-dev/issues/135#issuecomment-6676262164) |
| Floating outputs | `75ba84ab` | 15 JVM tests passed. The CLI produced `0,x` and `1,1`. Bus conflict still returned code 2, and the UI table uses the same cell model. [#143](https://github.com/ThalesMMS/logisim-mobile-dev/issues/143#issuecomment-6676288810) |
| Pin names in the 2.7.1 profile | `60b066b2` | Two JVM tests and the official JAR comparison covered duplicate, missing, and invalid labels. The four binary rows matched in both cases. [#145](https://github.com/ThalesMMS/logisim-mobile-dev/issues/145#issuecomment-6676328296) |
| Component counts | `7d8f6647` | On Android 15, the panel showed `Constant` counts of 0 local, 1 unique, and 2 recursive. The `unused` circuit was outside the selected instance. The CLI uses the same counting logic. [#146](https://github.com/ThalesMMS/logisim-mobile-dev/issues/146#issuecomment-6682381100) |
| Project options | `230d05f6` | 17 JVM tests passed. Analysis and vectors honored `gateUndefined` and the propagation limit, with the same expected results or diagnostics as live simulation. [#147](https://github.com/ThalesMMS/logisim-mobile-dev/issues/147#issuecomment-6676350604) |
| TikZ export | `869f53a7` | Production export in Compose Desktop/Skia generated two `.tex` files, one for the circuit and one for a selected wire. Tectonic compiled both, and the circuit PDF preserved accents and special characters. [#168](https://github.com/ThalesMMS/logisim-mobile-dev/issues/168#issuecomment-6676694498) |
| WaveDrom export | `4e7ee2f8` | The Android 15 picker saved JSON with Data before CLK, clock edges, and values `0x0` through `0x3`. WaveDrom CLI rendered the same file as SVG. [#169](https://github.com/ThalesMMS/logisim-mobile-dev/issues/169#issuecomment-6684914833) |
| CLI test vectors | `61f43e4a` | 28 JVM tests passed. The Mobile CLI verified combinational and sequential cases, exit codes 0, 1, 2, and 130, line numbers, and `gateUndefined`. The pinned Evolution JAR requires GUI initialization for this flow. [#185](https://github.com/ThalesMMS/logisim-mobile-dev/issues/185#issuecomment-6676614690) |
| CLI tables in Evolution mode | `b5dd4d83` | Seven of eight runs matched `d58a61e0` byte for byte. The eighth matched after normalizing the random UUID suffix of an invalid label. Evidence included radices, delimiters, buses, and floating outputs. [#186](https://github.com/ThalesMMS/logisim-mobile-dev/issues/186#issuecomment-6676512697) |
| CLI memory images and dumps | `05321e8a` | 48 JVM tests passed. Fixtures loaded RAM and ROM by label and saved RAM after `halt`. The dump with two labeled RAMs matched the pinned Evolution byte for byte. [#187](https://github.com/ThalesMMS/logisim-mobile-dev/issues/187#issuecomment-6676655794) |
| Analysis of 13- and 20-bit circuits | `3ef70739` | On Android 15, the table paginated, canceled a partial export, and exported all 8,192 and 1,048,576 rows with `A=Y`. The 20-bit case also included memory samples during execution. [#200](https://github.com/ThalesMMS/logisim-mobile-dev/issues/200#issuecomment-6685858284) |
| Embedded testbenches and CLI conversion | `491c7e5e` | Three JVM tests and real processes covered success, failure, limits, and diagnostics. A failure preserved the destination. The Evolution reader reopened the converted file with two circuits, four components, and two wires. [#220](https://github.com/ThalesMMS/logisim-mobile-dev/issues/220#issuecomment-6682126630) |
| CSV and TXT exchange in the analyzer | `e5394e1c` | The Android 15 picker imported and exported XOR and a bus table. The Evolution reader reopened both with their order, widths, and don't-care conditions intact. A conflicting CSV preserved the previous table's bytes. [#221](https://github.com/ThalesMMS/logisim-mobile-dev/issues/221#issuecomment-6685010410) |
| CLI startup options | `d835f3ca` | Java processes with a disposable profile verified version, IEC, locale, template, and reset. The JVM host diagnosed `--geometry` and reported that it already runs without a splash screen. [#257](https://github.com/ThalesMMS/logisim-mobile-dev/issues/257#issuecomment-6676666550) |
| Importing 13- and 20-bit tables | `5419665f` | Android 15 and a simulated iPad Air opened compact CSV/TXT and expanded CSV files at 13 and 20 bits, preserved the previous table on cancellation or conflict, and re-exported the 20-bit table with the same hash. Evolution `d58a61e0` reopened the sample files. The comment records timings and memory. [#263](https://github.com/ThalesMMS/logisim-mobile-dev/issues/263#issuecomment-6687165974) |

<a name="arquivos-projetos-e-bibliotecas"></a>

## Files, projects, and libraries

The [#246 workstream](https://github.com/ThalesMMS/logisim-mobile-dev/issues/246) cases were verified in the individual issue runs. Legacy import and export results are in the [2.7.1 profile record](../examples/compatibility/original-2.7.1/README.md#file-and-project-cases).

| Case | Fix on `main` | Recorded result |
| --- | --- | --- |
| Reloading a `.circ` library | `87154a42` | On Android 15, reauthorization through the picker changed `Y` from 0 to 1 and preserved IDs and wiring. The app rejected the incompatible library, and the export kept the same hash. [#160](https://github.com/ThalesMMS/logisim-mobile-dev/issues/160#issuecomment-6687613843) |
| Merging projects | `ddd344e6` | On Android 15, import included only `Top` and its dependencies, renamed collisions, and kept A as the main circuit. Undo restored the initial project. Redo reproduced the merged XML. [#162](https://github.com/ThalesMMS/logisim-mobile-dev/issues/162#issuecomment-6683759248) |
| Built-in libraries per project | `8928e826` | On Android 15, removing and adding TTL persisted in the `.circ` after reopening. The app rejected removal while another circuit used a 7400. Exports before and after rejection had the same hash. [#188](https://github.com/ThalesMMS/logisim-mobile-dev/issues/188#issuecomment-6687766576) |
| Exporting a circuit and dependencies | `28b1f5b8` | Android saved a `.circ` that reopened in Mobile and the Evolution reader with `Child` as the main circuit, `Grandchild` as a dependency, and the expected libraries. The run used binary `fbb1eca6`, whose relevant reader packages match `d58a61e0`. [#201](https://github.com/ThalesMMS/logisim-mobile-dev/issues/201#issuecomment-6684971105) |
| Recent documents and document identity | `264b8ae8` | Android 15 and a simulated iPad Air distinguished the updated original from the local copy. Android verified effective revocation and reauthorization. On iPad, the bookmark followed the moved file and the app preserved the copy when the original disappeared. [#228](https://github.com/ThalesMMS/logisim-mobile-dev/issues/228#issuecomment-6688142523) |
| Default Evolution and FPGA symbols | `c9c04a07` | 34 JVM tests passed. Five variants preserved terminals and `Y=0/1` when saved and reopened in both directions. JAR `fbb1eca6` has the same relevant appearance and codec code as `d58a61e0`. [#244](https://github.com/ThalesMMS/logisim-mobile-dev/issues/244#issuecomment-6676392902) |
| Saving to the original on Android | `07a40f72` | On Android 15, Close → Save wrote and reopened the edit at the same URI with a separate test provider that advertises atomic replacement. After revocation, the tab stayed open with the edit and the original kept the same bytes. With a provider lacking the protocol, Save As remained available and the edit persisted in the workspace copy. The original remained intact. All 13 instrumented tests passed on candidate `1af73e4b`, including file moves, write races, interruptions, and retries. [#262 and validation receipts](https://github.com/ThalesMMS/logisim-mobile-dev/issues/262#issuecomment-6688900283) |
| Implicit splitter mapping | `2f74cead` | Mobile and Evolution `d58a61e0` agreed on widths 3, 4, and 1 and outputs `110`, `0111`, and `0` for `0xDA`, including after reopening the export. The 25 focused tests ran the modified classes over the baseline Mobile JAR. [#264](https://github.com/ThalesMMS/logisim-mobile-dev/issues/264#issuecomment-6686822537) |

All 14 child issues have results from their individual runs. Acceptance on the same integrated revision is tracked in [#243](https://github.com/ThalesMMS/logisim-mobile-dev/issues/243).
