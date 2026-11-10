# Portable projects: iOS native and desktop evidence

Both issue #53 iOS Simulator flows passed against the same unchanged Beta app: import a bundle or folder, export, terminate and relaunch the process, export again, then import the actual export into a fresh workspace. Each flow also passed the four-snapshot storage oracle and separate mobile/desktop checks of both actual app exports.

| Mode | Native XCTest | Imported and restored workspace | Fresh reopened workspace | Each actual export |
| --- | --- | --- | --- | --- |
| Bundle | PASS, 222.379 s | `df082f0c0f31ed285059d617b7c67816` | `22535617f4e9efd12296430a8fa1a5d0` | 5,395 bytes, SHA-256 `930cebe2bffa952f917a6c7fff5b23fceb2444ea8a05b5c969d225df35ef4113` |
| Folder | PASS, 230.970 s | `ad07837495503960a177e42b4a4e1b59` | `7873408d19a1b28cc8258bacd72ca955` | 4,905 bytes, SHA-256 `3ffe3b61443a982cf54788b18cc65da954e01684f6ae9bcf123c1d297e2fc26b` |

The native receipts record two independent process IDs, outputs `00001000` and `00000011` before restart, after restart and after reopening, and the observed window identity. Copied catalog/attachment snapshots prove the source archive survived restart and that importing the export created a new workspace without an inherited native file reference. Both actual exports preserve three circuit definitions, 11 components, seven wires, four runtime child states and the explicit component IDs. Bundle mode also preserves the two opaque entries; folder mode captures the referenced circuit sources.

This is issue #53 evidence on one iPad Simulator and the desktop JAR. It does not qualify physical devices, Android, the complete iOS E2E suite or the epic. The runner's general `qualification_gate` remains literally `PARTIAL` because each invocation selected one #53 test; both selected native tests are `NATIVE_PASS`. Storage-only reports retain `nativeUiStatus: NOT_RUN`; the separate native receipts and paired export reports supply native execution evidence.

## Pinned artifacts and sources

- Product source: `b9bd71ae49e562661ee114b248e342a02ed29508`; bundle ID `thalesmms.logisim.mobile`; version `0.2.0` (2).
- Product tree SHA-256: `0b661a20fbd34e2e3d5e82096155a5db4ea0ca6067054e54d214bbe37f0a5d93`; executable SHA-256: `0f0c7eb6784f4b77f3ebe9778fa4a7bf8a236a87731a05733bb97130f50b7867`.
- Native runner source: `89c944468b03f24187acb3658d496b340028b557`; runner SHA-256 `950a1200e156a37e2c8c459a7e3c35069a39c8972e9abbcdcc8aa6c77b22d168`.
- Reference core JAR SHA-256: `7d16a8519f19e033f7ad3350b97402abf492270747b51ed725a9237228f64e20`.
- Desktop JAR SHA-256: `0f5af2768ae804c79923e942929e19e5e6ff5e9f125fa4c88ecfb09239e09c01`.
- Simulator: `B25F12A5-62D8-4E9E-964E-96519DB6FB69`, iPad Pro 11-inch (M5), iOS 27.0. The app was terminated at each teardown. The simulator/cache lease was returned before packaging.

`bundle/` and `folder/` contain complete native evidence directories, preserving every relative path in `ui-receipt.json`, all four workspace snapshots, the original three oracle reports/logs, the input bytes actually staged, and the frozen native harness. Each harness `Run.json` equals its runner result's `configuration`; each `Catalog.json` and all 17 Swift sources match the recorded SHA-256 values. The two Run files are distinct and retained separately.

`frozen/offline-replay/` contains the exact oracle sources named in the original reports, their Kotlin version declaration and the fixture contract. Its fixture bundle and circuit sources were verified against both executions' staged inputs. The golden `expected.json` retains its original preparation status: it is an expectation file, not a native execution receipt. `frozen/native-runner/` records the pinned runner bytes for provenance; no simulator replay is requested by this package.

`copy-provenance.json` maps committed copies to original paths and hashes. Recorded absolute paths inside original receipts and reports are retained as historical observations. Use the relative paths below when replaying. No app, APK, JAR, DerivedData or xcresult bundle is committed. Four duplicate runner final-screen images remain externally recorded by path/hash; all screenshots listed in a native receipt are included.

Captured logs retain their original whitespace. The local Git attributes disable whitespace lint for those `.log` files only; their exact bytes remain covered by the integrity manifest.

## Repeat the offline checks

Run from this directory. Supply Java 21, the two JARs with the hashes above and the already cached Kotlin 2.4.21 compiler dependencies used by the frozen wrapper. The commands invoke the compiler directly with Java; they do not invoke Gradle, Xcode or a device. Write new reports outside this immutable evidence package.

```sh
shasum -a 256 -c SHA256SUMS
export PYTHONDONTWRITEBYTECODE=1
export PORTABLE53_CORE_JAR=/absolute/path/to/core-jvm.jar
export PORTABLE53_DESKTOP_JAR=/absolute/path/to/logisim-evolution-5.1.0dev-all.jar
export PORTABLE53_REPLAY_OUTPUT=/absolute/path/to/new-offline-results
mkdir -p "$PORTABLE53_REPLAY_OUTPUT"

verify53() {
  python3 frozen/offline-replay/scripts/verify-portable53.py \
    --core-jar "$PORTABLE53_CORE_JAR" \
    --desktop-jar "$PORTABLE53_DESKTOP_JAR" "$@"
}

for mode in bundle folder; do
  evidence="$PWD/$mode/native-evidence"
  opaque_option=
  if [ "$mode" = folder ]; then opaque_option=--allow-no-opaque; fi
  verify53 $opaque_option \
    --workspace-before "$evidence/snapshots/before/workspace" \
    --workspace-imported "$evidence/snapshots/imported/workspace" \
    --workspace-restarted "$evidence/snapshots/restarted/workspace" \
    --workspace-reopened "$evidence/snapshots/reopened/workspace" \
    --output "$PORTABLE53_REPLAY_OUTPUT/$mode-storage.json"
  verify53 $opaque_option --origin app-export \
    --artifact "$evidence/export-before-restart.lsebdl" \
    --peer "$evidence/export-after-restart.lsebdl" \
    --ui-receipt "$evidence/ui-receipt.json" \
    --output "$PORTABLE53_REPLAY_OUTPUT/$mode-desktop-before.json"
  verify53 $opaque_option --origin app-export \
    --artifact "$evidence/export-after-restart.lsebdl" \
    --peer "$evidence/export-before-restart.lsebdl" \
    --ui-receipt "$evidence/ui-receipt.json" \
    --output "$PORTABLE53_REPLAY_OUTPUT/$mode-desktop-after.json"
done
```

New report timestamps, checkout revision and resolved paths will differ. The expected statuses are `STORAGE_SNAPSHOTS_VALIDATED` and `APP_EXPORT_ARTIFACT_VALIDATED`; every desktop result must be `PASSED` with outputs 8 and 3. The six original reports remain unchanged.

All six commands were repeated successfully against these copied evidence paths and frozen sources before this commit. `validation-replay/` contains that separate offline replay record and its six reports/logs; its native UI status comes from the unchanged original native receipts, not another device run.

## Earlier failed attempts

Both earlier attempts remain literally `FAILED` / `NATIVE_FAIL`. Their full native evidence roots, staged harnesses, inputs, runner results and test logs are retained under `failed-attempts/`.

1. `bundle-initial`: zero exports and only the before snapshot. A displaced accessibility frame on `editor-tool-sheet` caused the safe reveal helper to dismiss the panel. The fix first taps a unique enabled, hittable canonical button already fully visible in the app. It does not qualify scrolling to offscreen targets or change the product's accessibility frame.
2. `bundle-retry2`: one actual export and before/imported/restarted snapshots; no second export or reopened snapshot. Files exposed the run folder both in the sidebar and the `File View` collection. The fix separates location rows from content rows for Destination, PortableSource and Peer navigation, rejects multiple exposed content collections and allows waits while none is loaded.

The first actual export from retry2 separately passed a desktop check. `failed-attempts/bundle-retry2/partial-export1-desktop.json` retains `PARTIAL_REAL_APP_EXPORT_DESKTOP_VALIDATED`, `INCOMPLETE_EXPORT1_ONLY` and native status `FAILED`; the matching XCTest export attachment is included. This partial proof does not promote the failed flow. The later complete bundle/folder runs and their paired export reports are separate evidence.

The two harness fixes were committed as `b6f1d51eb0d00b7af4f9188a9038b989b9b8bf2e` and `15763008319d33b277ce83195d4b45e67d7ad962`, then integrated by the native harness owner before the successful runs. Preparation/typecheck records and the original execution index remain under `provenance/`.
