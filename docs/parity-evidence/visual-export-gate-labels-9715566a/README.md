# Logic gate, buffer, and tunnel labels compared with Evolution (#356)

Rows `format/image-svg` and `format/image-tikz`. This run repeats [`visual-export-labels-60c06fe9`](../visual-export-labels-60c06fe9/README.md) and adds `scripts/oracle/visual-export/svg-labels.circ`. The fixture includes labeled AND, NAND, XOR (30), OR (70), NOT, Buffer, Controlled Buffer, and Controlled Inverter (control on the left), and two labeled tunnels, in all four orientations, in ANSI and IEC.

- Mobile: `9715566a`, clean worktree. `core-jvm.jar` SHA-256 `b09faadfda5668ec…`. Vectors come from the `composeApp` JVM test target via `buildVisualExportAsset`, using Skia as on iOS.
- Evolution: JAR `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` (`d58a61e0`), `EvolutionVisualExport.java`, `-Djava.awt.headless=true`, with a limit of 180 s per run. The oracle now sets `GATE_SHAPE` before opening the file, when `AbstractGate.computeLabel` and `NotGate.configureLabel` read the shape, as in a session started in IEC.
- Environment: macOS (Apple silicon), Homebrew OpenJDK 21. JVM and host tests only. The `iosTest` raster tests require a simulator and were skipped. They cover other behavior than gate and tunnel label positions.
- The GIF rows in `results.json` reuse the historical Android and iOS `whole.png` files (`85da031e`, `6cef0542`). They cover those earlier revisions.


## Commands

```sh
./gradlew :core:jvmJar :composeApp:jvmTest --rerun --tests '*EvolutionVectorExportHostTest' \
  :composeApp:testAndroidHostTest --tests '*GateLabelPlacementTest' --tests '*CircuitDrawingTest' --tests '*RendererIECTest' \
  --tests '*PinLabelPlacementTest' --tests '*DarkCanvasContrastTest' --tests '*DarkThemeTextColorTest' --tests '*AppearancePreferencesTest' \
  --tests '*CircuitLabelViewportTest' --tests '*VisualExport*' --tests '*Raster*' --tests '*TextTool*'   # 68 tests, 0 failures
python3 -I scripts/oracle/visual-export/compare_exports.py --evolution-jar EVOLUTION_JAR --out docs/parity-evidence/visual-export-gate-labels-9715566a
```

The baseline in `logs/baseline-28b99806-results.json` uses the same fixture, oracle, and script with `composeApp/src/commonMain` from `28b99806`.

## Criterion

The `text-anchors` check in `compare_exports.py` compares each text element’s base-west anchor in circuit coordinates at scale 1. Anchors must be within 12 units in ANSI and IEC `svg-labels.circ`, with `qualification.circ` and `svg-gates.circ` still covered. The check runs once all Evolution text elements exist in Mobile.

## Results

| Row | Case | Result | Anchor deviation (maximum) |
| --- | --- | --- | --- |
| `format/image-svg`, `format/image-tikz` | `qualification` | PASS | 2.34 |
| `format/image-svg`, `format/image-tikz` | ANSI `svg-gates` | PASS | No text |
| `format/image-tikz` | IEC `svg-gates` | PASS | 2.44 |
| `format/image-svg`, `format/image-tikz` | ANSI `svg-labels` | DIFF (#358) | 1.46 (previously 49.00) |
| `format/image-svg`, `format/image-tikz` | IEC `svg-labels` | DIFF (#358) | 2.44 (previously 55.00) |

Issue #358 tracks the remaining `svg-labels` differences. Evolution draws the tunnel terminal, the controlled buffers’ control stroke without a marker, and input strokes on the wide OR. It retains the controlled buffer triangle in IEC, where Mobile alone draws eight `1` labels. `GateLabelPlacementTest` checks the anchors from `AbstractGate.computeLabel`, `NotGate.configureLabel`, and `Tunnel.computeBounds`. `DarkCanvasContrastTest` still passes.
