# iOS Command-A routing in Compose 1.12.2

This source analysis identifies a path that can select and focus a different text field. It does not identify the callback executed in the failing Logisim process. The original a046 full-flow failure remains open. The [four minimal probes](ios-focus-a046-001.json) did not reproduce it.

## Observed failure and scope of the probes

In `ios-all-dialog-a046-e2e-002`, Find is keyboard-focused after the tap. After `field.typeKey("a", modifierFlags: .command)`, Y is keyboard-focused. The target query still resolves Find at index 0 with unchanged bounds. Query reordering does not explain those two snapshots.

The app's Command-A binding belongs to the canvas. The explorer's preview handler implements Command-F. The insertion dialog has no explicit Command-A handler or request to focus Y.

On the same a046 artifact and EFC iPad simulator, all four minimal probes kept Find keyboard-focused. The probes compare field versus application `typeKey`, with and without a preceding `typeText("Q")`. They do not include the original search, Inspector, Label edit, Apply, and drag-dismiss roundtrip. They also visit X/Y without selecting and replacing their initial `100` values.

The probes establish the destination of Q and R, and preserve X/Y as `100`. They do not qualify Select All: an empty field and a one-character Q both become empty after one Delete even if Command-A does nothing. A selection check must use several characters and require that one Delete clears them all.

`XCUIElement.hasFocus` reports UI focus. In these captures it is false for every field while `debugDescription` reports Find as Keyboard Focused. The receipt records both observations without treating them as equivalent.

## Shared menu callbacks provide a possible focus path

The exact 1.12.2 sources contain this chain:

1. `CoreTextField.kt:556,717–723` wraps every field in `ContextMenuArea(manager, content)`.
2. [`ContextMenu.ios.kt:91,120–123,498–555`](https://github.com/JetBrains/compose-multiplatform-core/blob/5f017375d926c73f1937408601b6cb28c12a8812/compose/foundation/foundation/src/iosMain/kotlin/androidx/compose/foundation/text/ContextMenu.ios.kt#L498) observes each manager's menu state. It publishes callbacks into `LocalNativeTextInputContext` without checking `manager.state.hasFocus`. Its Select All callback calls `manager.selectAll()`.
3. `UIKitTextInputService.ios.kt:222–245` writes the callbacks into the scene's `currentInputConnection`. `ComposeTextInputConnection.ios.kt:116–129` forwards them to `updateAvailableSystemActions`.
4. [`CMPEditMenuView.m:145–155,329–344,392–397`](https://github.com/JetBrains/compose-multiplatform-core/blob/5f017375d926c73f1937408601b6cb28c12a8812/compose/ui/ui-uikit/src/iosMain/objc/CMPUIKitUtils/CMPUIKitUtils/CMPEditMenuView.m#L392) stores the system callback and invokes the menu callback, or the system callback when no menu callback exists. It does not automatically call the input connection's `selectAll()` method.
5. `TextFieldSelectionManager.kt:1002–1011` calls `enterSelectionMode()`. At `:732–735`, that method requests focus for its own manager when the manager lacks focus.

If Y's callback occupies Find's active connection, this chain can select and focus Y without another tap. Which manager last published the callback in the failing process remains unmeasured.

`KeyMapping.ios.kt:23–30` excludes Copy, Cut, Paste, and Select All because UIKit handles those commands. Therefore the Compose text-field key mapping is not a second Select All implementation in this chain. The 10 ms refocus in `FocusedViewsList` also recalculates the active view when it runs. Its delay alone does not prove that an old Y target was captured.

## Upstream changes and native input

Official commit [a728b22d / PR #3335](https://github.com/JetBrains/compose-multiplatform-core/commit/a728b22d9312577842c3f0e07dd9e3e50dee7a42), dated 2026-09-14, introduces `TextInputContainer.Holder` and an `editorToken` for each field. Its stated purpose is AutoFill. The commit does not claim to fix Logisim's Find-to-Y reproduction.

The first examined published version containing this architecture is **1.13.0-alpha02**. Alpha01 still uses the shared context. [`ContextMenu.ios.kt` in alpha02](https://github.com/JetBrains/compose-multiplatform-core/blob/76302a467bbbbcb874691630b6082eb548fb25b5/compose/foundation/foundation/src/iosMain/kotlin/androidx/compose/foundation/text/ContextMenu.ios.kt#L75) is identical to the beta01 and beta02 source. It publishes callbacks into the manager's holder. The input service associates the request and connection with the holder through `editorToken`.

The [Maven metadata](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui/maven-metadata.xml) read on 2026-11-04 lists 1.13.0-beta02 as latest and 1.12.2 as the last stable version. A dependency update is an isolated experiment until the full reproduction and editing checks pass.

The experimental public native-input API exists in 1.12.2: `KeyboardOptions(platformImeOptions = PlatformImeOptions { usingNativeTextInput(true) })`. The [exact API source](https://github.com/JetBrains/compose-multiplatform-core/blob/5f017375d926c73f1937408601b6cb28c12a8812/compose/ui/ui-text/src/iosMain/kotlin/androidx/compose/ui/text/input/PlatformImeOptions.ios.kt#L185) defaults to false.

Native input alone retains the shared menu callbacks in 1.12.2. `NativeTextInputConnection` forwards them to `NativeTextInputView`, whose Select All action invokes the installed callback. Enabling native input does not remove the identified sharing mechanism.

Enabling `ComposeFoundationFlags.isNewContextMenuEnabled` also requires a strict selection check. In 1.12.2 the new menu path publishes actions only for native text input. Disabling the focus jump by leaving Command-A without an action is not a successful fix.

## Source provenance

The source JARs came from Maven Central. Their SHA256 values are:

| Artifact | SHA256 |
| --- | --- |
| [foundation 1.12.2](https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation/1.12.2/foundation-1.12.2-sources.jar) | `bd7ac562e7284bcc15ff5c9e0bb9c7796217dceee4462638e15d00f1dcc7c73e` |
| [ui 1.12.2](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui/1.12.2/ui-1.12.2-sources.jar) | `24ffda111c0b65df33c746265cbf6afef7c8ad9b285cf037e1b5a56e3d4fec2b` |
| [ui-text 1.12.2](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-text/1.12.2/ui-text-1.12.2-sources.jar) | `89ba84c8e30dc38bb99a9225b7751c7a28c7b6c162d7cd0fdba4d0c06f45d4ac` |
| [foundation 1.13.0-alpha01](https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation/1.13.0-alpha01/foundation-1.13.0-alpha01-sources.jar) | `9f97c6ca4ee47e9cc5ffca7589e529d5d1e9bf6c5fa97d8c8a8218ba2c133491` |
| [foundation 1.13.0-alpha02](https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation/1.13.0-alpha02/foundation-1.13.0-alpha02-sources.jar) | `9419a39a846335c3a889d9b806ca68b70736e09ae58689e3d0f0d610fcee8a59` |
| [foundation 1.13.0-beta02](https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation/1.13.0-beta02/foundation-1.13.0-beta02-sources.jar) | `4a690d417310c124d502437d454ce47abb1b11a8d65141c9b2a73bfa902e5715` |

The extracted `ContextMenu.ios.kt` has SHA256 `b0951adac74cdbaea749c17a05dfacdf72888046e762016882a7cd2c419f8682` in 1.12.2. Alpha02, beta01, and beta02 all have `93e1eac185cd8a01c687b63cc2f1cc7251cc7c3552d9f61472adb2078b44ff6c`.
