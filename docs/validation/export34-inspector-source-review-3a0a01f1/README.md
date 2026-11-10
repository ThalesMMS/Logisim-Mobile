# Export acceptance and Inspector source review

This metadata update records issue #34 acceptance and refreshes the whole-file hashes used for issue #21. It changes no application code, inventory schema or verifier. The reviewed application source is `3a0a01f1af05dd3fea75d5a1499c762d039c478a`; the metadata base is `5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c`.

The [receipt](receipt.json) and independent reviews retain the exact source comparisons. Native evidence remains attached to Android `85da031e` and iOS `6cef0542`. `native_rerun` is false throughout this update. Matching current source hashes establishes the scope of this review; it does not move an old native result to a newer application.

| Scope | Recorded evidence | Metadata result |
| --- | --- | --- |
| Inspector #21 | Fourteen of sixteen mapped files are byte-identical to the native baseline. Only `App.kt` and `EditorState.kt` hashes change. Attribute validation, batch editing, labels and Undo bodies remain unchanged. The later lifecycle, Keyboard draft and accessibility ownership changes are reviewed separately. | Existing implemented status retained with two current hashes and explicit review limits. |
| Visual export #34 | [Android native pass](../android-export34-final-002.md), [iOS native pass](../ios-export34-final-6cef-002.md), and 25 unchanged renderer/export/adapter files. Whole PNG, selected PDF, native save/share/print preview, cancellation and complete project XML were checked. | Visual export, PNG, export command and print command are implemented within the recorded scope. |
| SVG | Primitive-scene export exists and has common tests. The complete mounted fixture is rejected when its SVG scene would be incomplete. | Partial. No native full-circuit SVG pass. |

The archived root gate reported 999 core JVM cases, 523 Android host cases and eight snapshot cases, with no failures, errors or skips. Its [manifest](root-gate-manifest.json) records which tasks executed and which unchanged task inputs reused results. Seventeen selected XML reports are included byte for byte: 65 Inspector/model/core cases, 14 inactive-initialization cases and 27 drawing/export cases. Reading these reports is an offline evidence check, not another execution.

The independently reviewed [export record](export34-independent-review.json) maps all three acceptance criteria. PDF is a printable raster format, not vector geometry. JPEG/GIF, complete SVG coverage, arbitrary external-viewer glyph equality, physical printer jobs, recipient delivery, performance, accessibility and the complete phone/tablet matrix are outside this result. The Android incomplete/complete snapshot pair does not prove one screen call or deadline. The iOS wrapper remains aggregate `PARTIAL` while its selected native scenario is `PASS`.

The existing `external_libraries` and `format/lsebdl` metadata are unchanged. No other issue is promoted.
