# `reverificar` sweep at Mobile 9ef2232e (2026-11-07)

Re-runs, for #236, the recorded commands of the rows flagged `reverificar` after #309, #310–#314, #317 and #241. Mobile `9ef2232e2cf67038daf986ddea7d83febe595e12` (`origin/main`), clean worktree, macOS (Apple silicon), Homebrew OpenJDK 21.0.13, Android SDK for the host tests only. Evolution JAR `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` (built from `d58a61e0`). This sweep was limited to the JVM; it did not start emulators or simulators.

## New evidence

| Evidence | Command | Result |
| --- | --- | --- |
| `corpus-replay-9ef2232e` | `scripts/oracle-replay-receipt.py` (see [README](../oracle-replay-9ef2232e/README.md)) | PASS: 222 records, 0 differences; 6 temporal fixtures in `settle` and `step`. |
| `product-jvm-9ef2232e` | `java -cp core-jvm.jar:kotlin-stdlib-2.4.21.jar dev.logisim.mobile.core.HeadlessCircuitCli --geometry 800x600 examples/and.circ`; `./gradlew :core:jvmTest --tests '*SharedVhdlDefinitionTest' --tests '*ValueCharactersTest' :composeApp:testAndroidHostTest --tests '*EditorKeyboardShortcutsTest' --tests '*EditorSearchCommandsTest' --tests '*ProjectSearchTest' --tests '*EditorPreferencesTest' --tests '*CatalogWorkspaceBindingTest' --tests '*EditorToolbarLayoutTest' --tests '*PrinterViewTest' --tests '*InactiveImportedSimulationTest' --tests '*PortableProjectModelTest' --tests '*PropertyInspectorSessionTest' --tests '*PropertyInspectorRuntimeTest' --tests '*RemainingColorsCharactersTest'` | `--geometry` exits with code 2 and the same diagnostic as at `05069e56` ([log](logs/geometry.txt)); 138 tests pass, 0 failures, including the 23 continuous-save tests of `CatalogWorkspaceBindingTest` (one more than at `05069e56`, from `3f3dd8bc`) ([tests](logs/gradle-tests.txt)). |

Rows cleared: `format/circ` (`corpus-replay-9ef2232e`); `preference/AUTOSAVE_ENABLED`, `preference/AUTOSAVE_INTERVAL` and `preference-panel/AutosaveOptions` (`product-jvm-9ef2232e`). The difference recorded for the autosave rows still holds: `3f3dd8bc` only adds Save As targets to the recent files list.

## Still `reverificar`: recorded evidence is an Android or iOS run

The recorded command of these rows drives an emulator or a simulator, so this JVM-only sweep could not repeat it. The flag stays until that run is repeated. The host tests above cover the commit that raised each flag and pass, so the sweep found no regression in those paths. They do not replace the device run.

| Rows | Recorded evidence | First change | Host tests at 9ef2232e (PASS) | Resume condition |
| --- | --- | --- | --- | --- |
| `Base/WiringTool`, `Base/EditTool`, `MenuEdit/undo`, `redo`, `copy`, `paste`, `delete`, `selall` | `editing-android-3a0a01f1`, `editor-ios-6cef0542` | `c832d126` (printer view), `e4699069` (inactive simulation) | `PrinterViewTest`, `InactiveImportedSimulationTest` | `scripts/e2e-android.py` editing scenarios; `scripts/run-ios-e2e.py` `EditorEndToEndTests` |
| `MenuSimulate/runToggle` | `sap1-android-85da031e` | `e4699069` | `InactiveImportedSimulationTest` | `scripts/e2e-android.py` `sequential-simulation-and-recovery` |
| `MenuFile/exportImage`, `format/image-png` | `export34-android-85da031e`, `export34-ios-6cef0542` | `2f896294` (printer view in exports) | `PrinterViewTest`; `CircuitDrawingRasterTest` is an iOS test and was not run | `visual-export34` (Android) and `VisualExportEndToEndTests` (iOS). Rendered values now use #311's characters (U, -), which match Evolution's defaults. |
| `MenuFile/exportProj`, `format/lsebdl`, `integration/external-circ` | `portable53-6cef0542` | `82362ba0` (active extension registry) | `PortableProjectModelTest` | `scripts/portable` bundle and folder flows, reopened in Evolution |
| `preference/SEARCH_DOUBLE_SHIFT` | `hotkeys-android-494bf291` | `796ad2f0` (#311, `EditorPreferences.kt`) | `EditorKeyboardShortcutsTest`, `EditorPreferencesTest`, `EditorSearchCommandsTest` | hotkeys scenario on the AVD (`docs/validation/hotkeys-314`) |
| `preference/HOTKEY_AUTO_LABEL_*`, `HOTKEY_ADD_TOOL_ROTATE`, `HOTKEY_GATE_MODIFIER_*` (8 rows) | `hotkeys-android-494bf291` (+ `hotkeys-ios-494bf291`) | `a16785c9` (#241, `EditorState.kt`) | `EditorKeyboardShortcutsTest` | hotkeys scenario on the AVD and `ShortcutRemapEndToEndTests` on iPad |
| `behavior/attribute-table` | `inspector-android-85da031e` | `654c9112` (Inspector drafts) | `PropertyInspectorSessionTest`, `PropertyInspectorRuntimeTest` | `scripts/e2e-android.py` `inspector-invalid-value-focus-and-undo` |

## #311 value characters

`ValueCharacters` defaults (`1`, `0`, `U`, `E`, `-`) match `SimTrueChar`, `SimFalseChar`, `SimUnknownChar`, `SimErrorChar` and `SimDontCareChar` in Evolution's `AppPreferences` at `d58a61e0`. The JVM oracles compare files, traces and simulator state, which keep fixed symbols, and the replay above produced the same records as before #311. Among the flagged rows, only the exports render shown values. They stay flagged until the device run.
