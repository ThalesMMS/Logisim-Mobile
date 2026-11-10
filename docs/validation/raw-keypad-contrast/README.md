# Raw keypad glyph contrast

The default public MatrixKeypad `raw` mode painted its unpressed numeric labels white on opaque cyan `#00AADC`. The source-color contrast is 2.698066480877:1, below both the normal-text 4.5:1 and large-text 3:1 benchmarks. This candidate uses black labels only for those cells, raising the ratio to 7.783351577451:1.

The cyan/red/blue backgrounds, PMOD presentation, pressed-state paints, labels, font sizes, positions, geometry, interaction and XML are unchanged. The condition explicitly excludes PMOD and pressed keys. Canvas and raster/print drawing share this renderer, so the intended foreground change also applies to their raw keypad labels. No app theme, wire palette, Inspector draft or focus code changed.

## Exact source-color table

All colors are opaque integer ARGB. Ratios use sRGB linearization and relative luminance; threshold comparisons use the unrounded values in [colors-before-after.json](colors-before-after.json). Normal and large keypad sizes share these paints. Circuit units do not establish physical typographic points, and the corrected pair passes either threshold.

| Meaningful key-label text | Background | Before foreground | After foreground | Before ratio | After ratio |
| --- | --- | --- | --- | ---: | ---: |
| Raw numeric, unpressed | `#00AADC` | `#FFFFFF` | `#000000` | 2.698066480877 | 7.783351577451 |
| Raw numeric, pressed | `#0078AA` | `#FFFFFF` | `#FFFFFF` | 4.921439482135 | 4.921439482135 |
| Raw function, unpressed | `#DC2828` | `#FFFFFF` | `#FFFFFF` | 4.797493526693 | 4.797493526693 |
| Raw function, pressed | `#AA1E1E` | `#FFFFFF` | `#FFFFFF` | 7.207414213862 | 7.207414213862 |
| PMOD, unpressed | `#1E508C` | `#FFFFFF` | `#FFFFFF` | 8.135252484385 | 8.135252484385 |
| PMOD, pressed | `#0F2D55` | `#FFFFFF` | `#FFFFFF` | 13.753883649365 | 13.753883649365 |

Unpressed keys remain available controls/content; they are not inactive controls exempt from the text benchmark. The existing high-contrast signal preference did not change this keypad renderer's foreground. The [W3C contrast explanation](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html) supplies the quantitative benchmark; this source calculation is not a complete WCAG or issue #28 certification.

## Scope and provenance

The renderer blob at base `c3d7e000289115582ce901702e32268fce439bb2` is identical to production `3a0a01f1`. [Source proof](source-proof.json) records original blob `f187aaaf`, patched blob `b8b13c4f781ff040dfeb409b01b58272322409a1`, SHA-256 and the independent source review. The patch adds no test, attribute, message or source-regex gate.

The qualified 3a keyboard-tty screenshot contains the blue PMOD keypad, because its retained `io-showcase.circ` explicitly selects `pmod_kypd`. That appearance is a distinct variant; the keyboard case does not qualify raw keypad contrast or PMOD interaction. Earlier Native evidence retains its original producer identity.

## Existing checks

[Checks](checks.json) preserve the raw XML and compressed logs for 21 existing tests: IoGeometryOracle (1), CircuitDrawing (7), ExternalIoRenderer (6), RendererIEC (6), and WorldDrawingText (1). All passed with zero failures, errors or skips. These exercise existing geometry, world text, palette/drawing/export and adjacent renderer behavior. WorldDrawingText checks circuit-coordinate sizing, not Native large-text UI.

The first invocation stopped before compilation because the new worktree lacked `ANDROID_HOME`; its configuration-error log is retained. The corrected environment used JDK 21 and the existing Android SDK, with the production-source hash unchanged. No APK/app assembly, Native test, UI/device operation or new mirror test was performed.

Recompute the table without a build:

```sh
python3 docs/validation/raw-keypad-contrast/calculate.py
```

Root will review this candidate and combine it with separately owned focus/layout work before the next app build. Raw keypad appearance at that new immutable producer remains a Native verification step. Other audit findings and the full reader/font/layout acceptance gaps are outside this patch.
