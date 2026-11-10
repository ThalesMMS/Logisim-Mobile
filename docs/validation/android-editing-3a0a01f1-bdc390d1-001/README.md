# Android editing stopped before the North XML export

One `--scenario editing` invocation failed on API 35 emulator `emulator-5556`, English UI, app producer `3a0a01f1af05dd3fea75d5a1499c762d039c478a`, helper `5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c`, and harness `bdc390d1a23f87c9429537db5bca6b2e05f88f1b`. The wrapper returned exit 1 after **205.804981 seconds**. This is a failure receipt, not a qualification of the four editing groups.

The primary stack is `create_and_properties` → `assert_document("editing-property-north", "north")` → `export` → `export_xml` → `wait_editor`. The retained source shows a 30-second editor-readiness deadline after preparing the clipboard receiver. The terminal getter had 0.462 seconds left. Its residual timeout is not established as the cause of the earlier readiness failure.

Two complete XML exports passed the frozen historical editor contract before the failure:

| Source | Actual document | Scope |
| --- | --- | --- |
| `editing-empty.circ` | Empty circuit, SHA256 `c9ae3bb10cf2ea2b72180e899ee0c18c991ae20f26684fad55756eaab78dd481` | Successful Copy XML checkpoint. |
| `editing-components.circ` | Three correctly labelled components, output facing West, NOT facing East, no wires; SHA256 `049e8daa354dd0ed76a918b6358845c46ec23fbc7082019e3926bb2380d4d9b9` | Successful Copy XML checkpoint; `last-document.circ` is byte-identical to it. |
| `failure-checkpoint-36.circ` | Three components, NOT facing North, no wires; SHA256 `11ba7d73610a3fc8043a3a974f29bf076ea70529936a8cc2e2844e7b8f91e6d8` | Private workspace checkpoint extracted by the failure collector. It is not a successful North Copy XML export. |

The last retained hierarchy still shows the Inspector for `E2E_NOT` with `Facing` / `North`. The audit log contains a requested Apply point `(818,1615)` at elapsed 167.788971, requested Back at 167.814441, and requested foreground clipboard-receiver preparation at 167.839587. These records establish requested commands and their order. They do not establish Back delivery, its handler, internal callback counts or an IME cause.

All 42 retained SDK snapshots are complete and record app PID 5182 before/after each query. CLI readiness subsequently failed. No SDK recovery or uninterrupted process continuity is inferred. Wrapper before/after getters record unchanged settings, last-ANR text, installed APK hashes and source hashes. The reader was off. Cleanup force-stopped only the owned isolated app after evidence collection and observed an empty app PID. The scenario runner had performed its normal isolated package reinstall/reset before the case.

The first group, `editing-create-properties`, remains **FAIL**. The other three groups, `editing-wires-truth-table`, `editing-selection-history`, and `editing-viewport-touch-parity`, are **NOT_RUN**. No earlier failure is reclassified and no product cause is assigned.

The packet contains 274 raw files and 74 controller files, plus the two historical oracle inputs, prepared-source snapshots, the unchanged historical preparation record and the necessary final private-workspace member. Its **358 inventory entries total 4,672,898 bytes**. The two original 86,586-byte TARs remain unchanged in the producer output, with SHA256 `796d0b3c091e397eea0f00fe1ae75546bc94f6c802885e648b91f76041b6d34b`; their references are retained, but the TARs are not published. The retained member is sufficient to confront the collector's final extracted North checkpoint. No APK or JAR is included.

Run from this directory after copying the packet elsewhere:

```sh
python3 -B verifier.py
python3 -B -m unittest discover -s . -p test_verify.py -v
```

The verifier uses the historical oracle only after checking both fixed hashes. It checks raw inventory, identities, source pre/post equality, getter stdout/stderr, the primary trace, final Inspector, two public exports, the private checkpoint, SDK records and the three request timestamps. Four semantic mutations recompute archive hashes before rejection. The relocation test needs neither Git nor Android. Checking the two unpublished TARs against their references is a separate local provenance check, not a requirement of the portable replay.
