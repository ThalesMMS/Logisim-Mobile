# iOS `unicode` scenario (#330)

New run on 2026-11-07. Mobile `8bb07643` with the new test case, which entered `main` as `689b9cc0` (the same patch, rebased from `fe21cb80`). Debug app `LogisimMobile.app` 0.2.0 (2). Executable SHA-256 `300cf7cd…184a` and bundle tree SHA-256 `b0577ff6…d1c2`. Xcode 27.2, iPhone 17 Pro Simulator with iOS 27.0 (24A434), created for this run.

## Command

```
xcodebuild -project iosApp/LogisimMobile.xcodeproj -scheme LogisimMobile -configuration Debug \
    -destination "platform=iOS Simulator,id=<udid>" -derivedDataPath <dd> CODE_SIGNING_ALLOWED=NO build
python3 scripts/run-ios-e2e.py --simulator <udid> --baseline-sha 8bb07643 --app <dd>/.../LogisimMobile.app \
    --output <out> --build-lock /tmp/logisim-build.lock \
    --only-testing EditorEndToEndTests/UnicodeLabelsEndToEndTests/testUnicodeAndDirectionalNamesAreRenamedAsEvolutionDoes
```

Result `PASS` in 24.3 s (`test-log.txt`).

## What the test does

`UnicodeLabelsEndToEndTests` writes the same `.circ` as `unicode_labels` in `scripts/e2e-android.py` to the run’s `Documents` directory. The circuit is named `Visão → ‫اختبار‬` and the pin is labeled `E2E — ligação longa → entrada / ‫مرحبا‬`. The test opens the file through the system with `XCUIDevice.shared.system.open` (`unicode-opened.png`) and exports the project through the XML dialog (`unicode-labels.circ`). It checks that the circuit and `<main>` are named `Vis_o_a6e3837c` and the label is `E2E_liga_o_longa_entrada_dab08e64` (`exported-names.txt`). Finally, it finds the `Toggle input E2E_liga_o_longa_entrada_dab08e64: 0` control in the Signals panel and verifies that no control retains the old label (`pin-control.txt`, `unicode-pin-control.png`).

The exported `unicode-labels.circ` has SHA-256 `1ee554d1…9ded`, which matches the Android export (`../unicode-android-f44bb2f9/`). Ignoring the space before `/>`, it matches `fixtures/evolution-names/mobile-unicode.circ`.

## Reopening in Evolution `d58a61e0` (JAR `d0b287d1…`)

`EvolutionNamesOracle` (`scripts/oracle/evolution-names`) ran in a temporary directory with the iOS file, saved as `mobile-ios-unicode-labels.circ`, and the repository’s `names.tsv`. `names-unchanged-on-open`, `same-names-on-second-open`, and `names-unchanged-after-resave` returned `true`. All 66 checks returned `true` (`evolution-oracle.tsv`).
