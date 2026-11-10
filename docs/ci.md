# Automated verification

`.github/workflows/verify.yml` defines the `Verify` workflow for pushes, pull requests, and manual runs. The Apple gate defaults to ARM64, with an Intel fallback available for manual runs. The workflow file is explicitly tracked as an exception to the general `.github` ignore rule. The jobs neither publish apps nor use distribution credentials.

| Job | Runner | Checks |
| --- | --- | --- |
| Desktop parity inventory | `ubuntu-24.04` | Parity inventory against the desktop checkout pinned in the workflow |
| JVM, editor and Android | `ubuntu-24.04` | JVM core tests, editor state tests, and debug APK |
| Native iOS core, editor and host | `macos-26`, arm64, default | Runs Native core, editor, and adapter tests and builds the unsigned iOS host |
| Native macOS x64 and iOS ARM64 cross-build | `macos-26-intel`, x86_64, manual fallback | Runs the Native core on macOS x64 and cross-builds the unsigned iOS ARM64 framework and host |

The jobs check for platform imports in `commonMain`, trailing spaces, and final newlines. Kotlin formatting requires a dedicated formatter. Before Gradle runs, `scripts/verify.sh` also validates the localization, identity, and icon catalogs and generated files and runs their Python regression tests. These checks use only the Python standard library.

## Tools and cache

The wrapper pins Gradle 9.6.1. The build pins Kotlin 2.4.21, Compose 1.13.0-beta02, and AGP 9.3.1. The workflow uses Temurin 21.0.11, Android platforms 37.0 and 37.1, Build Tools 36.0.0, Xcode 26.4.1, and XcodeGen 2.46.0. Platform 37.1 satisfies the Compose beta AAR metadata. Core and androidSnapshot still need 37.0. minSdk remains 26 and targetSdk remains 36. The workflow pins actions by commit and verifies the XcodeGen download by SHA-256.

The iOS job also installs Android platform packages because Gradle configures all KMP modules. The runners set `ANDROID_HOME` in place of `local.properties`. The repository uses Google Maven, Maven Central, and Gradle Plugin Portal directly.

`scripts/ci-install-android.sh` locates `sdkmanager` inside the SDK specified by `ANDROID_HOME` or `ANDROID_SDK_ROOT`. The script accepts `cmdline-tools/latest` and versioned installation directories independently of the runner's `PATH`. Run the script regressions with `python3 -m unittest discover -s scripts/tests -p 'test_*.py'`. Installer tests use a temporary SDK and make no package downloads.

`setup-gradle` maintains a per-platform Gradle cache. Pull requests have read-only access to that cache. An uncached run downloads Gradle, dependencies, and Kotlin/Native. Hosted runner images and Maven repositories can change even with explicit versions, so the build is not hermetic.

Jobs have 30-minute limits on Ubuntu and 45-minute limits on macOS. The macOS job costs more than Ubuntu. This gate uses a single configuration per runner without an additional device or OS matrix. JUnit and HTML reports, the APK, and Xcode results are retained for 14 days when available, including after failures.

A push leaves a running job in progress so Native builds can finish during a series of commits. GitHub keeps the active run and the latest queued push per branch. Pull request updates cancel their previous run to avoid duplicate work.

The `verify-stable` group separates this configuration from earlier preview runner runs. During migration, run `41861247914` stayed marked `queued` after regular cancellation, and `force-cancel` returned HTTP 409 because it considered the run inactive. This [inconsistent external state](https://docs.github.com/en/actions/how-tos/troubleshoot-workflows) leaves verification pending. The stable configuration uses its own group so earlier runs cannot block its jobs.

## Matrix cost

The repository is private. On November 4, 2026, the [official runner pricing table](https://docs.github.com/en/billing/reference/actions-runner-pricing) listed the following standard rates before usage included in the plan.

| Job | Reference rate per minute | Configured limit |
| --- | ---: | ---: |
| Ubuntu x64 | USD 0.006 | 30 minutes |
| macOS | USD 0.062 | 45 minutes |

The reference cost of a run is the sum of each job's duration multiplied by its rate. Plan credits, artifact storage, and billing rules determine actual cost. Caching reduces downloads in later runs. Artifact retention is 14 days. Review these rates when the matrix or prices change.

The Apple jobs use Xcode 26.4.1. The [Kotlin Multiplatform compatibility table](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html) lists Xcode 26.4 for Kotlin 2.4.20–2.4.21. A local build with Xcode 27 supplements the required remote result.

## Fallback architecture

In run [`41882443934`, commit `48ee36c`](https://github.com/ThalesMMS/logisim-mobile-dev/actions/runs/41882443934), Android completed its build. The macOS ARM64 job waited 15 minutes without a runner and the service cancelled it for lack of capacity. The job reached no Native tasks.

ARM64 remains the default. If capacity is still unavailable, run manually with `apple_host=intel` to execute the core on Native macOS x64 and build the iOS ARM64 app. [Kotlin/Native supports cross-compilation between supported macOS hosts](https://kotlinlang.org/docs/native-target-support.html). Kotlin announced the future removal of `macosX64`. In this repository only the core enables it, with `-PenableMacosX64Ci=true`, reusing the core's Foundation adapters. The regular Apple Silicon run leaves that target disabled.

[Compose removed the Apple x86_64 targets](https://github.com/JetBrains/compose-multiplatform/pull/5514), so the framework and app remain ARM64. Intel produces a cross-build. Core execution on iOS remains part of the ARM64 gate and local Apple Silicon verification. A passing fallback provides build evidence, with the remote iOS runtime gate still pending until it runs on ARM64.

## Run locally

```sh
./scripts/verify.sh android
./scripts/verify.sh ios
./scripts/verify.sh ios-intel
./scripts/verify.sh all
```

`all` runs Android and requires Apple Silicon to run iOS on macOS. `ios` requires Apple Silicon and runs `:core:iosSimulatorArm64Test` and `:composeApp:iosSimulatorArm64Test`. `ios-intel` requires an Intel host and runs only the Native tests in `:core:macosX64Test`. Both iOS modes build the framework and host for a generic ARM64 iOS Simulator destination. To install and open the app, use `scripts/run-ios.sh` or `scripts/run-android.sh`.

The ARM path uses `scripts/ci-boot-ios-simulator.py` to read `xcrun simctl list --json` and select an available iOS Simulator with a valid UUID. It prefers a booted device, then the latest runtime and an iPhone, with name and UUID as tie-breakers. It excludes other platform runtimes and unavailable devices. `xcrun simctl bootstatus UUID -b` boots the selected device if needed and waits up to 180 seconds. The script leaves devices running.

Once boot completes, the verifier passes `-PiosTestDevice=UUID` to Gradle. This pins the device for Compose tests configured with `standalone=false`. Core uses its existing Native configuration. Inventory, selection, or boot failures stop the gate before Gradle. `outputs/ci/ios-simulator.json` records inventory, selection, and diagnostics. The Apple artifact includes it alongside core and Compose JUnit and HTML reports, including after failures.

To select the Intel fallback explicitly, run the workflow with its host input.

```sh
gh workflow run verify.yml --ref main -f apple_host=intel
```

The default run depends on ARM64 runner capacity. Local iOS ARM64 execution evidence remains separate from the remote run and Intel fallback.

The standalone checker runs without a JDK or SDK.

```sh
python3 scripts/check-source.py
```

## Evidence scope

A GitHub Actions run verifies the combination of packages, runners, permissions, and caches it used. The Intel fallback and ARM64 gate produce distinctly named artifacts. Touch interaction, accessibility, physical devices, and signed distribution remain in their own epic gates.

The [macOS 26 Intel image](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-Readme.md) and [macOS 26 ARM64 image](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-arm64-Readme.md) list their explicit Xcode paths and installed SDKs.

## Verified hosted run

Run [`41898753622`, commit `cefaaf83`](https://github.com/ThalesMMS/logisim-mobile-dev/actions/runs/41898753622) completed both jobs. JUnit artifacts record 839 Native tests on iOS Simulator ARM64 and 385 editor state tests, all executed with no failures, errors, or skips. JVM reports contain 926 tests, with 921 executed and five FPGA and HDL integrations skipped because Yosys or NVC was unavailable. There were no failures or errors. The [issue #13 validation receipt](validation/ci-2026-11-04-cefaaf83.md) records the APK, its hash, and the iOS host Xcode result.

This result verifies the tools and ARM64 gate at that commit on a hosted checkout with its dependency cache restored. It applies to that cached run and revision. The earlier capacity failure for `48ee36c` is recorded above.

The receipt predates the addition of Native editor and adapter tests. The expanded gate still needs a hosted run to verify `:composeApp:iosSimulatorArm64Test`. The 385 editor tests in that receipt ran on the Android host.

## Local fallback validation

All 31 Python script tests passed, including four regressions for task selection and wrong-host rejection. On Apple Silicon, `./gradlew -PenableMacosX64Ci=true :core:linkDebugTestMacosX64` built and linked the x64 executable. Kotlin disables the run task on this architecture, so the x64 tests were not executed on that host. The default `:core:compileKotlinIosSimulatorArm64` task also compiled without enabling the macOS target. Intel execution and the iOS host cross-build depend on the first remote run of this configuration.
