# Independent MAIN6cef issue53 admission review

Status: **ADMITTED_NO_FINDINGS**. This review reads copied JSON/text, snapshots, archived exports and Git objects only. It does not repeat the native cases, simulation/desktop oracles, or access the app, Frameworks, DerivedData or any device.

| Case | Recorded native PASS | Imported/restored workspace | Fresh reopened workspace | Literal recorded PIDs |
| --- | --- | --- | --- | --- |
| bundle | 356.118 s | 6086b81489d71caa348cc9d04fc3fa2f | a796d460c21455246db49f725dfd0770 | 86574 → 87421 |
| folder | 318.815 s | 28edb25a840b9557ca04f35cdc4b63b1 | 86faeb465532963c228972ddafbe0952 | 90900 → 92135 |

Both raw runner records are PASS/NATIVE_PASS and their native receipts are PASSED with executedViaUi=true. The eight snapshots contain 681 regular copied files; all file hashes match the SharedLock collector manifests. Each collector is the exact canonical177 source and holds LOCK_SH on the existing catalog.lock while production commits use LOCK_EX. The manifest observation equals its separate observation.json, and the actual copied window-link tokens match that observation.

Each case has two distinct exported evidence paths, correctly marked before/after restart and matching the receipt hashes/sizes. The reopened portable-source bytes equal the second actual export. Imported and restored sourceArchive descriptors/bytes and circuit/component identities are equal. Fresh import/reopen workspace IDs, preserved catalog entries, the observed window binding and absence of external-<ID>.workspace-link were independently checked. Three captured canvas trees per case contain outputs 00001000 and 00000011; before/after PID diagnostics match the receipts and differ across process restart.

The real Run.json equals the raw runner configuration, supplies B25 and the final product fingerprint, and belongs to the actual Documents/LogisimE2E/run-id container. The earlier all-zero UUID/buildSHA NOT_RUN preparation is not native evidence. Both catalogs contain 2496 messages with SHA-256 83a6f69e2a6d530a8afff326250dcd2c09416d4deeb35187d03117b2b5f6c6ca. All 17 staged Swift files per case match Git blobs at 177ced4e87146a75b3ca2e80a80da7c654cb019d. Only the two PortableProjectEndToEndTests selectors were admitted, with alpha=false and the new exclusive ios53-final-6cef-derived path. No old ios-e2e-alpha-002 cache reference appears in the recorded build/test logs.

Source-app-before, supplied artifacts, installed and installed-after records consistently bind the final product tree d987d43d6587c0139ff0ecddca2d4ae95c9bad66ba6cd19d29c6452214be3080 and executable 198a06dc847cc0fd8717b7c05b06f334b8a1e701aaf63aed5a6012699be5b946. The after records retain debug dylib db2c909500c95f345362fdf06f5fd17c3647af9800863fc53d0b6a9bf31647cf. The first installed-before observation was an older binary; bundle explicitly replaced it with INSTALLED_VERIFIED. Folder then recorded REUSED_IDENTICAL. This documented replacement is not described as an identical before-install artifact.

The 301 production blobs at 6cef05429cf66be23569aaebc3c72950cf3fe53a and test-validation revision 85da031e4ed11474c2e763976392729810ef142d match the recorded digest fe8c7e9337359543a49fb38d9a6c6f003866436a16cb327acbb76abc8c80c9e2. The only revision delta is composeApp/src/androidHostTest/kotlin/dev/logisim/mobile/RuntimeSimulationRequestTest.kt.

Limits: the general runner qualification gate remains PARTIAL because one test was selected per invocation. This admits the recorded two issue53 cases on one iPad Simulator; it does not attest physical devices, Android, the complete iOS suite, SystemOpen/A4 or the entire epic. The separate six fresh oracle PASS reports belong to android_bundle_oracle and were not rerun here. Fingerprint provenance is reviewed from records; binaries were not read again under this offline-only scope.

admission-review.json records exact input/report hashes, IDs, PIDs, snapshot counts and exports. review-records.py is the read-only check used to produce it; rerunning it updates only this review output and never invokes a native tool or oracle.
