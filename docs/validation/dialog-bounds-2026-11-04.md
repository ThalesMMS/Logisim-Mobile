# Scrollable editor dialog bounds

Candidate `eb376d71cab4a13bc19ac18371d330464ac78327` bounds the Tools, XML and toolbar overflow dialogs by their parent window's available height, excluding safe drawing and keyboard insets. The bound is read before the dialog creates its separate composition. It keeps the scrollable text slot finite without assigning a fixed body height.

The source compiled for Android and iOS Simulator. An isolated arm64 iOS application built successfully and passed ad-hoc signature verification. Its bundle-tree SHA-256 is `c916296b913dbe75f9d98495ca19b59d1c6a5f6b5ddebef5fef3e01adaf942d1`; its debug dylib SHA-256 is `6fae78072812c9efa32f95ea059ac6d87f5b27868de23ff4e1da901d8bd40e65`.

On the same iPhone 17e simulator, the unchanged XCTest products first reproduced an empty XML dialog body in landscape on `9455c044`. With the candidate, the two focused tests passed: Tools to Explorer in landscape, and XML help/preview/Edit in portrait and landscape. The iPad SAP-1 screenshot also changed from a title/buttons-only dialog to a complete preview with visible Copy and Paste controls.

These observations prove the visual sizing correction. They do **not** qualify all native dialog actions: the iPad test still failed because its accessibility tree clips the dialog to `(0,0,560,448)` while it is drawn at `(137,381,560,448)`. An independent reading found the same offset loss in the iPhone landscape tree. Help/Edit can remain reachable while lower or right-hand actions lose their labels. The later tests must require all XML actions, rather than treating the two focused passes as full accessibility acceptance. Issues #12, #28 and #36 remain open.

Local before/after receipts are under:

- `accessibility/outputs/ios-dialog-bounds-before-9455-17e`
- `accessibility/outputs/ios-dialog-bounds-after-eb376-17e`
- `ios-final-e2e/outputs/ios-final-9455-e2e-001`
- `ios-final-e2e/outputs/ios-dialog-eb376-e2e-001`

The paths above are relative to `/Users/thales/GitHub/logisim-mobile-worktrees`. Failed native runs are retained alongside the visual comparison; no audit finding was filtered to obtain the focused results.
