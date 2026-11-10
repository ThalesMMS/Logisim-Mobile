# Visual export field census

**ALPHA_ONLY.** The diagnostic case passed in 29.436 seconds on the unchanged Beta application. The canonical runner enforced alpha mode. This does not qualify export or supersede the failed mounted attempt. See [the receipt](ios-export34-beta-field-census-001.json).

The native tree demonstrates the lookup problem:

| Observation | Value |
| --- | --- |
| Application frame | `(0,0,834,1210)` |
| `editor-tool-sheet` marker | `(97,0,640,768)`, empty sibling with no editable descendants |
| Actual content `Other` | `(109,550,616,628)`, contains the heading and fields |
| File name | TextView, `(121,730,592,64)`, exact label, hittable |
| Scale | TextView, `(121,802,292,64)`, exact label, hittable |
| Margin | TextView, `(421,802,292,64)`, exact label, hittable |

The application exposes three editable controls. A global canonical File name query finds the field and its static label. The same query within the tagged marker finds zero controls because that marker is not their ancestor. Its local rectangle also explains the earlier top-edge swipe. The missing scoped match was not caused by an incorrect label or an inaccessible input in this observed state.

The census opened the fixture and panel, then captured the tree, frames and attributes without search swipes or text replacement. Public app launch established a foreground baseline after the previous Notification Center state. The test terminated the app and restored portrait; the B25/cache lease was explicitly returned.

The next correction must scope to the observed content container and preserve unique-field, viewport, hittability and export-byte checks. No product, shared helper, timeout or fixed-coordinate fallback changed in this diagnostic run. Saved PNG/PDF, sharing and printing remain unqualified.

The private test correction now resolves a unique visible editable/action control before considering scroll. Its fallback requires one smallest `Other` ancestor containing the canonical heading and all three unique fields, recalculates it after layout changes, and uses the shared keyboard-aware scroll routine with the original twelve attempts in each direction. Offline inspection found seven eligible ancestors and one unique minimum matching the observed content rectangle. SDK/XCTest typechecking and independent source review passed. Native execution of this correction remains **NOT_RUN**, including title/Close hittability after keyboard presentation.

Original attachments, screenshot, XML and checksums are retained under [artifacts/ios-export34-beta-field-census-001](artifacts/ios-export34-beta-field-census-001/).
