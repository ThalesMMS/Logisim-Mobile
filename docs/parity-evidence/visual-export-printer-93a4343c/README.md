# Printer-view input strokes compared with Evolution (#360)

Rows `format/image-svg` and `format/image-tikz`. This run repeats [`visual-export-terminals-516b22bf`](../visual-export-terminals-516b22bf/README.md) with a new case, `svg-printer`, which exports `scripts/oracle/visual-export/svg-printer.circ` in ANSI printer view.

- Mobile: `93a4343c`, clean worktree. `core-jvm.jar` SHA-256 `680bd30c8afa66c3…`. Vectors come from the `composeApp` JVM test target via `buildVisualExportAsset`, using Skia as on iOS, with `CircuitDrawingStyle(printerView = true)` for `svg-printer`.
- Evolution: JAR `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` (`d58a61e0`), `EvolutionVisualExport.java` with `printerView=true` for `svg-printer`, `-Djava.awt.headless=true`, with a limit of 180 s per run.
- Environment: macOS (Apple silicon), Homebrew OpenJDK 21. JVM and host tests only. The `iosTest` raster tests require a simulator and were skipped.
- The GIF rows in `results.json` reuse the historical Android and iOS `whole.png` files (`85da031e`, `6cef0542`). They cover those earlier revisions.


## Change

`PainterShaped.paintInputLines` draws an ANSI OR, NOR, XOR, or XNOR input stroke in printer view only when `painter.isPortConnected(i + 1)`. That requires `Circuit.isConnected(loc, comp)` to find a wire endpoint or another component’s terminal at that point. Mobile gathers those endpoints in `drawCircuitDrawing` (`CircuitEnds` in `GateTerminalLines.kt`), which serves both the canvas and exports, and passes them to `shapedGateInputLines` when `printerView` is enabled.

## Fixture

`svg-printer.circ` has an east-facing OR of size 70 with a vertical wire at the upper input, an east-facing XOR of size 70 with a `Pin` whose terminal touches the lower input (component-only connection), an unconnected east-facing NOR of size 50 (zero-length strokes outside printer view), and a west-facing OR of size 70 with a vertical wire at the lower input. Evolution writes 8 input strokes in normal view and 3 in printer view.

## Commands

```sh
./gradlew :core:jvmJar :composeApp:jvmTest --rerun --tests '*EvolutionVectorExportHostTest'
ANDROID_HOME=~/Library/Android/sdk ./gradlew :composeApp:testAndroidHostTest --tests '*GateTerminalLinesTest' \
  --tests '*DarkCanvasContrastTest' --tests '*PrinterViewTest' --tests '*CircuitDrawing*'   # 20 tests, 0 failures
python3 -I scripts/oracle/visual-export/compare_exports.py --evolution-jar EVOLUTION_JAR --out OUT
```

The Android host tests ran before the commit on a tree identical to `93a4343c` (the rebase preserved the base). The `jvmTest` and comparison ran at `93a4343c`. See `logs/gradle-focused-tests.txt`.

## Criterion

The new `terminal-strokes` check in `compare_exports.py` counts integer points along straight strokes of width 3 (input and control strokes), excluding the fixture’s wire points. Evolution draws wires at width 3 and can join a wire and stroke in the same path. The check must pass for `svg-printer` and continue to pass for the other ANSI cases.

## Results

| Row | Case | Result | Detail |
| --- | --- | --- | --- |
| `format/image-gif` | `android-whole-gif`, `ios-whole-gif` | PASS | Historical rasters |
| `format/image-svg`, `format/image-tikz` | `qualification` | PASS | `terminal-strokes` 0/0 |
| `format/image-svg`, `format/image-tikz` | ANSI `svg-gates` | PASS | |
| `format/image-tikz` | IEC `svg-gates` | PASS | |
| `format/image-svg`, `format/image-tikz` | ANSI `svg-labels` | PASS | `terminal-strokes` 164/164 points |
| `format/image-svg`, `format/image-tikz` | IEC `svg-labels` | PASS | |
| `format/image-svg`, `format/image-tikz` | `svg-printer` (printer view) | DIFF `port-markers` | `terminal-strokes` matches at 21/21 points, covering only east-facing OR (130,80)→(135,80), XOR (320,120)→(325,120), and west-facing OR (270,320)→(260,320). `port-markers` 1 Evolution / 12 Mobile, tracked in #361. |

`GateTerminalLinesTest.printerViewKeepsOnlyTheLinesOfConnectedInputs` checks that a wire or another component’s terminal counts as a connection. `EvolutionVectorExportHostTest.printerViewOmitsTheInputLinesOfUnconnectedGateInputs` requires 3 strokes of width 3 in the exported SVG. `DarkCanvasContrastTest` still passes.

## Separate gap

In printer view, Evolution also omits terminal markers from ANSI gates (`AbstractGate.paintInstance`) and terminals 0 and 1 of controlled buffers. Mobile draws them. Issue #361 tracks the gap, and `compare_exports.py` marks the `port-markers` check for `svg-printer` as a DIFF for that issue.
