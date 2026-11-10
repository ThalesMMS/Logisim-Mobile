# Provider auth002 failed at the Files activation prompt

The selected auth002 case failed after opening the real provider location in Files. A native activation alert was still visible when the navigator required a directory heading. This archive records that failure. It does not establish a product defect or an authentication-denial result.

| Recorded item | Result |
| --- | --- |
| Run | `a26a02940002`, AD28 iPad Pro 11-inch M5, iOS Simulator 27.0 |
| Harness | Clean `92f739a68de9bab12cbaaccbf6cb23da2c4aecb3` |
| Main source | `3a0a01f1af05dd3fea75d5a1499c762d039c478a` |
| Fixture source | `601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd` |
| XCTest | Exit `65`, one failure, zero passes, zero skips; method duration `80.406` seconds |
| Runner | Exit `1`, attested by the runtime coordinator from tool session `30396` |
| Raw gates | `FAIL`, `NATIVE_INCOMPLETE`, `NATIVE_INCOMPLETE`, `NOT_QUALIFIED` |

The sibling console contains only the path of `results.json`. It is not proof of the runner's exit code. The runner-terminal attestation states this distinction. The raw XCTest log and exported summary establish the XCTest outcome.

## The recorded cohort includes Main and Provider

The pre-install application inventory contains the fixture and no Main. The runner installed the approved Main once and checked all three bundles before XCTest. That first fingerprint checkpoint occurs after installation. The Main tree is `813f1b2f…`; the reused fixture and provider trees are `e24e3fb5…` and `e91733f4…`. The three complete fingerprints match the recorded post-test checks. The runner did not install or refresh the fixture.

The isolated UI build returned zero through the reviewed serialization wrapper. Its retained source has SHA256 `61fb2ff0e8e1969b80c2880424683834a5dcd3cdcf79450a40dba15dc4f6471b`. The record shows wrapper completion without interruption before the direct `test-without-building` command. The wrapper source holds its lock until the child build ends and then closes it. The archive contains no separate live lock query.

## The stop point precedes the grant

Attachment `CFE85BE5-49AA-4E81-BCFE-C8EBC67BD6A0.txt` contains the native Alert at line 138, its title at line 145, its message at line 146, and Cancel and Turn On buttons at lines 158 and 159. Screenshot `B6930E6F-E6AA-4C94-AFBE-3205484F4C54.png` shows the same alert over the Files picker. The native test log records the provider-location tap and then the heading assertion failure. It records no tap on either alert button.

The test did not reach document selection, a grant, opened XML, fetch proof, editing, upload proof, restart, eviction, authentication denial, denied Save, or public Allow cleanup. No project XML or later provider-state snapshot was captured.

The public baseline snapshot matches the JSON displayed in the fixture's UITextView: authentication allowed, backend 552 bytes, SHA256 `54c247e7…`, generation 1, and event sequence 9. Its older registration errors and later registration completion are historical baseline events. They are not authentication-denial evidence from auth002.

The scenario attachment remains `INCOMPLETE_OR_FAILED` and contains only that baseline observation. Its static `route` and `authorizationScope` strings describe the intended case. They do not prove that restart or denial happened. Equal installed-bundle fingerprints do not establish unchanged backend or authentication state after the failure. That state is unobserved in the retained public evidence.

## Replay the archive offline

```sh
python3 verify-archived-evidence.py
python3 test-archive-verifier.py
```

The verifier uses only files in this directory. It checks original and stored hashes, command streams, source and resource bindings, the literal failure outcome, the public snapshot, installed fingerprints, and attachment dispositions. It works after moving the package. Its negative tests rehash altered packages and require semantic failures, rather than relying only on a broken file checksum. Neither command uses a device, Simulator tools, builds, or original absolute paths.

All seven PNGs, 34 text attachments, two JSON attachments, public evidence, scenario, runner records, full build and test logs, staged sources and resources, and raw entitlement-section proofs are archived. Large text files use deterministic gzip compression. Decompression restores their original bytes.

The 34 opaque attachments and one video, raw xcresult, and DerivedData remain at their explicit original local paths. The opaque attachments include UI snapshots and synthesized events. The archive contains their hashes and inventories, not those payloads. The original output was not modified or deleted. These local-only files are not required for portable replay of the committed package.

This selected-case failure does not close issue 26 or issue 36. It provides no authentication-denial pass, security-token revocation, cloud, or physical-device validation. No new Native execution was performed while creating this docs-only archive.
