# Isolated iOS text-action ownership experiment

Status: simulator app built and signed; native runtime and Android checks have not run. This branch is an experiment for #27. It does not qualify a Compose fix or change the project's release policy.

The product baseline is `a0462cc809f38a22fc1d41a0ab695e369231de8b`, which reproduced the Find-to-Y focus change after the complete editor history. The experiment changes only the Compose Gradle plugin and four explicit Compose dependency versions from `1.12.2` to `1.13.0-beta02`. Product Kotlin, Swift, resources, preferences, test documents and interaction code remain byte-identical to that baseline. Kotlin and its Compose compiler plugin remain `2.4.21`; Material3 remains `1.9.0`; lifecycle remains `2.11.0`. The existing versions are recorded rather than claimed compatible before compilation.

## Why test this version

The [1.12.2 publisher](https://github.com/JetBrains/compose-multiplatform-core/blob/5f017375d926c73f1937408601b6cb28c12a8812/compose/foundation/foundation/src/iosMain/kotlin/androidx/compose/foundation/text/ContextMenu.ios.kt#L498) publishes every field manager's UIKit actions into the scene's current input connection without checking the manager's focus. An unfocused manager's Select All action can request focus for that manager. The full-history native reproduction now shows Find focused before Command-A and Y focused afterward. The executed manager callback itself remains uninstrumented.

The 1.13 implementation associates menu updates with each field's `TextInputContainer.Holder`. `ContextMenu.ios.kt:202–215` publishes to that holder, and `TextInputService.ios.kt:136–138` resolves a request through its `editorToken`. This architecture first appears in the examined published `1.13.0-alpha02` sources and remains in beta02. The [upstream change](https://github.com/JetBrains/compose-multiplatform-core/commit/a728b22d9312577842c3f0e07dd9e3e50dee7a42) describes AutoFill, not this Logisim defect. Its relevance is an inference to test.

The official Maven artifacts for the beta02 plugin, runtime, foundation, UI and resources exist. The foundation and UI POMs declare Kotlin stdlib `2.2.20`. This is dependency metadata, not proof that the application's Kotlin 2.4.21 compilation or Material3 binary usage succeeds. The beta02 UI source still contains the public `onFocusBehavior` setting used by the a046 product.

## Alternatives examined

The independent public API review found no supported 1.12.2 hook that scopes the automatic UIKit keyboard-action publisher. `TextContextMenuProvider` changes the presented menu. The legacy publisher bypasses those providers, and the new-menu native-input path still publishes into the shared context. `LocalNativeTextInputContext` is marked `InternalComposeUiApi`.

Changing the new-menu flag alone may remove Select All from Compose input rather than correct it. Enabling native input alone keeps the shared publisher. An app-level key handler does not establish that UIKit's Select All selector was consumed. None is accepted as a fix without strict selection and clipboard tests.

The second concrete option is an explicit isolated Foundation source dependency based on 1.12.2. Its publisher would observe field focus and selection, emit only for its focused manager, and revalidate ownership before applying a collected update. It must cover both String/TextFieldValue and TextFieldState controls. An unfocused or detached manager must not clear a newer owner's actions. This fork is a design only; it has not been authored, compiled or run. It also requires maintaining a Foundation build and is broader operational work than the dependency experiment.

## Build and comparison procedure

Use this experiment's own worktree and output directory. Keep the a046 producer and its existing DerivedData unchanged so its artifact, catalog and failed reproduction remain available. Reuse only this experiment's Gradle, Kotlin and DerivedData caches on retries; never share writable build directories with another worktree. The normal global Gradle lock serializes the build after the already queued Native tests, IME app and external-I/O tests.

Generate the existing Xcode project from `iosApp/project.yml`, then run the same command shape as the a046 producer:

```sh
xcodegen generate --spec iosApp/project.yml
python3 /tmp/logisim-gradle-serial.py xcodebuild \
  -project iosApp/LogisimMobile.xcodeproj -scheme LogisimMobile \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath outputs/ios-focus-beta/DerivedData \
  -resultBundlePath outputs/ios-focus-beta/build.xcresult \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO build
```

Record the resolved dependency graph, source commit/tree, command, original exit status, build log, bundle tree hash, debug dylib hash and exact catalog hashes. Verify and ad-hoc sign the resulting simulator app through the existing producer procedure. Compilation is the first gate; do not install a partial artifact.

Run the same full-history harness and any smaller **verified failing** reduction on a046 and the beta artifact, on the same explicitly leased simulator. Preserve the software keyboard and system settings. Record every failed attempt. Do not tap or refocus after Command-A.

The acceptance check requires a multi-character Find value, Command-A, one Delete, an empty Find field and unchanged X/Y. Continue through replacement, insertion, Inspector numeric edit/invalid validation/undo, touch selection, and native Copy/Cut/Paste. Empty Find or one-character deletion cannot prove Select All. A disappearing focus jump with no selection is a failure.

If the reproduction passes, run the existing editor/layout, accessibility, document, host and Native checks before proposing the version change for main. The update affects all Compose targets in this experiment, so Android must also compile and pass its applicable checks. Neither the experiment nor its compiler success closes #27.

## Build result

The first build completed with original exit code 0 in 853.304 seconds. Both Native source compilation and debug framework linking executed, followed by `Xcode BUILD SUCCEEDED`. The resolved iOS Native dependency report also exited 0, with Compose beta02 and Kotlin stdlib 2.4.21. Ad-hoc deep strict signature verification and the canonical artifact hash comparison passed. All three catalog hashes match a046.

The frozen producer remains clean at `b9bd71ae49e562661ee114b248e342a02ed29508` in `ios-focus-beta-experiment`; this result is recorded in a separate evidence worktree. The app was handed to `ios_text_focus` for the unchanged full-history case, subject to an explicit simulator lease from `ios_files`. This producer performed no installation, launch or UI action. Compilation and artifact integrity do not establish the focus bug is fixed. See [the build receipt](ios-focus-beta-build-2026-11-04.json) for hashes, commands, logs and the exact native handoff.
