# Visual exports compared with Evolution (#331)

Campaign #331, rows `format/image-gif`, `format/image-jpg`, `format/image-svg`, `format/image-tikz`, and `format/image-wavedrom`.

- Mobile: Worktree at `a4e57de9eb3555fb5e6eed78ab6d5470e43ee229` with the new host test and oracles uncommitted. `core-jvm.jar` SHA-256 `3121d939a28779c1…` includes the in-progress #331 CLI changes, which leave `SignalTrace.kt` and `VisualGif.kt` untouched. Mobile rasters are historical `whole.png` captures saved by the mounted editor on Android (`85da031e`, `docs/validation/android-export34-final-002.json`) and iOS (`6cef0542`, `docs/validation/ios-export34-final-6cef-002.json`).
- Evolution: JAR `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` (`d58a61e0`), `-Djava.awt.headless=true`, in-memory preferences (`MemoryPreferencesFactory`).
- Environment: macOS (Apple silicon), Homebrew OpenJDK 21. JVM and host tests only. The Android SDK was used for the host test.


## Commands

```sh
./gradlew :core:jvmJar :composeApp:testAndroidHostTest --tests '*EvolutionVisualExportOracleTest'   # 3 tests, 0 failures
python3 -I scripts/oracle/visual-export/run.py --evolution-jar EVOLUTION_JAR --out docs/parity-evidence/visual-export-a4e57de9
```

`run.py` compiles the oracles and exports from Evolution (`EvolutionVisualExport.java`, a headless copy of `ExportImage.ExportThread.export` with and without printer view). It compares rasters (`RasterCompare.java`), SVG structures, and WaveDrom JSON (`WaveDromDesktopOracle.java` uses Evolution’s chronogram `Model` and the body of `ChronoPanel.exportWaveDrom`. `WaveDromMobileOracle.java` uses `TraceRecorder.exportWaveDrom`). `results.json` records each check. Outputs are in `evolution/` and `mobile/`, and logs in `logs/`.

## Criteria defined before the run

- Raster (`qualification.circ`, scale 0.25): both outputs must have ink in all 78 contract regions (2 text elements, 72 symbols, 4 frame edges). Ink outside the regions, symbol labels, and embedded image must cover at most 0.1% of the image. Red and green samples in the embedded image must match within 40 per channel. The Evolution frame must match Mobile’s frame minus the extra margin. For GIF, Mobile’s `VisualGif` encoder runs on Mobile pixels and must preserve regions and colors.
- SVG (`svg-gates.circ`, IEC gates, scale 1): matching wire edges (grid of 10), gate boxes (±0.5), symbols, terminal markers, and the negation bubble (center ±1.5).
- WaveDrom (`wavedrom.circ`, 4-bit counter, 34 half-cycles, one sample per half-cycle): matching name, wave, and data for each signal.

## Results

| Row | Result | What matches | Difference |
| --- | --- | --- | --- |
| `format/image-jpg` | PASS (Android and iOS) | Frame 1508×908 vs. 1503×903, differing only in margin (Mobile 10 units, Evolution 5). 78/78 regions, embedded image colors (`ff0000`, `7fff7f`, with Evolution compression yielding `fb0700`/`87fa7b`), no extra ink. | Mobile uses system JPEG encoding (Android `Bitmap.compress` q90, Skia q90 on iOS), which is unavailable in this JVM test. Evolution uses ImageIO (default quality 0.75). The check compares the drawings fed to the encoders. |
| `format/image-gif` | DIFF | Matching frame, regions, and ink. The Mobile GIF decodes with the correct size and 78/78 regions. | Mobile GIF uses a fixed 3-3-2 RGB palette (maximum error R/G 31, B 63), shifting embedded green from `7fff7f` to `6dff55`. Evolution uses ImageIO’s adaptive palette and reproduces the PNG of the same drawing with maximum error 1 (273 colors, compared with 431 and 363 in the Mobile PNGs). |
| `format/image-svg` | DIFF | With IEC gates, 56/56 wire edges, 4 boxes and symbols (`&`, `≥1`, `=1`, `1`), 11 terminal markers, and the NOT bubble. Styling differs (Evolution wires gray at 3 px, Mobile blue at 2 px, 370×210 vs. 373×214 because Mobile includes stroke in the bounds). | Mobile produces SVG only for fully vector scenes with IEC or DIN gates, wires, annotations, and subcircuits with vector appearances. ANSI gates (Evolution’s default), pins, other components, and the opaque appearance in `qualification.circ` make the scene null and disable SVG in the panel. Evolution exports SVG for any circuit (`evolution/svg-gates-ansi/main.svg` and `evolution/qualification/*.svg`). |
| `format/image-tikz` | NOT RUN on Mobile | Evolution generated a reference (`evolution/*/…tex`). | Mobile TikZ draws through the Compose canvas. JVM host lacks `android.graphics.Paint` (`mobile/host-canvas.txt`), so the run recorded no Mobile output. To resume, capture `renderDrawingTikz` for `qualification.circ` and `svg-gates.circ` in an Android or iOS harness and compare with these `.tex` files. |
| `format/image-wavedrom` | DIFF | Identical `clk`, `carry`, and `open`. The `q` values and sample times match. | (1) Evolution encodes a bus at minimum and maximum as `0` and `1` without data (`0000`, `1111`). Mobile writes `2` with data. The `q` waves match after mapping these encodings. (2) Evolution includes width in the bus name (`q[3..0]`). (3) Evolution also samples `t = end` and appends `x` to all waves (from the `t <= t1` loop). (4) Evolution’s default selection includes the counter itself. |

- Evolution GIF and JPEG in printer view (`evolution/qualification-printer/`) render the embedded image in grayscale. The comparison uses normal view, as in the recorded Mobile export.
- In the app, signal logging uses propagation time. The Mobile oracle captures one frame per half-cycle (`TraceTimeUnit.HALF_CYCLES`), the time base used by Evolution’s chronogram in clock mode.
