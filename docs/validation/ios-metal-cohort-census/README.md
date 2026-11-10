# Simulator compositor abort census

Five retained incidents identify the aborting actor as `backboardd`, on thread 2, `com.apple.coreanimation.display.primary`. Their stacks contain Metal texture-descriptor validation, `MTLSimDevice` and QuartzCore surface composition. Four have the same normalized 72-frame fingerprint; the fifth has 74 frames in the same family. This locates the abort in the service. Its external trigger remains unresolved, and the evidence establishes neither an app defect nor a leak.

The [census](census.json) preserves ten runs, their original execution/qualification gates, unknown fields and hash-pinned evidence. [Inputs](inputs.json) contain the reviewed observations; the script reparses the original IPS files and deduplicates incident IDs. No old failure is promoted by a later control. Apple's [crash-report field guide](https://developer.apple.com/documentation/xcode/examining-the-fields-in-a-crash-report) describes process, thread and image attribution; its [JSON guide](https://developer.apple.com/documentation/xcode/interpreting-the-json-format-of-a-crash-report) describes the two IPS JSON objects.

## Retained runs and controls

All ten runs have retained evidence for target iOS runtime build `24A434` (27.0). The IPS `osVersion` is the host macOS 27.2/`26B123`, not the target iOS runtime.

| Run | Cohort / source | Observed route and result |
| --- | --- | --- |
| e7 E2E003 | EFC / `e7ebaf0b` | ClipboardReceiver return, product activation and Close; native FAIL with service abort. Immediate System Open is not common to every incident. |
| Beta E2E004 | EFC / `b9bd71ae` | System Open of warm.circ; native FAIL with service abort. |
| Beta E2E005 | EFC / `b9bd71ae` | System Open of warm.circ; native FAIL with service abort. |
| Missing A001 | B25 / `4ab8aa93` | System Open of an existing file before delete/save; native FAIL with service abort. |
| IO44 WindowScope R2 | B25 / `3a0a01f1` | First System Open of owned Dimensions fixture; infrastructure interruption before exported Window-admission evidence; no IO44 qualification. Passive external video is recorded as enabled. |
| SaveAs008 | B25 / `b9bd71ae` | Selected native PASS, 215.584s, with three System Open operations: warm, distinct cold and saved destination. Suite remains PARTIAL. Same Beta app tree as EFC004/005. |
| UIKit document control | AD28 / `a8e32bc2` | Selected PASS, 11.247s, same M5 model/runtime as the tablet interruptions. A separate UIKit consumer reads and hashes an existing file; it does not exercise Compose, parsing or workspace rendering. |
| Inspector Phone005 | iPhone 17e / `6cef0542` | Files Import Copy setup; selected native PASS, 173.818s; suite PARTIAL. |
| Missing Files Phone001 | iPhone 17e / `ce3e061d` | Files Import Copy setup; selected native PASS, 169.212s; suite PARTIAL. |
| Missing Files001 | B25 / `6cef0542` | Files Import Copy reached a contextual-error guard and failed functionally. Kept separate from the five service-abort incidents. |

UIKit's lifecycle log contains two distinct scene IDs. This is not a simultaneous connected-scene or window count. Active scene/window cardinalities remain unknown across the reviewed runs. Recording remains unknown except for IO44 R2; absence of a video file does not establish recording disabled.

The same B25 service identity spans two incidents: the replacement `backboardd` PID 66271 appeared after the A001 abort around 01:09:48; the IO44 R2 IPS reports that PID and matching launch time when it aborts around 06:34:05. This is evidence of service history in a persistent cohort. It does not measure texture retention or identify a leak.

## Premise and source review

The premise under review was that selecting a different Window/test-helper target would explain the recurrent Metal abort. The service stack, older helper paths and pre-admission IO44 interruption do not isolate that premise. Conversely, successful UIKit System Open and the successful Beta SaveAs run prevent universal claims about System Open, multiple scenes or Compose. A service-process stack does not exonerate an external client from being a trigger.

The [source audit](source-audit.json) pins app `3a0a01f1` and the actual UIKit control `a8e32bc2`, including headers, plist, KLIB metadata and source hashes:

- SwiftUI `WindowGroup`/`onOpenURL` forwards a deduplicated request to the receiving document session. The session creates its Compose controller once. System Open does not explicitly create another controller in this code path.
- System Open opens in place; Import Copy presents a picker over the existing host and uses the same coordinated-read path, with a copied cache destination. Neither path changes renderer configuration or clears a graphics cache.
- Both apps support multiple scenes. The UIKit control is a separate SceneDelegate/UILabel/UIStackView consumer; its file read occurs on the callback thread. It is not an equivalent Compose workspace or proof of actual scene cardinality.
- Current CMP UI is `1.13.0-beta02`; inspected Skiko is `0.156.1`. The app changes focus policy to `DoNothing`, retaining the inspected opaque/parallel-rendering defaults. These source bindings do not sample the renderer active during the interruption.
- The inspected UIKit SkiaLayer returns `METAL`; its setter throws `UnsupportedOperationException`. The [fixed-tag upstream source](https://github.com/JetBrains/skiko/blob/v0.156.1/skiko/src/uikitMain/kotlin/org/jetbrains/skiko/SkiaLayer.uikit.kt) agrees with the local IR. A software enum or desktop environment variable is not a supported iOS fallback established by this evidence.

Apple documents that [Simulator translates Metal calls to the host GPU](https://developer.apple.com/documentation/metal/developing-metal-apps-that-run-in-simulator), rather than emulating the selected device GPU exactly. A simulated model comparison therefore also concerns geometry and lifecycle; it does not establish a physical M5 GPU defect.

## Installed runtime constraint

[Inventory](inventory.json) was captured with read-only CLI metadata. Xcode is 27.2/`27B52`, with Simulator SDK 27.2. iOS 27.0/`24A434` and iOS 27.1/`24A94401` are installed and available for arm64.

The installed 27.1 runtime declares exactly one supported model: `com.apple.CoreSimulator.SimDeviceType.iPhone-Duo`. It admits no M5/iPad profile. Its two existing AVAILABLE devices were both Shutdown at inventory time:

| Model | UDID | State |
| --- | --- | --- |
| iPhone Duo | `E080E673-6049-4238-A73D-7507A40EAE4A` | Shutdown |
| iPhone Duo | `AA1E9DE5-C242-4D32-96CB-D4202136430B` | Shutdown |

There is no supported-model intersection between the installed iOS 27.0 and 27.1 runtimes. Therefore the available 27.1 image is not a same-model runtime-update experiment for EFC/B25. A Duo run would change runtime and model/geometry together. No forced profile, download, creation or boot was performed.

## Smallest proposed next observation

Subject to Root's separate device/harness authorization, reuse the immutable 3a app and the exact R2 Dimensions file/setup in one fresh, supported M5 11-inch/12GB cohort on installed 27.0. Match the R2 recorder policy explicitly and bound the observation to the first public System Open/admission boundary. Retain app identity, service PID/launch identity, scene/window observations when obtainable, and any new IPS. Preserve EFC/B25 and their data; do not reset them or change Metal.

An admitted fresh-cohort run would show feasibility without the historical cohort state, with intermittency and host conditions still unresolved. A matching abort would show that historical cohort state alone is insufficient. Neither result proves a leak or a runtime compatibility fix, and neither qualifies the unexecuted IO44 controls. If recording itself becomes the question, it requires a separate matched on/off comparison rather than silently changing both factors.

The two existing Duo/27.1 devices can support a separately labeled exploratory compatibility observation after authorization. They cannot supply the missing same-model 27.0/27.1 comparison. No experiment was executed for this review.

## Rerun

```sh
python3 scripts/diagnostics/census-ios-metal.py --check
python3 scripts/diagnostics/census-ios-metal.py --output /tmp/ios-metal-census.json
```

The script performs offline file hashing and IPS parsing only. It fails on missing or changed evidence. It does not run `simctl`, inspect process arguments, build, download or touch a device. Absolute retained-evidence paths remain in the input registry; the archived corpus must be present to reproduce verification. The committed inventory is a timestamped snapshot, not a promise of future availability. Refreshing it requires a separate read-only `simctl list`/Xcode metadata capture.

Validation for this artifact: five unique incidents, 41 verified evidence files, deterministic offline census check, and diff formatting. No app build/test, device operation, reset, renderer change or runtime download was performed.
