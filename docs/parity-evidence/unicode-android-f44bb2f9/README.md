# Android `unicode` scenario (#321)

New run on 2026-11-07. Mobile `f44bb2f9` (= `origin/main`). APK E2E SHA-256 `48d5b8559532fc571b938c1a69b19179c682e6b7d6b69e465a012506949ef3f0` and test APK `c82dfd3b…31e1`. Android 15 AVD, API 35 (`google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.043`), `emulator-5566`.

## Command

```
./gradlew -PandroidTestBuildType=e2e :androidApp:assembleE2e :androidApp:assembleE2eAndroidTest :androidSnapshot:assembleDebug
python3 scripts/e2e-android.py --serial emulator-5566 --language en --scenario unicode
```

The result was `PASS: unicode-and-directional-labels` in 45.8 s (`results.json`, `manifest.json`, `unicode-and-directional-labels.png`). The run used the scenario expectations from `fb180b4b`.

## Exported file

`unicode-labels.circ` and `last-document.circ` are identical (SHA-256 `1ee554d1…9ded`). The circuit and `<main>` are both named `Vis_o_a6e3837c`, and the pin label is `E2E_liga_o_longa_entrada_dab08e64`. The names match Evolution’s `generateValidVHDLLabel` with Mobile’s deterministic suffix. The circuit `id` retains the original name. Ignoring the space before `/>`, the file matches `fixtures/evolution-names/mobile-unicode.circ`.

## Reopening in Evolution `d58a61e0` (JAR `d0b287d1…`)

`EvolutionNamesOracle` (`scripts/oracle/evolution-names`) ran with both files from the emulator and the repository’s `names.tsv`. For both files, `names-unchanged-on-open`, `same-names-on-second-open`, and `names-unchanged-after-resave` returned `true` (`evolution-reopen.tsv`). All 69 checks returned `true` (`evolution-oracle-full.tsv`).
