# Logisim Mobile

Logisim Mobile is a port of Logisim Evolution to Android and iOS built with Kotlin Multiplatform and Compose Multiplatform. The shared Kotlin engine imports and runs the [SAP-1 project](examples/SAP-1.md), whose program calculates `2 + 3 + 4 - 1 = 8`. Compatibility is limited to the subset documented in the [parity matrix](docs/parity.md).

The editor shares its canvas, library, inspector, and simulation controls across platforms. It uses side panels in wide windows and bottom panels on phones. The [editor guide](docs/parity-guide.md) describes the controls and how to run the example.

The SAP-1 example produced `08` when run through the app on an Android API 35 emulator and an 11-inch iPad Pro (M5) simulator running iOS 27.0. The [validation record](docs/validation.md) covers the tests, builds, and scope of that validation.

## Project structure

| Directory | Responsibility |
| --- | --- |
| `core` | Projects, editing, values from 1 to 64 bits, nets, simulation, the `.circ` codec, and tests |
| `composeApp` | Shared Compose editor and UIKit entry point |
| `androidApp` | Android host |
| `iosApp` | SwiftUI host and reproducible XcodeGen configuration |
| `examples` | AND and SAP-1 examples, with source and license information |

See the [architecture](docs/architecture.md) and [validation record](docs/validation.md).

The JVM core also runs `.circ` files in [temporal or truth-table mode from the command line](docs/headless-cli.md).

## Build and run

Use JDK 21, Android SDK 37, and, on macOS, Xcode with an iOS Simulator runtime. The Gradle wrapper downloads Gradle, and the Kotlin plugin downloads the Kotlin/Native compiler. The first build requires network access.

Set `ANDROID_HOME` or create a `local.properties` file with `sdk.dir=/path/to/Android/sdk`. Keep local paths out of version control.

```sh
./scripts/verify.sh
```

The script accepts `android`, `ios`, or `all`. iOS verification requires macOS, Xcode, and XcodeGen. Use the run scripts below to launch the app.

On Android, connect an emulator or device to `adb` and run the following command.

```sh
./scripts/run-android.sh
```

With the app installed, `python3 scripts/smoke-android.py` verifies the AND example, editing, export, and document recovery through the UI. The test modifies the open document, so use a test emulator. If more than one device is connected, set `ANDROID_SERIAL`.

On iOS, install XcodeGen and run the following command.

```sh
./scripts/run-ios.sh
# Optionally, pass the simulator UDID as the first argument.
```

The script generates the Xcode project from `iosApp/project.yml`. For a physical device, configure your signing team in Xcode. Simulator builds can run without distribution signing.

## Run SAP-1

Choose **File → Open example: SAP-1** and tap **Run** (▶) on the toolbar. The built-in example applies the initial reset pulse. **Step** (the clock pulse button) toggles the clock once. Choose **View → Fit** or tap the zoom percentage in the status bar to see the entire circuit. The output register ends at `08`.

The toolbar's **Reset** (⟲) button resets the simulator state. It also reapplies the initial pulse for an example just loaded from the library. After reopening a saved project or importing XML, apply the circuit's own reset pulse. In the **Signals** panel, toggle the `Reset` input from `0` to `1` and back to `0` before tapping **Run**.

With the Android app installed, `python3 scripts/smoke-sap1-android.py` repeats the run, exports the project, and verifies recovery after stopping the process. This test replaces the open project with the SAP-1 example.

To repeat the automated comparison with the desktop version, run the following command.

```sh
./scripts/verify-sap1.sh /path/to/logisim-evolution-5.1.0dev-all.jar
```

Omit the JAR argument to run the SAP-1 JVM test by itself. The recorded comparison covered 161 states, 4,991 pin values, and 161 output register values with zero differences. The [validation record](docs/validation.md) details the reference project and the tested sequence.

## Current scope

The engine uses deterministic events with per-component delays. It supports the buses, tunnels, splitters, subcircuits with per-instance state, ROM, registers, counters, gates, and arithmetic used by SAP-1. The timing comparisons cover the recorded scenarios.

The editor also includes memory, multiplexer, arithmetic, and I/O libraries. The [parity matrix](docs/parity.md) tracks the scope of support and the epic's outstanding checks.

The UI opens, exports, and shares `.circ` projects through the file system and provides a dialog to copy and paste XML. The reader preserves supported attributes and metadata and rejects incompatible content before replacing the open project. Verification of iOS provider authentication denial is pending.

The hosts maintain a local project catalog and versioned checkpoints with atomic writes and a recoverable previous copy. Transient simulation state resets when a project opens. The [validation record](docs/validation.md) tracks accessibility, performance, and hardware validation by scenario.

## License and provenance

This is an independent port of [Logisim Evolution](https://github.com/logisim-evolution/logisim-evolution), which continues Carl Burch's original Logisim. Modifications for the port began on November 4, 2026, and include changes to the simulator, document format, and mobile UI.

The port uses the [GNU GPL version 3](LICENSE.md). The [notices and credits](THIRD_PARTY_NOTICES.md) identify upstream code and materials and dependency licenses. The distribution guide explains how to identify and provide the [Corresponding Source for each build](docs/distribution-license-audit.md#corresponding-source).
