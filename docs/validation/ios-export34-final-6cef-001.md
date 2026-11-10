# iOS final mounted export attempt

**FAIL, with saved PNG, selected PDF and prepared share bytes verified.** The unchanged final application `6cef0542` ran the mounted scenario on B25 with canonical harness `b308ff1b`. One case failed in **390.711 seconds**. See [the receipt](ios-export34-final-6cef-001.json).

Verified subflows:

- The real editor saved a **1508 × 908 PNG**, 5,478,460 bytes, SHA-256 `18912f4fedb71f1d8bea9af6f4bbf0ceef098fdf278ede921280536285145c9a`. Colors and all 78 ink regions passed.
- Selection-only export saved a **42,759-byte PDF** with one A4 page and a **140 × 100 raster**. Native CoreGraphics and independent Poppler/raster checks passed.
- The native share popover presented the selected PDF. The prepared share file exactly equals the verified saved PDF, SHA-256 `1dff97f012a19b8cfa31f0145042c6c3f54a404498565d8b27dfcf7f727f914f`. No recipient delivery was attempted.

The failure is in the cancellation test. The captured popover at `(229,425,375,359)` has no Cancel or Close button; its native `PopoverDismissRegion` covers the application. The helper instead found the editor's background `editor-tool-close` at `(662,502,63,48)`. The log identifies that target and reports hit point `{-1,-1}`. Afterwards, the native marker disappeared and the assertion requiring the export tool to remain failed. This does not demonstrate what a correct native cancellation does, and the exact synthesized event beyond the reported invalid hit point is not inferred.

Print preview, staged print bytes, share cleanup and final document comparison were not reached. The full scenario remains failed. Offline byte verification independently rechecked the actual files and preserves this limit.

The B25 Simulator and its dedicated test cache were explicitly returned after terminal exit 65. Teardown terminated app 95170 and restored portrait. There was no retry, product change, recipient selection, printer job or timeout adjustment.

The actual PNG/PDFs, byte postflight, native share tree and screenshot, failure log and hashes are retained under [artifacts/ios-export34-final-6cef-001](artifacts/ios-export34-final-6cef-001/).

The private cancellation correction now searches only the presented native popover or navigation bar for a unique enabled, hittable Cancel/Close control. It excludes editor identifiers. If the observed iPad popover offers no such control, the test derives an outside-dismissal point from the actual dismissal and content rectangles, excluding the popover and all inputs/buttons including the editor Close. This is recorded as an outside gesture, not an AX Cancel action. The original fixed window coordinate was removed. Controller disappearance and continued tool availability remain mandatory.

[Ten offline geometry cases](ios-export34-share-dismiss-geometry-001.json) exercised the exact extracted Swift function with the captured native rectangles, translated coordinates, blocked regions and invalid bounds. SDK/XCTest typechecking and independent source review passed. UIKit execution of the correction remains **NOT_RUN**; the retained native scenario above remains failed.

A review addendum also blocks positive visible frames from Keyboard, `inputView` and `SystemInputAssistantView`. The latter two are queried independently even when no Keyboard node exists. Geometry is used without requiring input-container hittability or enabled state. [Fourteen offline cases](ios-export34-input-dismiss-geometry-001.json) preserve the captured no-IME point, reject each synthetic input overlay at that point, and reject complete input coverage. SDK/XCTest typechecking passed. Native input-surface collection and dismissal are still **NOT_RUN**.

A later [complete qualifying run](ios-export34-final-6cef-002.md) passed in 591.770 seconds with the corrected cancellation test and the identical application. The failure and NOT_RUN limits above describe this earlier attempt and remain preserved.
