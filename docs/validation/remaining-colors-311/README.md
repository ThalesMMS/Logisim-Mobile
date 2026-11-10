# Remaining canvas and analysis colours and value characters: on-screen evidence (#311)

On-screen evidence for criterion 4 of #311, compared with Evolution `d58a61e0` (`AppPreferences` defaults).
macOS host, Homebrew OpenJDK 21. Emulator and simulator only; no physical devices.

## Source and artifacts

The apps were built from `a751d47f` (`main` at the time) plus the changes now in `d4e61f7f`: the width captions are drawn after the components, the network diagnostic prints `(x,y)`, and the proof scenarios. `d4e61f7f` is that change rebased on `main` `fb180b4b`. The rebase brings #321/#323/#324 (names, template, tick frequency default). None of them touches the files behind this evidence: `CircuitDrawing.kt`, `CircuitCanvas.kt`, `AnalyzePanel.kt`, `EditorPreferencesPanel.kt`, `ValueCharacters.kt`, `WidthIncompatibility.kt`, `CircuitNetworks.kt`. In `EditorPreferences.kt`, only the tick frequency default changed.

| Platform | Device | Artifact |
| --- | --- | --- |
| Android | Own AVD `logisim311proof` (Pixel 7, `google_apis/arm64-v8a` API 35, `google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.043/12960925`), `emulator -gpu host -port 5558`, English, after `pm clear` | `androidApp-e2e.apk` SHA-256 `f97d79f1…a747d8`, androidTest `1e9942a8…0767e3` |
| iOS | Own simulator `Logisim 311 Proof` (iPhone 17 Pro, iOS 27.2 24B8117) | `LogisimMobile.app` Debug, bundle tree SHA-256 `e04058ef…8e87a2` (`ios/results.json`) |

## Commands and results

| Evidence | Command | Result |
| --- | --- | --- |
| `android/` | `python3 scripts/android_display_preferences_proof.py --serial emulator-5558 --output … --only 311` | PASS. Observations in `android/results.json` |
| `ios/` | `python3 scripts/run-ios-e2e.py --simulator <udid> --app LogisimMobile.app --baseline-sha a751d47f --only-testing EditorEndToEndTests/DisplayPreferencesEndToEndTests/testRemainingColorsAndValueCharacters` | PASS (188 s) |
| Fixes | `./gradlew :core:jvmTest --tests '*CoreLocalizationTest*' --tests '*CircuitNetworks*' --tests '*CircuitDiagnosticsTest*' --tests '*WidthIncompatibilityTest*'`; `./gradlew :composeApp:testAndroidHostTest --tests '*RemainingColorsCharactersTest*' --tests '*PrinterViewTest*' --tests '*WirePokeTest*' --tests '*CircuitDrawing*'` | 31 and 17 tests, 0 failures |

Fixtures (inline in both scenarios): "Width proof" has a 4/1 net and a 2/3/2 net (common width 2, so the 3-bit end is highlighted), a 1-bit net with a junction and a floating Probe. "Logic proof" is Y = A·B + C.

## What the captures show

| Item | Android | iOS |
| --- | --- | --- |
| Incompatible widths: wire colour, circles, captions and highlight, "Incompatible widths (2)" | `01` | `01`, `width-error-message.txt` |
| Wire Poke: the whole net, dashed in the stroke colour | `02` | `02` |
| Unknown character `U` → `?` on the floating probe | `03`, `05` (`valueChar.unknown=?`) | `03`–`05` |
| Width error colour set to `#0000FF` in Canvas colors | `04`, `05` (`color.widthError=#0000FF`) | `06`, `07` |
| After restart (new process, same document reopened): blue width error and `?` | `06` (PID 3013 → 4119, both values stored) | `08` (`app.terminate()` / `launch()`) |
| Restore canvas colors and `U` | `07` | `09`–`11` |
| Minimized K-map, two coloured groups, blue cell text | `08` ("Karnaugh map groups: 2") | `12`, `kmap-groups.txt` |
| Truth table: block selected (rows 0–2, B–Y), cursor on row 2 / Y | `09` | `13` |

## Comparison with Evolution's colours

Pixels sampled from the screenshots (`pixel()` in `android_display_preferences_proof.py`), identical on both platforms:

| Role | Evolution default | Measured |
| --- | --- | --- |
| `WIDTH_ERROR_COLOR` | `0xFF7B00` | `#FF7B00` (wire); `#0000FF` after the change; `#FF7B00` after restoring |
| `WIDTH_ERROR_CAPTION_COLOR` | `0x550000` | `#550000` caption pixels |
| `WIDTH_ERROR_BACKGROUND_COLOR` | `0xFFE6D2` | `#FFE6D2` caption outline |
| `WIDTH_ERROR_HIGHLIGHT_COLOR` | `0xFFFF00` | Yellow circle and outline at the 3-bit point only, as `getCommonBitWidth` gives 2 |
| `STROKE_COLOR` | `0xFF00FF` | `#FF00FF` dashes over the `#006400` wire |
| `TABLE_SELECTION_COLOR` | `0xC0C0FF` | `#C0C0FF` |
| `TABLE_CURSOR_COLOR` | `0xFFFFFF` | White cursor cell with the 2 px component border |
| `KMAP1_COLOR`, `KMAP2_COLOR` | `0x800000`, `0xE6194B` at alpha 128 | `#BF7F7F`, `#F28CA5` |
| `KMAP_CELL_TEXT_COLOR` | `0x0000FF` | Blue cell values |

## Fixed during this run

- **Captions under pins.** `CanvasPainter.paintContents` calls `drawWidthIncompatibilityData` after the circuit. Mobile drew it before the components, so the pins hid the captions. It now draws after them (`CircuitDrawing.kt`).
- **Diagnostic banner.** It read "network at GridPoint(x=100, y=100)" and now reads "(100,100)", like `Location.toString`. `CoreLocalizationTest` had pinned the old text.

## Not covered by these captures

- `TEXT_TOOL_COLOR`, `CLOCK_FREQUENCY_COLOR` and `TABLE_HIGHLIGHT_COLOR`, and the true, false, error and don't-care characters, keep their JVM tests (`RemainingColorsCharactersTest`, `ValueCharactersTest`, `TruthTableClipboardTest`). As noted in #311, `CLOCK_FREQUENCY_COLOR` is never drawn in Evolution either, and `TABLE_HIGHLIGHT_COLOR` marks pasted rows.
- #327: the table help text still says "0, 1, and x" although the don't-care character is now `-`.
