# XML dialog viewport candidate

Base: `eb376d71cab4a13bc19ac18371d330464ac78327`.

The XML dialog's iPad accessibility tree could have a root frame of `(0,0,560,448)` while the visible dialog was centered at `(137,381,560,448)`. Their intersection, `(137,381,423,67)`, matches the truncated accessibility containers. Controls below that rectangle lost their text descendants. The same mismatch occurred on iPhone in landscape.

The iOS XML presentation modifier now measures the existing Material3 content within the parent window's safe drawing and keyboard insets, but reports the full window size to the separate Dialog scene. It places the measured content at the center of the usable area. The original Material3 Surface, title, text, buttons, scrim and animation remain in use. Public iOS DialogProperties disable the duplicate width/inset constraints. The content retains the platform's preferred width of 320, 440 or 580 dp according to the window's smallest dimension; Material3's own width cap still applies. Android keeps the existing bounded-dialog modifier and default properties.

The full-window input layer dismisses only a primary gesture that starts and finishes outside the measured content. Gestures inside the dialog pass to the existing controls. The original Dialog still handles escape/back. Material3's existing touch targets remain in use; the scrollable body has the `editor-xml-body` test tag.

This candidate does not modify the controller or native document presenters. Other dialogs are outside this experiment.

The implementation was checked against the exact [UI 1.12.2 source artifact](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui/1.12.2/ui-1.12.2-sources.jar) and [Material3 1.9.0 source artifact](https://repo.maven.apache.org/maven2/org/jetbrains/compose/material3/material3/1.9.0/material3-1.9.0-sources.jar). It uses no internal Material3 override or removed platform-layers option.

## Verification

Source boundary/formatting checks passed. Android and iOS Simulator ARM64 source compilation completed successfully in 3m 16s for the final candidate. The command was serialized through the shared Gradle lock:

```sh
./gradlew :composeApp:compileAndroidMain :composeApp:compileKotlinIosSimulatorArm64 \
  -Pkotlin.incremental=false --console=plain
```

Local final build log: `/tmp/logisim-ios-dialog-xml-viewport-final-compile.log`. The earlier prototype also compiled before the redundant button-height overrides were removed and the preferred content width was restored.

Native application UI is **NOT_RUN** for this candidate. It must establish canonical labels, button roles, visible frames and interaction for Copy, Paste, Close and Import, including focused body scrolling, on iPad portrait/landscape and iPhone landscape. The Copy action must produce the complete project in the system pasteboard. Outside dismissal and an inside tap must be checked before promoting this experiment to a shared dialog policy.
## Native controls and gestures

The candidate application built successfully with bundle-tree SHA-256 `3e55a3a2cad6ed4d7a667e7c19f92fe0fd77e6f1c0ab99b1848b7672b8b1c80c` and debug dylib SHA-256 `69ec1036db82bf2795c7d72920ced61b4aaddc30c4f9824aea32a2dd21f5fd69`.

The same immutable strict XCTest products that failed against `eb376` passed on iPhone 17e in portrait and landscape: Copy, Paste, Close and Import XML each had their canonical label, button role, hittable state and complete frame of at least 48 points. A separate gesture case proved that Copy updates the pasteboard change counter while retaining the dialog, and tapping the visible outside scrim dismisses it. The iPad EFC ran both cases successfully as well (two cases, 74.117 seconds).

The iPhone landscape tree now has an `844×390` root, with the Material3 surface at `(262,14,320,356)`. After scrolling the identified XML body, Copy is `(286,226,83.3,48)`, Paste `(377.3,226,86.3,48)`, Close `(359.3,298,62.7,48)` and Import `(430,298,128,48)`. This is a real scene-size correction, rather than a coordinate fallback in the tests.

Local receipts are retained in `accessibility/outputs/ios-dialog-actions-after-1e4-17e` and `ios-final-e2e/outputs/ios-xml-1e4-e2e-002`, relative to `/Users/thales/GitHub/logisim-mobile-worktrees`.

Reading the large copied XML payload directly from the background XCTestRunner was rejected by iOS pasteboard privacy (`PBError 13`). That separate payload-reader test remains failed; a foreground test-only Paste control is being prepared. The successful control/gesture cases do not claim a successful large-payload read, complete iOS E2E, or accessibility acceptance of other dialogs.
