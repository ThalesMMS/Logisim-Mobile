# Printer-view terminal markers compared with Evolution (#361)

Rows `format/image-svg` and `format/image-tikz`. This run repeats [`visual-export-printer-93a4343c`](../visual-export-printer-93a4343c/README.md) after the fix with the same cases.

- Mobile: `74564f1a`, clean worktree. `core-jvm.jar` SHA-256 `54cd741eaa2470e3…`. Vectors come from the `composeApp` JVM test target via `buildVisualExportAsset`, using Skia as on iOS, with `CircuitDrawingStyle(printerView = true)` for `svg-printer`.
- Evolution: JAR `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` (`d58a61e0`), `EvolutionVisualExport.java` with `printerView=true` for `svg-printer`, `-Djava.awt.headless=true`, with a limit of 180 s per run.
- Environment: macOS (Apple silicon), Homebrew OpenJDK 21. JVM and host tests only. The `iosTest` raster tests require a simulator and were skipped.
- The GIF rows in `results.json` reuse the historical Android and iOS `whole.png` files (`85da031e`, `6cef0542`). They cover those earlier revisions.


## Change

The audit covered uses of `isPrintView` around `drawPort` and `drawPorts` in Evolution `d58a61e0`.

- `AbstractGate.paintInstance` (AND, OR, NAND, NOR, XOR, XNOR, even and odd parity) calls `drawPorts()` only when `!painter.isPrintView() || getGateShape() == SHAPE_RECTANGULAR`.
- `ControlledBuffer.paintInstance` (controlled buffer and inverter) calls `drawPort(0)` and `drawPort(1)` only outside printer view. Port 2 always lacks a marker.
- `NotGate` and `Buffer` always call `drawPorts()`. In SoC components, `isPrintView` hides only text and the bus selector, after `drawPort` calls. `PainterDin` uses `isPrintView` for input strokes only (covered by #360). All other components draw ports independently of `isPrintView`.

Mobile applies the same rule (`omitsPrinterViewPorts` in `ClassicRenderer.kt`) to the canvas (`drawClassicComponent`) and vector exports (`circuitDrawingTerminalMarkers`). In printer view, IEC keeps its markers and DIN 40700 omits them, since Evolution treats DIN as nonrectangular. `compare_exports.py` now runs the `port-markers` check for `svg-printer` without a known-difference exception.

## Commands

```sh
./gradlew :core:jvmJar :composeApp:jvmTest --rerun --tests '*EvolutionVectorExportHostTest' \
  :composeApp:testAndroidHostTest --tests '*GateTerminalLinesTest' --tests '*DarkCanvasContrastTest' \
  --tests '*PrinterViewTest' --tests '*CircuitDrawing*'   # 26 tests, 0 failures
python3 -I scripts/oracle/visual-export/compare_exports.py --evolution-jar EVOLUTION_JAR --out OUT
```

Tests and comparison ran at `74564f1a`. See `logs/gradle-focused-tests.txt`.

## Results

| Row | Case | Result | Detail |
| --- | --- | --- | --- |
| `format/image-gif` | `android-whole-gif`, `ios-whole-gif` | PASS | Historical rasters |
| `format/image-svg`, `format/image-tikz` | `qualification` | PASS | |
| `format/image-svg`, `format/image-tikz` | ANSI `svg-gates` | PASS | |
| `format/image-tikz` | IEC `svg-gates` | PASS | |
| `format/image-svg`, `format/image-tikz` | ANSI and IEC `svg-labels` | PASS | |
| `format/image-svg`, `format/image-tikz` | `svg-printer` (printer view) | PASS | `port-markers` 1 Evolution / 1 Mobile (Pin only), `terminal-strokes` 21/21 |

All checks in `results.json` passed. `PrinterViewTest.printerViewOmitsTerminalMarkersWhereEvolutionDoes` checks that printer view omits markers on ANSI gates and controlled buffers, retains IEC and NOT/Buffer markers, and leaves markers visible outside printer view.
