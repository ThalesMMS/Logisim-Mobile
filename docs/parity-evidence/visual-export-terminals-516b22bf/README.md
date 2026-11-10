# Tunnel terminals, control and input strokes, and parity gates compared with Evolution (#358)

Rows `format/image-svg` and `format/image-tikz`. This run repeats [`visual-export-gate-labels-9715566a`](../visual-export-gate-labels-9715566a/README.md), which records the historical DIFF results for #358. After the fix, `scripts/oracle/visual-export/svg-labels.circ` adds a labeled east-facing `Odd Parity`, a labeled west-facing `Even Parity`, and a north-facing `Odd Parity`.

- Mobile: Run at `41c29aeb` with a clean worktree, then integrated as `516b22bf` after a rebase. The run applies to the same `core`, `composeApp`, `fixtures`, and `scripts/oracle` sources. `git diff --stat 41c29aeb 516b22bf` shows changes only to `scripts/android_layout12_scenarios.py` and its test. `core-jvm.jar` SHA-256 `680bd30c8afa66c3…`. Vectors come from the `composeApp` JVM test target via `buildVisualExportAsset`, using Skia as on iOS.
- Evolution: JAR `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` (`d58a61e0`), `EvolutionVisualExport.java`, `-Djava.awt.headless=true`, with a limit of 180 s per run.
- Environment: macOS (Apple silicon), Homebrew OpenJDK 21. JVM and host tests only. The `iosTest` raster tests require a simulator and were skipped.
- The GIF rows in `results.json` reuse the historical Android and iOS `whole.png` files (`85da031e`, `6cef0542`). They cover those earlier revisions.


## Change

- `Tunnel`: the canvas and export draw a terminal marker at the tunnel point (`painter.drawPorts()`).
- `Controlled Buffer` and `Controlled Inverter` (`ControlledBuffer.paintInstance`): draw a stroke of width 3 in the value color from the control terminal to `pt0.translate(facing, 0, ±6)`, snapped to the half-grid like `Location.create(x, y, true)` (`x / 5 * 5`). Only ports 0 and 1 get markers. IEC uses the triangle and the inverter’s circle, following `paintShape` and replacing the previous `1` rectangle.
- ANSI OR, NOR, XOR, and XNOR (`PainterShaped.paintInputLines`): draw input strokes of width 3 in the value color, using lengths from `getInputLineLengths` (steps inside the shield from `computeShield`) and the same snapping.
- `Odd Parity` and `Even Parity`: use rectangles with `2k+1` and `2k` for all gate shapes (`paintShape` → `paintRectangular`), including ANSI.

## Commands

```sh
./gradlew :core:jvmJar :composeApp:jvmTest --rerun --tests '*EvolutionVectorExportHostTest'
ANDROID_HOME=~/Library/Android/sdk ./gradlew :composeApp:testAndroidHostTest --tests '*GateTerminalLinesTest' --tests '*RendererIECTest' \
  --tests '*RendererDINTest' --tests '*GateLabelPlacementTest' --tests '*CircuitDrawingTest' --tests '*PrinterViewTest' \
  --tests '*AppearancePreferencesTest' --tests '*DarkCanvasContrastTest' --tests '*DarkThemeTextColorTest' --tests '*VisualExport*' \
  --tests '*Raster*' --tests '*PinLabelPlacementTest' --tests '*TextTool*' --tests '*CircuitLabelViewportTest'   # 80 tests, 0 failures
python3 -I scripts/oracle/visual-export/compare_exports.py --evolution-jar EVOLUTION_JAR --out OUT
```

## Criterion

`wire-edges`, `port-markers`, and `texts` in `compare_exports.py` must pass for ANSI and IEC `svg-labels.circ` with `Odd Parity`, while retaining coverage of `qualification.circ` and `svg-gates.circ`. `compare_exports.py` drops the known-difference exceptions for #358.

## Results

| Row | Case | Result | Detail |
| --- | --- | --- | --- |
| `format/image-gif` | `android-whole-gif`, `ios-whole-gif` | PASS | Historical rasters |
| `format/image-svg`, `format/image-tikz` | `qualification` | PASS | |
| `format/image-svg`, `format/image-tikz` | ANSI `svg-gates` | PASS | |
| `format/image-tikz` | IEC `svg-gates` | PASS | |
| `format/image-svg`, `format/image-tikz` | ANSI `svg-labels` | PASS | 8/8 edges (previously 8/0), 97/97 markers at the same positions, 45/45 text elements with `2k+1` and `2k`, anchor deviation 3.82 |
| `format/image-svg`, `format/image-tikz` | IEC `svg-labels` | PASS | 4/4 edges (previously 4/0), 97/97 markers, 69/69 text elements (previously eight `1` labels only in Mobile), anchor deviation 3.82 |

`GateTerminalLinesTest` checks control and input stroke coordinates against Evolution’s SVG, the missing control marker, and rectangular parity gates in ANSI, IEC, and DIN. `RendererIECTest` now requires the controlled buffer triangle in IEC. `DarkCanvasContrastTest` still passes.

Printer view was outside this comparison. Evolution draws an input stroke only for a connected port (`isPortConnected`), while Mobile draws it for every input.
