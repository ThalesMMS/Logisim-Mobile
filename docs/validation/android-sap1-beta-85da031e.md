# SAP-1 Android Beta execution

The mounted Android editor passed `sequential-simulation-and-recovery` on emulator `emulator-5556`, API 35, with the English UI. The scenario took 245.35 seconds. This receipt qualifies this SAP-1 scenario only; issue #35 remains partial.

| Identity | Retained value |
| --- | --- |
| App producer | `85da031e4ed11474c2e763976392729810ef142d` |
| Identical production source | `6cef05429cf66be23569aaebc3c72950cf3fe53a` |
| Test driver | `8cf98577877f368af927ace72450b03d2fb5e512` |
| Isolated package | `thalesmms.logisim.mobile.e2e` |
| App SHA256 | `67e81d691beb3830cf49325c81ccffca9b66f2bf8aa98fdc46c9ee7d1f9e5e2c` |
| Fixture APK SHA256 | `c439cef16d4f9ccced20189d12b22b731449b17c5417dbe25d65167ad7e3d882` |
| Snapshot APK SHA256 | `2f17d8af178460537e2e4106d8ea79063fa938b0fdebcae1710b9b0294fee387` |

All three installed APK hashes match before and after in the [execution manifest](android-sap1-beta-85da031e/raw/manifest.json). No APK or JAR is included here. The manifest reports one untracked redirected log and no modified source files. The eight driver sources are retained with their recorded hashes.

The raw counter checkpoints cover reset at 0 and every eight manual half-cycles through 56. At 56, the [retained UI hierarchy](android-sap1-beta-85da031e/raw/sap1-at-half-cycle-56-ui.xml) exposes `Steps 56` and the final two display values `0000` and `1000`. The [unaltered screenshot](android-sap1-beta-85da031e/raw/sap1-at-half-cycle-56.png) has SHA256 `373aebbb080a768f9261c7038e16c9a44b7ceaf7e2d11106c81081d2bebc73d1`. The independent pixel receipt in commit `25695e70667014b5dcef12e9f27362e166633cfe`, `docs/validation/sap1-display-pixels-85da031e-beta.json`, confirms six and seven red segment regions for `08` and rejects `E`. This document verifier checks the screenshot hash and semantic display values; it does not reimplement the pixel oracle.

The retained runner asserts that Run advances beyond half-cycle 56, Pause freezes the same counter for the shared 2500 ms interval, and copying XML preserves the paused display values. The [scenario result](android-sap1-beta-85da031e/raw/results.json) records those assertions passing. A separate log of the numeric paused counter and its timestamps was not retained.

After force-stop and a cold activity launch, the complete exported document is byte-for-byte identical: 52,746 bytes, SHA256 `259d61bc36d2ad2b837df948eefca5f55adca2b7b3f0fc31fd41bbd88a16e298`, with seven circuits, 156 components and 318 wires. The final UI shows `Steps 0` and `00`, so this is document recovery, not qualification of transient kernel-state persistence. The existing 30 second editor-readiness check passed in this run. The activity launch timing is not an editor-ready measurement.

The [machine-readable receipt](android-sap1-beta-85da031e.json) lists the complete retained evidence. Replay the document checks without Android or application internals:

```sh
python3 docs/validation/android-sap1-beta-85da031e/verifier.py
python3 -m unittest discover -s docs/validation/android-sap1-beta-85da031e -p test_verify.py -v
```

This is one emulator run. It does not qualify physical devices, other languages, other scenarios, an overall performance budget, or the complete editor suite. No continuous process PID was retained. Earlier ANR and slow-restore evidence remains unchanged.
