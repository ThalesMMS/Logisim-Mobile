# Final acceptance evidence at `2653be42`

App revision `2653be42eac04211ba478e981850d8cf7de191d0`. Evolution `d58a61e0`, JAR `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`. macOS (Apple silicon), Android API 35 and 31 emulators, and iOS 27.0 (24A434) and 27.2 simulators. Emulator and simulator coverage only.

Changes after `2653be42` were limited to harnesses, documentation, the matrix, and fixtures (`git diff --name-only 2653be42 HEAD`).

Artifacts include APK `64ca65d406811a9b568d15f7ff90237258310d80cd7dc236a56ed771ea546ec9`, test APK `4cb03f55e55abf9df218a5bc370f6d5139e020789f7aecf00510068d2feb3ad7`, and snapshot APK `31bb9550c15990007e6091e230d0c6bc29580093dff26c361cc2f9fedb57bb41`. Final mixed-input test `c029834f7cc3f1d7…`. iOS app tree `e99e6550e74294c09b90e850bb29e0ffef22d44679b4b95e5d3e426af86e8bef`.

| Area | Result | Command or receipt |
| --- | --- | --- |
| Host | `./scripts/verify.sh all` passed. `scripts` 530 OK, companion 264 OK and 5 skips. | `verify.sh`, `python3 -m unittest` |
| Evolution oracles | All PASS, covering preferences, creation, names, corpus, integrations, JAR, Telnet, CLI, SoC, menus, HDL, boards, outputs, I/O, and open FPGA tools | `scripts/oracle/*` |
| Android, 11 groups | Phone and tablet 11/11 | `scripts/e2e-android.py` |
| Android mixed input | Phone 18/18 and tablet 14/14 without retries | `receipts/mixed-input-*.txt` |
| Android preferences and shortcuts | PASS on phone, shortcuts on a keyboard-equipped AVD | `android_display_preferences_proof.py`, `hotkeys-final` |
| Android FPGA | Synthesis, tool error, and cancellation returned `FPGA_SOFTWARE_SIMULATOR_PASS` | `receipts/fpga-android-*.json` |
| TalkBack API 35 | All steps PASS | `receipts/talkback-api35-results.json` |
| TalkBack API 31 (#363) | All steps PASS, using the same script and APK, unpatched TalkBack 14.1 (`0732029ec8d04bda…`) | `receipts/talkback-api31-*` |
| iOS 27.0 | Full matrix PASS on iPhone and iPad | `scripts/run-ios-e2e.py` |
| iOS 27.2 | iPhone and iPad PASS except cases requiring the file picker (known 27.2 limitation) | `scripts/run-ios-e2e.py` |
| iOS FPGA tick and shortcuts | 4/4 PASS on iPad 27.0 | `scripts/run-ios-fpga-tick.py` |
| `.circ` in Evolution | 470 opened, 7 are intentionally invalid `incompatible.circ` fixtures | `receipts/reopen-*` |

## Pending

- **iOS HDL** (`run-ios-hdl.py`) fails in keyboard and landscape clipping steps in the harness. Resume after adapting the scenario to that window.

## Observations

- A duplicate overlaid on another component gets no separate canvas node (4 nodes before and after with TalkBack).
- The app shows only the last 40 companion log entries.
- TalkBack API 31 (#363). The tutorial reopened over the app because `TalkBackService.shouldShowTutorial()` also requires `has_training_exit=true`. The script `receipts/talkback-api31-run.sh` writes that preference and the 14.1 onboarding keys with the service stopped, using the original TalkBack and app APKs.
