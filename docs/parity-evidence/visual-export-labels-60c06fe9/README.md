# Default labels and IEC symbols compared with Evolution (#355)

Rows `format/image-svg` and `format/image-tikz`. This run repeats the SVG and TikZ portion of [`visual-export-vector-029b7a0b`](../visual-export-vector-029b7a0b/README.md) after the canvas fix.

- Mobile: `60c06fe9`, clean worktree. `core-jvm.jar` SHA-256 `7569b0aac5e98745…`. Vectors come from the `composeApp` JVM test target via `buildVisualExportAsset`, using Skia as on iOS.
- Evolution: JAR `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` (`d58a61e0`), `EvolutionVisualExport.java`, `-Djava.awt.headless=true`, with a limit of 180 s per run.
- Environment: macOS (Apple silicon), Homebrew OpenJDK 21. JVM and host tests only.
- The GIF rows in `results.json` reuse the historical Android and iOS `whole.png` files (`85da031e`, `6cef0542`). They cover those earlier revisions.


## Commands

```sh
./gradlew :core:jvmJar :core:jvmTest --tests '*Codec*' --tests '*Original*' --tests '*Profile*' --tests '*Gates*' --tests '*Io*' --tests '*Tcl*' \
  :composeApp:jvmTest --tests '*EvolutionVectorExportHostTest' \
  :composeApp:testAndroidHostTest --tests '*CircuitDrawingTest' --tests '*RendererIECTest' --tests '*PinLabelPlacementTest' \
  --tests '*DarkCanvasContrastTest' --tests '*DarkThemeTextColorTest' --tests '*AppearancePreferencesTest' \
  --tests '*CircuitLabelViewportTest' --tests '*VisualExport*' --tests '*Raster*' --tests '*ToolDefaults*'   # 341 tests, 0 failures
python3 -I scripts/oracle/visual-export/compare_exports.py --evolution-jar EVOLUTION_JAR --out docs/parity-evidence/visual-export-labels-60c06fe9
```

## Criterion

The `text-anchors` check in `compare_exports.py` compares each text element’s base-west anchor in circuit coordinates at scale 1. Anchors must be within 12 units in `qualification.circ` and IEC `svg-gates.circ`. The other checks still apply to wire edges, terminals, the multiset of text elements, the image, and matching TikZ and SVG.

## Results

| Row | Case | Result | Anchor deviation (maximum) |
| --- | --- | --- | --- |
| `format/image-svg` | `qualification` | PASS | 2.34 (previously 31.3) |
| `format/image-tikz` | `qualification` | PASS | 2.34 (previously 31.3) |
| `format/image-svg` | ANSI `svg-gates` | PASS | No text |
| `format/image-tikz` | ANSI `svg-gates` | PASS | No text |
| `format/image-tikz` | IEC `svg-gates` | PASS | 2.44 (previously 15.76) |

The remaining deviation comes from the different widths that Skia and Java2D calculate for centered text. `DarkCanvasContrastTest` and `AppearancePreferencesTest` confirm `DARK_DEFAULT_LABEL_COLOR` in the dark theme and `#0000FF` in the light theme. `CircuitDrawingTest` covers the 12-point plain font for 2.7.1 files and saving without `labelfont`. Issue #356 tracks logic gate and tunnel label positions.
