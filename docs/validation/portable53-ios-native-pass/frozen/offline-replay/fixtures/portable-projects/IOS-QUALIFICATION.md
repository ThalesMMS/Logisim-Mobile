# Native iOS portable-project qualification

The new XCTest source exercises the production Portable projects panel and native Files choosers for both `.lsebdl` and explicitly granted source folders. The preparation receipt records a simulator SDK typecheck and executable macOS collector checks. Its `nativeUiStatus` remains `NOT_RUN` until the native owner runs these tests on the final app artifact.

The isolated sources are `scripts/ios_e2e_ui/PortableProjectEndToEndTests.swift` and `PortableWorkspaceEvidence.swift`. The native owner must add both to the existing harness target and runner copy list. They require the reviewed `NativeUIHarness`, `ObservedProjectXML`, `NativeFilesDestinationNavigator`, localized control catalog and Run.json resources. This change does not edit the production app or central runner.

`Run.json` must supply `run_id`, `documents`, `workspace`, `fixtures.portableBundle`, `fixtures.portableSources`, and `visualExport.buildSHA256`/`sourcePin`. `workspace` is the actual application container's `Library/Application Support/LogisimMobile/workspace`; runner commit `799f3795` provides it. The test checks that it shares the supplied Documents container. The build fingerprint must describe the installed app, not the test bundle.

Run the two test methods without another editor/window driver on the same app container. Every portable copy has a fresh catalog identity, so exactly one window should initially reference that new identity. Existing catalog entries and other window bindings must remain present.

Each mode writes `Run.documents/portable-MODE-evidence/` with:

- `ui-receipt.json`, initially RUNNING and PASSED only after the full native scenario;
- actual native Files outputs, moved intact to `export-before-restart.lsebdl` and `export-after-restart.lsebdl`;
- four complete snapshots in `snapshots/{before,imported,restarted,reopened}/workspace`;
- snapshot manifests, input hashes, screenshots and accessibility trees.

The read-only collector opens the existing `catalog.lock` and takes `flock(LOCK_SH)`. Production `IosCatalogStorage` commits use `LOCK_EX` on the same file, so the catalog, payloads, links and sidecar are copied at one committed state. It never creates or modifies source workspace files. Each snapshot excludes the live lock and records SHA-256 for every copied file. A failed or partial copy cannot be returned as successful evidence.

The test opens the bundle through **Tools → Portable projects → Open .lsebdl bundle**. For folder mode it explicitly selects the staged `sources` directory, confirms native Open/Abrir and chooses `main.circ` in the production tool. Unexpected permission UI or ambiguous native controls fail with evidence for the native owner to inspect. The prepared source-picker selectors still require admission against the actual OS tree.

Both modes verify outputs 8 and 3 and distinct FIRST53/SECOND53 instances. Import and reopen IDs must be absent from every earlier catalog. The first export precedes `terminate`; XCTest must observe `.notRunning` before `launch` returns to `.runningForeground`. Public `app.debugDescription` trees supply distinct process IDs before/after restart; no KVC or product hook is used. The restarted project must retain its source descriptor, exact archive bytes, identities and window binding without selecting sources again. Reopening the second actual app export must create a new workspace whose sidecar is those exact selected bytes. Native editable-file associations must remain absent.

The native receipt is only one part of acceptance. After collecting the complete evidence directory on the host, run the existing verifier with all four snapshots and separately with the two actual exports from that same receipt:

```sh
scripts/verify-portable53.py --core-jar CORE.jar --desktop-jar DESKTOP.jar \
  --workspace-before EVIDENCE/snapshots/before/workspace \
  --workspace-imported EVIDENCE/snapshots/imported/workspace \
  --workspace-restarted EVIDENCE/snapshots/restarted/workspace \
  --workspace-reopened EVIDENCE/snapshots/reopened/workspace \
  --output EVIDENCE/storage-proof.json

scripts/verify-portable53.py --core-jar CORE.jar --desktop-jar DESKTOP.jar \
  --artifact EVIDENCE/export-after-restart.lsebdl \
  --peer EVIDENCE/export-before-restart.lsebdl --origin app-export \
  --ui-receipt EVIDENCE/ui-receipt.json --output EVIDENCE/desktop-proof.json
```

Add `--allow-no-opaque` to both commands for folder mode. The graph oracle checks transitive external sources, metadata, shared definitions, component IDs and outputs; desktop reopening consumes the actual app export. Checked-in fixtures and files absent from the native receipt cannot substitute for those exports.

The preparation gate can be repeated without a device, Gradle, xcodebuild or app launch:

```sh
scripts/check-ios-portable-preparation.py --harness-root CURRENT_HARNESS_CHECKOUT \
  --output /tmp/portable-ios-preparation.json
```

It freezes the source bytes before typechecking against the simulator SDK and executing the Foundation/CryptoKit collector checks on macOS. `--core-snapshot PATH` additionally checks an offline workspace produced by the owning Kotlin core. Synthetic collector checks are never native execution evidence.
