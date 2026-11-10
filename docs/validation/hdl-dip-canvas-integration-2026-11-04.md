# HDL, DIP persistence and canvas integration

The integrated source is `9455c044b899e3eed2f4ced7f94b57d43fd2f881`. It includes desktop-compatible HDL source archives and terminal directions, exact source line-ending preservation, scrollable HDL panels, saved DIP positions, a shared I/O fixture, accessible I/O state descriptions, and clipped canvas drawing with full edge interaction targets.

| Check | Source | Result |
| --- | --- | --- |
| Core JVM | `fc5f8769904737b2e1fa86d8529219e5a17931e9` | 973 tests |
| Core iOS Simulator ARM64 | `fc5f8769904737b2e1fa86d8529219e5a17931e9` | 879 tests |
| Editor Android host | `9455c044b899e3eed2f4ced7f94b57d43fd2f881` | 453 tests |
| Editor iOS Simulator ARM64 | `9455c044b899e3eed2f4ced7f94b57d43fd2f881` | 456 tests |

All four suites completed with zero failures, errors or skipped tests. The core runs preceded the I/O accessibility message catalog addition; their source is recorded separately. The editor runs include the DIP persistence cases, I/O descriptions and edge target geometry cases. Native raster and document adapter tests executed on iOS Simulator; these are separate from mounted application UI scenarios.

The Android debug APK built successfully. Its SHA-256 is `5d9ad4014a774ab368017aa76da77d1dc33e725075340287f19e34de64cfb815`. This receipt does not claim that APK was installed or exercised. Source boundaries, formatting and the 2,490-key English/Portuguese localization check passed.

Commands, with Android SDK configured, were serialized through the local Gradle lock:

```sh
./gradlew :core:jvmTest :core:iosSimulatorArm64Test \
  :composeApp:testAndroidHostTest :androidApp:assembleDebug \
  :composeApp:compileTestKotlinIosSimulatorArm64 \
  -Pkotlin.incremental=false --continue --console=plain

./gradlew :composeApp:testAndroidHostTest :androidApp:assembleDebug \
  :composeApp:compileTestKotlinIosSimulatorArm64 \
  -Pkotlin.incremental=false --console=plain

./gradlew :composeApp:iosSimulatorArm64Test \
  -PiosTestDevice=20A4D87D-A2A6-46BD-A889-C4888CE1E152 \
  -Pkotlin.incremental=false --console=plain

python3 scripts/check-source.py
python3 scripts/generate-localization.py --check-ui --check
```

Local logs are retained in `outputs/implementation/hdl-dip-integrated-validation.log`, `io-semantics-canvas-integrated-validation.log` and `io-semantics-canvas-native-validation.log`. `outputs/implementation/9455c044-integrated-receipt.json` records aggregate XML report hashes and the frozen APK path/hash.

The [HDL desktop comparison](hdl-import-desktop-2026-11-04.md) records the separate real-engine oracle. Compact HDL UI, mounted I/O sequences, and the native accessibility audit of the new canvas build still require their own application receipts. This pin still had a separately reproduced input-queue cancellation on unchanged frequency configuration; the [follow-up correction and integrated verification](dip-frequency-idempotence-2026-11-04.md) records its resolution.
