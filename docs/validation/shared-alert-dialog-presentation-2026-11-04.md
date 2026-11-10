# Shared alert dialog presentation candidate

Base: `1e4ad98af7b896556b9307433e3564d51dfd20bb`, the XML-only viewport implementation.

`AppAlertDialog` forwards the complete Material3 1.9.0 AlertDialog signature, including every content slot, style and dialog property. All 24 callers in 14 files use this entry point. The XML-specific platform presentation names have been removed.

The iOS viewport modifier is applied before the caller's modifier. The caller's bounds, padding and test semantics therefore remain on the measured content inside the full-window root. Native dialog properties retain back/outside dismissal, scrim and animation settings. The layout applies the requested platform/keyboard insets and preferred-width policy itself before disabling those duplicate geometry constraints in the underlying Dialog.

Android receives the original modifier and DialogProperties. The three callers that already bounded their content, XML, Tools and toolbar overflow, continue to supply those bounds. The other callers gain no Android size constraint.

The migration covers editor errors, notices, help, files, tools, warnings, input values, XML, import reports, legal notices, project navigation, typed properties, accessible insertion, memory cells/images, trace/vector exports, signal picking, example descriptions, workspace naming and the Android bootstrap error. ModalBottomSheets and native document presenters use their existing implementations.

## Verification

The App.kt change was checked mechanically against the base: only dialog routing and the XML presentation call changed. The other 15 caller migrations preserve their arguments and modifiers byte-for-byte after reversing the function/import rename. Source boundaries and diff formatting passed. Independent review checked all forwarded parameters, native property mapping and modifier order.

Android and iOS Simulator ARM64 source compilation passed in 4m 48s. The command was serialized through the shared Gradle lock:

```sh
./gradlew :composeApp:compileAndroidMain :composeApp:compileKotlinIosSimulatorArm64 \
  -Pkotlin.incremental=false --console=plain
```

Local log: `/tmp/logisim-shared-dialog-presentation-compile.log`.

The generalized application UI is **NOT_RUN**. Its native gate must cover Tools and overflow menus, Files/import/error dialogs and a small editable dialog in both orientations. The existing strict XML and inside/outside gesture cases remain relevant regressions. This source migration alone does not complete the layout or accessibility issues.

## Later native observations

Native artifacta046 contains the same shared alert common/iOS implementation later integrated asca46fbbf. Current insertion and menu steps were reached, but the complete editing/inspector flow failed in focus or XML-fixture replacement; the unfiltered native audit retained findings. This is partial execution evidence and does not complete the earlier generalized-dialog gate. See the [independent c8 acceptance ledger](layout-inspector-acceptance-c8bef175.md) for hashes, exact FAIL/NOT_RUN scope and the candidate tool-sheet difference.
