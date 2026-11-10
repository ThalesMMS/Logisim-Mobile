# Independent integrity review for issue #53

Overall result: PASS for integrity and declared coverage of the two exact evidence commits. This is a read-only artifact review, not a new native run or a complete acceptance decision.

Reviewed Android commit `495fccc7353f88b1d861fa6fc1d0b25b2a488084`, path `docs/validation/portable53-android-bundle-pass`, and iOS commit `b811fd26e4fdb98cc0a9f9d9af6c29117836605e`, path `docs/validation/portable53-ios-native-pass`. Each was extracted directly with `git archive` into a separate temporary directory. Repository branches and files were not modified. No UI, ADB, Gradle, push, or issue-state action was used.

| Check | Result | Evidence |
| --- | --- | --- |
| Android inventory | PASS | 114 files, 2,238,955 bytes. All 113 `package-index.json` entries match their recorded SHA-256 and byte length. The sole uncovered file is `package-index.json` itself, explicitly excluded to avoid a recursive hash. No unknown exclusions or extra inventory paths. |
| Android copy chain | PASS | All 108 committed destinations match their copy-chain hashes and byte lengths. All 107 filesystem originals exist and match byte for byte. The remaining original is an explicit Git blob, verified against `bc98f934:fixtures/user-scenarios/editor-actions-v1.json`. |
| Android native receipt | PASS | Receipt SHA-256 `68d6c8ea3e55c05cc1ac865d9de834a298a9c6874c068d57b2bf5b5f6be05e7a` matches both inventory and copy chain. All 16 receipt evidence paths, 4 raw snapshot paths, and 2 export paths exist. Both actual exports are 5,395 bytes with SHA-256 `930cebe2bffa952f917a6c7fff5b23fceb2444ea8a05b5c969d225df35ef4113`. |
| Android frozen sources | PASS | All 8 frozen scenario sources and the scenario contract match the native run manifest hashes. |
| Android raw snapshot copies | PASS | The before/imported/restarted/reopened TARs contain respectively 9/11/13/15 regular workspace files. Their names and bytes exactly match the corresponding committed extracted snapshots. No unsafe members, links, duplicate names, or non-flat workspace member paths were found. This does not establish a shared-lock capture. |
| iOS manifest | PASS | 835 files, 35,973,541 bytes. All 833 manifest payload entries match SHA-256 and byte length; declared count and 35,674,508 payload bytes match. Only `manifest.json` and `SHA256SUMS` are excluded, as documented. |
| iOS SHA256SUMS | PASS | All 834 entries match, including `manifest.json`. Only `SHA256SUMS` itself is uncovered. No duplicate, missing, or extra paths. Manifest SHA-256 is `43436738ea37959de98f79ddef0f8d6ed11bdadd91ce0adbcdc8ab6e9a74cf4b`. |
| iOS copy provenance | PASS | All 812 destinations match hashes and lengths. All 810 filesystem originals match byte for byte. The other 2 sources use explicit `git:` references and match commit `89c944468b03f24187acb3658d496b340028b557`. No duplicate destination paths. |
| iOS staged harness manifests | PASS | Each mode has 17 matching Swift source hashes, a matching Catalog hash, a matching Run hash, and a Run JSON equal to that mode's runner `configuration`. |
| iOS passed receipt paths | PASS | Bundle has all 78 recorded evidence paths and folder all 80. Each has 4 existing snapshots and 2 exports whose byte lengths and hashes match its receipt. Bundle exports are 5,395 bytes/SHA-256 `930cebe2bffa952f917a6c7fff5b23fceb2444ea8a05b5c969d225df35ef4113`; folder exports are 4,905 bytes/SHA-256 `3ffe3b61443a982cf54788b18cc65da954e01684f6ae9bcf123c1d297e2fc26b`. |
| iOS declared exclusions | PASS | Four runner `last-screen.png` images are explicitly external-only and absent from the commit. Their originals still exist and match recorded hashes. All receipt-listed screenshots are committed, including screenshots from failed attempts. Product binaries, caches, JARs, and xcresult are deliberately outside this package. |
| Failure preservation | PASS | Android's outer `results.json` still records the later folder failure and the package explicitly qualifies bundle only. Both iOS failed native receipts remain `FAILED`; initial has 8 evidence paths/1 snapshot/0 exports and retry2 has 49 paths/3 snapshots/1 export, with every recorded path present. No partial attempt was promoted. |
| First partial export continuity | PASS | The actual export and XCTest ZIP in earlier commit `d5327c3790094af72dfe83a0d7bbb64260853103` are byte-identical to the retry2 export and retained attachment in the iOS evidence commit. They remain evidence of a partial failed flow. |
| Native rerun, binary reinstallation, oracle behavioral replay | NOT_RUN | Outside this integrity subtask. The acceptance reviewer checks behavior and the strict oracle source. |
| Android folder final evidence | NOT_RUN | Not present in Android bundle commit; remains a separate gate owned by `codec_full`. |

No integrity mismatch or silent inventory exclusion was found. These results qualify only the artifacts and scope above, including the declared Android product source `59d5606b` and iOS Beta source `b9bd71ae`; they do not reattribute those native executions to main `50668f45`.

Machine-readable recomputation results: `/tmp/logisim-portable53-integrity-results.json`.
Recheck script: `/tmp/logisim-portable53-integrity-check.py`.
Extracted evidence locations: `/tmp/logisim-portable53-integrity-paths.json`.
