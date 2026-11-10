# Provider auth003 failed during picker dismissal

Auth003 confirmed the native provider activation prompt and selected `revocation.circ` through Files. The selected XCTest case then failed while a picker navigation-bar frame query raced with dismissal. The final Main UI displayed the imported document's report. This is partial UI progress, not an authentication-denial pass or an established product defect.

| Recorded item | Result |
| --- | --- |
| Run | `a26a02940003`, AD28 iPad Pro 11-inch M5, iOS Simulator 27.0 |
| Harness | Clean `ed594596d7d1db2985194c02cad75fea090370bd` |
| Main source | `3a0a01f1af05dd3fea75d5a1499c762d039c478a` |
| Fixture source | `601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd` |
| XCTest | Exit `65`, one failure, zero passes, zero skips; method duration `164.013` seconds |
| Runner | Exit `1`, reported by the runtime coordinator |
| Raw gates | `FAIL`, `NATIVE_INCOMPLETE`, `NATIVE_INCOMPLETE`, `NOT_QUALIFIED` |

The sibling console contains only the path of `results.json`. The separate coordinator attestation records the runner exit; the console is not exit-code proof. The raw native log and exported summary establish the XCTest outcome.

## Recorded progress and failure

| Checkpoint | Evidence |
| --- | --- |
| Activation | Exactly one `Tap "Turn On" Button`, native log line 1029, `t=65.87` |
| Provider directory | Ready-document tree `73E9617E…`, provider heading, `File View`, and 552-byte document row |
| Document action | Enabled, hittable control `7274593E…`; real cell tap at line 2533, `t=157.94` |
| Transition | Screenshot at `t=158.45` still shows Files; transition tree `3D91203A…` still contains its navigation bar |
| Failure | The next navigation-bar existence query is followed by a missing-snapshot frame lookup at `ProviderFilesNavigator.swift:203` |
| Final Main UI | Tree `AE7D6F38…`, lines 25–30, and PNG `9B7B32E8…` show the import report |

The final report names source version `5.1.0-dev`, one circuit, document `revocation.circ`, and circuit `provider_revocation`. These fields are visible in the final evidence, not in the earlier transition screenshot. Files' `Uploaded` row text is provider UI metadata; it does not prove an upload performed by this case.

The test did not reach its XML capture, paired provider fetch proof, editing, successful upload proof, persisted-reference verification, restart, eviction, authentication denial, denied Save, or public Allow cleanup. Selection and the import report do not substitute for those checks. The retained scenario still has phase `baseline-before-files` because the navigator did not return to the caller's next checkpoint. Its static route and authorization strings describe intended actions, not completed proof.

## State and artifact boundaries

Main, fixture, and provider were already installed and were reused without any installation in this attempt. Their complete tree fingerprints before and after XCTest match `813f1b2f…`, `e24e3fb5…`, and `e91733f4…`. This is the existing Main-plus-Provider cohort.

The isolated UI build returned zero through the reviewed serialization wrapper. Its retained source matches SHA256 `61fb2ff0e8e1969b80c2880424683834a5dcd3cdcf79450a40dba15dc4f6471b`. The record shows wrapper completion without interruption before the direct `test-without-building` command. The wrapper closes its lock after its child build ends. No separate live lock query was made for this archive.

Only the public baseline state was captured: authentication allowed, 552 backend bytes, SHA256 `54c247e7…`, generation 1, and event sequence 9. The snapshot is byte-identical to the earlier auth002 baseline and matches the public UITextView. Its old registration errors and completion remain historical events. There is no public snapshot of backend or authentication state after auth003, and equal bundle fingerprints do not establish that state.

The coordinator reports that Main and fixture were terminated, orientation was restored, and AD28 was returned. That statement is recorded as an attestation. The scenario did not reach its public Allow-authentication cleanup action.

## Portable evidence and replay

```sh
python3 verify-archived-evidence.py
python3 test-archive-verifier.py
```

The verifier reads only the package. It checks original and stored hashes, source and resource bindings, command streams, artifact postflight, the actual Turn On action, selection timeline, final report fields, and failure limits. The negative cases rehash altered packages before requiring semantic rejection. A relocation test rejects reads of original external paths.

All 14 PNGs, 34 text attachments, two JSON attachments, the complete native log, build log, public baseline, scenario, lease, sources, resources, and small entitlement-section proofs are archived. Deterministic gzip files decompress to their original bytes. The 31 opaque attachments, one video, raw xcresult, and DerivedData remain local-only at explicit paths with hashes and inventories. Their payloads are not included in this commit. The original output was not modified or deleted.

The case remains failed and does not close issue 26 or issue 36. It supplies no authentication-denial pass, security-token revocation, cloud, or physical-device validation. Creating and replaying this docs-only archive performed no new Native execution or device query.
