# Mounted visual export oracle preflight

The Android and iOS mounted scenarios remain **NOT_RUN**. This change strengthens their evidence checks before a simulator lease. It does not qualify issue #34.

The previous whole-PNG checks accepted a 1508 × 908 white raster with just the two required colored image pixels. The shared contract now requires ink in disjoint regions for both text annotations, all 72 custom symbol instances and the four distant frame edges. The checks allow antialiasing and font variation without a font pixel golden. Android records each region's measured ink count; iOS consumes the same contract and attaches the corresponding counts and contract hash.

The iOS scenario also observes one new UUID directory in the actual application's `Library/Caches/LogisimAssetExports` directory for each share and print action. It compares that staged PDF with the saved, decoded selection PDF while the native controller is presented, records its path, bytes and SHA-256, then verifies cleanup after cancellation. The native surface and visible print preview remain required. No recipient delivery or completed printer job is claimed.

The source pin's PDF encoder is deterministic for equal pixels and page settings. `renderDrawingPdf` calls `VisualPdf.encode`, which writes RGB pixels, optional alpha, page geometry and fixed PDF objects without dates or generated identifiers. Each action renders independently; a differing staged payload fails the check.

Validation completed before runtime:

- Thirteen Android offline tests passed, including the old two-pixel counterexample, omission of every region or member class, exact minimum counts and background rounding.
- The final iOS test and its shared native helpers passed typechecking against the installed iOS Simulator SDK and XCTest framework. Swift parsing and the diff check passed.
- The canonical runner's prepare-only mode passed before the metadata extension. Its result explicitly records `NATIVE_NOT_RUN` and `NOT_QUALIFIED`.

Fixture SHA-256 remains `791508747474976204e73586a944caa1b0054801d7ab3306f6a02db7033f59d1`. The strengthened contract SHA-256 is `4724c4e02540d4321d3af86203ee86897910a641e3b926343245cb7ac86c72a7`. The runtime stager must supply that exact contract text, its hash and the actual application cache path.
