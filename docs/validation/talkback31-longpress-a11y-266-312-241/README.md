# TalkBack on API 31, long-press tip, iOS explorer tree (#266, #312, #241)

Tested tree `1295aead` (app sources), harness changes in this commit. Evolution reference `d58a61e0`.
Android APKs built with `./gradlew -PandroidTestBuildType=e2e :androidApp:assembleE2e :androidApp:assembleE2eAndroidTest`:
`androidApp-e2e.apk` SHA-256 `82ab774ea863ad32131a6d4fa8b9accbec12614aed6808ef2fb27c7fc5cc5aba`,
androidTest SHA-256 `c82dfd3b28f77be3dc57d54b6c40992a34d0163d1261356d88747bf62caf31e1`. Details in `receipt.json`.

## #266 — TalkBack on Android 12 (API 31)

TalkBack built from the official `github.com/google/talkback` at release 14_1 (`dcddeddc`) with JDK 11 (Homebrew `openjdk@11` 11.0.33), Gradle 7.3.3 (official checksum `b586e048…0302`) and NDK 27.2.12479018.
Local build-only changes: `ndkVersion '27.2.12479018'` in the two braille modules and `APP_STL := c++_static` in `braille/translate` (with `c++_shared` the APK lacked `libc++_shared.so` and the service crashed in `LouisTranslation.<clinit>`).
APK `com.android.talkback`, `TfPu_release_14_1`, minSdk 26, SHA-256 `1c467d9ec2d4c8a6a70252d00f1b67bd45c603e8906edcf152d05d14f428a0cd`.

AVD `logisim31play` (`android-31;google_apis_playstore;arm64-v8a`, Pixel profile 1080 × 2400), system `en-US` (`am get-config` … `en-rUS` … `v31`), app in Deutsch.
TalkBack ran with touch exploration and *Display speech output*. Gestures: emulator gRPC `sendTouch` on `127.0.0.1:8562` with token; activation is explore-tap followed by double-tap. Captions and focus outline are in `android-api31-talkback/`.

| Step | Observed | Files |
| --- | --- | --- |
| Preferences opened from Menü › Datei › Einstellungen | Focus on the handle: `Ziehpunkt, Button`, hint `Double-tap to Panel schließen` | `01` |
| Swipe right / left | `Editor-Einstellungen` → `Ziehpunkt, Button` | `02`, `03` |
| Double-tap on the handle | Sheet closed; focus on `Menü öffnen` at 1 s and 5 s | `04` |
| Circuits/library sheet, explore-tap outside | `Panel schließen, Button`; double-tap closed it; focus on `Schaltungen und Bibliothek öffnen, Button` | `05`–`07` |
| Preferences, Back | Closed; focus on `Menü öffnen` | `08`, `09` |
| Sprache › 日本語 with the sheet open | Sheet stayed open, header `保存済み · リビジョン 2` (same revision); handle `ドラッグ ハンドル, Button`, outside `パネルを閉じる, Button` | `10`–`13` |
| Japanese: outside, handle | Both closed the sheet; focus on the menu button | `14`, `15` |
| Back to Deutsch; two-finger drag down on the sheet | Labels back to German; the drag passed through TalkBack and closed the sheet; focus on `Menü öffnen` | `16`, `17` |

The project revision stayed at 2 throughout. Observation: the first close of the session (double-tap away from the handle right after TalkBack started) put focus on `Schaltungen und Bibliothek öffnen` (`18`); the repeat (`19`) and every later close returned it to the opener. The DocumentsUI and TalkBack's own prompts stayed in English (system language).

Restoration: `enabled_accessibility_services=null`, `accessibility_enabled=0`, `touch_exploration_enabled=0`, TalkBack uninstalled, AVD stopped.

## #312 — long-press tip on Android

AVD `logisim35play` (API 35 Play arm64, en-US), `python3 scripts/android_display_preferences_proof.py --serial emulator-5556 --output … --only 312` after opening *File › Open example: AND*: PASS. `02-312-long-press-menu` has `longPressTipShown: true`; the popup lists `AND` first, then Show Attributes, Copy, Cut, Paste, Duplicate, Delete, Select all, Fit selection. The other four options were captured again (`01`, `03`–`09`, `results.json`). The script now finds the AND gate by its accessible description; the bundled example's imported ID is `import-3`, not `and`.

## #241 — iOS explorer flow through the accessibility tree

`EditorEndToEndTests/testAccessibleAndGateExplorerFlowSaveAndReopen` drives the TalkBack AND flow through accessibility labels and identifiers only: New, Circuit explorer › Insert component (Input at 100,100 and 100,300, AND at 300,200, Output at 500,200), Edit properties › Label (A, B, G, Y), terminal actions to connect A→in0, B→in1, G out→Y, Activate/Refresh values for the truth table, selection, relaunch, Save as through Files, New, relaunch and system open of the saved file.

PASS on the own Simulator `t241 iPhone 27.0` (iOS 27.0 24A434), app built from `1295aead` (bundle tree `d4695b3a…a8c0`), 863.7 s, `NATIVE_PASS`. Observations are in `ios-241/accessibility-observations.txt`; the saved file is `ios-241/a11y-and-saved.circ` (SHA-256 `b2dc3a2c…6d13`, identical to the editor export); `ios-241/reopened-status.png` shows `File main.circ opened.`.

| Check | Observed |
| --- | --- |
| Component rows | Buttons `component:c1`–`c4`, labels `Select A` … `Select Y`, values `{kind}. {signal}`; selecting Y sets the selected trait and the `Selected.` value prefix |
| Terminal | Button `port:…:in`, value `Input in, 1 bit. Low, 0.`; its connections list `Select G, out`. The label is duplicated by Compose iOS (#328) |
| Truth table (Y) | 00 Low, 01 Low, 10 Low, 11 High — after editing, after relaunch and after reopening the saved file |
| Save / reopen | Native Files picker saved `main.circ` in the run folder with the exported bytes; after New and relaunch, system open restored the same XML |

On iOS 27.2 the Files picker did not list the app's run folders and folder taps did not navigate (runs 5–10), as #240 recorded; run 11 there passed every step before Files, including the relaunch restore.
