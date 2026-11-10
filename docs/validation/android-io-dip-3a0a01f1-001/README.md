# Android DIP persistence and dimension case passed

The selected `dip-last` case passed once in the mounted Android editor on API 35 emulator `emulator-5556`, with the English UI. The case took **284.82 seconds** and returned exit 0. The host wrapper, including its preflight and cleanup, took **299.343 seconds**. These are scenario durations, without a performance-budget claim.

The app producer is `3a0a01f1af05dd3fea75d5a1499c762d039c478a`; the helper producer is `5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c`. Harness `bdc390d1a23f87c9429537db5bca6b2e05f88f1b` prepares the XML receiver before revealing its toolbar action. This archive adds no production change and makes no claim about the cause of earlier failures.

The immutable source-preparation record retains `SOURCE_PREPARED`/`NOT_RUN`, its correct state before this subsequent Native invocation. `controller/unit.json`, the Native manifest and `raw/results.json` record the later execution separately.

| Observed checkpoint | Document and probe result |
| --- | --- |
| Baseline | Original 54 components/64 wire segments and all 19 baseline probe values. |
| DIP switches 1, 2, 1 | `OUT_DIP0=0`, `OUT_DIP1=1`; `resetbehavior=last`, `positions=2`. |
| Number 4 | Only the intended DIP dimension changes; all other components, attributes and terminal connectivity pass the original oracle. |
| Invalid number 0 | Captures 14/15 retain the focused field containing 0 and its specific validation error. The Apply text leaf reports `enabled=true`, while its clickable parent reports `enabled=false`. Cancel leaves the full document byte-identical to number 4. |
| Restore 8 and reopen | Final export, reopened export and `last-document.circ` are byte-identical, SHA256 `efafc313f3c2bd92131d74ac1ea8e4076b861ab9836f5ed14b3b63793c54829c`. Connected DIP probes still report 0/1. |

All six exported documents pass the frozen historical oracle. The verifier checks the manifest digest and all five oracle inputs before executing that oracle. It also checks the producer, source hashes before/after, single-case selection, four probe records, invalid-field ownership, and complete XML equality. The six exports each retain 54 components and 64 wire segments.

The app APK SHA256 is `10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973`, test APK `19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b`, helper APK `504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c`. Installed identities match before/after both the scenario and wrapper checks. The Native runner reinstalled and reset only its isolated `.e2e` packages as designed; the preflight PID 29166 therefore differs from the subsequent SDK capture PID 32167. All 22 successful SDK captures retain 32167 before/after their own query. This does not prove uninterrupted PID continuity between captures.

Wrapper readback found no changed settings and no changed last-ANR report. The reader was off before/after. Cleanup force-stopped only the owned isolated app after preserving evidence and observed an empty app PID. The ADB audit log records command requests, UTC timestamps and requested points. It does not establish command completion, delivery, or internal callback counts.

This packet preserves 131 raw files, 75 controller files, five oracle inputs with a manifest, five source snapshots and the historical preparation record: **218 inventory entries, 5,340,566 bytes**. No APK, JAR, TAR or credentials are included. Other I/O cases, SDK recovery, physical touch ergonomics, visual parity across every display and overall issue 44 completion remain outside this receipt.

Run offline from any location, without Android, Git or current source files:

```sh
python3 -B verifier.py
python3 -B -m unittest discover -s . -p test_verify.py -v
```

The small negative controls change an observed probe, an exported dimension, the invocation count and the invalid field's clickable owner, then recompute archive hashes. A separate check relocates the whole archive. The originals are never modified.
