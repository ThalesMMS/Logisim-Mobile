# GIF, SVG, and TikZ compared with Evolution (#350, #351, #352)

Rows `format/image-gif`, `format/image-svg`, and `format/image-tikz`. This run follows the GIF, SVG, and TikZ portion of the historical [`visual-export-a4e57de9`](../visual-export-a4e57de9/README.md).

- Mobile: `029b7a0b5ed44ed28ef830868435a6b4e61e3c8a`, clean worktree. `core-jvm.jar` SHA-256 `e717bbf3e91af815…`. Vectors come from the `composeApp` JVM test target via `buildVisualExportAsset`, using Skia as on iOS. This is the same path as the export panel. The GIF input rasters are historical `whole.png` captures saved by the mounted editor on Android (`85da031e`) and iOS (`6cef0542`). These are the pixels the GIF exporter encodes.
- Evolution: JAR `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` (`d58a61e0`), `EvolutionVisualExport.java` (a headless copy of `ExportImage`), `-Djava.awt.headless=true`, in-memory preferences, with a limit of 180 s per run.
- Environment: macOS (Apple silicon), Homebrew OpenJDK 21. JVM and host tests only.


## Commands

```sh
./gradlew :core:jvmJar :core:jvmTest --tests '*VisualGif*' :composeApp:jvmTest --tests '*EvolutionVectorExportHostTest'   # 5 + 3 tests, 0 failures
python3 -I scripts/oracle/visual-export/compare_exports.py --evolution-jar EVOLUTION_JAR --out docs/parity-evidence/visual-export-vector-029b7a0b
```

`results.json` records each check, `evolution/` and `mobile/` hold compared files, and `logs/` holds run output. The receipt keeps only compared files: Evolution rasters at scale 0.25 for GIF, and SVG and TikZ at scale 1.

## Criteria defined before the run

- GIF (`qualification.circ`, scale 0.25, Android and iOS): decoding `VisualGif` and ImageIO GIFs (the writer used by Evolution’s `ExportImage`) from identical inputs must yield matching pixels, with the same maximum per-channel error against the PNG. The decoded Mobile GIF must have 78/78 contract regions and preserve embedded image colors within a tolerance of 40 (`RasterCompare.java`).
- SVG and TikZ (scale 1, `qualification.circ` and ANSI `svg-gates.circ`, plus IEC for TikZ): matching wire edges on the grid of 10, terminal points (±0.5), multiset of text elements, and image position (±1) and size in circuit coordinates. Text anchors must be within 12 units, with larger differences attributed to the canvas. Mobile TikZ and SVG must have matching counts from the same recording.

## Results

| Row | Case | Result | Detail |
| --- | --- | --- | --- |
| `format/image-gif` | Android | PASS | 0 of 1,369,264 pixels differ from ImageIO GIF. 431 source colors, 256 palette colors, maximum error 3 for both (previously fixed 3-3-2, B=63, `7fff7f`→`6dff55`), 78/78 regions, exact `ff0000` and `7fff7f`. |
| `format/image-gif` | iOS | PASS | 0 different pixels, 363 source colors, maximum error 2 for both, 78/78 regions, exact colors. |
| `format/image-svg` | ANSI `svg-gates` | PASS | 56/56 wire edges, 11/11 terminals. Previously the scene was null and produced no SVG. |
| `format/image-svg` | `qualification` | DIFF `text-anchors` | 1,920/1,920 edges, 150/150 terminals, 75/75 text elements, embedded 2×1 PNG image at (80, 200). Anchor deviation up to 31.3 units for subcircuit labels without an explicit font (Evolution blue 16-point bold SansSerif, Mobile 11 px `#202070`, centered). |
| `format/image-tikz` | ANSI `svg-gates` | PASS | 56/56 edges and 11/11 terminals. Mobile TikZ matches SVG. |
| `format/image-tikz` | IEC `svg-gates` | DIFF `text-anchors` | 56/56 edges, 11/11 terminals, text `&`, `≥1`, `=1`, `1`. Mobile IEC symbol baselines are 15.76 units lower. |
| `format/image-tikz` | `qualification` | DIFF `text-anchors` | Matches the SVG result. Mobile TikZ and SVG have 226 fills, 153 strokes, and 75 text elements. |

Both exporters record the text anchors from the canvas drawing. Issue #355 tracks the difference, which also affects PNG, GIF, JPEG, and PDF. The comparison criteria exclude canvas styling such as wire width (Mobile 2 px, Evolution 3 px), wire color outside simulation, and gate outlines of 1.7 px with white fill. Mobile’s frame includes strokes and terminal points (380×214 vs. 370×210 in `svg-gates`).

The format follows `TikZWriter` with a comment header, `\begin{tikzpicture}[x=1pt,y=-1pt,line cap=rect]`, `\def\logisimfontA#1{\textsf{#1}}`, `custcol_RRGGBB` colors, `\draw [line width=…pt, custcol_…]`, `circle (r)`, nodes with `anchor=base west`, and an `[Image: WxH]` placeholder. Flat SVG uses `H`/`V`, `circle`, `text`, and `image` with a matrix. Mobile retains clips, stroke opacity, caps, joins, and dashes, and scales font size. Evolution writes the unscaled font size.
