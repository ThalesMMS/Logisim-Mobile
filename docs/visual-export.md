# Visual export contract

`VisualScene` contains drawing items in circuit coordinates. Each item has a stable document object ID, bounds that include its stroke and measured text, and vector primitives. The scene contains no viewport, gestures, selection handles, or temporary file references.

`VisualExportOptions` selects object IDs or the whole scene and configures scale, margins, background, and an optional clipping rectangle. Empty selections fail explicitly. SVG retains the circuit coordinate system in its `viewBox`; pixel dimensions reflect the chosen scale.

`VisualExport.svg` returns UTF-8 SVG bytes. Paths, rectangles, ellipses, text, affine groups, opacity, and dashed strokes remain vector elements. Images are embedded as data URIs. Neither output format refers to a source filename or temporary path.

`drawVisualScene` and `renderVisualBitmap` use the same Compose drawing function. Text uses the same supplied `TextMeasurer`, explicit circuit-unit sizes, baselines, styles, and alignment. `visualTextBounds` measures the actual text before the caller builds item bounds. Logical font names resolve to sans-serif, serif, monospace, or cursive in both exporters. An external SVG viewer supplies its own matching font, so exact glyph pixels outside the app are not guaranteed.

`renderVisualPng` draws an offscreen ARGB bitmap and passes its straight-alpha pixels to `VisualPng.encode`. The common encoder writes standard RGBA8 PNG with CRC-32, Adler-32, and stored DEFLATE blocks. Stored blocks trade larger files for a portable encoder with no native compression dependency. A platform image decoder must be supplied for embedded images; missing or invalid decoding fails instead of omitting an image.

PNG defaults to an 8,388,608-pixel allocation limit. Larger drawings can use SVG or a smaller scale. The limit can be changed explicitly up to 67,108,864 pixels. SVG is not subject to the raster allocation limit.

`renderDrawingBitmap`, `renderDrawingPng`, and `renderDrawingPdf` accept the same world-coordinate drawing callback as the interactive canvas. This path can export component renderers directly, without reconstructing them as generic boxes. Pass the interactive canvas `Density` when the callback performs density-dependent text measurement. Pixel scale remains a separate export option. Apply a selection to both the callback and its supplied bounds; these callback APIs reject a selection option that they cannot apply themselves.

`VisualPdf.encode` writes a printable one-page PDF 1.4 with an RGB image, an optional soft alpha mask, and an explicit page size. The default page is A4 with 24-point margins; the image is centered and fitted without changing its aspect ratio. This is a raster print path, not a claim that circuit text and gates become PDF vectors.

Images support stretch, fit, and cover, with the same centering and clipping rules in Compose and SVG.

The initial formats are PNG, SVG, and printable raster PDF. Desktop JPEG, GIF, and other print/export paths are not implied by these APIs. Native file creation, sharing, and printing must consume the returned bytes through the document adapters.

`visualExportEditorTool(source, delivery)` provides the export panel. It has file name, format, scale, margin, selection, and transparent background controls. It passes `LocalDensity.current` to the PNG and PDF drawing callback. SVG is available for every circuit: a complete `CircuitExportSource.scene` keeps its vector items and object IDs; any other drawing is recorded from the canvas callback, as Evolution's `ExportImage` writes SVG and TikZ from one `TikZWriter` recording. The source must filter both its bounds and drawing callback to the supplied selection IDs. Its default selection consists of the selected component IDs; the host can override this to include selected wires.

`DocumentAssetActions.visualExportDelivery()` connects the panel to the native create, share, and print methods. Print accepts PDF only and follows `supportsPrint`. Native cancellation stays distinct from a failure. Completion means that a file was saved or that the system sharing or printing interface opened; it does not prove delivery to another app or a printer. Translation strings live in `localization/messages/visual_export.json`. The host must register the tool with its real canvas source and native adapter to expose the panel.

## Canvas-recorded SVG and TikZ

`renderDrawingSvg` and `renderDrawingTikz` (`TikzDrawing.kt`) run the canvas drawing callback against a recording `Canvas`. It applies the current transform to every call and records shapes, text lines and images in output pixels; quarter-turn rotations keep rectangles and circles. Both writers follow Evolution's `TikZInfo`: TikZ uses `[x=1pt,y=-1pt,line cap=rect]`, `custcol_RRGGBB` colours and `\logisimfontX` font macros, and leaves a labeled placeholder for raster images; SVG writes flat `rect`, `circle`, `path` (with `H`/`V` runs), `text` and embedded PNG `image` elements. Unlike Evolution, clips are kept (TikZ scopes, SVG `clipPath`), stroke opacity, caps, joins and dashes are written, and text sizes follow the export scale.

## GIF

`VisualGif.encode` builds its palette like the ImageIO GIF writer that Evolution uses (`PaletteBuilder`): an octree over the pixels, scanned from the last pixel, keeps every colour when there are at most 256 and otherwise merges the least-used deepest branches. The decoded pixels equal ImageIO's GIF of the same pixels.

## Comparison with Evolution

`scripts/oracle/visual-export/compare_exports.py` compares the GIF palette with ImageIO and the SVG and TikZ structure with Evolution's `TikZWriter`. The Mobile vector files come from the JVM host target, which draws with Skia like iOS:

```sh
./gradlew :core:jvmJar :composeApp:jvmTest --tests '*EvolutionVectorExportHostTest'
python3 -I scripts/oracle/visual-export/compare_exports.py --evolution-jar EVOLUTION_JAR --out DIR
```

## Verification

Run the core format tests and Android compilation with the configured Android SDK:

```sh
./gradlew :core:jvmTest --tests '*Visual*Test' :composeApp:compileAndroidMain --console=plain
```

The panel's native delivery contract has host tests for byte preservation, MIME types, cancellation, PDF-only printing, and file names:

```sh
./gradlew :composeApp:testAndroidHostTest --tests '*VisualExportToolsTest' --console=plain
```

The JVM tests parse SVG with a standard XML parser and decode PNG with `ImageIO`. They compare every ARGB pixel across multiple DEFLATE blocks and check chunk CRCs with the JDK implementation. Common tests cover selection, scale, margins, text escaping, embedded images, clipping, transforms, printable page bounds, and large vector exports. Actual canvas exports and the native delivery flows remain integration checks.

`ImageBitmap.readPixels` returns non-premultiplied ARGB pixels, matching the common PNG input. [AndroidX source](https://android.googlesource.com/platform/frameworks/support/+/f2e05c341382db64d127118a13451dcaa554b702/compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/ImageBitmap.kt)

The standalone PDF interoperability check requires Poppler's `pdftoppm` executable:

```sh
./scripts/verify-visual-export.sh
```

It renders the generated PDF at 72 dpi, checks its dimensions, and compares the centers of six opaque color patches and four alpha-composited color patches against literal expected values. `PDFTOPPM` can specify the renderer path. The generated PNG, SVG, PDF, and rendered PDF images remain under `outputs/visual-export/`.
