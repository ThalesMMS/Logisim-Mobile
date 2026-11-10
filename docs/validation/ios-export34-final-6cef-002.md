# iOS mounted export qualification

The complete issue #34 scenario passed on the iPad Simulator in **591.770 seconds**, with one test and zero failures. The actual editor saved the complete drawing as PNG, saved the selected image as PDF, presented the native share interface and print preview, cancelled both, removed their temporary assets and preserved the project. See [the receipt](ios-export34-final-6cef-002.json).

The application remained the frozen `6cef0542` producer, bundle tree `d987d43d6587c0139ff0ecddca2d4ae95c9bad66ba6cd19d29c6452214be3080`. Harness `10d18458` used Visual source SHA-256 `f5c2fc51f3d8cb9df09520b5f3488f74b6f9ce94367f046f013b906f0e2bff47`. The producer manifest, executable and debug library hashes were checked again after execution; the isolated harness remained clean.

- The real Files exporter saved a **1508 × 908 PNG**, 5,478,460 bytes, SHA-256 `18912f4fedb71f1d8bea9af6f4bbf0ceef098fdf278ede921280536285145c9a`. Three color samples and all 78 required ink regions passed. Those regions cover two text annotations, all 72 symbols and all four edges of the 6000 × 3600 drawing frame.
- Selection export saved a **42,759-byte PDF**, SHA-256 `1dff97f012a19b8cfa31f0145042c6c3f54a404498565d8b27dfcf7f727f914f`. Native CoreGraphics opened its single A4 page, checked the 140 × 100 embedded image and rendered its red and green regions.
- The native share popover received a staged PDF exactly equal to the saved PDF. The observed popover had no Cancel control. The test derived `(152.25,600)` from its actual dismissal and content rectangles while avoiding the popover, editor controls and any reported input frames. The recorded `inputSurfaces` list was empty. The outside gesture dismissed the marker and popover, retained the export tool and triggered temporary asset cleanup. This was an outside dismissal, not an AX Cancel action.
- The native print interface showed **Page 1 of 1** and A4. Its image preview contained 26,180 red pixels and 26,004 green pixels. The staged print PDF exactly equalled the saved PDF. The scoped native Cancel control dismissed printing, retained the export tool and removed the temporary asset.
- After closing the export tool, the fixture circuit remained visible. Project XML before and after was identical byte for byte: 15,809 bytes, SHA-256 `85d27df58497fdf252feadf9b6e854bb9d05996a7d8f0893f50c23e0c2e466d4`.

The previous [390.711-second failure](ios-export34-final-6cef-001.md) remains failed. This later run validates the corrected test cancellation route against the identical application. Exactly the existing single `system.open` fixture bootstrap ran. There was no added provider scenario, fallback, retry, alpha census or application rebuild.

The wrapper reports `native_execution_gate=NATIVE_PASS`, `status=PASS` and test exit 0. Its aggregate `qualification_gate=PARTIAL` remains unchanged because only this complete scenario was selected, rather than the entire global test suite.

The B25 Simulator and dedicated DerivedData were explicitly returned to their owner after terminal exit 0. Teardown terminated application PID 40026 and restored portrait. This evidence covers an iPad Simulator. No recipient delivery or physical printer job was attempted. Full-fixture SVG is unavailable under the declared profile; this scenario qualifies PNG and printable PDF.

Actual files, native screenshots and trees, cancellation geometry, logs and their hashes are retained under [artifacts/ios-export34-final-6cef-002](artifacts/ios-export34-final-6cef-002/).

Independent offline verification passed all eight checks on the actual files. It also confirmed that the 436 × 616 print crop equals rectangle `[32,96,468,712]` of the 1668 × 2420 native screenshot and independently reproduced both color counts. The [postflight JSON](artifacts/ios-export34-final-6cef-002/offline-byte-postflight.json) records the checks and their limits.
