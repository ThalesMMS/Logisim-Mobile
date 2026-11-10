# Provider auth004 failed during preparatory eviction

Auth004 stopped at the one public preparatory eviction permitted by its ROOT lease. The callback failed with `NSCocoaErrorDomain / 3328` before Main entered Files. This is a fixture-preparation failure, not a passed eviction or an authentication-denial result. No fixture or Main source change is included.

| Recorded item | Result |
| --- | --- |
| Run | `a26a02940004`, AD28 iPad Pro 11-inch M5, iOS Simulator 27.0 |
| Harness | Clean `3f7ba0b4f0a9c48aff144bd578a11bab69f7b908` |
| Immutable Main source | `3a0a01f1af05dd3fea75d5a1499c762d039c478a` |
| Immutable fixture source | `601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd` |
| XCTest | Exit `65`, one failure, zero passes, zero skips; method duration `61.284` seconds |
| Runner | Exit `1`, reported by the runtime coordinator |
| Raw gates | `FAIL`, `NATIVE_INCOMPLETE`, `NATIVE_INCOMPLETE`, `NOT_QUALIFIED` |

The console sibling contains only the results-file path. The runner exit is a separate coordinator attestation; the native log and exported summary establish the XCTest outcome. The failure message quotes the successful callback status the test expected. It does not report that success occurred.

## The current operation is bounded by the public snapshots

The initial public snapshot contains events 1–80. Main was terminated at native-log line 93, before the sole `fixture.evict` tap at line 143, `t=48.35`. The failure snapshot `FC8BA1F6…txt` retains the complete earlier prefix and adds exactly these events:

| Sequence | Time, UTC | Event and identity |
| --- | --- | --- |
| 81 | `12:04:22.442` | `eviction.requested`, fixture `32596`, item `revocation-circuit-v1`, `denyAfterCompletion="false"` |
| 82 | `.546` | `provider.initialized`, provider `8735` |
| 83 | `.556` | `provider.modify.started`, same item, request `961C9B1C-1E0E-433B-B27B-275A7ED07291` |
| 84 | `.558` | `provider.modify.failed`, same provider process and request, Cocoa `3328` |
| 85 | `.578` | `eviction.failed`, fixture `32596`, same item and error |

Events 78–79 already belong to the baseline, at `11:56:59Z`, with request `951FCE5C-D7DC-49E5-905F-44DC46562029`. The provider process identifier is reused, so matching that identifier alone cannot classify them as auth004 requests. The verifier checks the new sequence window, item, request identifier, and process together.

The status tree `DE75BCF2…txt`, lines 16–18, reports Cocoa `3328` and the fixture's custom guard reason, **“The fixture does not commit metadata-only changes.”** The pinned fixture source requires both a `.contents` field and a non-nil contents URL at this guard. The captured requests record neither argument. The message does not establish which field bits were requested or whether a contents URL was supplied. No source fix or broader platform diagnosis is claimed.

## The failure snapshot is the state limit

The full public failure snapshot reports authentication allowed, backend 552 bytes, SHA256 `54c247e7…`, and generation 1. Those values match the initial snapshot. They describe the two captures, not a guarantee about all intervening writes or any later backend state. No observation after the failure snapshot is retained.

The scenario contains only its `initial-before-preparatory-eviction` observation. Its static route and authorization strings describe the intended later test. This attempt did not reach Main Files, a fresh fetch pair, XML capture, editing, upload proof, persisted-reference verification, restart, later eviction with deny, denied Save, or public Allow cleanup. The startup import-report capture belongs to the existing workspace and is not proof of a fresh Files open in auth004.

## Artifact and replay boundaries

The Main-plus-Provider cohort was reused without installation. Main, fixture, and provider fingerprints match before and after XCTest: `813f1b2f…`, `e24e3fb5…`, and `e91733f4…`. The isolated UI build returned zero through the reviewed wrapper, whose SHA256 is `61fb2ff0e8e1969b80c2880424683834a5dcd3cdcf79450a40dba15dc4f6471b`. The recorded wrapper completed before the direct test command; its source closes the lock after the build ends. No live lock query was made for this archive. The coordinator reported AD28 returned.

```sh
python3 verify-archived-evidence.py
python3 test-archive-verifier.py
```

The verifier reads only the package. It checks hashes, source and resource bindings, command streams, lease, artifact postflight, both public snapshots, the exact current request pair, typed error identity, and the stated unknown arguments. Rehashed negative packages must still fail semantic checks. Relocation rejects reads of the original absolute paths.

All five PNGs, nine text attachments, two JSON attachments, full build and native logs, public evidence, scenario, lease, harness sources, pinned fixture sources, resources, and small entitlement proofs are archived. Deterministic gzip files restore their original bytes. Nine opaque attachments, one video, raw xcresult, and DerivedData remain local-only with explicit paths, hashes, and inventories. Their payloads are not committed, and the original output was not modified or deleted.

The selected case remains failed. It does not close issue 26 or issue 36, prove authentication revocation, or validate cloud or physical-device behavior. Creating this docs-only archive performed no new Native execution, device query, build, or signing. No product, fixture, or harness source was changed.
