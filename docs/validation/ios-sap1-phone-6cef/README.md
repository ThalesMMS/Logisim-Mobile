# Selected SAP-1 iPhone Simulator receipts

These two selected native XCTest cases passed on the iPhone 17e Simulator, iOS 27.0, with app producer `6cef05429cf66be23569aaebc3c72950cf3fe53a`. Both raw runner reports retain `qualification_gate: PARTIAL`. This archive does not qualify all of issue #36, physical devices, other projects, save/restart, direct canvas wiring, SystemOpen or multiwindow.

| Case | Native result | Counter observations | Display evidence |
|---|---|---|---|
| [Sequential](sequential/README.md) | 1 test, 0 failures, 140.001 s; execution exit 0 | 56 manual half-cycles, then Run/Pause from 56 to 57 | Canvas output suffix `0000`, `1000`; two actual Hex components expose digits `0` and `8` |
| [Automatic](automatic/README.md) | 1 test, 0 failures, 28.827 s; execution exit 0 | Run/Pause from 0 to 1 | No Hex-digit or 56-step assertion in this case |

Both cases load SAP-1 through the Library shortcut and use the real Reset, Run and Pause controls. The sequential case additionally uses Step. They do not open the example through SystemOpen. The contract requests Run for 1500 ms and compares two paused counter readings around a requested 2500 ms wait. This proves those counter observations, not a measured clock frequency, continuous internal-state stability or every intermediate simulation state. The cases do not copy or compare project XML. The display checks are semantic native accessibility values, not an independent raster oracle.

## Identity and source

These pins identify different objects:

- App producer: `6cef05429cf66be23569aaebc3c72950cf3fe53a`.
- Related KMP validation commit: `85da031e4ed11474c2e763976392729810ef142d`. It adds test-only validation after the producer; it is not the Swift UI-harness pin.
- UI harness: `ba9d9682248fca28adbf5fc050f018156c7edc5c`.

The unchanged [producer artifact manifest](shared/app-artifact.json) has SHA-256 `6006751321c48752f5676ddaba3b6ae20cbc7f10f43f60a5000c855a1e34ac17`. Its `runtime_status: NOT_RUN` describes that producer's build-only job. The later selected native executions are recorded separately in each raw `results.json` and test log.

The executable fingerprint is `198a06dc847cc0fd8717b7c05b06f334b8a1e701aaf63aed5a6012699be5b946`; the app bundle-tree fingerprint is `d987d43d6587c0139ff0ecddca2d4ae95c9bad66ba6cd19d29c6452214be3080`. Each raw runner report records identical requested/before/installed fingerprints. Both retained `installed-after.json` files record the same read-only MATCH after the two authorized cases; they are not separate installations or new native runs.

The 17 captured Swift files match `results.harness_sources` and the UI-harness commit byte for byte. The target in `project.yml` lists 16 of them; the extra captured `AccessibilityUITests.swift` is not a selected test in these executions. The recorded Editor driver SHA is `df046e24e72b26919e1e2b8ae3d767a2eae3b784f5925a0d946198f11d17ed07`. The [executed contract](shared/harness/editor-actions-v1.json) SHA is `9a77ddc4628e87a87b3dbcbe28a46691ed15b10f22b94b70544cce9944216ec9`. Per-run `Run.json` files and the full localization catalog are retained.

## Example provenance

The Library's `App.kt` shortcut resolves `CircuitExamples.find("sap1")`, whose list comes from `BundledCircuitExamples`. The generated Kotlin SAP-1 XML decodes byte-identically to the retained [examples/sap1.circ](shared/app-source/examples/sap1.circ), SHA-256 `2103aae7364ef6bc96f8620d39e7223ce6e561b4cf51021e72a2e4479c962aac`. The retained producer source, example catalog, generator, MIT license and SAP-1 provenance document establish this route. They cite upstream `ThalesMMS/SAP-1-Logisim` revision `ddb9dd86d601ac217157beedeb88464ee0128c87`; this archival task did not perform a new network verification.

The source fixture contains seven circuits, 156 components and 318 wires in total; `main` alone contains 111 components and 220 wires. These are offline source counts, not additional XML assertions made by the two selected native tests.

## Portable verification

From any directory, using Python 3.10 or later:

```sh
python3 /path/to/ios-sap1-phone-6cef/verify.py
python3 -B -m unittest discover -s /path/to/ios-sap1-phone-6cef -p test_verify.py -v
```

`verify.py --root /copied/archive` validates the copied payload without Git, Xcode, an app bundle or a simulator. It checks file sizes and SHA-256, identities, selected-test results, source/contract links, native observations and fixture embedding. Negative controls rehash altered payload files to ensure that semantic changes are rejected too. This is an archival consistency check, not another native execution or a cryptographic signature of the original machine.

The original output folders remain untouched. This archive retains raw results, installation fingerprints, attachment manifests, relevant attachments and the compact complete XCTest logs. It excludes xcresult directories, app/framework binaries, build products, caches, the 166/186 MB simulator logs, unrelated staged fixtures and duplicate full-resolution simctl screenshots. The retained XCTest screenshots preserve their original bytes.
