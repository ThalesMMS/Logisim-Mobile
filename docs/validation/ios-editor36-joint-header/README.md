# Joint Components and Search preparation

This private XCTest change is SOURCE_PREPARED. Native execution is NOT_RUN. It follows the [header census](../editor36-header-census/README.md) without changing the app or moving its list header. The R1 failure in `4c60e16ed574eea549cff125c7bfb60f82841811` and R2 failure in `23ec091fc7f929632a3dc5e764905543088dd1d3` remain separate results. R2 reached Search using the preparation from `e8354b17a2d1fcb5b0592c4810cd5a3d95622571`; its later failure occurred while the generic reveal searched for Components.

`explorerSearch` now prepares Components and Search through one private resolver. Components must be a unique canonical Button in the Explorer list. A public `isSelected == true` observation permits skipping its redundant tap, including when that observed button is outside the viewport. The observation must still establish its canonical label, enabled state and finite frame of at least 48 by 48 points. Missing or ambiguous state does not imply selection. The helper never picks the first candidate from an ambiguous query.

When Components is not selected, the resolver requires a fresh observation with its entire frame inside the usable viewport and native hittability. It requests one tap, then queries the unique Button again until its public selection is confirmed or the five-second confirmation fails. Missing, ambiguous or covered controls terminate this path. There is no second tap. Logs count tap requests; they do not establish callback delivery.

Search resolves across both native text roles using its existing identifier and canonical label. The resolver returns a freshly observed, enabled, fully visible and hittable field to the unchanged `replaceEditorText` policy. It does not tap Search itself. The text policy still performs its existing actions.

Both targets use the short backward geometry from e835. The geometry function is byte-identical. Each requested drag spans at most 96 points or one quarter of the usable viewport, with a maximum of twelve drags. The resolver captures the list, app and all observed keyboard, inputView and assistant regions before each action. Its attachments identify invocation, observation order, phase, target, count, public selection, frame, usable viewport and input regions. Hittability stays unqueried until a control is fully inside the usable viewport.

The preparation deliberately fails if an observed target lies below the viewport. Search precedes Components in the LazyColumn, so an input view can leave an unselected Components below the usable area. Backward-only geometry cannot reveal that state. This source-only unit does not qualify that case or assert gesture consumption. A new direction or product layout change requires a separate reviewed unit.

Normalizing only the private resolver and its two call-site substitutions reproduces the complete Editor source at `199fafaecee12bc1112909b392e1633a7eff51be`. This preserves the 62 actions and oracles, Start/Complete connection bodies, `replaceEditorText`, and existing diagnostic probes. The shared literal contract, native base classes and target declaration remain byte-identical.

The [receipt](receipt.json), [source review](source-review.json) and [invariant review](invariant-review.json) and [verifier review](check-review.json) record the checks. The final replay passed all fifteen existing geometry cases and an iOS Simulator SDK typecheck of the seventeen actual target Swift sources. The verifier pins the entire literal probe to the base and compares all input bytes before and after compilation. The initial replay omitted the Swift XCTest overlay search path. Its compiler diagnostic and failed receipt remain in [initial-setup-diagnostic](initial-setup-diagnostic/validation.json). Adding the platform's `Developer/usr/lib` include path corrected that check command without changing the Swift source.

To rerun the source checks from this repository root on a Mac with Xcode, use a new output directory:

```sh
python3 docs/validation/ios-editor36-joint-header/check.py --output outputs/joint-header-check-new
```

The script requires the shared compiler lock wrapper at `/tmp/logisim-gradle-serial.py`, or an explicit `--serial-lock` path to that wrapper. It does not build an app, install a bundle, open a Simulator, or run XCTest. A passed source check does not establish native selected-state delivery, successful scrolling or an Editor62 pass.
