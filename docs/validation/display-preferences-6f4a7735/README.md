# Display, theme, creation-default and PLA file evidence (2026-11-07)

`6f4a7735` is the branch commit before the final rebase. On `main` the same changes are `b87224ef` (creation defaults, dark label colour), `c21f1db9` (Android `uiMode`, proof scenarios) and `befd67b7` (harness), on top of `157a6243`. Between `6f4a7735` and `befd67b7` only `main`'s #319, #320 and #322 commits differ (explorer width, TalkBack focus return, iPad double Shift). The files behind the JVM evidence (`CreationDefaults.kt`, `CircCodec.kt`, `CatalogWorkspaceBinding.kt`, `EditorState.kt`, `fixtures/creation-defaults`) are identical. The Android and iOS runs below use the pre-rebase app sources.

On-screen evidence for #310, #312 and #316 and the JVM comparison for #313, against Evolution `d58a61e0`
(`logisim-evolution-5.1.0dev-all.jar`, SHA-256 `d0b287d1…a7a038`). macOS host, Homebrew OpenJDK 21.

## Devices and artifacts

| Platform | Device | Artifact |
| --- | --- | --- |
| Android | Own AVD `logisim35proof` (Pixel 7 profile, `google_apis/arm64-v8a` API 35, fingerprint `google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.043/12960925`), `emulator -gpu host -port 5558`, English locale | `androidApp-e2e.apk` built from `6f4a7735` app sources after the rebase on `main` (SHA-256 `6eb3cc2b…58df52`) with its androidTest APK (`1e9942a8…0767e3`) |
| iOS | Own simulator `Logisim Proof 27.0` (iPhone 17 Pro, iOS 27.0 runtime 24A434) | `LogisimMobile.app` Debug built at `65e409cf` (pre-rebase; bundle tree SHA-256 `efd56ab9…`). The UI tests ran with the harness at `6f4a7735`. |

## Commands and results

| Evidence | Command | Result |
| --- | --- | --- |
| `android/` | `python3 scripts/android_display_preferences_proof.py --serial emulator-5558 --output …` (after `pm clear`, at `6f4a7735`) | PASS: 310, 312, 316. Steps and observations in `android/results.json`. |
| `ios/display-*`, `ios/pla-*` | `python3 scripts/run-ios-e2e.py --simulator <udid> --baseline-sha 65e409cf --app LogisimMobile.app --only-testing EditorEndToEndTests/DisplayPreferencesEndToEndTests` | PASS: `testDisplayPreferencesAndEditorTheme` (225 s), `testPlaTableExportAndImportUseTheFilesPicker` (163 s). `ios/results.json`. |
| `ios/310-*` | `xcrun simctl launch`, `xcrun simctl ui <udid> appearance light`, screenshot, `appearance dark`, screenshot | The open app switched to dark with the same process (PID 47786 before and after), keeping its open dialog. |
| #313 | `scripts/oracle/creation-defaults/run.sh EVOLUTION_JAR`; `./gradlew :composeApp:testAndroidHostTest --tests '*CreationDefaultsEvolutionTest'` | See `fixtures/creation-defaults/evolution-load.tsv`. |

### #310 system theme and HDL editor themes

- Android (`01`–`05`): with Preferences open, `cmd uimode night yes` turned the sheet dark in place (same PID 17055, the sheet stayed open). The editor-theme row follows the mode: Default in light, the dark choice in dark. Choosing Monokai in dark saved `darkEditorTheme=monokai` and kept `lightEditorTheme=default`. The Verilog editor showed Monokai keywords in dark, then Default (blue keywords, green comments) after `night no`, with the typed source kept.
- Before `c21f1db9`, the switch recreated the activity, closing open sheets and showing the import report again. `MainActivity` now handles `uiMode`.
- Before `b87224ef`, labels without `labelcolor` stayed dark blue on the dark canvas. They now use Evolution's `DARK_DEFAULT_LABEL_COLOR` (`#6CB6FF`).
- iOS: `310-system-*` (simctl switch, same process). `display-16`–`18`: the light editor theme set to Eclipse colours the Verilog source (Eclipse keyword colour).

### #312 display options

| Option | Android | iOS |
| --- | --- | --- |
| Attribute halo | `06` on, `08` off (`attributeHalo=false`) | `display-01` on, `display-03`/`04` off |
| Component tips (touch) | `07`: no long-press menu, `longPressTipShown: false` (#326) | `display-02`: the long-press menu lists the tip `AND` first |
| Pin dots | `09` dot-bigger, `10` dot-small (`pinAppearance` saved) | `display-06`–`11` |
| Lined K-map | `11` numbered, `12` lined (`kmapLinedStyle` false → true) | `display-12`, `display-13` |
| Appearance editor grid | `13` on, `14` off (`appearanceShowGrid` true → false) | `display-14`, `display-15` |

### #316 PLA table through the native picker

- Android (`15`–`21`): Export opened DocumentsUI with `decoder.txt` (Evolution's `normalizeName`). The saved file is byte-identical to `fixtures/pla/evolution-saved.txt` (SHA-256 `c7490a2a…10ea7e`). Import of a 5-input file showed `Error loading PLA program` / `File contains unexpected input width, expected 4 but found 5` and kept the table. Importing a 4x3 file replaced it, and Apply saved it.
- iOS (`pla-*`): the same flow through UIDocumentPicker (export to the run folder, open from it). The exported file has the same SHA-256 (`ios/exported-decoder.txt`), and the same mismatch message appears.

### #313 creation defaults against Evolution

For profiles `classic-false-false-true`, `evolution-true-true-false` and `logisim_evolution-true-true-true` (appearance, new I/O shapes, fixed boxes, remove unused libraries), Evolution built the same project (new file from its template, a D flip-flop and an input Pin from the toolbar tools, a new circuit) with in-memory preferences. It then loaded Mobile's saved file. Circuits and components load with identical attributes in all three profiles (`circuits-and-components-match true`). With removal on, Evolution's XmlWriter re-saves Mobile's file with the same libraries (`evolution-resave-unchanged true`).

Remaining differences, reported in the TSV and outside the creation preferences:
- Evolution's template toolbar keeps `#Gates`. The main circuit's `simulationFrequency` is 1.0 in Evolution and 4.0 in Mobile. Mobile declares `#HDL-IP` (#324).
- Without removal, Evolution adds a second `#FPArithmetic` to Mobile's `3.8.0` file (#323).

Also fixed: a new project's main circuit now takes the circuit creation defaults, as Evolution's template circuit does.

These runs use emulators and simulators only. They do not cover physical devices.

### Reverification of the continuous-save rows

`b87224ef` changed `CatalogWorkspaceBinding.createProject` (the new main circuit takes the circuit creation defaults). `./gradlew :composeApp:testAndroidHostTest --tests '*CatalogWorkspaceBindingTest'` passed after the rebase on `main` (23 tests, app sources identical to `6f4a7735`). Together with `CatalogWorkspaceRuntimeTest` (16) and `CreationDefaultsEvolutionTest` (1), this renews the evidence for `preference-panel/AutosaveOptions`, `preference/AUTOSAVE_ENABLED` and `preference/AUTOSAVE_INTERVAL` as `product-jvm-6f4a7735`.
