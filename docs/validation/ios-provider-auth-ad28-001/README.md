# Provider authentication attempt 001: inventory preflight failure

The attempted run remains **FAIL**, with `NATIVE_INCOMPLETE`, test `NATIVE_NOT_RUN`, and `NOT_QUALIFIED`. The runner failed while decoding an installed-app inventory, before installing or launching Main, compiling the harness, invoking the build wrapper, starting XCTest, or using the fixture UI. The Main-plus-Provider phase did not occur.

The clean harness source was `ab6d78ce96e93794bae25855210b9c9200ebd9ad`. Its `results.json` timestamps span **0.888792 seconds**; this is runner time, not XCTest duration. The coordinator reported CLI exit 1. That return code was observed through its execution tool; the retained console contains only the results path.

All nine recorded subprocesses returned 0: six read-only signature checks, XcodeGen project generation, and two inventory queries. Source/resource staging and project generation occurred. The 58,955-byte `listapps` output is an OpenStep property list, SHA256 `3c665ecc5253c87abfd631dd5483c12b1333dcea042771221c9b8d07ceb9f3d5`. Passing it to `json.loads` produced the retained `JSONDecodeError`. The existing offline conversion is preserved; this archive's portable parser independently reconstructs the same 44 application entries without Apple tools.

Main is absent from that inventory. The captured filesystem postflight reports the source Main tree `813f1b2f…`, installed fixture tree `e24e3fb5…`, and embedded provider tree `e91733f4…`, matching the approved preflight fingerprints. These are captured artifact identities, not a new inspection of backend bytes or authentication state. The valid Root lease and the separate historical `nativeOperationAuthorizedByCurrentArtifactReview=false` flag remain unchanged; the failure was in inventory parsing.

The complete output, console, both lease copies, staged harness, pinned source, static entitlement blobs, conversion, and postflight are retained byte for byte under `evidence/`. `manifest.json` inventories those bytes; `SHA256SUMS` also covers the receipt and verification tools. No binary bundles, XCTest results, screenshots, crash reports, or videos were produced by this attempt. No retry is part of this receipt.

Fetch, upload, eviction, authentication denial, Save errors, local XML/backend preservation, revocation, cloud, physical devices, and issue #26 remain unqualified. The earlier registration-only PASS is separate history: original archive commit `53d6653a06c5cac52ce853a915dabace76280192`, `docs/validation/ios-provider-xcode601-ad28-003`; it does not establish current backend policy or qualify this attempt.

Run from any location with Python 3 and no repository or Apple tooling:

```sh
python3 /absolute/path/to/ios-provider-auth-ad28-001/verify.py
python3 /absolute/path/to/ios-provider-auth-ad28-001/test_archive.py
```

The verifier reads only the archive. Absolute paths inside the retained records are historical text and are never opened. The tests include relocation and deliberate corruptions with refreshed checksums to exercise semantic checks.
